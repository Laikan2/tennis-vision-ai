"""Packaged local engine. No Python/Node installation is needed by the user."""
import argparse
import os
import re
import shutil
import sys
import threading
from pathlib import Path


def prepare_state(state, resources, project=None):
    from cryptography.fernet import Fernet
    from dotenv import load_dotenv
    state = Path(state).resolve()
    state.mkdir(parents=True, exist_ok=True)
    if project:
        project = Path(project).resolve()
        if not (project / 'backend' / 'api.py').is_file():
            raise ValueError('Selecione a pasta tennis-vision-ai do projeto existente.')
        load_dotenv(project / '.env', override=True)
        data = project / 'data'
    else:
        load_dotenv(state / 'settings.env', override=True)
        data = state / 'data'
    # Start Menu shortcuts use the installation directory as cwd. Existing
    # project/settings profiles can contain paths relative to their own root.
    settings_root = project if project else state
    for name in ('TV_MODEL_PATH', 'TV_POSE_PATH', 'TV_APPEARANCE_DIR'):
        value = os.getenv(name)
        if value and not Path(value).is_absolute():
            os.environ[name] = str((settings_root / value).resolve())
    data.mkdir(parents=True, exist_ok=True)
    key_file = state / 'camera.key'
    if not os.getenv('TV_SECRET_KEY'):
        if not key_file.is_file():
            key_file.write_bytes(Fernet.generate_key())
        os.environ['TV_SECRET_KEY'] = key_file.read_text().strip()
    os.environ['TV_DATA_DIR'] = str(data)
    model_root = data / 'models'
    model_root.mkdir(exist_ok=True)
    packaged = Path(resources) / 'models'
    for model in list(packaged.glob('*.pt'))+list(packaged.glob('*.onnx'))+list(packaged.glob('*pose-256.json')):
        if not (model_root / model.name).exists():
            shutil.copy2(model, model_root / model.name)
    appearance = data / 'learning'
    if not (appearance / 'active.json').exists() and (packaged / 'learning').exists():
        shutil.copytree(packaged / 'learning', appearance, dirs_exist_ok=True)
    def apk_version(path):
        match = re.search(r'-v(\d+)\.(\d+)\.(\d+)\.apk$', path.name)
        return tuple(map(int, match.groups())) if match else (-1, -1, -1)
    # A development/resource folder may still contain the previous APK. Do not
    # let directory enumeration silently offer an older mobile application.
    apk = max((Path(resources) / 'android').glob('*.apk'), key=apk_version, default=None)
    if apk: os.environ['TV_ANDROID_APK_PATH'] = str(apk)
    return data


def main():
    import multiprocessing
    multiprocessing.freeze_support()
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--origin', required=True)
    parser.add_argument('--state', required=True)
    parser.add_argument('--resources', required=True)
    parser.add_argument('--project')
    args = parser.parse_args()
    if not getattr(sys, 'frozen', False):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    prepare_state(args.state, args.resources, args.project)
    os.environ['TV_DESKTOP_ORIGIN'] = args.origin
    os.environ.setdefault('OMP_NUM_THREADS', '2')
    os.environ.setdefault('OPENBLAS_NUM_THREADS', '1')
    os.environ['YOLO_AUTOINSTALL'] = 'false'
    from backend.api import app
    import uvicorn
    server = uvicorn.Server(uvicorn.Config(app, host='127.0.0.1', port=args.port,
        access_log=False, log_level='warning', timeout_graceful_shutdown=8))
    def watch_parent():
        # Closing the pipe (including an unexpected parent exit) also closes
        # the service. Uvicorn runs lifespan cleanup to preserve active replays.
        try:
            for line in sys.stdin:
                if line.strip() == 'shutdown': break
        finally:
            server.should_exit = True
    threading.Thread(target=watch_parent, daemon=True).start()
    server.run()


if __name__ == '__main__': main()
