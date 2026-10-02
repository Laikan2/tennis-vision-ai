import time
import numpy as np
import pytest
from backend.camera import Frame,FrameBuffer,CameraCapture
from backend.tracking import BallTracker
from backend.calibration import CalibrationEngine
from backend.speed import SpeedEstimator
from backend.bounce import BounceDetector
from backend.court import CourtDetector
from backend.analytics import StatisticsEngine
from backend.fusion import CameraObservation,MultiCameraFusion
from backend.security import CameraSecrets,public_camera
from backend.database import Store


def detection(x=50,y=50,t=1,**kw):
    return dict(x=x-3,y=y-3,width=6,height=6,centerX=x,centerY=y,confidence=.95,timestamp=t,source='model',**kw)


def test_latest_frame_buffer_drops_old_frames():
    q=FrameBuffer(2)
    for i in range(6):q.push(Frame(np.zeros((2,2,3)),float(i),float(i),i))
    assert q.pop().sequence==5
    assert q.dropped==5
    assert q.pop(timeout=.001) is None


def test_tracking_recovers_short_occlusion_and_resets_long_gap():
    tracker=BallTracker()
    first=tracker.update([detection()],1)
    second=tracker.update([detection(53)],1.033)
    predicted=tracker.update([],1.066)
    recovered=tracker.update([detection(59)],1.099)
    assert predicted['predicted']
    assert first['trackID']==second['trackID']==recovered['trackID']
    assert tracker.update([],1.4)['predicted'] is True
    assert tracker.update([],1.85) is None
    new=tracker.update([detection(200)],1.883)
    assert new['trackID']!=first['trackID']
    assert tracker.update([detection()],1.0) is None


def test_tracking_keeps_identity_when_ball_reverses_beyond_prediction_gate():
    tracker=BallTracker()
    tracks=[]
    for x,t in [(100,0),(200,.05),(300,.1),(180,.15),(80,.2)]:
        tracks.append(tracker.update([detection(x=x,t=t)],t))
    assert all(track is not None for track in tracks)
    assert len({track['trackID'] for track in tracks}) == 1
    assert tracks[-2]['measuredX'] == 180
    assert tracks[-1]['velocityX'] < 0


def test_observed_overlay_uses_exact_measurement_instead_of_lagging_filter():
    tracker=BallTracker()
    tracker.update([detection(x=100,t=0)],0)
    result=tracker.update([detection(x=160,t=.05)],.05)
    assert result['predicted'] is False
    assert result['centerX'] == 160
    assert result['measuredX'] == 160


def test_calibration_plane_and_resolution_guard():
    src=[[100,100],[900,100],[900,700],[100,700]]
    dst=[[0,0],[10.97,0],[10.97,23.77],[0,23.77]]
    p=CalibrationEngine.fit(src,dst,[1000,800],'test')
    engine=CalibrationEngine(p)
    assert engine.map(500,400,[1000,800])==pytest.approx([5.485,11.885],abs=1e-5)
    assert engine.map(500,400,[1920,1080]) is None
    assert p['quality']=='não validada' and p['rmseMeters'] is None
    with pytest.raises(ValueError):CalibrationEngine.fit([[0,0],[1,1],[2,2],[3,3]],dst,[1000,800],'bad')
    with pytest.raises(ValueError):CalibrationEngine.fit([src[0],src[2],src[1],src[3]],dst,[1000,800],'crossed')


def test_heuristic_track_requires_consistent_motion_before_overlay():
    tracker = BallTracker()
    for i,x in enumerate([20,40,60]):
        result = tracker.update([dict(detection(x=x),source='heuristic')], i*.05)
        if i < 2:
            assert result is None
    assert result is not None
    flicker = BallTracker()
    for i,x in enumerate([20,21,20,21,20]):
        assert flicker.update([dict(detection(x=x),source='heuristic')], i*.05) is None


def test_tracker_prefers_distinctive_green_ball_over_false_bright_track():
    tracker=BallTracker()
    false=lambda x: dict(detection(x=x,y=50),source='heuristic',moving=True,
                         roundness=.75,ballScore=.62,tennisColor=False)
    green=lambda x: dict(detection(x=x,y=130),source='heuristic',moving=False,
                         width=42,height=40,roundness=.86,ballScore=.82,
                         tennisColor=True,tennisFraction=.8,insidePerson=True)
    for i,x in enumerate([50,54,58]):
        tracker.update([false(x)],i*.04)
    results=[]
    for i in range(3,6):
        results.append(tracker.update([false(60),green(220)],i*.04))
    assert results[-1] is not None
    assert results[-1]['tennisColor'] is True
    assert results[-1]['measuredX'] == 220


def test_tracker_can_acquire_stationary_green_ball_before_throw():
    tracker=BallTracker()
    ball=dict(detection(x=160,y=90),source='heuristic',moving=False,
              width=36,height=34,roundness=.9,ballScore=.84,
              tennisColor=True,tennisFraction=.82,insidePerson=True)
    assert tracker.update([ball],0) is None
    assert tracker.update([ball],.04) is None
    result=tracker.update([ball],.08)
    assert result is not None and result['tennisColor'] is True


def test_tracker_ignores_stationary_green_background_without_person():
    tracker=BallTracker()
    background=dict(detection(x=22,y=888),source='heuristic',moving=False,
                    width=20,height=18,roundness=.82,ballScore=.8,
                    tennisColor=True,tennisFraction=.78,insidePerson=False)
    assert tracker.update([background],0) is None
    assert tracker.update([background],.04) is None
    assert tracker.update([background],.08) is None
    assert tracker.state is None


def test_green_only_tracker_rejects_moving_skin_or_bright_reflection():
    tracker=BallTracker(green_only=True)
    generic=lambda x,t: dict(detection(x=x,y=300,t=t),source='heuristic',moving=True,
                             width=24,height=20,roundness=.75,ballScore=.8,
                             tennisColor=False,tennisFraction=0)
    for index,x in enumerate([100,140,180,220]):
        assert tracker.update([generic(x,index*.04)],index*.04) is None
    assert tracker.state is None


def test_moving_tennis_ball_replaces_stationary_green_track_and_clears_trail():
    tracker=BallTracker()
    held=dict(detection(x=30,y=700),source='heuristic',moving=False,
              width=34,height=32,roundness=.86,ballScore=.82,
              tennisColor=True,tennisFraction=.8,insidePerson=True)
    for t in [0,.04,.08]:
        tracker.update([held],t)
    old_id=tracker.track_id
    moving=lambda x,t: dict(detection(x=x,y=360,t=t),source='heuristic',moving=True,
                            width=22,height=20,roundness=.72,ballScore=.76,
                            tennisColor=True,tennisFraction=.7,insidePerson=False)
    results=[tracker.update([moving(x,t)],t) for x,t in [(500,.12),(540,.16),(580,.20)]]
    assert results[-1] is not None
    assert results[-1]['trackID'] != old_id
    assert min(point['centerX'] for point in tracker.history) > 450


def test_high_fidelity_moving_ball_beats_weak_fragment_near_prediction():
    tracker=BallTracker(green_only=True)
    weak=lambda x,t: dict(detection(x=x,y=500,t=t),source='heuristic',moving=True,
                          width=18,height=16,roundness=.55,ballScore=.72,
                          tennisColor=True,tennisFraction=.45)
    for x,t in [(100,0),(120,.04),(140,.08)]:
        tracker.update([weak(x,t)],t)
    actual=dict(detection(x=620,y=260,t=.12),source='heuristic',moving=True,
                width=58,height=54,roundness=.88,ballScore=.95,
                tennisColor=True,tennisFraction=.82)
    result=tracker.update([weak(160,.12),actual],.12)
    # A clean replacement needs two more observations before a heuristic track
    # is exposed, and its trail must never connect back to the weak fragment.
    tracker.update([dict(actual,centerX=650,x=621,timestamp=.16)],.16)
    result=tracker.update([dict(actual,centerX=680,x=651,timestamp=.20)],.20)
    assert result is not None and result['centerX'] == 680
    assert min(point['centerX'] for point in tracker.history) >= 620


def test_green_track_survives_long_motion_blur_and_reacquires_same_id():
    tracker=BallTracker()
    def ball(x,t):
        return dict(detection(x=x,y=90,t=t),source='heuristic',moving=True,
                    width=18,height=16,roundness=.78,ballScore=.8,
                    tennisColor=True,tennisFraction=.75)
    measured=[]
    for i,x in enumerate([100,145,190]):
        measured.append(tracker.update([ball(x,i*.04)],i*.04))
    track_id=measured[-1]['trackID']
    for t in [.16,.24,.32,.40,.48,.56]:
        predicted=tracker.update([],t)
        assert predicted is not None and predicted['predicted'] is True
        assert tracker.hint(t+.01) is not None
    reacquired=tracker.update([ball(620,.64)],.64)
    assert reacquired is not None
    assert reacquired['trackID'] == track_id
    assert reacquired['predicted'] is False


def test_native_resolution_prior_finds_fast_green_blur(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH','')
    detector=BallDetector()
    detector.prior=dict(centerX=600,centerY=300,velocityX=900,velocityY=0,
                        age=.08,width=12,height=12,tennisColor=True)
    detector.motion_mask=np.zeros((360,640),np.uint8)
    cv2.rectangle(detector.motion_mask,(300,140),(345,160),255,-1)
    image=np.full((720,1280,3),35,np.uint8)
    green=tuple(int(v) for v in cv2.cvtColor(np.uint8([[[35,180,180]]]),cv2.COLOR_HSV2BGR)[0,0])
    cv2.ellipse(image,(640,300),(24,5),0,0,360,green,-1)
    candidates=detector._prior_tennis_candidates(image,.1)
    assert candidates
    assert abs(candidates[0]['centerX']-640) < 4
    assert candidates[0]['tennisColor'] is True


def test_native_prior_does_not_call_static_green_background_a_moving_ball(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH','')
    detector=BallDetector()
    detector.prior=dict(centerX=600,centerY=300,velocityX=900,velocityY=0,
                        age=.08,width=12,height=12,tennisColor=True)
    detector.motion_mask=np.zeros((360,640),np.uint8)
    image=np.full((720,1280,3),35,np.uint8)
    green=tuple(int(v) for v in cv2.cvtColor(np.uint8([[[35,180,180]]]),cv2.COLOR_HSV2BGR)[0,0])
    cv2.ellipse(image,(640,300),(24,5),0,0,360,green,-1)
    assert detector._prior_tennis_candidates(image,.1) == []


def test_room_calibration_maps_known_distance_to_metres():
    profile = CalibrationEngine.fit_scale([[100, 400], [300, 400]], 1.0, [1000, 800], 'quarto')
    engine = CalibrationEngine(profile)
    assert profile['mode'] == 'room'
    assert engine.map(300, 400, [1000, 800]) == pytest.approx([1, 0], abs=1e-6)
    assert engine.map(200, 500, [1000, 800]) == pytest.approx([.5, .5], abs=1e-6)


def test_speed_uses_timestamps_and_never_reports_airborne_speed():
    engine=SpeedEstimator()
    result=None
    for t in [0,.031,.071,.104,.147]:
        result=engine.update(dict(trackID=1,timestamp=t,predicted=False,source='model'),[10*t,0])
    assert result['projectedKmh']==pytest.approx(36)
    assert result['physicalKmh'] is None and result['confidence'] is None
    assert engine.update(dict(trackID=1,timestamp=.17,predicted=True,source='model'),[2,0]) is None


def test_direction_reversal_never_becomes_confirmed_bounce():
    b=BounceDetector(); event=None
    for i,y in enumerate([10,20,30,40,30,20,10]):
        event=(b.update(dict(detection(y=y),timestamp=i*.03,trackID=1,predicted=False,
                             groundSurfaceEligible=True),[5,12])
               or event)
    assert event['status']=='candidate'
    assert event['inOrOut']=='INCONCLUSIVO'
    assert event['confidence'] is None
    b=BounceDetector()
    for i in range(7):
        assert b.update(dict(detection(y=10+i*10),timestamp=i*.03,trackID=1,predicted=False,
                             groundSurfaceEligible=True),[5,12]) is None


def test_classification_requires_ground_evidence_and_guards_lines():
    c=CourtDetector()
    assert c.classify([5,12])=='INCONCLUSIVO'
    assert c.classify([5,12],.1,True)=='IN'
    assert c.classify([-.5,12],.1,True)=='OUT'
    assert c.classify([.05,12],.1,True)=='INCONCLUSIVO'


def test_room_horizontal_impact_without_calibration():
    detector = BounceDetector()
    event = None
    for i, x in enumerate([20, 40, 60, 40, 20]):
        event = detector.update(dict(detection(x=x), timestamp=i*.1, trackID=1, predicted=False), None, mode='room')
    assert event['kind'] == 'possible_impact'
    assert event['position'] == [60, 50]
    assert event['status'] == 'candidate'
    assert event['speedBefore'] is None


def test_room_straight_motion_is_not_impact():
    detector = BounceDetector()
    for i in range(10):
        assert detector.update(dict(detection(x=i*20), timestamp=i*.1, trackID=1, predicted=False), None, mode='room') is None


def test_room_flight_apex_is_not_painted_as_wall_impact():
    detector=BounceDetector()
    for i,(x,y) in enumerate([(100,200),(120,160),(140,140),(160,160),(180,200)]):
        track=dict(detection(x=x,y=y),timestamp=i*.1,trackID=1,predicted=False)
        assert detector.update(track,None,mode='room') is None


def test_white_ball_is_segmented_from_moving_coloured_hand(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH', '')
    detector = BallDetector()
    background = np.full((240,320,3), 40, np.uint8)
    for i in range(20):
        detector.detect(background, i*.03)
    frame = background.copy()
    cv2.rectangle(frame, (50,60), (160,210), (30,70,210), -1)
    cv2.circle(frame, (164,90), 9, (245,245,245), -1)
    candidates = detector.detect(frame, .7)
    assert any(abs(c['centerX']-164)<5 and abs(c['centerY']-90)<5 for c in candidates)


def test_empty_statistics_and_actual_reference_errors():
    s=StatisticsEngine.summarize([])
    assert s['maxKmh'] is None and s['accuracy'] is None and s['bounces']==0
    v=StatisticsEngine.validation([dict(estimated=110,reference=100),dict(estimated=90,reference=100)])
    assert v['mae']==10 and v['mape']==10 and v['bias']==0 and v['ci95'] is None


def test_large_white_ball_keeps_full_silhouette_when_held(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH', '')
    detector=BallDetector(); tracker=BallTracker()
    background=np.full((720,960,3),40,np.uint8)
    for i in range(20): detector.detect(background,i*.03)
    frame=background.copy()
    cv2.circle(frame,(450,350),65,(250,240,230),-1)
    results=[]
    for i in range(5):
        t=.7+i*.03
        candidates=detector.detect(frame,t)
        ball=next(c for c in candidates if abs(c['centerX']-450)<3)
        assert ball['width'] > 120
        results.append(tracker.update(candidates,t))
    assert results[-1] is not None
    assert not results[-1]['predicted']


def test_blurred_ball_on_white_wall_uses_temporal_silhouette(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH', '')
    detector=BallDetector()
    wall=np.full((240,320,3),190,np.uint8)
    for i in range(20): detector.detect(wall,i*.03)
    frame=wall.copy()
    cv2.ellipse(frame,(160,100),(20,5),0,0,360,(245,245,245),-1)
    candidates=detector.detect(frame,.7)
    assert any(c.get('motionOnly') and abs(c['centerX']-160)<5 and abs(c['centerY']-100)<5 for c in candidates)


def test_warm_room_light_ball_is_detected_and_tiny_glint_is_ignored(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH','')
    detector=BallDetector()
    desk_hsv=np.full((240,320,3),(35,9,214),np.uint8)
    desk=cv2.cvtColor(desk_hsv,cv2.COLOR_HSV2BGR)
    for i in range(20): detector.detect(desk,i*.03)
    cream=tuple(int(v) for v in cv2.cvtColor(np.uint8([[[22,55,200]]]),cv2.COLOR_HSV2BGR)[0,0])
    frame=desk.copy()
    cv2.circle(frame,(160,100),9,cream,-1)
    cv2.circle(frame,(250,80),2,(255,255,255),-1)
    candidates=detector.detect(frame,.7)
    assert any(abs(c['centerX']-160)<4 and abs(c['centerY']-100)<4 for c in candidates)
    assert not any(abs(c['centerX']-250)<4 and abs(c['centerY']-80)<4 for c in candidates)


def test_camera_pan_suppresses_global_motion_candidates(monkeypatch):
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH','')
    detector=BallDetector()
    dark=np.full((240,320,3),35,np.uint8)
    for i in range(20): detector.detect(dark,i*.03)
    moved=np.full((240,320,3),210,np.uint8)
    assert detector.detect(moved,.7) == []
    assert detector.camera_moving is True


def test_room_impact_speed_uses_approach_and_departure_separately():
    detector=BounceDetector(); event=None
    for i,x in enumerate([0,20,40,30,20]):
        track=dict(detection(x=x),timestamp=i*.1,trackID=1,predicted=False,planePosition=[x*.01,0])
        event=detector.update(track,None,mode='room')
    assert event is not None
    assert event['speedBefore'] == pytest.approx(7.2)
    assert event['speedAfter'] == pytest.approx(3.6)


def test_speed_survives_one_predicted_frame_without_using_it():
    estimator=SpeedEstimator()
    def track(t,predicted=False): return dict(trackID=1,timestamp=t,predicted=predicted,source='model')
    estimator.update(track(0),[0,0]); estimator.update(track(.05),[.5,0])
    assert estimator.update(track(.08,True),[999,999]) is None
    assert estimator.update(track(.1),[1,0])['projectedKmh'] == pytest.approx(36)


def test_room_speed_uses_known_ping_pong_diameter_without_calibration():
    estimator=SpeedEstimator(.04)
    result=None
    for index,t in enumerate([0,.05,.1,.15]):
        track=dict(detection(x=100+index*20,t=t),trackID=1,predicted=False,
                   source='heuristic',width=20,height=20)
        result=estimator.update(track,None)
    assert result['projectedKmh'] == pytest.approx(2.88)
    assert result['approximate'] is True
    assert result['method'] == 'ball_diameter_estimate'


def test_room_impact_keeps_approach_across_one_missed_frame():
    detector=BounceDetector(); event=None
    observations=[
        (0.0,1192,371,34,False),
        (0.1,1216,424,40,False),
        (0.15,1216,424,40,True),
        (0.2,1240,475,46,False),
        (0.3,1201,374,36,False),
        (0.4,1137,352,21,False),
    ]
    for t,x,y,size,predicted in observations:
        track=dict(detection(x=x,y=y,t=t),timestamp=t,trackID=1,
                   predicted=predicted,width=size,height=size,
                   metersPerPixel=.04/size)
        event=detector.update(track,None,mode='room') or event
    assert event is not None
    assert event['kind'] == 'possible_impact'
    assert event['position'] == [1240,475]
    assert event['speedMethod'] == 'ball_diameter_estimate'
    assert event['speedBefore'] is not None


def test_target_region_keeps_full_trajectory_and_tags_wall_candidate(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH', '')
    detector = BallDetector()
    detector.region = [.5, .25, 1, 1]
    background = np.full((240,320,3), 40, np.uint8)
    for i in range(20):
        detector.detect(background, i*.03)
    frame = background.copy()
    cv2.circle(frame, (80,100), 9, (245,245,245), -1)
    cv2.circle(frame, (230,100), 9, (245,245,245), -1)
    candidates = detector.detect(frame, .7)
    assert len(candidates) == 2
    inside=next(c for c in candidates if c['centerX'] > 160)
    outside=next(c for c in candidates if c['centerX'] < 160)
    assert inside['centerX'] == pytest.approx(230, abs=1)
    assert inside['centerY'] == pytest.approx(100, abs=1)
    assert inside['insideTarget'] is True
    assert outside['insideTarget'] is False


def test_small_fluorescent_green_tennis_ball_gets_priority(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH','')
    detector=BallDetector()
    background=np.full((240,320,3),35,np.uint8)
    for i in range(20): detector.detect(background,i*.03)
    frame=background.copy()
    green=tuple(int(v) for v in cv2.cvtColor(np.uint8([[[32,220,220]]]),cv2.COLOR_HSV2BGR)[0,0])
    cv2.circle(frame,(180,100),4,green,-1)
    candidates=detector.detect(frame,.7)
    ball=next(c for c in candidates if abs(c['centerX']-180)<3)
    assert ball['tennisColor'] is True
    assert ball['label'] == 'green tennis ball candidate'


def test_green_tennis_ball_round_trip_produces_wall_impact_and_speed(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH','')
    detector=BallDetector(); detector.region=[.75,0,1,1]
    tracker=BallTracker(); impact=BounceDetector()
    background=np.full((240,320,3),35,np.uint8)
    for i in range(20): detector.detect(background,i*.03)
    green=tuple(int(v) for v in cv2.cvtColor(np.uint8([[[32,220,220]]]),cv2.COLOR_HSV2BGR)[0,0])
    event=None
    for index,x in enumerate([100,140,180,220,260,220,180]):
        frame=background.copy(); cv2.circle(frame,(x,100),9,green,-1)
        t=.7+index*.04
        track=tracker.update(detector.detect(frame,t),t)
        if track:
            diameter=(track['width']+track['height'])/2
            track['metersPerPixel']=.067/diameter
            event=impact.update(track,None,mode='room') or event
    assert event is not None
    assert event['position'][0] == pytest.approx(260,abs=3)
    assert event['speedBefore'] is not None


def test_fast_entry_without_reversal_does_not_count_contact():
    detector=BounceDetector()
    event=None
    for i,x in enumerate([100,150,205,265]):
        track=dict(detection(x=x,y=100,t=i*.05),timestamp=i*.05,trackID=1,
                   predicted=False,insideTarget=x>=250,frameWidth=1000,
                   metersPerPixel=.003,planePosition=None)
        event=detector.update(track,None,mode='room') or event
    assert event is None  # Entering a drawn rectangle is not a physical impact.


def test_front_facing_wall_impact_uses_ball_size_reversal():
    detector=BounceDetector(); event=None
    # The centre keeps drifting right, so there is no 2D direction reversal.
    # Depth grows while the known-size ball shrinks, then falls as it returns.
    depths=[3.0,4.0,5.2,4.1,3.1]
    sizes=[30,23,18,22,29]
    for index,(depth,size) in enumerate(zip(depths,sizes)):
        t=index*.05
        track=dict(detection(x=100+index*5,y=100,t=t),timestamp=t,trackID=1,
                   predicted=False,width=size,height=size,tennisColor=True,
                   insideTarget=True,depthMeters=depth,depthReliable=True,metersPerPixel=.067/size)
        event=detector.update(track,None,mode='room') or event
    assert event is not None
    assert event['kind'] == 'possible_depth_impact'
    assert event['position'] == pytest.approx([110,100])
    assert event['speedBefore'] is not None
    assert event['speedMethod'] == 'monocular_depth_estimate'


def test_room_zigzag_highlights_do_not_create_impact():
    detector = BounceDetector()
    for i, (x,y) in enumerate([(20,20),(40,50),(60,20),(40,50),(20,20)]):
        event = detector.update(dict(detection(x=x,y=y), timestamp=i*.1, trackID=1, predicted=False), None, mode='room')
        assert event is None


def test_encryption_and_public_redaction(monkeypatch):
    from cryptography.fernet import Fernet
    monkeypatch.setenv('TV_SECRET_KEY',Fernet.generate_key().decode())
    secret=CameraSecrets()
    c=dict(host='192.168.1.64',port=554,path='/Streaming/Channels/101',userEncrypted=secret.encrypt('name'),passwordEncrypted=secret.encrypt('p@ss:word'))
    assert 'p%40ss%3Aword' in secret.url(c)
    assert 'Encrypted' not in str(public_camera(c))
    monkeypatch.delenv('TV_SECRET_KEY')
    with pytest.raises(ValueError):CameraSecrets().encrypt('abc')


def test_fusion_requires_sync_and_identity():
    p1=np.array([[100,0,0,0],[0,100,0,0],[0,0,1,0]],float)
    p2=np.array([[100,0,0,-100],[0,100,0,0],[0,0,1,0]],float)
    a=CameraObservation('1',1,(20,20),p1);b=CameraObservation('2',1,(0,20),p2)
    fusion=MultiCameraFusion()
    assert fusion.triangulate(a,b) is None
    assert fusion.triangulate(a,b,True,True)==pytest.approx([1,1,5])


def test_capture_uses_real_reader_boundary_and_stops():
    class FakeCapture:
        def isOpened(self):return True
        def read(self):time.sleep(.01);return True,np.zeros((24,32,3),np.uint8)
        def release(self):pass
    q=FrameBuffer();cap=CameraCapture('unused',q,FakeCapture)
    cap.start(); frame=q.pop(timeout=1)
    deadline=time.time()+1
    cached,cached_frame=cap.latest_preview()
    while cached is None and time.time()<deadline:
        time.sleep(.01);cached,cached_frame=cap.latest_preview()
    jpeg,preview=cap.latest_jpeg(92)
    cap.stop()
    assert frame.image.shape==(24,32,3) and cap.frames>=1
    assert cached[:2] == b'\xff\xd8' and cached_frame.sequence >= frame.sequence
    assert jpeg[:2] == b'\xff\xd8' and preview.sequence >= frame.sequence
    assert not cap.thread.is_alive()
    assert not cap.preview_thread.is_alive()


def test_preview_encoder_survives_a_slow_first_camera_frame():
    class SlowFirstCapture:
        def __init__(self): self.first=True
        def isOpened(self): return True
        def read(self):
            if self.first:
                self.first=False
                time.sleep(.6)
            else:
                time.sleep(.01)
            return True,np.zeros((24,32,3),np.uint8)
        def release(self): pass
    cap=CameraCapture('unused',FrameBuffer(),SlowFirstCapture)
    cap.start()
    deadline=time.time()+1.5
    jpeg,frame=cap.latest_preview()
    while jpeg is None and time.time()<deadline:
        time.sleep(.02);jpeg,frame=cap.latest_preview()
    cap.stop()
    assert jpeg[:2] == b'\xff\xd8' and frame.sequence >= 1
    assert not cap.preview_thread.is_alive()


def test_detector_separates_persons_from_ball_when_model_labels_are_available():
    from backend.detection import BallDetector
    class Scalar:
        def __init__(self,v): self.v=v
        def cpu(self): return self
        def tolist(self): return self.v
        def __float__(self): return float(self.v)
        def __int__(self): return int(self.v)
    class Box:
        def __init__(self,xyxy,conf,cls): self.xyxy=[Scalar(xyxy)];self.conf=[Scalar(conf)];self.cls=[Scalar(cls)]
    class FakeModel:
        names={0:'person',1:'tennis ball',2:'car'}
        def predict(self,*args,**kwargs):
            return [type('Result',(),{'boxes':[Box([1,2,21,42],.91,0),Box([60,70,68,78],.88,1),Box([100,100,150,150],.99,2)]})()]
    d=BallDetector(); d.model=FakeModel(); d.status='fake'; d.persons=[]
    balls=d.detect(np.zeros((160,200,3),np.uint8),1)
    assert len(d.persons)==1 and d.persons[0]['label']=='person'
    assert len(balls)==1 and balls[0]['label']=='tennis ball'


def test_person_box_rejects_clothing_print_but_keeps_ball_outside(monkeypatch):
    import cv2
    from backend.detection import BallDetector
    monkeypatch.setenv('TV_MODEL_PATH','')
    class Scalar:
        def __init__(self,value): self.value=value
        def cpu(self): return self
        def tolist(self): return self.value
        def __float__(self): return float(self.value)
        def __int__(self): return int(self.value)
    class Box:
        def __init__(self,xyxy,conf,cls):
            self.xyxy=[Scalar(xyxy)]; self.conf=[Scalar(conf)]; self.cls=[Scalar(cls)]
    class PersonModel:
        names={0:'person'}
        def predict(self,*args,**kwargs):
            return [type('Result',(),{'boxes':[Box([180,20,315,235],.95,0)]})()]
    detector=BallDetector(); detector.model=PersonModel(); detector.model_interval=1
    background=np.full((240,320,3),40,np.uint8)
    for i in range(20): detector.detect(background,i*.03)
    frame=background.copy()
    cv2.circle(frame,(230,170),10,(245,245,245),-1)  # clothing/highlight
    cv2.circle(frame,(120,100),10,(245,245,245),-1)  # flying ball
    candidates=detector.detect(frame,.7)
    # A strong moving circle inside the person box remains a hypothesis so a
    # real ball can cross the torso, but it is explicitly tagged and cannot be
    # confirmed as a static held-ball shortcut by the tracker.
    inside=next(c for c in candidates if abs(c['centerX']-230)<5)
    assert inside['insidePerson'] is True
    assert any(abs(c['centerX']-120)<5 for c in candidates)


def test_sqlite_persists(tmp_path):
    path=tmp_path/'test.sqlite'
    a=Store(path);saved=a.put('event',dict(value=4))
    b=Store(path)
    assert b.get('event',saved['id'])['value']==4
