import cv2
import numpy as np
import pytest
from backend.target import (contains, contains_target, map_target, validate_polygon,
                            orient_target, court_sector)
from backend.pose import advance_pose, PoseEstimator
from backend.stroke import StrokeClassifier
from backend.detection import BallDetector
from backend.bounce import BounceDetector
from backend.analytics import StatisticsEngine


def test_surface_uses_perspective_instead_of_bounding_box():
    polygon = [[.3,.2],[.7,.2],[.95,.9],[.05,.9]]
    validate_polygon(polygon)
    assert not contains(.1,.25,polygon=polygon)
    assert contains(.1,.85,polygon=polygon)
    for corner, expected in zip(polygon, [[0,0],[1,0],[1,1],[0,1]]):
        assert map_target(*corner,polygon=polygon) == pytest.approx(expected,abs=1e-5)
    assert contains(.01,.99)


def test_tennis_zones_follow_selected_court_perspective():
    polygon = [[.3,.15],[.7,.15],[.95,.9],[.05,.9]]
    # Left doubles alley is inside the full court but outside singles.
    point=cv2.perspectiveTransform(
        np.array([[[.05,.35]]],np.float32),
        cv2.getPerspectiveTransform(np.array([[0,0],[1,0],[1,1],[0,1]],np.float32),
                                    np.array(polygon,np.float32)))[0,0]
    assert contains_target(*point,polygon=polygon,target_zone='full')
    assert not contains_target(*point,polygon=polygon,target_zone='singles')
    service=cv2.perspectiveTransform(
        np.array([[[.3,.4]]],np.float32),
        cv2.getPerspectiveTransform(np.array([[0,0],[1,0],[1,1],[0,1]],np.float32),
                                    np.array(polygon,np.float32)))[0,0]
    assert contains_target(*service,polygon=polygon,target_zone='far_left_service')
    assert not contains_target(*service,polygon=polygon,target_zone='near_left_service')


def test_opposite_baseline_uses_same_global_court_and_named_sectors():
    assert orient_target([.2,.3],'opposite') == pytest.approx([.8,.7])
    assert court_sector([.05,.4]) == 'left_alley'
    assert court_sector([.3,.1]) == 'far_deep_left'
    assert court_sector([.7,.6]) == 'near_service_right'


def test_pose_side_classifies_right_handed_forehand_and_backhand():
    def person(dominant_wrist_x):
        joints=[[0,0,0] for _ in range(17)]
        joints[5]=[40,80,.95];joints[6]=[60,80,.95]
        joints[10]=[dominant_wrist_x,82,.95]
        return dict(keypoints=joints,x=20,y=30,width=80,height=140,centerX=60,centerY=100)
    forehand=StrokeClassifier('right').update(
        dict(trackID=1,predicted=False,centerX=88,centerY=82,measuredX=88,measuredY=82),
        [person(84)])
    backhand=StrokeClassifier('right').update(
        dict(trackID=2,predicted=False,centerX=22,centerY=82,measuredX=22,measuredY=82),
        [person(26)])
    assert forehand['strokeType'] == 'forehand' and forehand['strokeConfidence'] > .4
    assert backhand['strokeType'] == 'backhand' and backhand['strokeConfidence'] > .4


def test_crossed_or_degenerate_surface_rejected():
    for polygon in ([[0,0],[1,1],[0,1],[1,0]], [[0,0],[0,0],[0,0],[0,0]],
                    [[0,0],[2,0],[1,1],[0,1]]):
        with pytest.raises(ValueError):
            validate_polygon(polygon)


def test_green_ball_stays_separate_from_bright_wall(monkeypatch):
    monkeypatch.setenv('TV_MODEL_PATH','')
    detector=BallDetector()
    background=np.full((240,320,3),220,np.uint8)
    for i in range(20):
        detector.detect(background,i*.03)
    green=tuple(int(v) for v in cv2.cvtColor(np.uint8([[[35,220,220]]]),cv2.COLOR_HSV2BGR)[0,0])
    frame=background.copy()
    cv2.circle(frame,(180,120),9,green,-1)
    ball=next(c for c in detector.detect(frame,.7) if abs(c['centerX']-180)<3 and c.get('tennisColor'))
    assert ball['width'] < 25
    assert ball['ballScore'] >= .7


def test_pose_flow_moves_measured_joints_and_rejects_blank_frame():
    rng=np.random.default_rng(21)
    gray=rng.integers(0,255,(180,240),dtype=np.uint8)
    moved=cv2.warpAffine(gray,np.float32([[1,0,6],[0,1,4]]),(240,180))
    points=[[60+(i%4)*25,50+(i//4)*15,.9] for i in range(17)]
    person=dict(x=40,y=30,width=130,height=100,centerX=105,centerY=80,keypoints=points)
    result=advance_pose([person],gray,moved)
    assert len(result)==1
    for before,after in zip(points,result[0]['keypoints']):
        if after[2]>=.3:
            assert after[:2] == pytest.approx([before[0]+6,before[1]+4],abs=.5)
    assert advance_pose([person],gray,np.zeros_like(gray)) == []


def test_pose_expires_instead_of_drawing_stale_skeleton(monkeypatch):
    monkeypatch.setenv('TV_POSE_PATH','')
    pose=PoseEstimator()
    assert pose.update(np.zeros((120,160,3),np.uint8),1)==[]


def test_wall_reversal_does_not_emit_duplicate_pending_arrival():
    detector = BounceDetector()
    event = None
    for i, x in enumerate([100, 125, 150, 125, 100]):
        track = dict(centerX=x, centerY=100, width=14, height=14, trackID=1,
                     timestamp=i*.05, predicted=False, insideTarget=x>=150,
                     depthMeters=3, metersPerPixel=.003, frameWidth=1000)
        event = detector.update(track, None, mode='room') or event
    assert event is not None and event['kind'] == 'possible_impact'
    assert detector.update(dict(track, timestamp=.8, predicted=True), None, mode='room') is None


def test_selected_wall_does_not_score_an_unobserved_contact():
    detector = BounceDetector()
    base = dict(centerX=320, centerY=180, trackID=7, predicted=False,
                insideTarget=True, insidePerson=False, tennisColor=True,
                moving=True, source='heuristic', frameWidth=1280,
                planePosition=None, depthReliable=True)
    for i, (x, diameter, depth) in enumerate([
            (250, 30, 1.6), (285, 23, 2.1), (320, 16, 3.0)]):
        event = detector.update(dict(base, centerX=x, measuredX=x, measuredY=180,
                                     width=diameter, height=diameter,
                                     depthMeters=depth, metersPerPixel=.067/diameter,
                                     timestamp=i*.06), None, mode='room')
        assert event is None
    # The tracker can bridge a brief occlusion for display, but a lost ball
    # cannot establish where (or whether) the wall was hit.
    event = detector.update(dict(base, centerX=350, measuredX=320, measuredY=180,
                                 width=16, height=16, depthMeters=3.0,
                                 metersPerPixel=.067/16, timestamp=.24,
                                 predicted=True), None, mode='room')
    assert event is None


def test_selected_wall_does_not_turn_static_ball_into_impact():
    detector = BounceDetector()
    base = dict(centerX=300, centerY=180, trackID=3, predicted=False,
                insideTarget=True, insidePerson=False, tennisColor=True,
                moving=False, source='heuristic', frameWidth=1280,
                planePosition=None, depthReliable=True, width=22, height=22,
                depthMeters=2.0, metersPerPixel=.067/22)
    for i in range(3):
        assert detector.update(dict(base, timestamp=i*.06), None, mode='room') is None
    assert detector.update(dict(base, timestamp=.24, predicted=True), None, mode='room') is None


def test_court_can_show_possible_bounce_without_metre_calibration():
    detector = BounceDetector()
    event = None
    for i,y in enumerate([10,20,30,40,30,20,10]):
        event = detector.update(dict(centerX=100,centerY=y,trackID=1,
                                     measuredX=100,measuredY=y,width=12,height=12,
                                     tennisColor=True,moving=True,insidePerson=False,
                                     source='model',
                                     groundSurfaceEligible=True,
                                     timestamp=i*.03,predicted=False), None, mode='court') or event
    assert event is not None and event['kind'] == 'possible_bounce'
    assert event['status'] == 'candidate' and event['courtPosition'] is None
    assert event['contactEvidence'] == 'ground_measured_bounce'
    assert event['position'] == pytest.approx([100,40])


def test_estimated_stats_do_not_claim_physical_measurements():
    result = StatisticsEngine.summarize([
        dict(speedBefore=20,status='candidate'),dict(speedBefore=40,status='candidate'),
        dict(speedBefore=None,status='candidate')])
    assert result['estimatedMaxKmh'] == 40 and result['estimatedMeanKmh'] == 30
    assert result['maxKmh'] is None and result['accuracy'] is None


def test_two_measurements_of_entry_do_not_prove_contact():
    detector = BounceDetector()
    outside = dict(centerX=100, centerY=120, measuredX=100, measuredY=120,
                   width=14, height=14, trackID=9, timestamp=0., predicted=False,
                   insideTarget=False, source='model', frameWidth=1000,
                   planePosition=None, depthMeters=None, metersPerPixel=.003)
    inside = dict(outside, centerX=180, measuredX=180, timestamp=.05,
                  insideTarget=True)
    assert detector.update(outside, None, mode='room') is None
    event = detector.update(inside, None, mode='room')
    assert event is None


def test_target_var_counts_candidate_inside_and_outside():
    result = StatisticsEngine.summarize([
        dict(id='one', status='candidate', inOrOut='IN', speedBefore=30, cameraId='a'),
        dict(id='two', status='candidate', inOrOut='OUT', speedBefore=50, cameraId='a'),
        dict(id='three', status='candidate', inOrOut='INCONCLUSIVO', speedBefore=None, cameraId='b'),
    ])
    assert result['throws'] == 3
    assert result['inside'] == 1 and result['outside'] == 1
    assert result['inconclusive'] == 1 and result['insideRate'] == 50
    assert result['varAlerts'] == 1 and result['camerasUsed'] == 2


def test_stats_split_strokes_and_court_sectors():
    result=StatisticsEngine.summarize([
        dict(status='candidate',inOrOut='IN',speedBefore=60,strokeType='forehand',
             targetPosition=[.3,.1],courtSector='far_deep_left'),
        dict(status='candidate',inOrOut='OUT',speedBefore=45,strokeType='backhand',
             targetPosition=[.05,.4],courtSector='left_alley'),
    ])
    assert result['strokeStats']['forehand']['inside'] == 1
    assert result['strokeStats']['backhand']['outside'] == 1
    assert result['courtSectors']['left_alley']['backhand'] == 1


def test_two_cameras_fuse_one_throw(tmp_path):
    from backend.database import Store
    from backend.multi_pipeline import PipelineManager

    manager = PipelineManager(Store(tmp_path/'fusion.sqlite'))
    base = dict(sessionId='session', timestamp=10., position=[100,100],
                resolution=[1000,600], targetPosition=[.5,.5], targetMode='surface',
                status='candidate', kind='possible_impact', confidence=None,
                speedBefore=42., speedAfter=None, inOrOut='OUT',
                strokeType='forehand',strokeConfidence=.7)
    first = manager._record_event(dict(base, cameraId='camera-a'))
    second = manager._record_event(dict(base, cameraId='camera-b', timestamp=10.18,
                                        position=[300,200],targetPosition=[.54,.52],
                                        strokeType='forehand',strokeConfidence=.8,
                                        inOrOut='OUT'))
    saved = manager.store.all('event')
    assert first['id'] == second['id'] and len(saved) == 1
    assert saved[0]['cameraIds'] == ['camera-a','camera-b']
    assert saved[0]['observationCount'] == 2 and saved[0]['inOrOut'] == 'OUT'
    assert saved[0]['varAlert'] is True
    assert saved[0]['targetPosition'] == pytest.approx([.5,.5])
    assert saved[0]['positionSourceCameraId'] == 'camera-a'


def test_two_cameras_fuse_delayed_observation_and_choose_measured_contact(tmp_path):
    from backend.database import Store
    from backend.multi_pipeline import PipelineManager

    manager=PipelineManager(Store(tmp_path/'delayed-fusion.sqlite'))
    base=dict(sessionId='session',timestamp=20.,position=[800,500],resolution=[1920,1080],
              targetPosition=[.52,.55],targetMode='surface',status='candidate',
              kind='possible_target_contact',confidence=None,speedBefore=35.,speedAfter=None,
              inOrOut='IN',contactEvidence='sparse_approach_loss',positionUncertaintyPx=80.)
    first=manager._record_event(dict(base,cameraId='phone'))
    second=manager._record_event(dict(base,cameraId='ip-camera',timestamp=21.45,
                                      targetPosition=[.48,.51],position=[900,520],
                                      contactEvidence='ground_measured_bounce',
                                      positionUncertaintyPx=6.))
    assert first['id']==second['id']
    assert second['observationCount']==2
    assert second['targetPosition']==pytest.approx([.48,.51])
    assert second['positionSourceCameraId']=='ip-camera'


def test_two_cameras_do_not_fuse_spatially_unrelated_impacts(tmp_path):
    from backend.database import Store
    from backend.multi_pipeline import PipelineManager

    manager=PipelineManager(Store(tmp_path/'separate-fusion.sqlite'))
    base=dict(sessionId='session',timestamp=30.,position=[100,100],resolution=[1000,600],
              targetMode='surface',status='candidate',kind='possible_bounce',
              confidence=None,speedBefore=30.,speedAfter=None,inOrOut='OUT',
              contactEvidence='ground_measured_bounce')
    first=manager._record_event(dict(base,cameraId='a',targetPosition=[-.3,.2]))
    second=manager._record_event(dict(base,cameraId='b',timestamp=30.8,targetPosition=[1.2,.9]))
    assert first['id']!=second['id']
    assert len(manager.store.all('event'))==2
