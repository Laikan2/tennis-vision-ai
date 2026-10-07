"""Bounded latest-frame capture. Network decode never waits for a browser."""
from collections import deque
from dataclasses import dataclass
import logging
import os
import threading
import time
import cv2
import numpy as np

log = logging.getLogger('tennis.capture')


@dataclass
class Frame:
    image: np.ndarray
    timestamp: float
    monotonic: float
    sequence: int
    source_time: float | None = None
    source_clock: int | None = None


class FrameBuffer:
    def __init__(self, capacity=1, preserve_all=False, stop_event=None):
        self.items = deque(maxlen=capacity)
        self.condition = threading.Condition()
        self.dropped = 0
        self.preserve_all = preserve_all
        self.stop_event = stop_event

    def push(self, frame):
        with self.condition:
            while self.preserve_all and len(self.items)==self.items.maxlen:
                if self.stop_event and self.stop_event.is_set():return
                self.condition.wait(.1)
            if len(self.items) == self.items.maxlen:
                self.dropped += 1
            self.items.append(frame)
            self.condition.notify()

    def pop(self, timeout=.5):
        with self.condition:
            if not self.items:
                self.condition.wait(timeout)
            if not self.items:
                return None
            if self.preserve_all:
                frame=self.items.popleft()
                self.condition.notify_all()
                return frame
            # The capacity may exceed one, but processing always consumes newest.
            self.dropped += len(self.items) - 1
            latest = self.items[-1]
            self.items.clear()
            return latest


class CameraCapture:
    def __init__(self, url, buffer, source_factory=None):
        self.url = url
        self.buffer = buffer
        self.source_factory = source_factory or self._open
        self.stop_event = threading.Event()
        self.thread = None
        self.status = 'offline'
        self.frames = 0
        self.fps = None
        self.resolution = None
        self.last_received = None
        self.error = None
        self.latest_lock = threading.Lock()
        self.preview_condition = threading.Condition(self.latest_lock)
        self.latest_frame = None
        self.preview_frame = None
        self.preview_thread = None
        self.preview_fps = None
        try:self.preview_max_fps=max(10,min(120,int(os.getenv('TV_PREVIEW_MAX_FPS','60'))))
        except ValueError:self.preview_max_fps=60
        try:self.preview_quality=max(65,min(90,int(os.getenv('TV_PREVIEW_QUALITY','80'))))
        except ValueError:self.preview_quality=80
        try:self.preview_max_width=max(960,min(3840,int(os.getenv('TV_PREVIEW_MAX_WIDTH','1600'))))
        except ValueError:self.preview_max_width=1600
        self.jpeg_sequence = None
        self.jpeg_quality = None
        self.jpeg = None
        self.raw_jpeg_cache = None
        self.preview_requested_until = 0.
        self.source_timing_available = False
        self.arrival_gap_p95_ms = None
        self.connection_generation = 0

    def _open(self):
        # Do not log the URL: it contains credentials.
        # OpenCV/FFmpeg otherwise keeps an internal RTSP queue. That queue is
        # useful for files, but turns a live camera into delayed playback when
        # inference temporarily falls behind. Users can override the options
        # for unusual cameras through TV_FFMPEG_CAPTURE_OPTIONS.
        os.environ['OPENCV_FFMPEG_CAPTURE_OPTIONS'] = (
            os.getenv('TV_FFMPEG_CAPTURE_OPTIONS') or
            os.environ.get('OPENCV_FFMPEG_CAPTURE_OPTIONS') or
            'rtsp_transport;tcp|fflags;nobuffer|flags;low_delay'
        )
        # Auto selected 16 decoder threads on the supplied PC. Frame threading
        # can retain one additional frame per extra thread. Two threads decoded
        # the supplied H.264 recording as fast as 16 without that long pipeline.
        # This controls decoder concurrency, never the source frame rate.
        try:decoder_threads=max(1,min(16,int(os.getenv('TV_DECODER_THREADS','2'))))
        except ValueError:decoder_threads=2
        parameters=[cv2.CAP_PROP_OPEN_TIMEOUT_MSEC,4000,cv2.CAP_PROP_READ_TIMEOUT_MSEC,2000]
        if hasattr(cv2,'CAP_PROP_N_THREADS'):
            parameters.extend([cv2.CAP_PROP_N_THREADS,decoder_threads])
        cap = cv2.VideoCapture(self.url, cv2.CAP_FFMPEG, parameters)
        # OpenCV backends may ignore this property, but when FFmpeg honors it
        # it prevents a growing decode queue from turning the live view into a
        # delayed recording.
        try:
            cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        except Exception:
            pass
        return cap

    def start(self):
        self.preview_thread = threading.Thread(target=self._encode_preview, daemon=True,
                                               name='preview-encoder')
        self.preview_thread.start()
        self.thread = threading.Thread(target=self._run, daemon=True, name='rtsp-capture')
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        with self.preview_condition:
            self.preview_condition.notify_all()
        if self.thread:
            self.thread.join(timeout=7)
        if self.preview_thread:
            self.preview_thread.join(timeout=3)

    def latest_preview(self):
        """Return an already encoded frame without doing work in the HTTP request."""
        with self.preview_condition:
            # WebRTC plays the original H.264 stream. Encoding an unused
            # 1600px JPEG for every capture used CPU needed by ball analysis.
            # MJPEG/frame consumers renew this short demand lease by reading.
            self.preview_requested_until = time.monotonic() + 1.
            self.preview_condition.notify_all()
            return self.jpeg, self.preview_frame

    def latest_jpeg(self, quality=80):
        """Return the newest decoded image, independently of AI throughput."""
        with self.latest_lock:
            frame = self.latest_frame
            if frame is None:
                return None, None
            cached = self.raw_jpeg_cache
            if cached and cached[1].sequence == frame.sequence and cached[2] == quality:
                return cached[0], cached[1]
            image = frame.image
        ok, encoded = cv2.imencode('.jpg', image, [cv2.IMWRITE_JPEG_QUALITY, quality])
        if not ok:
            return None, frame
        jpeg = encoded.tobytes()
        with self.latest_lock:
            if self.latest_frame is not None and self.latest_frame.sequence == frame.sequence:
                # Original-resolution reads must not mutate the paced preview:
                # its JPEG and metadata are published together by its worker.
                self.raw_jpeg_cache = (jpeg, frame, quality)
        return jpeg, frame

    def _encode_preview(self):
        """Encode the newest capture on a bounded worker instead of an HTTP request."""
        previous = None
        times = deque(maxlen=90)
        next_encode_at=0.
        while not self.stop_event.is_set():
            with self.preview_condition:
                self.preview_condition.wait_for(
                    lambda: self.stop_event.is_set() or
                    (self.latest_frame is not None and self.latest_frame.sequence != previous and
                     time.monotonic() < self.preview_requested_until),
                    timeout=.5,
                )
                if self.stop_event.is_set():
                    return
                frame = self.latest_frame
                # ``wait_for`` also returns after its timeout. The first RTSP
                # frame may take longer than that, so keep waiting instead of
                # trying to encode a missing (or already encoded) image.
                if (frame is None or frame.sequence == previous or
                        time.monotonic() >= self.preview_requested_until):
                    continue
            # Only the fallback JPEG display is paced. Source decode and ball
            # analysis stay independent. Read the latest frame after waiting,
            # never retain the older image that woke the worker.
            delay=next_encode_at-time.monotonic()
            if delay>0 and self.stop_event.wait(delay):return
            with self.latest_lock:
                frame=self.latest_frame
                if (frame is None or frame.sequence==previous or
                        time.monotonic() >= self.preview_requested_until):continue
            encode_started=time.monotonic()
            image = frame.image
            if image.shape[1] > self.preview_max_width:
                height = max(1, round(image.shape[0]*self.preview_max_width/image.shape[1]))
                image = cv2.resize(image, (self.preview_max_width, height), interpolation=cv2.INTER_AREA)
            ok, encoded = cv2.imencode('.jpg', image,
                                      [cv2.IMWRITE_JPEG_QUALITY, self.preview_quality])
            if not ok:
                continue
            now = time.monotonic()
            times.append(now)
            with self.latest_lock:
                self.jpeg = encoded.tobytes()
                self.jpeg_sequence = frame.sequence
                self.jpeg_quality = self.preview_quality
                self.preview_frame = frame
                self.preview_fps = ((len(times)-1)/(times[-1]-times[0])
                                    if len(times)>1 and times[-1]>times[0] else None)
            previous = frame.sequence
            next_encode_at=encode_started+1/self.preview_max_fps

    def _run(self):
        times = deque(maxlen=120)
        while not self.stop_event.is_set():
            cap = None
            try:
                self.status = 'connecting'
                cap = self.source_factory()
                if not cap.isOpened():
                    raise RuntimeError('open')
                log.info('Camera connected')
                times.clear()
                self.connection_generation += 1
                previous_source_time = None
                self.source_timing_available = False
                while not self.stop_event.is_set():
                    ok, image = cap.read()
                    if not ok:
                        raise RuntimeError('read')
                    now = time.monotonic()
                    self.frames += 1
                    times.append(now)
                    if len(times)>2 and self.frames%15==0:
                        self.arrival_gap_p95_ms = float(np.percentile(np.diff(times)*1000,95))
                    self.fps = (len(times)-1)/(times[-1]-times[0]) if len(times)>1 and times[-1]>times[0] else None
                    self.resolution = [image.shape[1], image.shape[0]]
                    self.last_received = time.time()
                    self.status, self.error = 'online', None
                    source_time = None
                    # FFmpeg exposes presentation time even when TCP delivers
                    # several frames together. Never use packet-arrival gaps
                    # as the time a ball took to travel between those images.
                    try:
                        seconds=float(cap.get(cv2.CAP_PROP_POS_MSEC))/1000
                        if np.isfinite(seconds) and seconds>=0:
                            if previous_source_time is not None and seconds>previous_source_time:
                                source_time=seconds
                            elif previous_source_time is not None and seconds<previous_source_time:
                                self.connection_generation += 1
                            previous_source_time=seconds
                    except (AttributeError,TypeError,ValueError,cv2.error):
                        pass
                    self.source_timing_available = source_time is not None
                    frame = Frame(image, self.last_received, now, self.frames,
                                  source_time,self.connection_generation)
                    with self.preview_condition:
                        self.latest_frame = frame
                        self.preview_condition.notify()
                    self.buffer.push(frame)
            except Exception:
                self.status = 'offline'
                self.fps = None
                self.error = 'Stream indisponível. Verifique rede, RTSP e credenciais.'
                log.warning('Camera disconnected; reconnect scheduled')
            finally:
                if cap is not None:
                    cap.release()
            self.stop_event.wait(2)
        self.status = 'offline'


class FileCapture(CameraCapture):
    """Read an original recording sequentially, with back-pressure and no loop."""
    def __init__(self, path, buffer, epoch=None):
        super().__init__(str(path), buffer, source_factory=lambda: cv2.VideoCapture(str(path)))
        if buffer.stop_event is None:buffer.stop_event=self.stop_event
        self.epoch=epoch if epoch is not None else time.time()
        self.eof=False
        self.failed=False

    def _run(self):
        cap=None
        try:
            cap=self.source_factory()
            if not cap.isOpened():raise ValueError('open')
            fps=float(cap.get(cv2.CAP_PROP_FPS))
            if not np.isfinite(fps) or fps<=0:raise ValueError('fps')
            self.fps=fps
            previous=-1.
            while not self.stop_event.is_set():
                ok,image=cap.read()
                if not ok:break
                source=float(cap.get(cv2.CAP_PROP_POS_MSEC))/1000
                if not np.isfinite(source) or source<0 or source<=previous:
                    source=self.frames/fps
                previous=source
                self.frames+=1
                self.last_received=time.time()
                self.resolution=[image.shape[1],image.shape[0]]
                self.status='online'
                self.source_timing_available=True
                frame=Frame(image,self.epoch+source,time.monotonic(),self.frames,source,1)
                with self.preview_condition:
                    self.latest_frame=frame;self.preview_condition.notify_all()
                self.buffer.push(frame)
            self.eof=True
        except Exception:
            self.failed=True;self.eof=True
            self.error='Não foi possível decodificar a gravação.'
        finally:
            if cap is not None:cap.release()
            self.status='finished' if not self.failed else 'offline'
