"""Missed floor returns and bursty phone timestamps from 18 September."""
import json
from pathlib import Path
import numpy as np
import pytest
from backend.ball_evidence import clear_of_body
from backend.bounce import BounceDetector
from backend.camera import CameraCapture, FrameBuffer
from backend.observation_time import observation_times
from backend.speed import estimate_segment_speed, SpeedEstimator
from backend.tests.test_evening_ground import point


def recorded():
    return json.loads((Path(__file__).parent/'fixtures/afternoon_floor_reacquisition.json').read_text())


def test_free_ball_outside_streak_margin_with_one_lost_wrist():
    row=min(recorded(),key=lambda r:abs(r['track']['timestamp']-1.843))
    assert row['track']['insidePerson'] is True
    assert clear_of_body(row['track'],row['persons']) is True
    # Moving that same ball onto an observed hand must still exclude it.
    person=row['persons'][0]; wrist=person['keypoints'][10]
    held=point(1,wrist[0],wrist[1],35,insidePerson=True)
    assert clear_of_body(held,[person]) is False


def test_missing_wrist_near_ball_stays_conservative():
    row=min(recorded(),key=lambda r:abs(r['track']['timestamp']-2.758))
    ball=row['track'];person=row['persons'][0]
    assert clear_of_body(ball,[person]) is True
    person['keypoints'][9]=[ball['centerX']+20,ball['centerY'],0]
    assert clear_of_body(ball,[person]) is False


def test_visible_hand_outside_stale_body_box_is_still_excluded():
    row=recorded()[0];person=row['persons'][0]
    wrist=next(j for j in person['keypoints'][9:11] if j[2]>=.35)
    held=point(1,wrist[0],wrist[1],35,insidePerson=True)
    person['x']+=600
    assert clear_of_body(held,[person]) is False


def test_recorded_fast_return_survives_new_tracker_id():
    detector=BounceDetector();events=[]
    for row in recorded():
        ball=row['track']
        if ball.get('insidePerson'):
            ball['clearOfBody']=clear_of_body(ball,row['persons'])
        event=detector.update(ball,None)
        if event:events.append(event)
    recovered=[e for e in events if 2.4<e['timestamp']<2.7]
    assert len(recovered)==1
    assert recovered[0]['position'][0]==pytest.approx(963,abs=25)
    assert 925<recovered[0]['position'][1]<1060


def test_static_other_object_cannot_bridge_a_fast_return():
    a=point(0,1000,900)
    b=point(.08,995,600)
    b.update(trackID=2,moving=False)
    assert not BounceDetector._continuous_ground_ball(a,b)
    b.update(moving=True,centerX=50,measuredX=50)
    assert not BounceDetector._continuous_ground_ball(a,b)


def test_source_time_prevents_burst_arrival_from_inflating_speed():
    rows=[point(t,600+i*100,700,sourceTime=i*.1,sourceClock=1)
          for i,t in enumerate([10.,10.001,10.002])]
    speed,method=estimate_segment_speed(rows)
    assert speed==pytest.approx(1000*.067/30*3.6)
    estimator=SpeedEstimator(.067)
    for row in rows:result=estimator.update(row,None)
    assert result['projectedKmh']==pytest.approx(speed)
    assert method=='ball_diameter_estimate'


def test_ground_intersection_uses_video_time_and_keeps_replay_time():
    detector=BounceDetector();events=[]
    rows=[point(t,600+i*10,y,sourceTime=i*.1,sourceClock=1)
          for i,(t,y) in enumerate([(10.,600),(10.16,850),(10.17,850),(10.33,600)])]
    for row in rows:
        event=detector.update(row,None)
        if event:events.append(event)
    assert len(events)==1
    assert events[0]['position']==pytest.approx([615,975])
    assert events[0]['timestamp']==pytest.approx(10.165)


def test_clock_change_never_joins_unrelated_source_times():
    rows=[dict(timestamp=1,sourceTime=9,sourceClock=1),
          dict(timestamp=1.1,sourceTime=.1,sourceClock=2)]
    assert observation_times(rows)==pytest.approx([1,1.1])


def test_skipped_contact_below_frame_requires_review():
    detector=BounceDetector();event=None
    for t,y in [(0,600),(.1,1050),(.2,700),(.3,400)]:
        event=detector.update(point(t,600,y),None) or event
    assert event is not None
    assert event['contactLocated'] is False


def test_capture_keeps_source_time_separate_from_arrival():
    frames=[]
    class Sink:
        def push(self,frame):frames.append(frame)
    class Source:
        def __init__(self):self.index=-1
        def isOpened(self):return True
        def read(self):
            self.index+=1
            if self.index==3:capture.stop_event.set()
            return True,np.zeros((16,16,3),np.uint8)
        def get(self,_):return [0.,16.667,0.,16.667][self.index]
        def release(self):pass
    capture=CameraCapture('unused',Sink(),Source)
    capture._run()
    assert frames[1].source_time==pytest.approx(.016667)
    assert frames[0].source_time is None
    assert frames[3].source_clock!=frames[1].source_clock
    assert frames[1].timestamp>1000000000
