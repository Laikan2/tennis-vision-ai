"""Package our unsigned device build for signing with AltStore Classic on Windows.

No Apple identity or provisioning profile belongs in this redistributable archive.
The resulting IPA must still be signed by the user's installation tool.
"""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import struct
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
BUNDLE_ID = 'ai.tennisvision.camera.ios'


def device_binary(data):
    if len(data) < 32:
        raise ValueError('Missing iPhone executable')
    magic, cpu, _, file_type, commands, size, _, _ = struct.unpack_from('<IiiIIIII', data)
    if magic != 0xFEEDFACF or cpu != 0x0100000C or file_type != 2:
        raise ValueError('An arm64 iPhone executable is required')
    if commands > 65535 or size > len(data) - 32:
        raise ValueError('Invalid executable load commands')
    offset, platform = 32, None
    for _ in range(commands):
        if offset + 8 > 32 + size:
            raise ValueError('Truncated load command')
        command, length = struct.unpack_from('<II', data, offset)
        if length < 8 or offset + length > 32 + size:
            raise ValueError('Invalid load command length')
        if command == 0x32:  # LC_BUILD_VERSION: iOS=2, iOS Simulator=7.
            if length < 24:
                raise ValueError('Truncated build version')
            platform = struct.unpack_from('<I', data, offset + 8)[0]
        if command in (0x21, 0x2C):  # Never redistribute an encrypted App Store executable.
            if length < 20 or struct.unpack_from('<I', data, offset + 16)[0]:
                raise ValueError('Encrypted executable cannot be re-signed')
        offset += length
    if platform != 2:
        raise ValueError('A device build is required; simulator apps cannot install on iPhone')


def app_metadata(info, executable):
    if info.get('CFBundleIdentifier') != BUNDLE_ID:
        raise ValueError('Only the Tennis Vision Camera build is accepted')
    if info.get('CFBundleExecutable') != 'TennisVisionCamera':
        raise ValueError('Unexpected application executable')
    if info.get('CFBundleSupportedPlatforms') != ['iPhoneOS']:
        raise ValueError('This is not an iPhoneOS application')
    version = info.get('CFBundleShortVersionString', '')
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Missing release version')
    device_binary(executable)
    return dict(version=version, minimumIOS=info.get('MinimumOSVersion'),
                bundleIdentifier=BUNDLE_ID, signing='required-by-installation-tool')


def verify_ipa(ipa):
    ipa = Path(ipa)
    prefix = 'Payload/TennisVisionCamera.app/'
    with ZipFile(ipa) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or not names:
            raise ValueError('Duplicate or empty IPA entries')
        if any(not name.startswith(prefix) or '..' in name.split('/') for name in names):
            raise ValueError('IPA contents must stay inside the app Payload')
        if any('embedded.mobileprovision' in name or '_CodeSignature' in name for name in names):
            raise ValueError('Personal signing artifacts must not be distributed')
        executable = archive.read(prefix + 'TennisVisionCamera')
        metadata = app_metadata(plistlib.loads(archive.read(prefix + 'Info.plist')), executable)
        if not archive.getinfo(prefix + 'TennisVisionCamera').external_attr >> 16 & 0o111:
            raise ValueError('Missing executable permissions')
        if archive.testzip():
            raise ValueError('Damaged IPA archive')
    return metadata | dict(filename=ipa.name, bytes=ipa.stat().st_size,
                           sha256=hashlib.sha256(ipa.read_bytes()).hexdigest())


def package_ipa(app, output=None):
    app = Path(app).resolve()
    if not app.is_dir() or app.name != 'TennisVisionCamera.app':
        raise ValueError('Select the built TennisVisionCamera.app for a physical iPhone')
    metadata = app_metadata(plistlib.loads((app / 'Info.plist').read_bytes()),
                            (app / 'TennisVisionCamera').read_bytes())
    output = Path(output or ROOT / 'mobile/releases' /
                  f"Tennis-Vision-Camera-iOS-v{metadata['version']}-AltStore.ipa").resolve()
    if output.is_relative_to(app):
        raise ValueError('The output must be outside the input application')
    paths = sorted(app.rglob('*'))
    if any(path.is_symlink() or not path.resolve().is_relative_to(app) for path in paths):
        raise ValueError('Application bundle contains a link outside its contents')
    if any(path.name in ('embedded.mobileprovision', '_CodeSignature') for path in paths):
        raise ValueError('Use the unsigned build; do not bundle personal signing data')
    output.parent.mkdir(parents=True, exist_ok=True)
    pending = output.with_suffix('.ipa.tmp')
    try:
        with ZipFile(pending, 'w', ZIP_DEFLATED) as archive:
            for path in paths:
                if not path.is_file() or path.name == '.DS_Store':
                    continue
                name = 'Payload/TennisVisionCamera.app/' + path.relative_to(app).as_posix()
                entry = ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                entry.create_system = 3
                entry.external_attr = (0o100755 if path.name == 'TennisVisionCamera' else 0o100644) << 16
                entry.compress_type = ZIP_DEFLATED
                archive.writestr(entry, path.read_bytes())
        verify_ipa(pending)
        pending.replace(output)
    finally:
        if pending.exists():
            pending.unlink()
    result = verify_ipa(output)
    output.with_suffix('.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return output, result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--app', type=Path)
    group.add_argument('--verify', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = verify_ipa(args.verify) if args.verify else package_ipa(args.app, args.output)[1]
    print(json.dumps(result))
