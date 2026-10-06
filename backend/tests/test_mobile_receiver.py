"""The phone handshake checks actual published video and keeps saved profiles."""
import socket
import threading
import time
import pytest
from backend.mobile_receiver import receiver_status
from backend.tests.test_api import client


def rtsp_peer(reply, delay=0):
    listener=socket.socket()
    listener.bind(('127.0.0.1',0))
    listener.listen(1)
    port=listener.getsockname()[1]
    def serve():
        try:
            peer,_=listener.accept()
            with peer:
                request=peer.recv(4096)
                assert b'DESCRIBE rtsp://127.0.0.1:' in request
                time.sleep(delay)
                for chunk in reply:
                    peer.sendall(chunk)
        except OSError:
            pass
        finally:
            listener.close()
    thread=threading.Thread(target=serve,daemon=True)
    thread.start()
    return port,thread


def test_real_rtsp_handshake_handles_fragmented_sdp_and_announced_240fps():
    body=b'v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=framerate:240\r\n'
    header=f'RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: {len(body)}\r\n\r\n'.encode()
    port,thread=rtsp_peer([header[:12],header[12:],body[:10],body[10:]])
    result=receiver_status(port=port)
    thread.join(1)
    assert result['receiverAvailable'] and result['streamReady']
    assert result['announcedFps']==240


@pytest.mark.parametrize('response',[
    b'RTSP/1.0 404 Not Found\r\nCSeq: 1\r\n\r\n',
    b'RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n\r\n',
    b'RTSP/1.0 200 OK\r\nContent-Length: 9000000\r\n\r\n'+b'x'*33000,
    b'RTSP/1.0 200 OK\r\nContent-Length: 8\r\n\r\nm=audio ',
],ids=['unpublished','authentication','oversized','audio-only'])
def test_open_port_does_not_pretend_to_be_a_working_video(response):
    port,thread=rtsp_peer([response])
    result=receiver_status(port=port)
    thread.join(1)
    assert result['receiverAvailable'] and not result['streamReady']


def test_unresponsive_receiver_cannot_block_the_panel():
    port,thread=rtsp_peer([],delay=.35)
    started=time.monotonic()
    result=receiver_status(port=port,timeout=.1)
    assert time.monotonic()-started<.3
    assert result['receiverAvailable'] and not result['streamReady']
    thread.join(1)


def test_connect_starts_once_and_preserves_saved_camera_configuration(client,monkeypatch):
    from backend import api
    monkeypatch.setattr(api,'receiver_status',lambda:dict(streamReady=True))
    camera=client.post('/api/cameras',json=dict(name='Meu Galaxy',host='127.0.0.1',port=8554,
                                             path='/galaxy',requestedFps=240,username='saved',password='keep')).json()
    assert client.post('/api/capture-region',json=dict(cameraId=camera['id'],region=[.2,.3,.8,.9],impactSurface='ground')).status_code==200
    before=api.store.get('camera',camera['id'])
    calls=[]
    def start(camera,url,session):
        calls.append(session)
        api.pipeline.session=session
    monkeypatch.setattr(api.pipeline,'start',start)
    first=client.post('/api/mobile-camera/connect')
    assert first.status_code==200 and first.json()['alreadyRunning'] is False
    second=client.post('/api/mobile-camera/connect')
    assert second.status_code==200 and second.json()['alreadyRunning'] is True
    assert len(calls)==1
    assert api.store.get('camera',camera['id'])==before
    assert len(client.get('/api/cameras').json())==1
    assert len(client.get('/api/sessions').json())==1
    assert 'keep' not in first.text and 'Encrypted' not in first.text


def test_no_incoming_video_creates_no_camera_or_session(client,monkeypatch):
    from backend import api
    monkeypatch.setattr(api,'receiver_status',lambda:dict(streamReady=False))
    assert client.post('/api/mobile-camera/connect').status_code==409
    assert client.get('/api/cameras').json()==[]
    assert client.get('/api/sessions').json()==[]


def test_first_connection_registers_a_local_camera_and_starts_analysis(client,monkeypatch):
    from backend import api
    monkeypatch.setattr(api,'receiver_status',lambda:dict(streamReady=True))
    calls=[]
    monkeypatch.setattr(api.pipeline,'start',lambda camera,url,session:calls.append((camera,url,session)))
    response=client.post('/api/mobile-camera/connect')
    assert response.status_code==200
    camera=response.json()['camera']
    assert camera['ballDiameterMm']==67 and camera['path']=='/galaxy'
    assert calls[0][1]=='rtsp://127.0.0.1:8554/galaxy'
    assert response.json()['session']['cameraId']==camera['id']
    assert len(client.get('/api/cameras').json())==1


def test_connect_cannot_replace_another_active_session(client,monkeypatch):
    from backend import api
    session=dict(id='keep',cameraId='other')
    api.pipeline.session=session
    assert client.post('/api/mobile-camera/connect').status_code==409
    assert api.pipeline.session==session
    assert client.get('/api/cameras').json()==[]
