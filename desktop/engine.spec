from pathlib import Path
import os
from zipfile import ZipFile, ZIP_DEFLATED
from PyInstaller.utils.hooks import collect_data_files, collect_submodules, get_package_paths, collect_dynamic_libs

root = Path(SPECPATH).parent
os.environ['YOLO_AUTOINSTALL'] = 'false'
# Torchvision 0.29 loads _C_stable.pyd through torch.ops, without a Python
# import. PyInstaller's older hook sees only _C. Preserve the native operators
# explicitly, including image.pyd, so pose NMS also works after installation.
vision_root = Path(get_package_paths('torchvision')[1])
vision_ops = [(str(file), 'torchvision') for file in vision_root.glob('*.pyd')]
analysis = Analysis([str(root / 'desktop/engine.py')], pathex=[str(root)],
    binaries=vision_ops + collect_dynamic_libs('onnxruntime'), datas=collect_data_files('ultralytics'),
    hiddenimports=['backend.api', 'uvicorn.logging', 'uvicorn.loops.asyncio',
        'uvicorn.protocols.http.h11_impl', 'uvicorn.protocols.websockets.websockets_impl',
        'uvicorn.lifespan.on', 'torch', 'torchvision', 'onnxruntime'] + collect_submodules('ultralytics'),
    hookspath=[], hooksconfig={'matplotlib':{'backends':['Agg']}}, runtime_hooks=[],
    excludes=['tkinter','IPython','pytest','tensorboard','tensorflow','PyQt5','PyQt6','PySide6'],
    noarchive=False)
# Keep every upstream license, in an archive. Deep Torch license trees exceed
# Windows installer path lengths when copied beneath the application folder.
license_entries = [item for item in analysis.datas if '/licenses/' in item[0].replace('\\','/')]
if license_entries:
    license_path = root / 'desktop/build/third-party-licenses.zip'
    license_path.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(license_path, 'w', ZIP_DEFLATED) as archive:
        for destination, source, _kind in license_entries:
            archive.write(source, destination.replace('\\','/'))
    analysis.datas = [item for item in analysis.datas if item not in license_entries]
    analysis.datas.append(('third-party-licenses.zip', str(license_path), 'DATA'))
pyz = PYZ(analysis.pure)
exe = EXE(pyz, analysis.scripts, [], exclude_binaries=True, name='TennisVisionEngine',
    debug=False, strip=False, upx=False, console=True)
bundle = COLLECT(exe, analysis.binaries, analysis.datas, strip=False, upx=False, name='TennisVisionEngine')
