import importlib
import json
import pytest
from fastapi.testclient import TestClient
from cryptography.fernet import Fernet


@pytest.fixture
def client(tmp_path,monkeypatch):
    from backend.database import Store
    from backend.security import CameraSecrets
    from backend.pipeline import Pipeline
    from backend import api
    monkeypatch.setenv('TV_SECRET_KEY',Fernet.generate_key().decode())
    store=Store(tmp_path/'db.sqlite')
    monkeypatch.setattr(api,'store',store)
    monkeypatch.setattr(api,'secrets',CameraSecrets())
    monkeypatch.setattr(api,'pipeline',Pipeline(store))
    with TestClient(api.app) as c:yield c


def test_camera_credentials_never_returned(client):
    r=client.post('/api/cameras',json=dict(name='Camera',host='192.168.0.1',username='testuser',password='secretpassword'))
    assert r.status_code==200
    assert 'secretpassword' not in r.text and 'Encrypted' not in r.text and 'testuser' not in r.text
    assert 'secretpassword' not in client.get('/api/cameras').text
    assert client.post('/api/cameras',json=dict(name='x',host='bad@host')).status_code==422


def test_camera_accepts240fps_without_a60fps_receiver_limit(client):
    response=client.post('/api/cameras',json=dict(name='Galaxy 240',host='127.0.0.1',port=8554,
                                               path='/galaxy',requestedFps=240))
    assert response.status_code==200
    assert response.json()['requestedFps']==240
    assert client.post('/api/cameras',json=dict(name='Too high',host='127.0.0.1',requestedFps=241)).status_code==422


def test_mobile_camera_returns_local_publisher_and_safe_receiver(client,monkeypatch):
    from backend import api
    monkeypatch.setattr(api,'_local_ipv4',lambda:'192.168.15.2')
    payload=client.get('/api/mobile-camera').json()
    assert payload['available'] is True
    assert payload['androidUrl'].startswith('https://192.168.15.2:8892/galaxy/publish?')
    assert 'video-framerate=60' in payload['androidUrl']
    assert payload['appPairingUri'].startswith('tennisvision://pair?')
    assert 'host=192.168.15.2' in payload['appPairingUri']
    assert payload['srtUrl']=='srt://192.168.15.2:8890?streamid=publish:galaxy&latency=40&pkt_size=1316'
    assert 'latency=40' in payload['appPairingUri']
    assert payload['receiverUrl']=='rtsp://127.0.0.1:8554/galaxy'
    assert payload['cameraPreset']['host']=='127.0.0.1'
    assert payload['cameraPreset']['path']=='/galaxy'
    assert 'password' not in json.dumps(payload).lower()


def test_offline_has_no_fake_data(client):
    status=client.get('/api/status').json()
    assert status['cameraStatus']=='offline' and status['track'] is None
    assert client.get('/api/frame').status_code==404
    assert client.post('/api/replays',json={}).status_code==409
    assert client.get('/api/accuracy').json()['summary']['mae'] is None


def test_android_app_download_and_unavailable_state(client,monkeypatch,tmp_path):
    from backend import api
    apk=tmp_path/'Tennis-Vision-Camera.apk'
    monkeypatch.setattr(api,'ANDROID_APK',apk)
    assert client.get('/api/mobile-camera').json()['androidApp']['available'] is False
    assert client.get('/api/mobile-camera/android.apk').status_code==404
    apk.write_bytes(b'test-apk-content')
    info=client.get('/api/mobile-camera').json()['androidApp']
    assert info['available'] is True and info['sizeBytes']==len(b'test-apk-content')
    download=client.get(info['downloadUrl'])
    assert download.status_code==200 and download.content==b'test-apk-content'
    assert download.headers['content-type']=='application/vnd.android.package-archive'
    assert 'attachment' in download.headers['content-disposition']


def test_ball_scale_changes_without_resaving_credentials_or_surface(client):
    from backend import api
    camera=client.post('/api/cameras',json=dict(name='Camera',host='192.168.0.2',
                                               username='admin',password='keep-secret')).json()
    client.post('/api/capture-region',json=dict(cameraId=camera['id'],region=[.2,.2,.8,.8]))
    original=api.store.get('camera',camera['id'])
    result=client.post(f"/api/cameras/{camera['id']}/ball-scale",json=dict(ballDiameterMm=67))
    assert result.status_code==200 and result.json()['ballDiameterMm']==67
    saved=api.store.get('camera',camera['id'])
    assert saved['captureRegion']==[.2,.2,.8,.8]
    assert saved['passwordEncrypted']==original['passwordEncrypted']
    assert 'keep-secret' not in result.text
    assert client.post(f"/api/cameras/{camera['id']}/ball-scale",json=dict(ballDiameterMm=999)).status_code==422


def test_analysis_mode_changes_without_erasing_saved_surface(client):
    from backend import api
    camera=client.post('/api/cameras',json=dict(name='Parede',host='192.168.0.2')).json()
    polygon=[[.2,.1],[.8,.1],[.95,.9],[.05,.9]]
    client.post('/api/capture-region',json=dict(cameraId=camera['id'],polygon=polygon,
                                                analysisMode='room'))
    result=client.post(f"/api/cameras/{camera['id']}/analysis-mode",
                       json=dict(analysisMode='tracking'))
    assert result.status_code==200 and result.json()['analysisMode']=='tracking'
    saved=api.store.get('camera',camera['id'])
    assert saved['capturePolygon']==polygon
    assert client.post(f"/api/cameras/{camera['id']}/analysis-mode",
                       json=dict(analysisMode='invalid')).status_code==422


def test_capture_region_is_validated_and_persisted(client):
    from backend import api
    camera = client.post('/api/cameras', json=dict(name='Quarto', host='192.168.0.2')).json()
    region = [.1,.2,.8,.9]
    assert client.post('/api/capture-region', json=dict(cameraId=camera['id'],region=region)).status_code == 200
    assert api.store.get('camera',camera['id'])['captureRegion'] == region
    for invalid in [[-.1,0,1,1],[.8,.2,.1,.9],[0,0,.001,1]]:
        assert client.post('/api/capture-region', json=dict(cameraId=camera['id'],region=invalid)).status_code == 400
    assert client.post('/api/capture-region', json=dict(cameraId=camera['id'],region=None)).status_code == 200
    assert api.store.get('camera',camera['id'])['captureRegion'] is None


def test_cross_origin_rejected(client):
    assert client.post('/api/sessions/stop',json={},headers={'Origin':'https://evil.example'}).status_code==403
    assert client.get('/api/status',headers={'Host':'evil.example'}).status_code==403


def test_polygon_target_can_be_saved_and_removed_for_full_image(client):
    from backend import api
    camera=client.post('/api/cameras',json=dict(name='Parede',host='192.168.0.2')).json()
    polygon=[[.2,.1],[.8,.1],[.95,.9],[.05,.9]]
    result=client.post('/api/capture-region',json=dict(cameraId=camera['id'],polygon=polygon,
                                                       analysisMode='court',targetZone='singles'))
    assert result.status_code==200
    assert api.store.get('camera',camera['id'])['capturePolygon']==polygon
    assert api.store.get('camera',camera['id'])['targetZone']=='singles'
    assert result.json()['targetZone']=='singles'
    bad=client.post('/api/capture-region',json=dict(cameraId=camera['id'],polygon=[[0,0],[1,1],[0,1],[1,0]]))
    assert bad.status_code==400
    result=client.post('/api/capture-region',json=dict(cameraId=camera['id'],polygon=None,region=None))
    assert result.status_code==200 and result.json()['capturePolygon'] is None


def test_camera_can_track_without_counting_impacts(client):
    from backend import api
    camera=client.post('/api/cameras',json=dict(name='Rastreamento',host='192.168.0.3')).json()
    result=client.post('/api/capture-region',json=dict(
        cameraId=camera['id'],polygon=None,analysisMode='tracking'))
    assert result.status_code==200
    assert result.json()['analysisMode']=='tracking'
    assert api.store.get('camera',camera['id'])['analysisMode']=='tracking'


def test_two_areas_remain_independent_after_edit_and_database_reopen(client):
    from backend import api
    from backend.database import Store
    a=client.post('/api/cameras',json=dict(name='Chão A',host='192.168.0.10')).json()
    b=client.post('/api/cameras',json=dict(name='Parede B',host='192.168.0.11')).json()
    ground=[[.3,.3],[.7,.3],[.9,.9],[.1,.9]]
    wall=[[.1,.1],[.8,.15],[.85,.8],[.15,.8]]
    for camera,polygon,mode in [(a,ground,'court'),(b,wall,'room')]:
        response=client.post('/api/capture-region',json=dict(cameraId=camera['id'],polygon=polygon,analysisMode=mode))
        assert response.status_code == 200
    client.post('/api/capture-region',json=dict(cameraId=a['id'],polygon=ground,analysisMode='court',targetZone='singles'))
    db_path=api.store.db.execute('PRAGMA database_list').fetchone()[2]
    reopened=Store(db_path)
    try:
        assert reopened.get('camera',a['id'])['capturePolygon']==ground
        assert reopened.get('camera',a['id'])['targetZone']=='singles'
        assert reopened.get('camera',b['id'])['capturePolygon']==wall
        assert reopened.get('camera',b['id'])['analysisMode']=='room'
    finally:
        reopened.db.close()


def test_validation_and_export_errors(client):
    assert client.post('/api/accuracy',json=dict(estimated=110,reference=100,source='Radar real')).status_code==200
    assert client.get('/api/accuracy').json()['summary']['mae']==10
    assert client.post('/api/accuracy',json=dict(estimated=110,reference=0,source='Radar')).status_code==422
    assert client.get('/api/sessions/missing/export').status_code==404


def test_calibration_save_persists_profile_and_camera_link(client):
    camera = client.post('/api/cameras', json=dict(name='Camera', host='192.168.0.1')).json()
    response = client.post('/api/calibrations', json=dict(
        cameraId=camera['id'],
        name='Quadra de teste',
        imagePoints=[[10, 10], [110, 10], [110, 210], [10, 210]],
        resolution=[120, 220],
    ))
    assert response.status_code == 200
    profile = response.json()
    assert profile['quality'] == 'não validada'
    saved = client.get('/api/cameras').json()[0]
    assert saved['calibrationId'] == profile['id']


def test_room_calibration_accepts_two_known_points(client):
    camera = client.post('/api/cameras', json=dict(name='Quarto', host='192.168.0.2')).json()
    response = client.post('/api/calibrations', json=dict(
        cameraId=camera['id'], mode='room', realDistanceM=1,
        imagePoints=[[20, 20], [220, 20]], resolution=[640, 480],
    ))
    assert response.status_code == 200
    assert response.json()['mode'] == 'room'


def test_synchronized_preview_keeps_encoded_frame_resolution_after_camera_reconnect(client, monkeypatch):
    import time
    import cv2
    import numpy as np
    from types import SimpleNamespace
    from backend import api
    # This test concerns resolution after reconnect, not wall-clock freshness.
    # Keep the frame fresh even if the first JPEG encode or a busy CI host takes
    # more than two seconds. Freshness rejection has separate regression tests.
    frame_time = time.time()
    monkeypatch.setattr(api, 'time', SimpleNamespace(time=lambda: frame_time))
    worker = api.pipeline
    worker.session = dict(id='resolution-test', name='test')
    worker.capture = SimpleNamespace(resolution=[1920,1080], stop=lambda: None)
    worker.state = worker.frame_state = dict(timestamp=frame_time, sequence=1, resolution=[640,480])
    ok, encoded = cv2.imencode('.jpg', np.zeros((480,640,3), np.uint8))
    assert ok
    worker.jpeg = encoded.tobytes()
    response = client.get('/api/frame?synchronized=true')
    assert response.status_code == 200
    assert json.loads(response.headers['X-Frame-Metadata'])['resolution'] == [640,480]
    with client.websocket_connect('/api/video', headers={'Origin':'http://127.0.0.1:3000'}) as socket:
        packet = socket.receive_bytes()
        size = int.from_bytes(packet[:4], 'big')
        assert json.loads(packet[4:4+size])['resolution'] == [640,480]
        socket.send_text('1')


@pytest.mark.parametrize('checks', [[{}], [dict(image=[20,30], court=['bad',1])]])
def test_invalid_calibration_check_does_not_get_saved_or_crash_api(client, checks):
    from backend import api
    camera = client.post('/api/cameras', json=dict(name='Camera',host='192.168.0.1')).json()
    response = client.post('/api/calibrations', json=dict(cameraId=camera['id'],
        imagePoints=[[10,10],[110,10],[110,210],[10,210]], resolution=[120,220], checks=checks))
    assert response.status_code in (400,422)
    assert not api.store.all('calibration')


def test_missing_replay_media_returns_a_useful_error(client, monkeypatch, tmp_path):
    from backend import api
    monkeypatch.setattr(api, 'DATA', tmp_path)
    api.store.put('replay', dict(id='missing-media',sessionId='s',frames=10))
    response = client.get('/api/replays/missing-media')
    assert response.status_code == 404
    assert 'Replay' in response.json()['detail']
