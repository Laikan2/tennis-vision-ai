import json
import threading
import time
from types import SimpleNamespace
import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient
from backend.appearance import features, votes, should_promote, train_model
from backend.learning import LearningManager,session_split
from backend.speed import SpeedEstimator
from backend.tracking import BallTracker
from backend.bounce import BounceDetector
from backend.adaptive import AdaptiveBallSignature,appearance_descriptor,descriptor_similarity


def jpeg():
    frame=np.full((120,200,3),40,np.uint8)
    cv2.circle(frame,(60,60),10,(20,220,180),-1)
    return cv2.imencode('.jpg',frame)[1].tobytes()


def test_capture_does_not_create_training_labels_and_is_bounded(tmp_path):
    manager=LearningManager(tmp_path);manager.settings['autoTrain']=False
    state=dict(timestamp=time.time(),track=None)
    item=manager.capture(jpeg(),state,'session')
    assert item and item['box'] is None and manager.examples==[]
    assert manager.capture(jpeg(),state,'session') is None
    manager.queue=[dict(item,id=str(i)) for i in range(120)]
    assert manager.capture(jpeg(),state,'session',manual=True) is None


def test_review_requires_valid_manual_box_and_persists_only_reviewed_patch(tmp_path):
    manager=LearningManager(tmp_path);manager.settings['autoTrain']=False
    item=manager.capture(jpeg(),dict(timestamp=1),'same-session',manual=True)
    for box in (None,[-1,0,5,5],[190,20,25,10],[0,0,1,1],[float('nan'),0,10,10]):
        with pytest.raises(ValueError):manager.review(item['id'],1,box)
    assert manager.status()['reviewed']==0
    manager.review(item['id'],1,[50,50,20,20])
    restored=LearningManager(tmp_path)
    assert restored.examples[0]['reviewed'] and restored.examples[0]['label']==1
    assert restored.examples[0]['split']==session_split('same-session')
    assert (tmp_path/restored.examples[0]['image']).is_file()
    assert not (tmp_path/'frames'/f"{item['id']}.jpg").exists()
    with pytest.raises(ValueError):manager.image_path('../secrets')
    with pytest.raises(ValueError):manager.review(item['id'],0,[50,50,20,20])


def test_ignored_capture_never_enters_training(tmp_path):
    manager=LearningManager(tmp_path)
    item=manager.capture(jpeg(),dict(timestamp=1),'session',manual=True)
    manager.review(item['id'],None,None)
    assert not manager.examples and not manager.status()['pending']


def test_training_cannot_overlap_capture_and_unreviewed_do_not_count(tmp_path):
    manager=LearningManager(tmp_path);manager.is_running=lambda:True
    with pytest.raises(ValueError,match='Encerre'):manager.train()
    rows=[dict(reviewed=False,label=i%2,split='train',group='x') for i in range(100)]
    (tmp_path/'examples.json').write_text(json.dumps(rows))
    with pytest.raises(ValueError,match='10 exemplos'):train_model(tmp_path)


def test_same_session_cannot_leak_into_validation(tmp_path):
    rows=[dict(reviewed=True,label=i%2,split=split,group='same') for split in ('train','test') for i in range(60)]
    (tmp_path/'examples.json').write_text(json.dumps(rows))
    with pytest.raises(ValueError,match='mesma sessão'):train_model(tmp_path)


def test_promotion_preserves_better_existing_model():
    current=dict(precision=.97,recall=.9,f1=.933)
    assert not should_promote(dict(precision=.89,recall=1.,f1=.94),None)
    assert not should_promote(dict(precision=.95,recall=.99,f1=.96),current)
    assert should_promote(dict(precision=.98,recall=.95,f1=.965),current)


def test_green_support_rejects_neutral_centre_even_with_green_context():
    patch=np.full((40,40,3),(20,220,180),np.uint8)
    patch[12:28,12:28]=230
    vector=features(patch)
    model={'w0':np.zeros((len(vector),1)),'b0':np.ones(1),
           'w1':np.ones((1,1)),'b1':np.ones(1),'w2':np.ones((1,1)),'b2':np.ones(1)*20}
    assert votes(model,[vector])[0]==0


def test_automatic_signature_learns_from_coherent_motion_and_persists(tmp_path):
    signature=AdaptiveBallSignature('camera-one',tmp_path/'automatic')
    history=[]
    colour=tuple(int(v) for v in cv2.cvtColor(
        np.uint8([[[38,220,220]]]),cv2.COLOR_HSV2BGR)[0,0])
    for index in range(10):
        x=35+index*10;timestamp=index*.1
        image=np.full((140,220,3),35,np.uint8)
        cv2.circle(image,(x,70),9,colour,-1)
        track=dict(x=x-9,y=61,width=18,height=18,centerX=x,centerY=70,
                   measuredX=x,measuredY=70,trackID=1,timestamp=timestamp,
                   predicted=False,insidePerson=False,source='heuristic',moving=True,
                   tennisColor=True,tennisFraction=.9,ballScore=.95,roundness=.9,
                   frameWidth=220)
        history.append(track)
        signature.learn(image,track,history,confirmed=True)
    assert signature.ready and signature.status()['sampleCount'] >= signature.ready_samples
    restored=AdaptiveBallSignature('camera-one',tmp_path/'automatic')
    assert restored.ready and restored.status()['sampleCount'] >= restored.ready_samples
    candidates=[dict(x=116,y=61,width=18,height=18,centerX=125,centerY=70,
                     source='heuristic',moving=True,roundness=.9,ballScore=.4)]
    image=np.full((140,220,3),35,np.uint8);cv2.circle(image,(125,70),9,colour,-1)
    restored.score_candidates(image,candidates)
    assert candidates[0]['autoAppearanceVerified']
    assert candidates[0]['autoAppearanceScore'] >= restored.verify_threshold


def test_automatic_signature_rejects_static_object_and_distinguishes_colour(tmp_path):
    green=np.full((50,50,3),(30,200,80),np.uint8)
    blue=np.full((50,50,3),(220,70,30),np.uint8)
    first=appearance_descriptor(green,[5,5,40,40])
    similar=appearance_descriptor(np.clip(green.astype(np.int16)+12,0,255).astype(np.uint8),[5,5,40,40])
    other=appearance_descriptor(blue,[5,5,40,40])
    assert descriptor_similarity(first,similar) > descriptor_similarity(first,other)+.25
    signature=AdaptiveBallSignature('static',tmp_path/'automatic')
    image=np.full((100,100,3),50,np.uint8)
    history=[dict(x=40,y=40,width=14,height=14,centerX=47,centerY=47,
                  measuredX=47,measuredY=47,trackID=1,timestamp=i*.1,
                  predicted=False,insidePerson=False,source='model',moving=False,
                  roundness=.9,ballScore=.9,frameWidth=100) for i in range(5)]
    assert not signature.learn(image,history[-1],history,confirmed=True)
    assert signature.status()['sampleCount']==0


def test_automatic_signature_rejects_flicker_that_jumps_between_background_details(tmp_path):
    signature=AdaptiveBallSignature('jumping-background',tmp_path/'automatic')
    image=np.full((360,640,3),(40,80,150),np.uint8)
    positions=[(472,213),(472,213),(472,214),(527,188),(472,213),(499,200)]
    history=[]
    for index,(x,y) in enumerate(positions):
        track=dict(x=x-5,y=y-6,width=10,height=12,centerX=x,centerY=y,
                   measuredX=x,measuredY=y,trackID=7,timestamp=index/30,
                   predicted=False,insidePerson=False,source='heuristic',moving=True,
                   tennisColor=True,tennisFraction=.7,ballScore=.72,roundness=.62,
                   frameWidth=640,frameHeight=360)
        history.append(track)
        signature.learn(image,track,history,confirmed=True)
    assert signature.status()['sampleCount']==0


def test_automatic_signature_never_learns_a_track_that_crossed_person(tmp_path):
    signature=AdaptiveBallSignature('green-shirt',tmp_path/'automatic')
    image=np.full((240,320,3),(25,145,50),np.uint8)
    history=[]
    for index in range(7):
        x=80+index*18
        track=dict(x=x-12,y=90,width=24,height=28,centerX=x,centerY=104,
                   measuredX=x,measuredY=104,trackID=4,timestamp=index*.08,
                   predicted=False,insidePerson=index<5,source='heuristic',moving=True,
                   tennisColor=True,tennisFraction=.8,ballScore=.9,roundness=.78,
                   frameWidth=320,frameHeight=240)
        history.append(track)
        signature.learn(image,track,history,confirmed=True)
    assert signature.status()['sampleCount']==0


def test_speed_does_not_shrink_when_blur_stretches_long_axis():
    for width in (20,70):
        estimator=SpeedEstimator(.067);result=None
        for i in range(5):
            result=estimator.update(dict(trackID=1,timestamp=i*.04,predicted=False,source='appearance',
                                        centerX=i*20,centerY=10,width=width,height=20),None)
        assert result['projectedKmh']==pytest.approx(6.03)
        assert result['physicalKmh'] is None


def test_speed_uses_departure_only_after_reversal():
    estimator=SpeedEstimator();result=None
    for i,x in enumerate([0,1,2,3,2,1,0]):
        result=estimator.update(dict(trackID=1,timestamp=i*.1,predicted=False,source='model'),[x,0])
    assert result['projectedKmh']==pytest.approx(36)


def test_two_verified_observations_acquire_without_waiting_for_third():
    tracker=BallTracker(green_only=True)
    def ball(x,t):return dict(x=x-6,y=20,width=12,height=8,centerX=x,centerY=24,timestamp=t,
                             source='appearance',appearanceVerified=True,appearanceScore=.98,
                             tennisColor=True,tennisFraction=.6,ballScore=.95,moving=True)
    assert tracker.update([ball(100,0)],0) is None
    assert tracker.update([ball(140,.04)],.04)['predicted'] is False


def test_depth_impact_not_inferred_from_extreme_motion_blur():
    detector=BounceDetector();events=[]
    for i,depth in enumerate([1.,1.3,1.6,1.3,1.]):
        track=dict(trackID=1,timestamp=i*.05,predicted=False,centerX=100,centerY=100,
                   width=60,height=4,depthMeters=depth,depthReliable=False,tennisColor=True)
        events.append(detector.update(track,None,mode='room'))
    assert not any(events)


def test_video_ack_skips_intermediate_frames_and_keeps_metadata_pair(monkeypatch):
    from backend import api
    pipe=SimpleNamespace(lock=threading.RLock(),session={'id':'test'},jpeg=b'jpeg-one',
                         capture=SimpleNamespace(resolution=[200,120]),
                         frame_state=dict(sequence=1,timestamp=time.time(),track={'centerX':10}),
                         state=dict(sequence=2,timestamp=time.time(),track={'centerX':20}))
    monkeypatch.setattr(api,'pipeline',pipe)
    def unpack(packet):
        length=int.from_bytes(packet[:4],'big')
        return json.loads(packet[4:4+length]),packet[4+length:]
    with TestClient(api.app) as client:
        # Avoid app shutdown invoking the real pipeline interface on this fake.
        pipe.stop=lambda:None
        with client.websocket_connect('/api/video',headers={'origin':'http://localhost:3000'}) as ws:
            metadata,data=unpack(ws.receive_bytes())
            assert metadata['sequence']==1 and data==b'jpeg-one'
            with pipe.lock:
                pipe.frame_state=dict(sequence=3,timestamp=time.time(),track={'centerX':30})
                pipe.state=dict(sequence=4,timestamp=time.time(),track={'centerX':40})
                pipe.jpeg=b'jpeg-three'
            ws.send_text('1')
            metadata,data=unpack(ws.receive_bytes())
            assert metadata['sequence']==3 and metadata['track']['centerX']==30 and data==b'jpeg-three'


def test_video_rejects_untrusted_origin():
    from backend import api
    from starlette.websockets import WebSocketDisconnect
    with TestClient(api.app) as client:
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect('/api/video',headers={'origin':'https://elsewhere.example'}):pass


def test_decoder_uses_small_thread_budget_without_setting_fps(monkeypatch):
    from backend.camera import CameraCapture,FrameBuffer
    calls=[]
    class Capture:
        def set(self,key,value):calls.append(('set',key,value))
    def open_capture(url,backend,parameters):
        calls.append(('open',parameters));return Capture()
    monkeypatch.setattr(cv2,'VideoCapture',open_capture)
    monkeypatch.delenv('TV_DECODER_THREADS',raising=False)
    CameraCapture('test',FrameBuffer())._open()
    parameters=calls[0][1]
    assert parameters[parameters.index(cv2.CAP_PROP_N_THREADS)+1]==2
    assert not any(c[0]=='set' and c[1]==cv2.CAP_PROP_FPS for c in calls)


def test_training_is_automatically_dispatched_only_after_review_batch(tmp_path,monkeypatch):
    manager=LearningManager(tmp_path);calls=[]
    monkeypatch.setattr(manager,'train',lambda:calls.append(True))
    manager.examples=[dict(reviewed=True) for _ in range(7)]
    manager.maybe_train();assert not calls
    manager.examples.append(dict(reviewed=True))
    manager.is_running=lambda:True;manager.maybe_train();assert not calls
    manager.is_running=lambda:False;manager.maybe_train();assert len(calls)==1


def test_idle_video_client_disconnect_does_not_leave_stream_loop(monkeypatch):
    from backend import api
    pipe=SimpleNamespace(lock=threading.RLock(),session=None,jpeg=None,state={},frame_state={},stop=lambda:None)
    monkeypatch.setattr(api,'pipeline',pipe)
    with TestClient(api.app) as client:
        with client.websocket_connect('/api/video',headers={'origin':'http://localhost:3000'}):pass
