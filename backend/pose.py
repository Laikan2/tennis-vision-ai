"""Latest-frame pose inference with measured optical-flow propagation.

The worker never queues old frames and never blocks ball analysis. Joints are
carried to the current image by forward/backward checked optical flow, then
expire if the model has not refreshed them recently.
"""
import logging
import os
import threading
import time
import cv2
import numpy as np
from .config import DATA

log = logging.getLogger('tennis.pose')


def advance_pose(persons, previous, current):
    if previous is None or previous.shape != current.shape:
        return []
    output = []
    for person in persons:
        joints = np.asarray(person['keypoints'], np.float32).copy()
        valid = np.flatnonzero(joints[:, 2] >= .3)
        if len(valid) < 3:
            continue
        points = joints[valid, :2].reshape(-1, 1, 2)
        moved, ok, _ = cv2.calcOpticalFlowPyrLK(previous, current, points, None,
                                             winSize=(25, 25), maxLevel=3)
        if moved is None:
            continue
        back, back_ok, _ = cv2.calcOpticalFlowPyrLK(current, previous, moved, None,
                                                  winSize=(25, 25), maxLevel=3)
        if back is None:
            continue
        h, w = current.shape
        good = ((ok.ravel() > 0) & (back_ok.ravel() > 0) &
                (np.linalg.norm(back[:, 0] - points[:, 0], axis=1) < 2.5) &
                (moved[:, 0, 0] >= 0) & (moved[:, 0, 0] < w) &
                (moved[:, 0, 1] >= 0) & (moved[:, 0, 1] < h))
        joints[valid[~good], 2] = 0
        joints[valid[good], :2] = moved[good, 0]
        if good.sum() < 3:
            continue
        shift = np.median(moved[good, 0] - points[good, 0], axis=0)
        item = dict(person, keypoints=joints.tolist())
        item.update(x=float(person['x'] + shift[0]), y=float(person['y'] + shift[1]),
                    centerX=float(person['centerX'] + shift[0]), centerY=float(person['centerY'] + shift[1]))
        output.append(item)
    return output


class PoseEstimator:
    def __init__(self):
        self.path = os.getenv('TV_POSE_PATH', str(DATA / 'models' / 'yolo11n-pose.pt'))
        self.available = bool(self.path and os.path.isfile(self.path))
        self.status = 'carregando pose' if self.available else 'modelo de pose indisponível'
        self.condition = threading.Condition()
        self.stopped = False
        self.pending = None
        self.result = None
        self.consumed = None
        self.persons = []
        self.previous_gray = None
        self.observed_at = None
        self.inference_ms = None
        self.thread = None
        self.acceleration = None
        self.device = 'cpu'
        try:
            self.max_width = max(256, min(640, int(os.getenv('TV_POSE_MAX_WIDTH', '320'))))
        except ValueError:
            self.max_width = 384
        try:
            # Pose is contextual; ball tracking owns the real-time budget. The
            # old cadence kept this CPU saturated because a pose pass itself
            # took about .9 s and immediately consumed the next pending frame.
            self.inference_interval = max(.35, float(os.getenv('TV_POSE_INTERVAL', '1.25')))
        except ValueError:
            self.inference_interval = 1.25
        try:
            self.model_size = max(256, min(384, int(os.getenv('TV_POSE_IMGSZ', '256'))))
        except ValueError:
            self.model_size = 256
        self.last_submitted = None

    def start(self):
        if self.available:
            self.thread = threading.Thread(target=self._run, daemon=True, name='pose-worker')
            self.thread.start()

    def stop(self):
        with self.condition:
            self.stopped = True
            self.pending = None
            self.condition.notify_all()
        if self.thread:
            self.thread.join(timeout=10)

    def _run(self):
        try:
            from pathlib import Path
            onnx_path=Path(self.path).with_name('yolo11n-pose-256.onnx')
            manifest=onnx_path.with_suffix('.json')
            approved=False
            if Path(self.path).stem == 'yolo11n-pose' and onnx_path.is_file() and manifest.is_file():
                import hashlib,json
                try:
                    evidence=json.loads(manifest.read_text(encoding='utf-8'))
                    approved=(evidence['originalSha256']==hashlib.sha256(Path(self.path).read_bytes()).hexdigest() and
                              evidence['onnxSha256']==hashlib.sha256(onnx_path.read_bytes()).hexdigest() and evidence.get('parity') is True)
                except (KeyError,ValueError,OSError):pass
            if approved:
                try:
                    from .onnx_pose import OnnxPose
                    runtime=OnnxPose(onnx_path)
                    self.acceleration=runtime.report
                    self.device=runtime.report['device']
                    if runtime.report['validated'] and 'TV_POSE_INTERVAL' not in os.environ:
                        self.inference_interval=.35
                    self.status='pose ativa · 17 pontos · '+self.device
                    self._onnx_loop(runtime)
                    return
                except Exception:
                    log.exception('ONNX pose unavailable; original model kept')
            import torch
            from ultralytics import YOLO
            # Ultralytics disables OpenCV threading during import for its
            # training DataLoader. This app uses a live inference worker, so
            # restore the two-thread budget shared with ball analysis.
            cv2.setNumThreads(2)
            # Ball tracking owns the real-time budget. One pose thread at a
            # lower cadence leaves decoder and colour tracking responsive.
            torch.set_num_threads(1)
            model = YOLO(self.path)
            device = '0' if torch.cuda.is_available() else 'cpu'
            self.device=device
            model.predict(np.zeros((self.model_size, self.model_size, 3), np.uint8),
                          imgsz=self.model_size, device=device, verbose=False)
            # select_device() inside the first YOLO prediction resets this to
            # eight CPU threads. Restore AFTER warmup; otherwise the pose
            # worker steals the ball/decoder budget as soon as it loads.
            if device == 'cpu':torch.set_num_threads(1)
            self.status = 'pose ativa · 17 pontos'
            while True:
                with self.condition:
                    self.condition.wait_for(lambda: self.pending is not None or self.stopped)
                    if self.stopped:
                        return
                    image, timestamp = self.pending
                    self.pending = None
                started = time.perf_counter()
                result = model.predict(image, imgsz=self.model_size, conf=.45, max_det=4,
                                       device=device, verbose=False)[0]
                persons = []
                if result.keypoints is not None:
                    for box, joints in zip(result.boxes, result.keypoints.data.cpu().numpy()):
                        x, y, right, bottom = box.xyxy[0].cpu().tolist()
                        persons.append(dict(x=x, y=y, width=right-x, height=bottom-y,
                                            centerX=(x+right)/2, centerY=(y+bottom)/2,
                                            confidence=float(box.conf[0]), source='pose',
                                            timestamp=timestamp, keypoints=joints.tolist()))
                self.inference_ms = (time.perf_counter()-started)*1000
                with self.condition:
                    self.result = (timestamp, cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), persons)
        except Exception:
            self.status = 'pose indisponível'
            log.exception('Pose worker stopped')

    def _onnx_loop(self, runtime):
        while True:
            with self.condition:
                self.condition.wait_for(lambda:self.pending is not None or self.stopped)
                if self.stopped:return
                image,timestamp=self.pending
                self.pending=None
            started=time.perf_counter()
            persons=runtime.predict(image,timestamp)
            self.inference_ms=(time.perf_counter()-started)*1000
            self.acceleration=dict(runtime.report)
            self.device=runtime.report['device']
            with self.condition:
                self.result=(timestamp,cv2.cvtColor(image,cv2.COLOR_BGR2GRAY),persons)

    def update(self, image, timestamp):
        if not self.available:
            return []
        scale = min(1., self.max_width/image.shape[1])
        small = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale < 1 else image.copy()
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        with self.condition:
            if self.last_submitted is None or timestamp-self.last_submitted >= self.inference_interval:
                self.pending = (small, timestamp)
                self.last_submitted = timestamp
                self.condition.notify()
            result = self.result
        if result is not None and result[0] != self.consumed:
            self.consumed = result[0]
            self.observed_at = result[0]
            self.persons = advance_pose(result[2], result[1], gray) if timestamp > result[0] else result[2]
        elif self.persons:
            self.persons = advance_pose(self.persons, self.previous_gray, gray)
        self.previous_gray = gray
        if self.observed_at is None or timestamp-self.observed_at > max(1.8,self.inference_interval+1.):
            self.persons = []
        output = []
        for person in self.persons:
            item = dict(person)
            for key in ('x','y','width','height','centerX','centerY'):
                item[key] /= scale
            item['keypoints'] = [[x/scale, y/scale, conf] for x,y,conf in person['keypoints']]
            item['poseAgeMs'] = max(0, (timestamp-self.observed_at)*1000)
            output.append(item)
        return output
