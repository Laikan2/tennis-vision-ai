import threading
from backend.frame_publisher import LatestFramePublisher


def test_slow_encoder_never_queues_old_frames_or_separates_metadata():
    entered=threading.Event();release=threading.Event();published=[]
    def publish(frame,state,session):
        if frame==1:
            entered.set()
            assert release.wait(3)
        published.append((frame,state['sequence'],session))
    worker=LatestFramePublisher(publish)
    try:
        worker.submit(1,dict(sequence=1),'session')
        assert entered.wait(3)
        # Detection can keep publishing without waiting for JPEG compression.
        for sequence in range(2,101):worker.submit(sequence,dict(sequence=sequence),'session')
        assert worker.skipped==98
        assert published==[]
    finally:
        release.set();worker.stop()
    assert published==[(1,1,'session'),(100,100,'session')]
    assert not worker.thread.is_alive()


def test_pose_restores_cpu_budget_after_yolo_resets_it(monkeypatch):
    import sys
    from types import SimpleNamespace
    from backend.pose import PoseEstimator
    calls=[]
    torch=SimpleNamespace(set_num_threads=lambda n:calls.append(n),
                          cuda=SimpleNamespace(is_available=lambda:False))
    # Reproduce Ultralytics select_device's first-prediction side effect.
    model=SimpleNamespace(predict=lambda *args,**kwargs:torch.set_num_threads(8))
    monkeypatch.setitem(sys.modules,'torch',torch)
    monkeypatch.setitem(sys.modules,'ultralytics',SimpleNamespace(YOLO=lambda _:model))
    pose=PoseEstimator();pose.stopped=True
    pose._run()
    assert calls==[1,8,1]


def test_paced_preview_reads_latest_frame_after_wait_and_can_stop(monkeypatch):
    import time
    import cv2
    import numpy as np
    from backend.camera import CameraCapture, Frame, FrameBuffer
    first=threading.Event();paced=threading.Event();release=threading.Event();second=threading.Event()
    encoded=[]
    def encode(_extension,image,_options):
        encoded.append(int(image[0,0,0]))
        (first if len(encoded)==1 else second).set()
        return True,np.array([1,2,3],np.uint8)
    monkeypatch.setattr(cv2,'imencode',encode)
    capture=CameraCapture('unused',FrameBuffer())
    capture.preview_max_fps=10
    original_event=capture.stop_event
    class StopDuringPacing:
        def is_set(self):return original_event.is_set()
        def set(self):original_event.set();release.set()
        def wait(self,timeout):
            paced.set()
            assert release.wait(3)
            return original_event.is_set()
    capture.stop_event=StopDuringPacing()
    def publish(sequence):
        now=time.monotonic()
        frame=Frame(np.full((12,12,3),sequence,np.uint8),time.time(),now,sequence)
        with capture.preview_condition:
            capture.latest_frame=frame
            capture.preview_requested_until=now+5
            capture.preview_condition.notify_all()
        capture.buffer.push(frame)
    publish(1)
    capture.preview_thread=threading.Thread(target=capture._encode_preview)
    capture.preview_thread.start()
    try:
        assert first.wait(3)
        publish(2)
        assert paced.wait(3)
        # Capture keeps updating while preview waits. Frame 2 must not appear.
        for sequence in range(3,101):publish(sequence)
        assert capture.buffer.pop().sequence==100
        release.set()
        assert second.wait(3)
        assert encoded==[1,100]
    finally:
        capture.stop()
    assert not capture.preview_thread.is_alive()
