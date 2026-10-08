"""Pixel-to-contact regressions for the phone stream; all frames are synthetic."""
import cv2
import numpy as np
import pytest
from backend.detection import BallDetector
from backend.tracking import BallTracker
from backend.bounce import BounceDetector
from backend.pipeline import Pipeline
from backend.calibration import CalibrationEngine
from backend.target import classify_impact


@pytest.fixture(autouse=True)
def bounded_opencv_threads():
    previous=cv2.getNumThreads()
    cv2.setNumThreads(2)
    yield
    cv2.setNumThreads(previous)


def flight(fps, lighting, x, blur=False):
    detector=BallDetector(external_pose=True)
    tracker=BallTracker(green_only=True)
    bounce=BounceDetector()
    pipeline=Pipeline(None)
    pipeline.calibration=CalibrationEngine()
    pipeline.ball_diameter_m=.067
    pipeline.horizontal_fov_deg=70
    pipeline.region=[.3,.5,.7,.9]
    pipeline.polygon=None
    pipeline.floor_boundary=[[.05,.52],[.95,.52]]
    detector.region=pipeline.region
    wall=np.full((360,640,3),145,np.uint8)
    for col in range(0,640,40): cv2.line(wall,(col,0),(col,359),(135,135,135),1)
    for i in range(25): detector.detect(wall,i/fps)
    events=[]; observed=0
    for i in range(round(.6*fps)+1):
        t=1+i/fps
        x_now=x+int(120*(i/fps-.3));y=round(275-500*abs(i/fps-.3))
        image=wall.copy()
        cv2.ellipse(image,(x_now,y),(13 if blur else 7,7),20,0,360,(25,215,160),-1)
        if lighting: image=cv2.add(image,(-24 if i%2 else 24,)*3+(0,))
        detector.prior=tracker.hint(t,t,1)
        candidates=detector.detect(image,t)
        track=tracker.update(candidates,t,t,1)
        if track:
            pipeline._enrich_track(track,[640,360])
            observed+=not track.get('predicted')
            if not track['predicted'] and bounce.track_id!=track['trackID']:
                for prior in [p for p in tracker.history if not p.get('predicted') and p['trackID']==track['trackID'] and p['timestamp']<t][-2:]:
                    bounce.update(pipeline._enrich_track(dict(prior),[640,360]),None,mode='court')
        event=bounce.update(track,None,mode='court',timestamp=t)
        if event:
            event.update(classify_impact(event['position'],[640,360],region=pipeline.region,
                ball_diameter_px=event.get('ballDiameterPx',0),uncertainty_px=event.get('positionUncertaintyPx',2),
                contact_located=event.get('contactLocated',True),possible_positions=event.get('possiblePositions')))
            events.append(event)
    return events,observed


@pytest.mark.parametrize('fps',[30,60,120,240])
@pytest.mark.parametrize('lighting',[False,True])
@pytest.mark.parametrize('x,decision',[(320,'IN'),(510,'OUT')])
def test_pixels_to_floor_contact_inside_and_outside(monkeypatch,tmp_path,fps,lighting,x,decision):
    monkeypatch.setenv('TV_MODEL_PATH','')
    monkeypatch.setenv('TV_APPEARANCE_DIR',str(tmp_path))
    events,observed=flight(fps,lighting,x)
    assert observed>=.8*round(.6*fps)
    assert len(events)==1
    assert events[0]['inOrOut']==decision
    assert events[0]['position']==pytest.approx([x,275],abs=12)


@pytest.mark.parametrize('fps',[60,120,240])
def test_motion_blur_still_produces_one_outside_contact(monkeypatch,tmp_path,fps):
    monkeypatch.setenv('TV_MODEL_PATH','')
    monkeypatch.setenv('TV_APPEARANCE_DIR',str(tmp_path))
    events,observed=flight(fps,True,510,blur=True)
    assert observed>=.8*round(.6*fps)
    assert len(events)==1 and events[0]['inOrOut']=='OUT'


def test_exposure_change_cannot_turn_stationary_green_object_into_ball(monkeypatch,tmp_path):
    monkeypatch.setenv('TV_MODEL_PATH','')
    monkeypatch.setenv('TV_APPEARANCE_DIR',str(tmp_path))
    detector=BallDetector(external_pose=True)
    tracker=BallTracker(green_only=True)
    wall=np.full((360,640,3),145,np.uint8)
    cv2.circle(wall,(320,240),8,(25,215,160),-1)
    for i in range(25):detector.detect(wall,i/60)
    for i in range(12):
        image=cv2.add(wall,(-24 if i%2 else 24,)*3+(0,))
        candidates=detector.detect(image,1+i/60)
        assert all(not candidate['moving'] for candidate in candidates)
        assert tracker.update(candidates,1+i/60) is None


def test_textured_pan_still_disables_impact_evidence(monkeypatch,tmp_path):
    monkeypatch.setenv('TV_MODEL_PATH','')
    monkeypatch.setenv('TV_APPEARANCE_DIR',str(tmp_path))
    detector=BallDetector(external_pose=True)
    wall=np.random.default_rng(19).integers(30,220,(360,640,3),dtype=np.uint8)
    for i in range(25):detector.detect(wall,i/60)
    detector.detect(np.roll(wall,20,axis=1),1)
    assert detector.camera_moving
