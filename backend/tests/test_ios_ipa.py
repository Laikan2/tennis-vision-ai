"""Prevent shipping a simulator binary, encrypted app or personal signing data."""
import plistlib
import runpy
import struct
from pathlib import Path
from zipfile import ZipFile
import pytest

PACKAGING = runpy.run_path(str(Path(__file__).resolve().parents[2] / 'scripts/package-ios-ipa.py'))


def app_bundle(tmp_path, platform=2, cpu=0x0100000C, encrypted=False):
    app = tmp_path / 'TennisVisionCamera.app'
    app.mkdir()
    commands = struct.pack('<IIIIII', 0x32, 24, platform, 0x110400, 0x1A0000, 0)
    if encrypted:
        commands += struct.pack('<IIIIII', 0x2C, 24, 0, 32, 1, 0)
    binary = struct.pack('<IiiIIIII', 0xFEEDFACF, cpu, 0, 2, 2 if encrypted else 1,
                         len(commands), 0, 0) + commands + b'app-code'
    (app / 'TennisVisionCamera').write_bytes(binary)
    (app / 'Info.plist').write_bytes(plistlib.dumps(dict(CFBundleIdentifier='ai.tennisvision.camera.ios',
        CFBundleExecutable='TennisVisionCamera', CFBundleSupportedPlatforms=['iPhoneOS'],
        CFBundleShortVersionString='0.1.0', MinimumOSVersion='17.4')))
    (app / 'PrivacyInfo.xcprivacy').write_bytes(b'<plist/>')
    return app


def test_ipa_payload_keeps_device_binary_permissions_and_requires_signing(tmp_path):
    app = app_bundle(tmp_path)
    ipa, metadata = PACKAGING['package_ipa'](app, tmp_path / 'Tennis-Vision.ipa')
    assert metadata['version'] == '0.1.0' and metadata['minimumIOS'] == '17.4'
    assert metadata['signing'] == 'required-by-installation-tool'
    with ZipFile(ipa) as archive:
        executable = 'Payload/TennisVisionCamera.app/TennisVisionCamera'
        assert archive.read(executable) == (app / 'TennisVisionCamera').read_bytes()
        assert archive.getinfo(executable).external_attr >> 16 & 0o777 == 0o755
        assert all(name.startswith('Payload/TennisVisionCamera.app/') for name in archive.namelist())
    assert metadata == PACKAGING['verify_ipa'](ipa)
    assert ipa.with_suffix('.json').is_file()


@pytest.mark.parametrize('options', [dict(platform=7), dict(cpu=0x01000007), dict(encrypted=True)])
def test_ipa_rejects_simulator_wrong_cpu_and_encrypted_apps(tmp_path, options):
    with pytest.raises(ValueError):
        PACKAGING['package_ipa'](app_bundle(tmp_path, **options), tmp_path / 'invalid.ipa')
    assert not (tmp_path / 'invalid.ipa').exists()


def test_ipa_does_not_redistribute_personal_provisioning(tmp_path):
    app = app_bundle(tmp_path)
    (app / 'embedded.mobileprovision').write_bytes(b'personal-profile')
    with pytest.raises(ValueError, match='personal signing'):
        PACKAGING['package_ipa'](app, tmp_path / 'invalid.ipa')


def test_ipa_rejects_wrong_app_and_recursive_output(tmp_path):
    app = app_bundle(tmp_path)
    with pytest.raises(ValueError, match='outside'):
        PACKAGING['package_ipa'](app, app / 'self.ipa')
    info = plistlib.loads((app / 'Info.plist').read_bytes())
    info['CFBundleIdentifier'] = 'unrelated.app'
    (app / 'Info.plist').write_bytes(plistlib.dumps(info))
    with pytest.raises(ValueError, match='Only the Tennis Vision'):
        PACKAGING['package_ipa'](app, tmp_path / 'invalid.ipa')


def test_ipa_verification_rejects_archive_path_escape(tmp_path):
    ipa = tmp_path / 'invalid.ipa'
    with ZipFile(ipa, 'w') as archive:
        archive.writestr('Payload/TennisVisionCamera.app/../../outside', b'bad')
    with pytest.raises(ValueError, match='inside'):
        PACKAGING['verify_ipa'](ipa)
