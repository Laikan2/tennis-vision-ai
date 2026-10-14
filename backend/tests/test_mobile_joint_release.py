"""A feature release must not announce one platform while serving an old package."""
import hashlib,json,plistlib,runpy
from pathlib import Path
import pytest
from backend.tests.test_ios_ipa import app_bundle,PACKAGING

ROOT=Path(__file__).resolve().parents[2]
PUBLISH=runpy.run_path(str(ROOT/'scripts/publish-mobile-release.py'))

def files(tmp_path,ios_version='0.6.4',bundled_version='0.6.4'):
    contract=json.loads((ROOT/'mobile/shared/release.json').read_text(encoding='utf-8'))
    app=app_bundle(tmp_path)
    info=plistlib.loads((app/'Info.plist').read_bytes())
    info.update(CFBundleShortVersionString=ios_version,CFBundleVersion='5')
    (app/'Info.plist').write_bytes(plistlib.dumps(info))
    bundled=contract|{'version':bundled_version}
    (app/'MobileRelease.json').write_text(json.dumps(bundled),encoding='utf-8')
    ipa=PACKAGING['package_ipa'](app,tmp_path/f'Tennis-Vision-Camera-iOS-v{ios_version}-AltStore.ipa')[0]
    apk=tmp_path/'Tennis-Vision-Camera-Android-v0.6.4.apk';apk.write_bytes(b'controlled APK fixture; not a device build')
    metadata=dict(version='0.6.4',filename=apk.name,bytes=apk.stat().st_size,sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),certificateSha256=PUBLISH['ANDROID_CERT'])
    return ipa,apk,metadata,contract

def test_joint_release_keeps_platform_formats_and_same_feature_baseline(tmp_path):
    value=PUBLISH['validate'](*files(tmp_path))
    assert value['version']=='0.6.4' and value['ios']['version']==value['android']['version']
    assert value['ios']['signing']=='required-by-installation-tool'

def test_old_iphone_package_cannot_be_announced_as_android_parity(tmp_path):
    with pytest.raises(ValueError,match='Both apps'):PUBLISH['validate'](*files(tmp_path,ios_version='0.3.0'))

def test_stale_bundled_contract_is_rejected(tmp_path):
    with pytest.raises(ValueError,match='contract is stale'):PUBLISH['validate'](*files(tmp_path,bundled_version='0.3.0'))

def test_android_identity_and_integrity_are_preserved(tmp_path):
    ipa,apk,meta,contract=files(tmp_path)
    with pytest.raises(ValueError,match='identity changed'):PUBLISH['validate'](ipa,apk,meta|{'certificateSha256':'different'},contract)
    apk.write_bytes(b'corrupt')
    with pytest.raises(ValueError,match='verified metadata'):PUBLISH['validate'](ipa,apk,meta,contract)
