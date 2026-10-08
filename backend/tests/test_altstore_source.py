import plistlib,runpy
from pathlib import Path
import pytest
from backend.tests.test_ios_ipa import app_bundle,PACKAGING

SOURCE=runpy.run_path(str(Path(__file__).resolve().parents[2]/'scripts/altstore-source.py'))['source']

def ipa(tmp_path,version='0.2.0'):
    app=app_bundle(tmp_path)
    info=plistlib.loads((app/'Info.plist').read_bytes())
    info.update(CFBundleShortVersionString=version,CFBundleVersion='2',
        NSCameraUsageDescription='Camera test permission',NSLocalNetworkUsageDescription='LAN test permission')
    (app/'Info.plist').write_bytes(plistlib.dumps(info))
    return PACKAGING['package_ipa'](app,tmp_path/'Tennis-Vision-Camera-iOS-v0.2.0-AltStore.ipa')[0]

def test_source_uses_actual_ipa_size_version_and_privacy_declarations(tmp_path):
    path=ipa(tmp_path);value=SOURCE(path,'v0.6.8')
    app=value['apps'][0];release=app['versions'][0]
    assert release['size']==path.stat().st_size and release['version']=='0.2.0' and release['buildVersion']=='2'
    assert release['downloadURL'].endswith('/v0.6.8/'+path.name)
    assert app['appPermissions']['privacy']==dict(NSCameraUsageDescription='Camera test permission',NSLocalNetworkUsageDescription='LAN test permission')
    assert 'TestFlight' not in value['name']

def test_windows_only_release_keeps_immutable_ios_download(tmp_path):
    path=ipa(tmp_path);original=SOURCE(path,'v0.6.8')
    newer_windows=SOURCE(path,'v0.6.9',original)
    assert newer_windows['apps'][0]['versions']==original['apps'][0]['versions']

def test_source_refuses_downgrades_and_changed_existing_versions(tmp_path):
    path=ipa(tmp_path);previous=SOURCE(path,'v0.6.8')
    previous['apps'][0]['versions'][0]['version']='0.3.0'
    with pytest.raises(ValueError,match='downgrade'): SOURCE(path,'v0.6.9',previous)
    previous['apps'][0]['versions'][0].update(version='0.2.0',size=1)
    with pytest.raises(ValueError,match='cannot be replaced'): SOURCE(path,'v0.6.9',previous)
