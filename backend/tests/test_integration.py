"""Synthetic fixtures exercise plumbing only; never count as field validation."""
import json
import time
import cv2
import numpy as np
from backend.database import Store
from backend.camera import CameraCapture
from backend.pipeline import Pipeline
from backend.replay import ReplayManager


def test_encoded_frames_replay_and_persistence(tmp_path,monkeypatch):
    import backend.replay as replay_module
    root=tmp_path/'media';(root/'replays').mkdir(parents=True)
    monkeypatch.setattr(replay_module,'DATA',root)
    store=Store(tmp_path/'db.sqlite')
    replay=ReplayManager(store,pre=.2,post=.1)
    ok,buffer=cv2.imencode('.jpg',np.full((64,64,3),40,np.uint8));assert ok
    jpeg=buffer.tobytes()
    for t in [1.,1.05,1.1]:replay.push(t,jpeg,dict(sequence=int(t*100)))
    id=replay.trigger(1.1,'test-session')
    replay.push(1.15,jpeg,{})
    replay.push(1.21,jpeg,{})
    replay.flush()
    result=store.get('replay',id)
    assert result['frames']==5 and not result['truncated']
    manifest=json.loads((root/'replays'/id/'manifest.json').read_text())
    assert manifest[0]['timestamp']==1.0 and manifest[-1]['timestamp']==1.21
    assert cv2.imread(str(root/'replays'/id/'0.jpg')).shape==(64,64,3)


def test_full_pipeline_consumes_decoded_frames(tmp_path,monkeypatch):
    import backend.replay as replay_module
    # The developer machine may have the first-run YOLO model; this synthetic
    # plumbing test intentionally exercises the no-model path.
    monkeypatch.setenv('TV_MODEL_PATH','')
    monkeypatch.setenv('TV_POSE_PATH','')
    root=tmp_path/'media';(root/'replays').mkdir(parents=True)
    monkeypatch.setattr(replay_module,'DATA',root)
    class Reader:
        def isOpened(self):return True
        def read(self):
            time.sleep(.02)
            image=np.zeros((96,128,3),np.uint8)
            cv2.circle(image,(50,50),4,(0,230,230),-1)
            return True,image
        def release(self):pass
    monkeypatch.setattr(CameraCapture,'_open',lambda self:Reader())
    store=Store(tmp_path/'db.sqlite')
    pipe=Pipeline(store)
    pipe.start(dict(id='camera',buffer=1),'test-source',dict(id='session',name='fixture'))
    try:
        deadline=time.monotonic()+3
        while not pipe.jpeg and time.monotonic()<deadline:time.sleep(.02)
        s=pipe.snapshot()
        assert s['framesProcessed']>=1 and s['resolution']==[128,96]
        assert s['statistics']['maxKmh'] is None
        assert s['statistics']['bounces']==0
        assert cv2.imdecode(np.frombuffer(pipe.jpeg,np.uint8),cv2.IMREAD_COLOR).shape==(96,128,3)
    finally:pipe.stop()
    assert not pipe.thread.is_alive()
    assert pipe.snapshot()['track'] is None
    assert store.get('session','session')['endedAt']>0
