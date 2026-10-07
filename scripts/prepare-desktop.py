"""Prepare redistributable assets, keeping recordings and camera secrets out."""
from pathlib import Path
import json
import re
import shutil
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'desktop' / 'assets'
ASSETS.mkdir(parents=True, exist_ok=True)

# Code-native tennis emblem, rasterized at high resolution for the Windows icon.
icon = Image.new('RGBA', (512, 512))
draw = ImageDraw.Draw(icon)
draw.rounded_rectangle((0, 0, 511, 511), radius=110, fill='#111d24')
draw.rounded_rectangle((36, 36, 475, 475), radius=84, outline='#344b50', width=3)
draw.ellipse((116, 116, 396, 396), fill='#c8fa70')
draw.arc((28, 79, 230, 427), start=278, end=81, fill='#17291e', width=13)
draw.arc((282, 79, 484, 427), start=98, end=261, fill='#17291e', width=13)
draw.line((75, 347, 142, 325), fill='#8ba853', width=10)
draw.line((62, 305, 124, 285), fill='#8ba853', width=8)
icon.save(ASSETS / 'icon.png')
icon.save(ASSETS / 'icon.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])

DEST = ROOT / 'desktop' / 'build' / 'models'
DEST.mkdir(parents=True, exist_ok=True)
source_models = ROOT / 'data' / 'models'
if not any(source_models.glob('*.pt')):
    source_models = ROOT / 'release-assets' / 'models'
for model in list(source_models.glob('*.pt'))+list((ROOT/'release-assets'/'models').glob('*.onnx'))+list((ROOT/'release-assets'/'models').glob('*pose-256.json')):
    shutil.copy2(model, DEST / model.name)
active = ROOT / 'data' / 'learning' / 'active.json'
if not active.exists():
    active = ROOT / 'release-assets' / 'models' / 'learning' / 'active.json'
if active.exists():
    settings = json.loads(active.read_text(encoding='utf-8'))
    model = (active.parent / settings['file']).resolve()
    if not model.is_relative_to((active.parent / 'models').resolve()) or model.suffix != '.npz':
        raise ValueError('Invalid appearance model path')
    (DEST / 'learning' / 'models').mkdir(parents=True, exist_ok=True)
    shutil.copy2(active, DEST / 'learning' / 'active.json')
    shutil.copy2(model, DEST / 'learning' / 'models' / model.name)
print('Windows icon and bundled model weights prepared. No camera profiles or videos included.')
android = ROOT / 'desktop' / 'build' / 'android'
android.mkdir(exist_ok=True)
mobile_gradle = (ROOT / 'mobile/android/app/build.gradle.kts').read_text(encoding='utf-8')
mobile_version = re.search(r'versionName\s*=\s*"(\d+\.\d+\.\d+)"', mobile_gradle).group(1)
shutil.copy2(ROOT / 'mobile' / 'releases' / f'Tennis-Vision-Camera-Android-v{mobile_version}.apk', android)
