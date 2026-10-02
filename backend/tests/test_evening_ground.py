"""Regressions from the three 17 September evening recordings."""
import json
import threading
import time
from pathlib import Path
import cv2
import numpy as np
import pytest
from backend.ball_evidence import clear_of_body, annotate_ball_evidence
from backend.bounce import BounceDetector
from backend.camera import CameraCapture, FrameBuffer, Frame
from backend.calibration import CalibrationEngine
from backend.pipeline import Pipeline
from backend.target import classify_impact
from backend.tracking import BallTracker


def point(t, x, y, size=30., **extra):
    return dict(timestamp=t, centerX=x, centerY=y, measuredX=x, measuredY=y,
                x=x-size/2, y=y-size/2, width=size, height=size,
                ballDiameterPx=size, trackID=1, source='heuristic', predicted=False,
                tennisColor=True, tennisCorePixels=200, tennisCoreFraction=.4,
                tennisFraction=.8, ballScore=.86, roundness=.7, moving=True,
                groundSurfaceEligible=True, frameWidth=1920, frameHeight=1080,
                metersPerPixel=.067/size, **extra)


def test_recorded_contact_near_legs_is_localized_between_frames():
    detector=BounceDetector();events=[]
    # Measured phone observations: the two lowest frames are still airborne.
    samples=[(0,541.2,472.5,39.8),(.142,550.5,803.5,41.7),
             (.231,546.2,852.5,35.6),(.390,530,521.2,40.)]
    for t,x,y,size in samples:
        event=detector.update(point(t,x,y,size,insidePerson=True,clearOfBody=True),None)
        if event:events.append(event)
    assert len(events)==1
    assert events[0]['position']==pytest.approx([552,928],abs=3)
    assert events[0]['impactEstimatedBetweenFrames'] is True
    assert events[0]['positionUncertaintyPx']>=10


def test_ball_outside_body_is_not_discarded_with_a_missing_shoulder_joint():
    person=json.loads((Path(__file__).parent/'fixtures/evening_person_pose.json').read_text())
    ball=point(1,550.5,803.5,41.7,insidePerson=True)
    assert clear_of_body(ball,[person]) is True
    # A real hand and a ball held there must still suppress contact evidence.
    hand=person['keypoints'][9]
    assert clear_of_body(point(1,hand[0],hand[1],25,insidePerson=True),[person]) is False
    person['keypoints']=[]
    assert clear_of_body(ball,[person]) is False


def test_repeated_return_frames_do_not_recount_the_ground_contact():
    detector=BounceDetector();events=[]
    for t,y in [(0,500),(.10,800),(.20,850),(.30,600),(.48,400),(.58,300),(.7,260)]:
        event=detector.update(point(t,600,y),None)
        if event:events.append(event)
    assert len(events)==1


def test_lifting_ball_from_floor_does_not_prove_a_bounce():
    detector=BounceDetector()
    for t,y in [(0,950),(.08,850),(.16,750),(.24,650),(.32,550)]:
        assert detector.update(point(t,600,y),None) is None


def test_scoring_target_does_not_hide_floor_behind_its_far_edge():
    pipeline=Pipeline(None)
    pipeline.calibration=CalibrationEngine()
    pipeline.ball_diameter_m=.067
    pipeline.horizontal_fov_deg=70
    pipeline.region=None
    pipeline.polygon=[[.2,.8],[.7,.8],[.8,.95],[.1,.95]]
    ball=pipeline._enrich_track(point(1,700,735),[1920,1080])
    assert ball['groundSurfaceEligible'] is True
    decision=classify_impact([700,735],[1920,1080],polygon=pipeline.polygon,
                             ball_diameter_px=30,uncertainty_px=8)
    assert decision['inOrOut']=='OUT'


def test_unlocalized_turn_cannot_trigger_out_even_far_from_target():
    decision=classify_impact([100,900],[1920,1080],region=[.3,.7,.6,.9],
                             contact_located=False)
    assert decision['inOrOut']=='REVISAR'
    assert decision['varAlert'] is False


def test_return_near_gate_keeps_real_ball_identity():
    tracker=BallTracker();ids=[]
    # Reversal and an irregular next interval previously changed the ID.
    for t,x,y in [(0,580,685),(.145,637,832),(.257,637,802),(.359,664.5,559.5)]:
        track=tracker.update([point(t,x,y,38,autoAppearanceVerified=True,autoAppearanceScore=.95)],t)
        if track:ids.append(track['trackID'])
    assert len(set(ids))==1


def test_native_green_evidence_rejects_skin_coloured_motion():
    image=np.zeros((100,200,3),np.uint8)
    skin=cv2.cvtColor(np.uint8([[[17,130,190]]]),cv2.COLOR_HSV2BGR)[0,0]
    green=cv2.cvtColor(np.uint8([[[35,190,190]]]),cv2.COLOR_HSV2BGR)[0,0]
    image[35:65,35:65]=skin;image[35:65,135:165]=green
    proposals=[point(1,50,50),point(1,150,50)]
    annotate_ball_evidence(image,proposals,[])
    assert proposals[0]['tennisCorePixels']==0
    assert proposals[1]['tennisCorePixels']>500
    assert BallTracker._rank(proposals[1])>BallTracker._rank(proposals[0])


def test_small_ball_uses_native_colour_even_if_downsampled_core_is_lost():
    ball=point(1,600,800,7)
    ball.update(tennisCorePixels=12,tennisCoreFraction=.25,tennisVividPixels=0)
    assert BounceDetector._ground_identity(ball) is True


def test_direct_capture_does_not_encode_unused_mjpeg(monkeypatch):
    capture=CameraCapture('unused',FrameBuffer())
    capture.latest_frame=Frame(np.zeros((48,64,3),np.uint8),time.time(),time.monotonic(),1)
    encoded=threading.Event();real_encode=cv2.imencode
    def encode(*args,**kwargs):
        encoded.set()
        return real_encode(*args,**kwargs)
    monkeypatch.setattr(cv2,'imencode',encode)
    thread=threading.Thread(target=capture._encode_preview,daemon=True)
    thread.start()
    try:
        assert not encoded.wait(.06)
        capture.latest_preview()
        assert encoded.wait(1)
    finally:
        capture.stop_event.set()
        with capture.preview_condition:capture.preview_condition.notify_all()
        thread.join(1)
    assert not thread.is_alive()


def test_recorded_throw_keeps_body_overlap_as_approach_only():
    detector=BounceDetector(); event=None
    samples=[(27.381,721,260,False),(27.519,674,529,False),
             (27.768,641,703,True),(27.888,664,497,False)]
    for index,(t,x,y,surface) in enumerate(samples):
        value=point(t,x,y,30)
        value['groundSurfaceEligible']=surface
        if index==0:
            value.update(insidePerson=True,clearOfBody=False)
        event=detector.update(value,None,mode='court') or event
    assert event is not None
    assert event['contactEvidence']=='ground_interpolated_bounce'
    assert event['contactLocated'] is True
    assert event['position']==pytest.approx([621.5,849],abs=4)


def test_recorded_irregular_frames_skip_slow_airborne_sample():
    detector=BounceDetector(); event=None
    samples=[(34.375,784,259),(34.475,751,427),(34.697,742,477),
             (34.801,712,626),(34.908,730,460)]
    for index,(t,x,y) in enumerate(samples):
        value=point(t,x,y,30)
        value['groundSurfaceEligible']=index==3
        event=detector.update(value,None,mode='court') or event
    assert event is not None
    assert event['contactEvidence']=='ground_interpolated_bounce'
    assert event['position']==pytest.approx([686.5,793.5],abs=4)


def test_contact_corridor_only_calls_in_or_out_when_endpoints_agree():
    region=[.3,.2,.7,.8]
    inside=classify_impact([500,400],[1000,600],region=region,
                           possible_positions=[[500,450],[500,470]],uncertainty_px=4)
    crossing=classify_impact([500,400],[1000,600],region=region,
                             possible_positions=[[500,550]],uncertainty_px=4)
    outside=classify_impact([100,400],[1000,600],region=region,
                            possible_positions=[[150,430]],uncertainty_px=4)
    assert inside['inOrOut']=='IN'
    assert crossing['inOrOut']=='REVISAR'
    assert outside['inOrOut']=='OUT'


def test_real_lowest_frame_cannot_be_skipped_into_false_out():
    detector=BounceDetector(); event=None
    samples=[(63.707,949.5,403.5,False),(63.834,964.5,456,False),
             (63.973,926,792,True),(64.111,970.5,578,True),
             (64.231,995.5,461,False)]
    for t,x,y,surface in samples:
        value=point(t,x,y,30,insidePerson=True,clearOfBody=True)
        value['groundSurfaceEligible']=surface
        event=detector.update(value,None,mode='court') or event
    assert event is not None
    assert event['contactEvidence']=='ground_measured_bounce'
    assert event['position'][1]>=792
    decision=classify_impact(event['position'],[1920,1080],
                             polygon=[[.331,.521],[.651,.644],[.633,.974],[.149,.822]],
                             ball_diameter_px=event['ballDiameterPx'],
                             uncertainty_px=event['positionUncertaintyPx'],
                             possible_positions=event['possiblePositions'])
    assert decision['inOrOut']=='IN'
