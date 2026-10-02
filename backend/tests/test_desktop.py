import importlib.util
from pathlib import Path
from cryptography.fernet import Fernet


def engine_module():
    spec = importlib.util.spec_from_file_location('studio_engine', Path(__file__).parents[2] / 'desktop' / 'engine.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_desktop_keeps_encryption_key_and_never_overwrites_user_model(tmp_path, monkeypatch):
    monkeypatch.delenv('TV_SECRET_KEY', raising=False)
    monkeypatch.delenv('TV_ANDROID_APK_PATH', raising=False)
    resources = tmp_path / 'resources'
    (resources / 'models').mkdir(parents=True)
    (resources / 'models' / 'yolo11n.pt').write_bytes(b'bundled-model')
    (resources / 'android').mkdir()
    apk = resources / 'android' / 'Camera.apk'
    apk.write_bytes(b'apk')
    engine = engine_module()
    state = tmp_path / 'state'
    data = engine.prepare_state(state, resources)
    key = (state / 'camera.key').read_bytes()
    token = Fernet(key).encrypt(b'camera-secret')
    (data / 'models' / 'yolo11n.pt').write_bytes(b'user-model')
    monkeypatch.delenv('TV_SECRET_KEY')
    engine.prepare_state(state, resources)
    assert (state / 'camera.key').read_bytes() == key
    assert Fernet(key).decrypt(token) == b'camera-secret'
    assert (data / 'models' / 'yolo11n.pt').read_bytes() == b'user-model'
    import os
    assert os.environ['TV_ANDROID_APK_PATH'] == str(apk)
    monkeypatch.delenv('TV_DATA_DIR', raising=False)
    monkeypatch.delenv('TV_SECRET_KEY', raising=False)
    monkeypatch.delenv('TV_ANDROID_APK_PATH', raising=False)


def test_desktop_existing_project_preserves_encrypted_credentials(tmp_path, monkeypatch):
    project = tmp_path / 'existing-project'
    (project / 'backend').mkdir(parents=True)
    (project / 'backend' / 'api.py').write_text('')
    key = Fernet.generate_key().decode()
    (project / '.env').write_text('TV_SECRET_KEY=' + key + '\nTV_MODEL_PATH=data/models/custom.pt')
    monkeypatch.delenv('TV_SECRET_KEY', raising=False)
    monkeypatch.setenv('TV_MODEL_PATH', '')
    data = engine_module().prepare_state(tmp_path / 'state', tmp_path / 'resources', project)
    assert data == project / 'data'
    import os
    assert os.environ['TV_SECRET_KEY'] == key
    assert os.environ['TV_MODEL_PATH'] == str(project / 'data' / 'models' / 'custom.pt')
    assert not (tmp_path / 'state' / 'camera.key').exists()
    monkeypatch.delenv('TV_DATA_DIR', raising=False)
    monkeypatch.delenv('TV_SECRET_KEY', raising=False)


def test_desktop_serves_the_latest_android_when_resources_keep_previous_apks(tmp_path, monkeypatch):
    monkeypatch.delenv('TV_SECRET_KEY', raising=False)
    for name in ('TV_DATA_DIR', 'TV_ANDROID_APK_PATH'):
        monkeypatch.setenv(name, '')
    folder = tmp_path / 'resources' / 'android'
    folder.mkdir(parents=True)
    for version in ('0.5.1', '0.5.9', '0.5.10', '0.5.2'):
        (folder / f'Tennis-Vision-Camera-Android-v{version}.apk').write_bytes(version.encode())
    engine_module().prepare_state(tmp_path / 'state', folder.parent)
    import os
    assert Path(os.environ['TV_ANDROID_APK_PATH']).read_bytes() == b'0.5.10'
