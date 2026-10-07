"""False OUT and door-as-floor regressions from reviewed raw room frames."""
import json
from pathlib import Path
import pytest
import cv2
import numpy as np
from backend.bounce import BounceDetector
from backend.pipeline import Pipeline
from backend.calibration import CalibrationEngine
from backend.surface import validate_floor_boundary, on_floor_side
from backend.target import classify_impact
from backend.tests.test_evening_ground import point
from backend.tests.test_api import client


def call(position, possible=None, **extra):
    return classify_impact(position,[1000,1000],region=[.3,.3,.7,.7],
                           possible_positions=possible,**extra)


@pytest.mark.parametrize('ends', [
    [[200,400],[400,200]],  # Outside each edge, corridor touches the corner.
    [[200,500],[800,500]],  # Both ends OUT, passes across the entire target.
    [[100,100],[900,100],[500,950]],  # Envelope contains the court.
    [[250,250],[250,750],[750,750],[750,250]],
])
def test_outside_samples_whose_envelope_crosses_target_require_review(ends):
    result=call(ends[0],ends[1:],uncertainty_px=2)
    assert result['inOrOut']=='REVISAR'
    assert result['varAlert'] is False


def test_diagonal_graze_uses_whole_ball_and_uncertainty():
    # Segment misses the corner by about 7px, within a 5px radius + 4px uncertainty.
    near=call([200,390],[[390,200]],ball_diameter_px=10,uncertainty_px=4)
    clear=call([200,375],[[375,200]],ball_diameter_px=10,uncertainty_px=4)
    assert near['inOrOut']=='REVISAR'
    assert clear['inOrOut']=='OUT'


def test_unambiguous_envelopes_keep_in_out():
    assert call([400,400],[[500,600],[600,400]])['inOrOut']=='IN'
    assert call([150,400],[[200,600],[170,700]])['inOrOut']=='OUT'


def test_random_corridors_cannot_announce_out_if_an_intermediate_point_touches_target():
    rng=np.random.default_rng(61006)
    target=np.array([[300,300],[700,300],[700,700],[300,700]],np.float32)
    out=review=0
    for _ in range(250):
        a,b=rng.uniform(0,1000,(2,2));radius=float(rng.uniform(1,18));uncertainty=4.
        result=call(a.tolist(),[b.tolist()],ball_diameter_px=radius*2,uncertainty_px=uncertainty)
        if result['inOrOut']=='OUT':
            out+=1
            # Independent densely sampled oracle, including the rounded ball footprint.
            assert all(cv2.pointPolygonTest(target,tuple(map(float,p)),True)+radius < -uncertainty
                       for p in np.linspace(a,b,201))
        elif result['inOrOut']=='REVISAR':review+=1
    assert out>10 and review>10


@pytest.mark.parametrize('fps',[60,120,240])
def test_fast_floor_bounce_keeps_contact_at_dense_capture_rates(fps):
    detector=BounceDetector();events=[]
    for i in range(round(.32*fps)+1):
        t=i/fps;y=950-2200*abs(t-.16)
        event=detector.update(point(t,600+t*650,y,12),None,mode='court')
        if event:events.append(event)
    assert len(events)==1
    assert events[0]['timestamp']==pytest.approx(.16,abs=1/60)
    assert events[0]['position']==pytest.approx([704,950],abs=18)


@pytest.mark.parametrize('extra', [dict(position=[float('nan'),500]),
    dict(position=[500,500],possible=[[float('inf'),500]]),
    dict(position=[500,500],ball_diameter_px=float('nan')),
    dict(position=[500,500],uncertainty_px=-1),
])
def test_invalid_observations_never_announce_out(extra):
    result=call(**extra)
    assert result['inOrOut']=='REVISAR' and result['varAlert'] is False


def test_reviewed_held_ball_cannot_count_without_pose():
    # Pose was absent as the hand filled the camera. The original sequence
    # shrank from an 88px occluded fragment to 37px and falsely announced OUT.
    rows=json.loads((Path(__file__).parent/'fixtures/held_ball_false_floor.json').read_text())
    detector=BounceDetector()
    for row in rows:
        assert detector.update(row['track'],None,mode='court',timestamp=row['timestamp']) is None


@pytest.mark.parametrize('bad', [[],[[.1,.5]],[[.4,.5],[.42,.6]],
    [[-.1,.5],[.9,.6]],[[.1,float('nan')],[.9,.6]],[[.1,.5],[.9,1.1]]])
def test_floor_boundary_validation(bad):
    with pytest.raises(ValueError):validate_floor_boundary(bad)


def test_floor_boundary_is_sloped_extended_and_allows_ball_radius():
    line=validate_floor_boundary([[.8,.7],[.2,.5]])
    assert line==[[.2,.5],[.8,.7]]
    assert not on_floor_side([500,550],[1000,1000],line,30)
    assert on_floor_side([500,582],[1000,1000],line,30)
    assert on_floor_side([50,500],[1000,1000],line,30)
    assert on_floor_side([950,780],[1000,1000],line,30)


def test_room_corner_needs_both_visible_floor_edges():
    line=validate_floor_boundary([[.9,.65],[.2,.9],[.5,.55]])
    assert not on_floor_side([250,720],[1000,1000],line,20)  # Left wall.
    assert not on_floor_side([850,550],[1000,1000],line,20)  # Door.
    assert on_floor_side([250,900],[1000,1000],line,20)
    assert on_floor_side([850,760],[1000,1000],line,20)


def pipe(line):
    pipeline=Pipeline(None)
    pipeline.calibration=CalibrationEngine()
    pipeline.ball_diameter_m=.067
    pipeline.horizontal_fov_deg=70
    pipeline.region=None
    pipeline.polygon=[[.3,.8],[.7,.8],[.8,.95],[.2,.95]]
    pipeline.floor_boundary=line
    return pipeline


@pytest.mark.parametrize('ys,expected', [
    ([500,640,520,400],False), # Return on door, above physical floor.
    ([500,850,680,520],True),  # Real floor bounce outside far scoring edge.
])
def test_floor_gate_excludes_wall_without_hiding_outside_floor(ys,expected):
    pipeline=pipe([[.1,.7],[.9,.7]])
    detector=BounceDetector();events=[]
    for i,y in enumerate(ys):
        track=pipeline._enrich_track(point(i*.12,400,y),[1920,1080])
        event=detector.update(track,None,mode='court')
        if event:events.append(event)
    assert bool(events) is expected
    if events:
        e=events[0]
        assert classify_impact(e['position'],[1920,1080],polygon=pipeline.polygon,
            ball_diameter_px=e['ballDiameterPx'],uncertainty_px=e['positionUncertaintyPx'],
            possible_positions=e.get('possiblePositions'))['inOrOut']=='OUT'


def test_floor_line_does_not_reject_contact_between_two_airborne_frames():
    pipeline=pipe([[.1,.8],[.9,.8]])
    detector=BounceDetector();event=None
    for i,y in enumerate([500,750,750,500]):
        track=pipeline._enrich_track(point(i*.12,600,y),[1920,1080])
        assert track['groundSurfaceEligible'] is False
        event=detector.update(track,None,mode='court') or event
    assert event is not None
    assert event['position']==pytest.approx([600,875])


def test_floor_boundary_survives_two_camera_reopen_and_area_edit(client):
    from backend import api
    from backend.database import Store
    a=client.post('/api/cameras',json=dict(name='Piso A',host='192.168.0.10',password='keep')).json()
    b=client.post('/api/cameras',json=dict(name='Piso B',host='192.168.0.11')).json()
    polygon=[[.3,.5],[.7,.5],[.9,.9],[.1,.9]]
    client.post('/api/capture-region',json=dict(cameraId=a['id'],polygon=polygon,analysisMode='court'))
    original=api.store.get('camera',a['id'])
    line=[[.1,.45],[.9,.6]]
    response=client.post(f"/api/cameras/{a['id']}/floor-boundary",json=dict(boundary=line))
    assert response.status_code==200
    assert 'Encrypted' not in response.text and 'keep' not in response.text
    assert response.json()['capturePolygon']==polygon
    assert api.store.get('camera',a['id'])['passwordEncrypted']==original['passwordEncrypted']
    assert api.store.get('camera',b['id']).get('floorBoundary') is None
    client.post('/api/capture-region',json=dict(cameraId=a['id'],region=[.2,.5,.8,.9]))
    path=api.store.db.execute('PRAGMA database_list').fetchone()[2]
    reopened=Store(path)
    try:
        assert reopened.get('camera',a['id'])['floorBoundary']==line
        assert reopened.get('camera',a['id'])['captureRegion']==[.2,.5,.8,.9]
    finally:reopened.db.close()
    response=client.post(f"/api/cameras/{a['id']}/floor-boundary",json=dict(boundary=None))
    assert response.status_code==200 and response.json()['floorBoundary'] is None
    assert response.json()['captureRegion']==[.2,.5,.8,.9]


def test_floor_boundary_updates_active_worker_and_rejects_invalid_input(client):
    from backend import api
    camera=client.post('/api/cameras',json=dict(name='Piso',host='192.168.0.10')).json()
    worker=api.pipeline
    worker.camera_id=camera['id'];worker.session=dict(id='test-session');worker.region_changed_at=0
    line=[[.1,.5],[.9,.6]]
    response=client.post(f"/api/cameras/{camera['id']}/floor-boundary",json=dict(boundary=line))
    assert response.status_code==200
    assert worker.floor_boundary==line and worker.region_changed_at>0
    assert worker.snapshot()['floorBoundary']==line
    assert client.post(f"/api/cameras/{camera['id']}/floor-boundary",json=dict(boundary=[[.4,.5],[.42,.6]])).status_code==400
    assert worker.floor_boundary==line
    assert client.post('/api/cameras/missing/floor-boundary',json=dict(boundary=line)).status_code==404
