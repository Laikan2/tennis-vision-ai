"""Regression sequences for missed OUT contacts and false trajectory joins."""
import numpy as np
import pytest
import cv2
from backend.bounce import BounceDetector
from backend.target import classify_impact
from backend.speed import SpeedEstimator, estimate_segment_speed
from backend.detection import BallDetector
from backend.tracking import BallTracker


def observation(t, x, y=180, size=20, **extra):
    value=dict(timestamp=t, centerX=x, centerY=y, measuredX=x, measuredY=y,
               width=size, height=size, ballDiameterPx=size, trackID=1,
               predicted=False, source='heuristic', tennisColor=True,
               tennisFraction=.9, ballScore=.9, roundness=.8, moving=True,
               insidePerson=False, frameWidth=1280, frameHeight=720,
               metersPerPixel=.067/size)
    value.update(extra)
    return value


@pytest.mark.parametrize('inside', [True, False])
def test_wall_approach_waits_for_measured_return_on_both_sides_of_target(inside):
    b=BounceDetector()
    for i,(x,size,z) in enumerate([(400,30,1.6),(420,23,2.1),(440,16,3.0)]):
        p=observation(i*.06,x,size=size,insideTarget=inside,depthMeters=z,depthReliable=True)
        assert b.update(p,None,mode='room') is None
    event=b.update(dict(p,timestamp=.24,predicted=True),None,mode='room')
    assert event is None
    assert b.update(observation(.26,420,size=23,insideTarget=inside,
                                depthMeters=2.1,depthReliable=True),None,mode='room') is None
    event=b.update(observation(.32,400,size=30,insideTarget=inside,
                               depthMeters=1.6,depthReliable=True),None,mode='room')
    assert event is not None
    assert event['position'] == pytest.approx([440,180])


@pytest.mark.parametrize('inside', [True, False])
def test_two_strong_frames_retain_fast_wall_approach(inside):
    detector=BounceDetector()
    first=observation(0,360,size=30,insideTarget=inside,
                      depthMeters=1.5,depthReliable=True)
    second=observation(.08,430,size=16,insideTarget=inside,
                       depthMeters=3.,depthReliable=True)
    assert detector.update(first,None,mode='room') is None
    assert detector.update(second,None,mode='room') is None
    event=detector.update(dict(second,timestamp=.22,predicted=True),None,mode='room')
    assert event is None


def test_two_weak_green_fragments_do_not_become_wall_contact():
    detector=BounceDetector()
    for i,(x,size) in enumerate([(360,30),(430,16)]):
        point=observation(i*.08,x,size=size,insideTarget=False,
                          depthMeters=1.5+i*1.5,depthReliable=True)
        point.update(ballScore=.5,tennisFraction=.35)
        assert detector.update(point,None,mode='room') is None
    assert detector.update(dict(point,timestamp=.22,predicted=True),None,mode='room') is None


def test_slow_size_jitter_does_not_become_wall_contact():
    detector=BounceDetector()
    for i,(size,depth) in enumerate([(22,2.),(21,2.04),(20,2.08)]):
        point=observation(i*.12,400,size=size,insideTarget=False,
                          depthMeters=depth,depthReliable=True)
        assert detector.update(point,None,mode='room') is None
    assert detector.update(dict(point,timestamp=.36,predicted=True),None,mode='room') is None


def test_ball_carried_by_person_never_becomes_wall_contact():
    detector=BounceDetector()
    for i,(x,size,depth) in enumerate([(300,30,1.5),(350,22,2.1),(410,15,3.0)]):
        point=observation(i*.06,x,size=size,insideTarget=False,
                          depthMeters=depth,depthReliable=True,insidePerson=True)
        assert detector.update(point,None,mode='room') is None
    assert detector.update(dict(point,timestamp=.24,predicted=True),None,mode='room') is None


def test_one_wall_throw_cannot_emit_two_impacts_on_same_track():
    detector=BounceDetector();events=[]
    for i,x in enumerate([300,340,380,340,300,340,380,340,300]):
        event=detector.update(observation(i*.05,x,insideTarget=False),None,mode='room')
        if event: events.append(event)
    assert len(events)==1


def test_ground_mode_rejects_air_apex_and_person_motion():
    for ys,inside_person in [([30,20,10,0,10,20,30],False),
                             ([10,20,30,40,30,20,10],True)]:
        detector=BounceDetector()
        for i,y in enumerate(ys):
            point=observation(i*.03,100,y,insideTarget=True,insidePerson=inside_person)
            assert detector.update(point,None,mode='court') is None


def test_ground_mode_confirms_fast_bounce_with_one_descent_and_two_returns():
    detector=BounceDetector();event=None
    # Recorded phone streams frequently skip the middle of the descent. The
    # measured contact and two coherent return samples still prove the bounce.
    for t,y in [(0.,700),(.16,1000),(.26,820),(.36,700)]:
        point=observation(t,620,y,groundSurfaceEligible=True)
        event=detector.update(point,None,mode='court') or event
    assert event is not None
    assert event['position']==pytest.approx([620,1000])
    assert event['contactEvidence']=='ground_measured_bounce'


def test_ground_mode_bridges_release_to_reacquired_ball_at_contact():
    detector=BounceDetector();event=None
    held=observation(0.,540,620,insidePerson=True,trackID=8)
    assert detector.update(held,None,mode='court') is None
    for t,y in [(.16,1010),(.25,820),(.34,690)]:
        point=observation(t,570,y,trackID=9,groundSurfaceEligible=True)
        event=detector.update(point,None,mode='court') or event
    assert event is not None
    assert event['trackID']==9
    assert event['position']==pytest.approx([570,1010])


def test_ground_mode_rejects_same_turn_above_selected_floor():
    detector=BounceDetector()
    for t,y in [(0.,300),(.12,460),(.22,330),(.32,250)]:
        point=observation(t,500,y,groundSurfaceEligible=(y<400))
        assert detector.update(point,None,mode='court') is None


def test_tracking_only_never_emits_an_impact():
    detector=BounceDetector()
    for i,x in enumerate([300,340,380,340,300]):
        assert detector.update(observation(i*.05,x,insideTarget=False),None,
                               mode='tracking') is None


def test_person_margin_includes_ball_held_beside_hand():
    person=dict(x=100,y=60,width=100,height=240)
    assert BallDetector._inside_person([person],214,150,10) is True
    assert BallDetector._inside_person([person],235,150,10) is False


def test_crossing_target_boundary_without_contact_is_not_an_impact():
    b=BounceDetector()
    for i,x in enumerate([100,140,180,220,260,300,340]):
        assert b.update(observation(i*.05,x,insideTarget=x>=200),None,mode='room') is None


@pytest.mark.parametrize('inside', [True, False])
def test_shallow_side_hit_is_detected_equally_inside_and_outside(inside):
    b=BounceDetector(); events=[]
    # Two coherent legs with a sharp oblique turn, not a 180-degree reversal.
    for i,(x,y) in enumerate([(300,200),(340,200),(380,200),(400,235),(420,270)]):
        e=b.update(observation(i*.05,x,y,insideTarget=inside),None,mode='room')
        if e: events.append(e)
    assert len(events)==1
    assert events[0]['position'] == pytest.approx([380,200],abs=2)


def test_contact_between_frames_uses_intersection_not_last_approach_pixel():
    b=BounceDetector(); events=[]
    # Actual contact at x=450, t=.15, missing between .10 and .20.
    for t,x in [(0,300),(.10,400),(.20,425),(.30,375)]:
        e=b.update(observation(t,x,insideTarget=False),None,mode='room')
        if e: events.append(e)
    assert len(events)==1
    assert events[0]['position'][0] == pytest.approx(450,abs=2)
    assert events[0]['timestamp'] == pytest.approx(.15,abs=.005)


def test_identity_switch_cannot_create_impact_or_speed():
    b=BounceDetector()
    for i,x in enumerate([300,350,400,350,300]):
        p=observation(i*.04,x,insideTarget=False)
        if i==3: p['associationReset']=True
        assert b.update(p,None,mode='room') is None


def test_two_separate_fast_throws_are_not_hidden_by_half_second_cooldown():
    b=BounceDetector(); events=[]
    for track_id,start in [(1,0.),(2,.33)]:
        for i,x in enumerate([300,340,380,340,300]):
            p=observation(start+i*.04,x,insideTarget=False);p['trackID']=track_id
            e=b.update(p,None,mode='room')
            if e: events.append(e)
    assert len(events)==2


def test_consecutive_throws_work_without_a_person_pose_or_new_track_id():
    detector=BounceDetector();events=[]
    for start in [0.,.75,1.5]:
        for i,x in enumerate([300,340,380,340,300]):
            event=detector.update(observation(start+i*.05,x),None,mode='room')
            if event:events.append(event)
    assert len(events)==3


def test_recorded_oblique_wall_return_anchors_to_ball_not_later_hand_fragment():
    detector=BounceDetector();events=[]
    # Four measured frames from the user's 17 September recording. The old
    # size-reversal code waited and marked (1475,954) near the player's hand.
    samples=[(0,1428.75,793.75,37.5),(.106,1396,773,34),
             (.195,1435,841,33.856),(.304,1475,953.75,17.124),
             (.396,1503.75,1051.25,28.215),(.509,1487.5,1067.5,25)]
    for t,x,y,size in samples:
        p=observation(t,x,y,size,frameWidth=1920,frameHeight=1080,
                      depthMeters=91.86/size,depthReliable=True)
        event=detector.update(p,None,mode='room')
        if event:events.append(event)
    assert len(events)==1
    assert events[0]['position']==pytest.approx([1396,773])
    assert events[0]['timestamp']==pytest.approx(.106)


def test_fragment_size_jump_does_not_create_a_depth_impact():
    detector=BounceDetector()
    for i,(x,y,size) in enumerate([(1240,927,11.3),(988,842,32.5),
                                  (908,1014,49.8),(925,982,14),(903,967,10)]):
        p=observation(i*.1,x,y,size,frameWidth=1920,frameHeight=1080,
                      depthMeters=91.86/size,depthReliable=True)
        assert detector.update(p,None,mode='room') is None


def test_static_green_poster_near_moving_hand_does_not_inherit_ball(monkeypatch,tmp_path):
    monkeypatch.setenv('TV_MODEL_PATH','')
    monkeypatch.setenv('TV_APPEARANCE_DIR',str(tmp_path))
    detector=BallDetector()
    green=tuple(map(int,cv2.cvtColor(np.uint8([[[35,220,220]]]),cv2.COLOR_HSV2BGR)[0,0]))
    bg=np.full((240,320,3),60,np.uint8)
    cv2.circle(bg,(120,100),8,green,-1)
    for i in range(35):detector.detect(bg,i*.03)
    frame=bg.copy()
    cv2.rectangle(frame,(101,107),(143,150),(30,70,210),-1)
    cv2.circle(frame,(225,90),8,green,-1)
    candidates=detector.detect(frame,1.1)
    assert not any(np.hypot(c['centerX']-120,c['centerY']-100)<12 for c in candidates)
    assert any(np.hypot(c['centerX']-225,c['centerY']-90)<5 for c in candidates)


def test_disappearing_at_edge_of_image_is_not_wall_contact():
    b=BounceDetector()
    for i,(x,size,z) in enumerate([(40,30,1.6),(20,23,2.1),(4,16,3.0)]):
        p=observation(i*.06,x,size=size,insideTarget=False,depthMeters=z,depthReliable=True)
        assert b.update(p,None,mode='room') is None
    assert b.update(dict(p,timestamp=.24,predicted=True),None,mode='room') is None


def test_ball_footprint_and_uncertainty_decide_line_calls():
    def call(x):
        return classify_impact([x,300],[1000,600],region=[.3,.2,.7,.8],
                               ball_diameter_px=20,uncertainty_px=2)['inOrOut']
    assert call(320)=='IN'
    assert call(296)=='IN'  # Centre outside, footprint clearly touches the line.
    assert call(290)=='REVISAR'
    assert call(275)=='OUT'


def test_perspective_outside_contact_is_not_clamped_into_target():
    polygon=[[.4,.15],[.6,.15],[.85,.9],[.15,.9]]
    assert classify_impact([200,150],[1000,600],polygon=polygon)['inOrOut']=='OUT'
    assert classify_impact([200,500],[1000,600],polygon=polygon)['inOrOut']=='IN'


def test_speed_uses_ball_thickness_in_diagonal_blur():
    speed=SpeedEstimator(.067)
    for i in range(4):
        p=observation(i*.05,300+i*40)
        p.update(width=70,height=70,ballDiameterPx=20)
        result=speed.update(p,None)
    assert result['projectedKmh']==pytest.approx(9.648)


def test_head_on_flight_has_nonzero_approximate_speed():
    speed=SpeedEstimator(.067)
    for i in range(4):
        p=observation(i*.05,300,depthMeters=2+i*.2,depthReliable=True)
        result=speed.update(p,None)
    assert result['projectedKmh']==pytest.approx(14.4)
    assert result['approximate'] and result['physicalKmh'] is None


def test_impact_speed_includes_lateral_and_depth_motion():
    points=[observation(i*.05,300+i*20,depthMeters=2+i*.15,depthReliable=True) for i in range(3)]
    value,method=estimate_segment_speed(points,include_depth=True)
    assert value==pytest.approx(np.hypot(400*.067/20,3)*3.6)
    assert method=='monocular_depth_estimate'


def test_diagonal_green_blur_keeps_its_thin_diameter(monkeypatch,tmp_path):
    monkeypatch.setenv('TV_MODEL_PATH','')
    monkeypatch.setenv('TV_APPEARANCE_DIR',str(tmp_path))
    d=BallDetector(); bg=np.full((240,320,3),60,np.uint8)
    for i in range(20): d.detect(bg,i*.03)
    frame=bg.copy()
    green=tuple(map(int,cv2.cvtColor(np.uint8([[[35,220,220]]]),cv2.COLOR_HSV2BGR)[0,0]))
    cv2.ellipse(frame,(180,100),(32,5),45,0,360,green,-1)
    boxes=d.detect(frame,.7)
    ball=min(boxes,key=lambda c:np.hypot(c['centerX']-180,c['centerY']-100))
    assert 8<=ball['ballDiameterPx']<=15
    assert min(ball['width'],ball['height'])>30


@pytest.mark.parametrize('hit_x,expected',[(160,'OUT'),(260,'IN')])
def test_green_video_to_impact_to_target_call(monkeypatch,tmp_path,hit_x,expected):
    monkeypatch.setenv('TV_MODEL_PATH','')
    monkeypatch.setenv('TV_APPEARANCE_DIR',str(tmp_path))
    d=BallDetector(); d.region=[.7,.2,.95,.8]
    tracker=BallTracker(); b=BounceDetector(); events=[]
    bg=np.full((240,320,3),60,np.uint8)
    for i in range(20): d.detect(bg,i*.03)
    green=tuple(map(int,cv2.cvtColor(np.uint8([[[35,220,220]]]),cv2.COLOR_HSV2BGR)[0,0]))
    for i,x in enumerate([hit_x-100,hit_x-80,hit_x-60,hit_x-40,hit_x-20,hit_x,hit_x-20,hit_x-40]):
        image=bg.copy();cv2.circle(image,(x,100),9,green,-1); t=.7+i*.04
        d.prior=tracker.hint(t); track=tracker.update(d.detect(image,t),t)
        if track:
            track.update(frameWidth=320,frameHeight=240,metersPerPixel=.067/track['ballDiameterPx'])
            e=b.update(track,None,mode='room')
            if e: events.append(e)
    assert len(events)==1
    event=events[0]
    assert event['position']==pytest.approx([hit_x,100],abs=3)
    decision=classify_impact(event['position'],[320,240],region=d.region,
                            ball_diameter_px=event['ballDiameterPx'],
                            uncertainty_px=event['positionUncertaintyPx'])
    assert decision['inOrOut']==expected
    assert event['speedBefore']>0
