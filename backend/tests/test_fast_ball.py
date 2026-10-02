import cv2
import numpy as np
import pytest
from backend.detection import BallDetector
from backend.tracking import BallTracker
from backend.appearance import features, patch_for, verification_patch


@pytest.mark.parametrize('radius', [4, 6])
def test_native_tiles_acquire_small_ball_without_prior_and_follow_fast_motion(monkeypatch, tmp_path, radius):
    monkeypatch.setenv('TV_MODEL_PATH', '')
    monkeypatch.setenv('TV_APPEARANCE_DIR', str(tmp_path))
    detector = BallDetector(external_pose=True)
    tracker = BallTracker()
    wall = np.full((1080, 1920, 3), 150, np.uint8)
    for i in range(25): detector.detect(wall, i / 60)
    observed = []
    for i, x in enumerate([400, 480, 560, 640, 720]):
        frame = wall.copy()
        cv2.circle(frame, (x, 400), radius, (20, 225, 170), -1)
        t = .5 + i / 60
        candidates = detector.detect(frame, t)
        ball = next(c for c in candidates if abs(c['centerX'] - x) < 3)
        assert ball['tennisColor'] and ball['nativeResolution']
        assert ball['ballDiameterPx'] == pytest.approx(radius * 2, abs=3)
        track = tracker.update(candidates, t)
        if track and not track.get('predicted'):
            assert track['centerX'] == pytest.approx(x, abs=8)
            observed.append(track['trackID'])
    assert len(observed) >= 4
    assert len(set(observed)) == 1


def test_moving_hand_does_not_make_static_green_dot_a_ball(monkeypatch, tmp_path):
    monkeypatch.setenv('TV_MODEL_PATH', '')
    monkeypatch.setenv('TV_APPEARANCE_DIR', str(tmp_path))
    detector = BallDetector(external_pose=True)
    wall = np.full((1080, 1920, 3), 150, np.uint8)
    cv2.circle(wall, (500, 400), 4, (20, 225, 170), -1)
    for i in range(25): detector.detect(wall, i / 60)
    frame = wall.copy()
    cv2.rectangle(frame, (490, 410), (600, 490), (80, 140, 195), -1)
    candidates = detector.detect(frame, .5)
    assert not any(c.get('tennisColor') and abs(c['centerX'] - 500) < 5 for c in candidates)


def test_blurred_ball_keeps_color_support_after_verifier_downsampling():
    wall = np.full((300, 400, 3), 170, np.uint8)
    cv2.rectangle(wall, (150, 147), (249, 152), (20, 225, 170), -1)
    box = [150, 147, 100, 6]
    previous = features(patch_for(wall, box))[-1]
    preserved = features(verification_patch(wall, box))[-1]
    assert preserved >= .5
    assert preserved > previous
    assert verification_patch(wall, [150, 140, 14, 14]).shape == patch_for(wall, [150, 140, 14, 14]).shape
