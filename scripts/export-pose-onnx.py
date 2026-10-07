"""Build a fixed, non-quantized pose export without changing the active PT model."""
import os
from pathlib import Path
import shutil
os.environ['YOLO_AUTOINSTALL']='false'
from ultralytics import YOLO
root=Path(__file__).resolve().parents[1]
folder=root/'work-improvements-20261006'/'pose-export'
folder.mkdir(parents=True,exist_ok=True)
source=root/'release-assets'/'models'/'yolo11n-pose.pt'
shutil.copy2(source,folder/source.name)
path=YOLO(str(folder/source.name)).export(format='onnx',imgsz=256,opset=17,simplify=False,dynamic=False,device='cpu')
print(path)
