import json
import threading
import time
from types import SimpleNamespace
import cv2
import numpy as np
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from backend.analytics import StatisticsEngine
from backend.calibration import CalibrationEngine
from backend.camera import FileCapture, FrameBuffer
from backend.database import Store
from backend.frame_publisher import LatestFramePublisher
from backend.learning import LearningManager, session_split
from backend.multi_pipeline import PipelineManager
from backend.recordings import Recordings
from backend.review import review_event, ReviewInput, synchronization, SyncInput
from backend.onnx_pose import pose_tensor, decode_pose


@pytest.fixture
def evidence(tmp_path):
    store=Store(tmp_path/'store.sqlite');folder=tmp_path/'replays';folder.mkdir()
    store.put('camera',dict(id='a',captureRegion=[.1,.1,.9,.9]))
    store.put('camera',dict(id='b',captureRegion=[.1,.1,.9,.9]))
    store.put('event',dict(id='event',sessionId='session',cameraId='a',timestamp=10.,position=[60,60],
                           targetPosition=[.3,.5],inOrOut='OUT',status='candidate',speedBefore=40.,speedMethod='plane',varAlert=True))
    for id,camera,offset in [('ra','a',0.),('rb','b',.8)]:
        path=folder/id;path.mkdir()
        frames=[]
        for i in range(6):
            image=np.full((120,200,3),40,np.uint8);cv2.circle(image,(60,60),10,(20,220,180),-1)
            cv2.imwrite(str(path/f'{i}.jpg'),image)
            frames.append(dict(index=i,timestamp=10+offset+i*.1,overlay=dict(resolution=[200,120])))
        (path/'manifest.json').write_text(json.dumps(frames),encoding='utf-8')
        store.put('replay',dict(id=id,sessionId='session',cameraId=camera,eventIds=['event']))
    learning=LearningManager(tmp_path/'learning');learning.settings['autoTrain']=False
    return store,folder,learning


def test_review_rejects_false_impact_without_deleting_original_evidence(evidence):
    store,folder,learning=evidence
    result=review_event(store,learning,folder,'event',ReviewInput(replayId='ra',frameIndex=2,reason='false_impact'))
    assert result['event']['status']=='rejected' and not result['event']['varAlert']
    assert StatisticsEngine.summarize(store.all('event'))['impacts']==0
    assert store.all('feedback')[0]['previousEvent']['inOrOut']=='OUT'
    assert (folder/'ra'/'2.jpg').is_file()


def test_corrected_contact_invalidates_old_speed_and_preserves_session_group(evidence):
    store,folder,learning=evidence
    event=review_event(store,learning,folder,'event',ReviewInput(replayId='ra',frameIndex=2,reason='impact_position',position=(100,60),decision='IN'))['event']
    assert event['position']==[100,60] and event['targetPosition']==pytest.approx([.5,.5])
    assert event['speedBefore'] is None and event['inOrOut']=='IN' and event['manualReview']
    review_event(store,learning,folder,'event',ReviewInput(replayId='ra',frameIndex=2,reason='ball_missed',box=(50,50,20,20)))
    assert learning.examples[0]['group']=='session' and learning.examples[0]['split']==session_split('session')
    assert learning.examples[0]['label']==1 and learning.examples[0]['reviewed']


def test_contact_review_uses_original_area_and_scale_after_camera_edit(evidence):
    store,folder,learning=evidence
    old=CalibrationEngine.fit_surface([[20,12],[180,12],[180,108],[20,108]],2,4,[200,120],'Piso original','ground')
    snapshot=json.loads(json.dumps(dict(captureRegion=[.1,.1,.9,.9],capturePolygon=None,
                  courtOrientation='opposite',analysisMode='court',calibration=old)))
    store.put('event',dict(store.get('event','event'),surfaceSnapshot=snapshot))
    new=store.put('calibration',CalibrationEngine.fit_surface([[0,0],[200,0],[200,120],[0,120]],10,20,[200,120],'Piso novo','ground'))
    store.put('camera',dict(store.get('camera','a'),captureRegion=[.3,.3,.9,.9],calibrationId=new['id']))
    event=review_event(store,learning,folder,'event',ReviewInput(replayId='ra',frameIndex=2,reason='impact_position',position=(60,60),decision='IN'))['event']
    assert event['targetPosition']==pytest.approx([.75,.5])
    assert event['courtPosition']==pytest.approx([.5,2.])
    assert event['surfaceSnapshot']==snapshot


def test_secondary_view_review_preserves_its_own_original_full_image(evidence):
    store,folder,learning=evidence
    original=dict(captureRegion=None,capturePolygon=None,courtOrientation='normal',analysisMode='court',calibration=None)
    observation=dict(cameraId='b',timestamp=10.8,surfaceSnapshot=original)
    assert PipelineManager._observation(observation)['surfaceSnapshot']==original
    store.put('event',dict(store.get('event','event'),surfaceSnapshot={'captureRegion':[.3,.3,.9,.9]},
                           observations=[observation]))
    store.put('camera',dict(store.get('camera','b'),captureRegion=[.3,.3,.9,.9]))
    event=review_event(store,learning,folder,'event',ReviewInput(replayId='rb',frameIndex=2,reason='impact_position',position=(60,60)))['event']
    assert event['targetPosition']==pytest.approx([.3,.5])
    assert event['courtPosition'] is None
    assert event['surfaceSnapshot']==original


@pytest.mark.parametrize('data', [dict(frameIndex=100),dict(position=(-1,20)),dict(box=(190,90,30,30),reason='false_ball')])
def test_invalid_review_never_changes_decision_or_creates_labels(evidence,data):
    store,folder,learning=evidence
    payload=dict(replayId='ra',frameIndex=1,reason='impact_position',position=(100,60));payload.update(data)
    with pytest.raises(ValueError):review_event(store,learning,folder,'event',ReviewInput(**payload))
    assert store.get('event','event')['inOrOut']=='OUT' and not store.all('feedback') and not learning.examples


def test_sync_uses_actual_frame_times_and_rejects_wrong_or_duplicate_references(evidence):
    store,folder,_=evidence
    anchors=[dict(referenceReplayId='ra',referenceFrame=i,secondaryReplayId='rb',secondaryFrame=i) for i in (0,2,4)]
    data=SyncInput(referenceCameraId='a',secondaryCameraId='b',anchors=anchors)
    saved=synchronization(store,folder,data)
    assert saved['offsetSeconds']==pytest.approx(.8) and saved['jitterMs']<1e-9
    assert saved['frameUncertaintyMs']==pytest.approx(100)
    with pytest.raises(ValueError):synchronization(store,folder,data.model_copy(update={'anchors':[data.anchors[0]]*3}))
    store.put('camera_sync',saved)
    manager=PipelineManager(store);manager.pipelines={'a':None,'b':None}
    def impact(camera,t):return dict(sessionId='session',cameraId=camera,timestamp=t,status='candidate',targetPosition=[.5,.5],targetMode='surface',analysisMode='court',inOrOut='IN')
    first=manager._record_event(impact('a',10.))
    # A late second view must not undo a human correction.
    store.put('event',dict(first,inOrOut='OUT',targetDecision='OUT',manualReview={'reviewer':'user'},varAlert=False))
    result=manager._record_event(impact('b',10.8))
    assert len(store.all('event'))==2  # pre-existing fixture event + one fused event
    assert result['observationCount']==2 and result['inOrOut']=='OUT' and not result['varAlert']
    assert result['observations'][1]['timeCorrectionMs']==pytest.approx(800)


def test_planar_room_scale_corrects_perspective_and_requires_real_dimensions():
    points=[[50,10],[150,10],[195,110],[5,110]]
    profile=CalibrationEngine.fit_surface(points,2,4,[200,120],'Piso','ground')
    engine=CalibrationEngine(profile)
    assert profile['quality']=='não validada'
    for point,expected in zip(points,[[0,0],[2,0],[2,4],[0,4]]):assert engine.map(*point,[200,120])==pytest.approx(expected)
    assert engine.map(100,50,[400,240]) is None
    with pytest.raises(ValueError):CalibrationEngine.fit_surface(points,float('nan'),4,[200,120],'Piso','ground')


def movie(path,frames=12,fps=120):
    writer=cv2.VideoWriter(str(path),cv2.VideoWriter_fourcc(*'mp4v'),fps,(160,120))
    assert writer.isOpened()
    for i in range(frames):writer.write(np.full((120,160,3),i*5,np.uint8))
    writer.release()


def test_original_file_reads_every_frame_at_source_rate_without_looping(tmp_path):
    path=tmp_path/'video.mp4';movie(path)
    buffer=FrameBuffer(2,preserve_all=True);capture=FileCapture(path,buffer,epoch=100.)
    capture.start();frames=[]
    try:
        while not capture.eof or buffer.items:
            frame=buffer.pop(.1)
            if frame:frames.append(frame)
    finally:capture.stop()
    assert len(frames)==capture.frames==12 and buffer.dropped==0
    assert [f.sequence for f in frames]==list(range(1,13))
    assert frames[-1].source_time-frames[0].source_time==pytest.approx(11/120,abs=.001)
    assert capture.eof and not capture.failed


def test_offline_publisher_is_bounded_and_never_discards_evidence():
    output=[];worker=LatestFramePublisher(lambda f,s,id: (time.sleep(.005),output.append(f)),preserve_all=True)
    for i in range(15):worker.submit(i,{},'s')
    worker.stop()
    assert output==list(range(15)) and worker.skipped==0


def test_recording_upload_rejects_bad_input_and_preserves_original(tmp_path):
    app=FastAPI();store=Store(tmp_path/'record.sqlite');store.put('camera',dict(id='cam'))
    service=Recordings(app,store,SimpleNamespace(session=None),SimpleNamespace(busy=False),tmp_path/'files',threading.RLock())
    client=TestClient(app)
    assert client.post('/api/recordings?cameraId=cam',content=b'not video').status_code==400
    assert not list((tmp_path/'files').glob('*.mp4'))
    path=tmp_path/'video.mp4';movie(path,6,60)
    response=client.post('/api/recordings?cameraId=cam',content=path.read_bytes())
    assert response.status_code==200
    row=response.json();assert row['sourceFrames']==6 and row['sourceFps']==60
    assert client.get(f"/api/recordings/{row['id']}/original").content==path.read_bytes()
    assert client.get('/api/recordings/not-uuid/frame').status_code==404


def test_recording_job_finishes_all_frames_without_changing_saved_camera(tmp_path,monkeypatch):
    import backend.replay as replay_module
    data=tmp_path/'media';(data/'replays').mkdir(parents=True)
    monkeypatch.setattr(replay_module,'DATA',data)
    monkeypatch.setenv('TV_POSE_PATH',str(tmp_path/'missing-pose.pt'))
    monkeypatch.setenv('TV_AUTO_LEARNING_DIR',str(tmp_path/'automatic'))
    app=FastAPI();store=Store(tmp_path/'record.sqlite')
    original=store.put('camera',dict(id='cam',ballDiameterMm=67,capturePolygon=[[.1,.2],[.9,.2],[.9,.9],[.1,.9]],analysisMode='court'))
    service=Recordings(app,store,SimpleNamespace(session=None),SimpleNamespace(busy=False),data/'recordings',threading.RLock())
    path=tmp_path/'video.mp4';movie(path,30,120)
    client=TestClient(app)
    row=client.post('/api/recordings?cameraId=cam',content=path.read_bytes()).json()
    assert client.post(f"/api/recordings/{row['id']}/analyze",json={}).status_code==200
    service.worker.join(15)
    assert not service.busy
    result=store.get('recording',row['id'])
    assert result['status']=='complete' and result['framesProcessed']==result['decodedFrames']==30
    assert result['progress']==100 and store.get('session',result['sessionId'])['endedAt']
    assert store.get('camera','cam')==original


def test_onnx_preprocess_decode_preserves_coordinates_and_rejects_bad_output():
    image=np.zeros((120,200,3),np.uint8);tensor,transform=pose_tensor(image)
    assert tensor.shape==(1,3,256,256) and tensor.dtype==np.float32
    raw=np.zeros((1,56,1),np.float32)
    raw[0,:5,0]=[128,128,100,100,.9]
    raw[0,5:,0]=np.tile([128,128,.8],17)
    persons=decode_pose(raw,transform,image.shape,10.)
    assert len(persons)==1 and persons[0]['centerX']==pytest.approx(100)
    assert persons[0]['centerY']==pytest.approx(60,abs=1)
    assert len(persons[0]['keypoints'])==17
    with pytest.raises(ValueError):decode_pose(np.zeros((1,55,1)),transform,image.shape,10.)


def test_two_cameras_do_not_initialize_and_probe_the_runtime_concurrently(tmp_path,monkeypatch):
    from concurrent.futures import ThreadPoolExecutor
    import backend.onnx_pose as pose_module
    import sys
    model=tmp_path/'pose.onnx';model.write_bytes(b'approved-test-model')
    state={'active':0,'maximum':0};guard=threading.Lock();barrier=threading.Barrier(2)
    def fake_session(path,provider):
        with guard:
            state['active']+=1;state['maximum']=max(state['maximum'],state['active'])
        time.sleep(.03)
        with guard:state['active']-=1
        return SimpleNamespace(get_inputs=lambda:[SimpleNamespace(name='images',shape=[1,3,256,256])])
    monkeypatch.setitem(sys.modules,'onnxruntime',SimpleNamespace(get_available_providers=lambda:['CPUExecutionProvider']))
    monkeypatch.setattr(pose_module,'session',fake_session)
    def create(_):
        barrier.wait(timeout=3)
        return pose_module.OnnxPose(model)
    with ThreadPoolExecutor(2) as pool:results=list(pool.map(create,range(2)))
    assert state['maximum']==1 and len(results)==2
    assert all(r.report['device']=='CPU · ONNX' for r in results)
