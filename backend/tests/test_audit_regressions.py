"""Failures found in the app audit; fixtures never modify saved camera data."""
import time
from types import SimpleNamespace
import cv2
import numpy as np
import pytest
from backend.camera import CameraCapture, Frame, FrameBuffer
from backend.detection import BallDetector


@pytest.mark.parametrize('first_has_person', [True, False])
def test_latest_pose_is_used_even_when_ball_model_skips_a_frame(monkeypatch, first_has_person):
    monkeypatch.setenv('TV_MODEL_PATH', '')
    detector = BallDetector(external_pose=True)
    detector.model = SimpleNamespace(predict=lambda *a, **kw: [SimpleNamespace(boxes=[])], names={})
    image = np.zeros((240, 320, 3), np.uint8)
    person = dict(x=20., y=30., width=80., height=150., centerX=60., centerY=105., confidence=.9)
    detector.detect(image, 1., [person] if first_has_person else [])
    moved = dict(person, x=140., centerX=180.)
    detector.detect(image, 1.02, [moved])
    assert detector.persons[0]['centerX'] == 180.
    assert detector.person_count == 1


def test_on_demand_jpeg_cannot_replace_preview_image_without_its_metadata():
    capture = CameraCapture('unused', FrameBuffer())
    first = Frame(np.zeros((24, 24, 3), np.uint8), time.time(), time.monotonic(), 1)
    second = Frame(np.full((24, 24, 3), 220, np.uint8), time.time(), time.monotonic(), 2)
    capture.latest_frame = second
    capture.preview_frame = first
    ok, encoded = cv2.imencode('.jpg', first.image)
    assert ok
    capture.jpeg = encoded.tobytes()
    capture.jpeg_sequence = 1
    capture.jpeg_quality = 80
    raw, raw_frame = capture.latest_jpeg(85)
    assert raw_frame.sequence == 2
    assert cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR).mean() > 200
    preview, metadata = capture.latest_preview()
    image = cv2.imdecode(np.frombuffer(preview, np.uint8), cv2.IMREAD_COLOR)
    assert image.mean() < 10 if metadata.sequence == 1 else image.mean() > 200


@pytest.mark.parametrize('key,value,attribute,expected', [
    ('TV_PREVIEW_QUALITY', '', 'preview_quality', 80),
    ('TV_PREVIEW_QUALITY', 'high', 'preview_quality', 80),
    ('TV_PREVIEW_MAX_WIDTH', 'auto', 'preview_max_width', 1600),
    ('TV_PREVIEW_MAX_WIDTH', '', 'preview_max_width', 1600),
    ('TV_PREVIEW_MAX_FPS', 'auto', 'preview_max_fps', 60),
])
def test_invalid_preview_setting_does_not_prevent_camera_start(monkeypatch, key, value, attribute, expected):
    monkeypatch.setenv(key, value)
    assert getattr(CameraCapture('unused', FrameBuffer()), attribute) == expected


def test_partial_second_camera_start_releases_both_workers(monkeypatch, tmp_path):
    import backend.multi_pipeline as module
    from backend.database import Store
    workers = []
    class Worker:
        def __init__(self, *a, **kw):
            self.active = False
            self.capture = None
            workers.append(self)
        def start(self, *a):
            self.active = True
            if len(workers) == 2:
                raise RuntimeError('failure after allocating a resource')
        def stop(self, save_session=True):
            assert not save_session
            self.active = False
    monkeypatch.setattr(module, 'Pipeline', Worker)
    manager = module.PipelineManager(Store(tmp_path / 'db.sqlite'))
    workers.clear()  # idle placeholder has no allocated resource
    with pytest.raises(RuntimeError):
        manager.start_many([dict(id='a'), dict(id='b')], ['a', 'b'], dict(id='s'))
    assert len(workers) == 2
    assert all(not worker.active for worker in workers)
    assert manager.session is None and not manager.pipelines


@pytest.mark.parametrize('during_start', [True, False])
def test_one_cleanup_error_does_not_leave_other_camera_running(monkeypatch, tmp_path, during_start):
    import backend.multi_pipeline as module
    from backend.database import Store
    workers = []
    class Worker:
        def __init__(self, *args, **kwargs):
            self.active = False
            self.capture = None
            workers.append(self)
        def start(self, *args):
            self.active = True
            if during_start and len(workers) == 2:
                raise RuntimeError('startup failure')
        def stop(self, save_session=True):
            self.active = False
            if self is workers[0]:
                raise RuntimeError('cleanup failure')
    monkeypatch.setattr(module, 'Pipeline', Worker)
    manager = module.PipelineManager(Store(tmp_path / 'db.sqlite'))
    workers.clear()
    session = dict(id='s', name='test')
    if during_start:
        with pytest.raises(RuntimeError, match='startup failure'):
            manager.start_many([dict(id='a'), dict(id='b')], ['a', 'b'], session)
    else:
        manager.start_many([dict(id='a'), dict(id='b')], ['a', 'b'], session)
        with pytest.raises(Exception):
            manager.stop()
        assert manager.store.get('session', 's')['endedAt'] > 0
    assert all(not worker.active for worker in workers)
    assert manager.session is None and not manager.pipelines
