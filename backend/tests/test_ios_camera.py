"""iPhone pairing remains distinct from Android and preserves saved per-camera areas."""
from urllib.parse import urlparse,parse_qs
import pytest
from backend.tests.test_api import client


def test_ios_pairing_has_real_lan_ip_and_separate_transport(client,monkeypatch):
    from backend import api
    monkeypatch.setattr(api,'_local_ipv4',lambda:'192.168.15.2')
    ios=client.get('/api/mobile-camera?platform=ios').json()
    android=client.get('/api/mobile-camera').json()
    fields=parse_qs(urlparse(ios['appPairingUri']).query)
    assert ios['platform']=='ios' and ios['receiverUrl']=='rtsp://127.0.0.1:8554/iphone'
    assert fields['host']==['192.168.15.2'] and fields['port']==['8554']
    assert fields['transport']==['rtsp'] and fields['path']==['iphone']
    assert fields['fps']==['60'] and fields['bitrate']==['12000000']
    assert ios['cameraPreset']['ballDiameterMm']==67
    assert android['receiverUrl']=='rtsp://127.0.0.1:8554/galaxy'
    assert parse_qs(urlparse(android['appPairingUri']).query)['transport']==['srt']


def test_native_ios_does_not_claim_apple_signed_distribution(client,monkeypatch,tmp_path):
    from backend import api
    monkeypatch.delenv('TV_IOS_TESTFLIGHT_URL',raising=False)
    monkeypatch.setattr(api,'IOS_PROJECT_ZIP',tmp_path/'missing.zip')
    monkeypatch.setattr(api,'IOS_IPA',tmp_path/'missing.ipa')
    metadata=client.get('/api/mobile-camera?platform=ios').json()['iosApp']
    assert metadata['distribution']=='xcode' and metadata['testFlightUrl'] is None
    assert not metadata['sourceAvailable'] and metadata['sizeBytes'] is None
    assert not metadata['ipaAvailable'] and metadata['ipaSizeBytes'] is None
    assert metadata['minimumIOS']=='17.4'
    assert client.get(metadata['sourceDownloadUrl']).status_code==404
    assert client.get(metadata['ipaDownloadUrl']).status_code==404


def test_ios_personal_installation_on_windows_has_ipa_but_no_fake_testflight(client,monkeypatch,tmp_path):
    from backend import api
    monkeypatch.delenv('TV_IOS_TESTFLIGHT_URL',raising=False)
    ipa=tmp_path/'Tennis-Vision-Camera-iOS-v0.1.0-AltStore.ipa'
    ipa.write_bytes(b'PK test IPA')
    monkeypatch.setattr(api,'IOS_IPA',ipa)
    metadata=client.get('/api/mobile-camera?platform=ios').json()['iosApp']
    assert metadata['distribution']=='altstore' and metadata['testFlightUrl'] is None
    assert metadata['ipaAvailable'] and metadata['ipaSizeBytes']==ipa.stat().st_size
    response=client.get(metadata['ipaDownloadUrl'])
    assert response.status_code==200 and response.content==ipa.read_bytes()
    assert 'attachment' in response.headers['content-disposition']
    assert '.ipa' in response.headers['content-disposition']


@pytest.mark.parametrize('url',['javascript:alert(1)','https://evil.example/join/abcd1234',
                              'https://testflight.apple.com.evil.example/join/abcd1234',
                              'https://testflight.apple.com/join/abcd1234?redirect=evil'])
def test_testflight_distribution_rejects_unsafe_or_unrelated_urls(client,monkeypatch,url):
    monkeypatch.setenv('TV_IOS_TESTFLIGHT_URL',url)
    assert client.get('/api/mobile-camera?platform=ios').json()['iosApp']['testFlightUrl'] is None


def test_configured_apple_invitation_and_project_download(client,monkeypatch,tmp_path):
    from backend import api
    project=tmp_path/'Tennis-Vision-Camera-iOS-v0.1.0-Projeto.zip'
    project.write_bytes(b'PK test project')
    monkeypatch.setattr(api,'IOS_PROJECT_ZIP',project)
    invitation='https://testflight.apple.com/join/abcd1234'
    monkeypatch.setenv('TV_IOS_TESTFLIGHT_URL',invitation)
    metadata=client.get('/api/mobile-camera?platform=ios').json()['iosApp']
    assert metadata['testFlightUrl']==invitation and metadata['distribution']=='testflight'
    assert metadata['sourceAvailable'] and metadata['sizeBytes']==project.stat().st_size
    download=client.get(metadata['sourceDownloadUrl'])
    assert download.status_code==200 and download.content==project.read_bytes()
    assert 'application/zip' in download.headers['content-type']


def test_ios_status_and_connect_require_iphone_video_not_galaxy_video(client,monkeypatch):
    from backend import api
    requested=[]
    def check(path='galaxy'):
        requested.append(path)
        return dict(streamReady=path=='galaxy',receiverAvailable=True,announcedFps=60,message='test')
    monkeypatch.setattr(api,'receiver_status',check)
    assert not client.get('/api/mobile-camera/status?platform=ios').json()['streamReady']
    assert client.post('/api/mobile-camera/connect?platform=ios').status_code==409
    assert requested==['iphone','iphone']
    assert client.get('/api/cameras').json()==[] and client.get('/api/sessions').json()==[]


def test_ios_connection_reuses_saved_area_without_overwriting_it(client,monkeypatch):
    from backend import api
    monkeypatch.setattr(api,'receiver_status',lambda path:dict(streamReady=path=='iphone'))
    camera=client.post('/api/cameras',json=dict(name='Meu iPhone',host='127.0.0.1',port=8554,
        path='/iphone',requestedFps=120,username='saved',password='keep')).json()
    assert client.post('/api/capture-region',json=dict(cameraId=camera['id'],region=[.1,.4,.9,.95],impactSurface='ground')).status_code==200
    before=api.store.get('camera',camera['id'])
    calls=[]
    def start(camera,url,session):
        calls.append(url); api.pipeline.session=session
    monkeypatch.setattr(api.pipeline,'start',start)
    first=client.post('/api/mobile-camera/connect?platform=ios')
    second=client.post('/api/mobile-camera/connect?platform=ios')
    assert first.status_code==200 and not first.json()['alreadyRunning']
    assert second.status_code==200 and second.json()['alreadyRunning']
    assert len(calls)==1
    parsed=urlparse(calls[0])
    assert (parsed.hostname,parsed.port,parsed.path)==('127.0.0.1',8554,'/iphone')
    assert api.store.get('camera',camera['id'])==before
    assert len(client.get('/api/cameras').json())==1
    assert 'keep' not in first.text and 'Encrypted' not in first.text


@pytest.mark.parametrize('endpoint',['/api/mobile-camera','/api/mobile-camera/status','/api/mobile-camera/connect'])
def test_unknown_mobile_platform_is_rejected(client,endpoint):
    response=client.post(endpoint+'?platform=unknown') if endpoint.endswith('/connect') else client.get(endpoint+'?platform=unknown')
    assert response.status_code==422
