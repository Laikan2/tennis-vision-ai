import json
from types import SimpleNamespace

import numpy as np
import pytest

from backend.appearance import AppearanceModel, load_model, should_promote
from backend.database import Store
from backend.multi_pipeline import PipelineManager
from backend.tracking import BallTracker
from backend.bounce import BounceDetector


def test_session_index_and_cached_stats_include_review_edits(tmp_path, monkeypatch):
    store = Store(tmp_path/'history.sqlite')
    store.put('event', dict(id='old', sessionId='past', inOrOut='OUT'))
    event = store.put('event', dict(id='now', sessionId='live', timestamp=1., inOrOut='OUT'))
    manager = PipelineManager(store)
    manager.session = dict(id='live')
    first, _ = manager._session_summary()
    assert first['impacts'] == first['outside'] == 1
    calls = []
    query = store.for_session
    monkeypatch.setattr(store, 'for_session', lambda *args: calls.append(args) or query(*args))
    assert manager._session_summary()[0] is first
    assert calls == []
    store.put('event', dict(event, inOrOut='IN', status='confirmed'))
    revised, latest = manager._session_summary()
    assert len(calls) == 1
    assert revised['inside'] == revised['bounces'] == 1 and revised['outside'] == 0
    assert latest['id'] == 'now'
    plan = store.db.execute("EXPLAIN QUERY PLAN SELECT payload FROM objects WHERE kind=? AND json_extract(payload, '$.sessionId')=?", ('event','live')).fetchall()
    assert 'objects_session' in str(plan)


def impact(camera, timestamp=10., **kwargs):
    return dict(sessionId='s', cameraId=camera, timestamp=timestamp, position=[100,100],
                resolution=[1000,600], status='candidate', targetMode='surface',
                targetPosition=[.5,.5], inOrOut='IN', analysisMode='court', **kwargs)


@pytest.mark.parametrize('change', [dict(targetMode='image'), dict(analysisMode='room'),
                                   dict(targetPosition=[.8,.8]), dict(targetPosition=None)])
def test_different_views_do_not_fuse_without_compatible_contact(tmp_path, change):
    manager = PipelineManager(Store(tmp_path/'fusion.sqlite'))
    manager._record_event(impact('a'))
    manager._record_event(impact('b', 10.1) | change)
    assert len(manager.store.all('event')) == 2


def test_ambiguous_fast_rally_is_reviewed_instead_of_guessing(tmp_path):
    manager = PipelineManager(Store(tmp_path/'fusion.sqlite'))
    manager._record_event(impact('a', 10.))
    manager._record_event(impact('a', 10.2))
    event = manager._record_event(impact('b', 10.1))
    assert event['inOrOut'] == 'REVISAR' and not event['varAlert']
    assert len(event['possibleDuplicateOf']) == 2


def test_fusion_keeps_speed_method_and_camera_of_best_observation(tmp_path):
    manager = PipelineManager(Store(tmp_path/'fusion.sqlite'))
    manager._record_event(impact('a', speedBefore=12., speedMethod='image_size',
                                 contactEvidence='sparse_approach_loss'))
    event = manager._record_event(impact('b',10.1, speedBefore=45., speedMethod='plane',
                                         contactEvidence='ground_measured_bounce'))
    assert event['speedBefore'] == 45.
    assert event['speedMethod'] == 'plane' and event['speedSourceCameraId'] == 'b'


def ball(x, t):
    return dict(x=x-5, y=95., width=10., height=10., centerX=x, centerY=100.,
                source='model', confidence=.95, timestamp=t)


@pytest.mark.parametrize('fps', [60,120,240])
def test_motion_uses_video_time_despite_bursty_network(fps):
    regular, bursty = BallTracker(), BallTracker()
    for i in range(12):
        pts = i/fps
        t = 100+pts
        arrival = 100 + i*.001 + (i//4)*.12
        a = regular.update([ball(100+900*pts,t)],t,pts,'decoder-1')
        b = bursty.update([ball(100+900*pts,arrival)],arrival,pts,'decoder-1')
        assert a['centerX'] == b['centerX']
        assert a['velocityX'] == pytest.approx(b['velocityX'])
        assert b['timestamp'] == arrival
    predicted = bursty.update([], arrival+.01, pts+1/fps,'decoder-1')
    assert predicted['predicted']
    assert predicted['x']+predicted['width']/2 == predicted['centerX']
    assert predicted['sourceTime'] == pts+1/fps
    assert predicted['predictionAge'] == pytest.approx(1/fps)


def test_clock_restart_does_not_draw_an_old_trajectory():
    tracker = BallTracker()
    first = tracker.update([ball(100,10)],10,5.,'old')
    second = tracker.update([ball(800,11)],11,0.,'new')
    assert second['trackID'] != first['trackID']
    assert len(tracker.history) == 1 and second['velocityX'] == 0
    assert tracker.hint(11.1,.1,'old') is None


def weights():
    shapes = dict(w0=(1073,48), b0=(48,), w1=(48,16), b1=(16,), w2=(16,1), b2=(1,))
    return {key:np.zeros(shape,np.float32) for key,shape in shapes.items()}


def test_broken_replacement_model_keeps_working_version(tmp_path):
    np.savez(tmp_path/'good.npz', **weights())
    (tmp_path/'active.json').write_text(json.dumps(dict(version='good',file='good.npz',threshold=.85)))
    appearance = AppearanceModel(tmp_path)
    original = appearance.model
    corrupt = weights(); corrupt['w1'] = np.zeros((47,16),np.float32)
    np.savez(tmp_path/'broken.npz', **corrupt)
    with pytest.raises(ValueError): load_model(tmp_path/'broken.npz')
    (tmp_path/'active.json').write_text(json.dumps(dict(version='broken',file='broken.npz',threshold=.85)))
    appearance.checked = 0; appearance.reload()
    assert appearance.model is original and appearance.version == 'good'
    assert len(appearance.scores([np.zeros((20,20,3),np.uint8)])) == 1


def test_promotion_cannot_trade_more_missed_balls_for_precision():
    assert not should_promote(dict(precision=1.,recall=.9,f1=.947),
                              dict(precision=.92,recall=.94,f1=.93))


@pytest.mark.parametrize('fps', [60,120,240])
def test_dense_floor_contact_preserves_location_without_duplicates(fps):
    detector = BounceDetector()
    events=[]
    for t in np.arange(0,.22,1/fps):
        point=ball(200+t*180,float(t))
        point.update(centerY=600-900*abs(t-.1), width=12, height=12,
                     trackID=1,predicted=False,groundSurfaceEligible=True,
                     metersPerPixel=.003,sourceTime=float(t),sourceClock='video')
        event=detector.update(point,None,mode='court')
        if event: events.append(event)
    assert len(events) == 1
    assert events[0]['position'] == pytest.approx([218,600],abs=12)


@pytest.mark.parametrize('held', [True,False])
def test_high_rate_hand_motion_and_small_centroid_jitter_do_not_count(held):
    detector = BounceDetector()
    for i in range(180):
        t=i/240
        point=ball(250+t*100,t)
        point.update(centerY=600+(80 if held else 2)*np.sin(t*30),width=12,height=12,
                     trackID=1,predicted=False,groundSurfaceEligible=True,
                     insidePerson=held,metersPerPixel=.003,sourceTime=t,sourceClock='video')
        assert detector.update(point,None,mode='court') is None
