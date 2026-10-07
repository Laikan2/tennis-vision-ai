"""Compare the original weights with ONNX CPU/DirectML using local reviewed frames."""
import json
import hashlib
from pathlib import Path
import statistics
import sys
import time
import cv2
import numpy as np
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root))
from backend.onnx_pose import OnnxPose, pose_tensor
from ultralytics import YOLO
import torch
torch.set_num_threads(1)
folder=root/'work-improvements-20261006'/'pose-export'
runtime=OnnxPose(folder/'yolo11n-pose.onnx')
original=YOLO(str(folder/'yolo11n-pose.pt'))
original.fuse(verbose=False)
original.model.eval()
rows=json.loads((root/'data'/'diagnostics'/'ground-0918-1301'/'replays.json').read_text(encoding='utf-8-sig'))
all_frames=[]
for replay in rows:
    base=root/'data'/'replays'/replay['id']
    if (base/'manifest.json').exists():
        for frame in json.loads((base/'manifest.json').read_text(encoding='utf-8')):
            path=base/f"{frame['index']}.jpg"
            if not path.exists():path=base/f"{frame['index']:05d}.jpg"
            if path.exists():all_frames.append(path)
frames=all_frames[::max(1,len(all_frames)//12)][:12]
if not frames:
    frames=sorted((root/'data'/'learning'/'frames').glob('*.jpg'))[:12]
if not frames:raise ValueError('Representative local frames are required; do not approve on blank inputs alone.')
cpu_times=[];gpu_times=[];max_error=0.;counts=[]
for path in frames:
    image=cv2.imread(str(path));tensor,_=pose_tensor(image)
    with torch.inference_mode():
        reference=original.model(torch.from_numpy(tensor))[0].numpy()
    actual=runtime.runtime.run(None,{runtime.input:tensor})[0]
    if not np.allclose(reference,actual,rtol=.002,atol=.03):raise AssertionError('ONNX differs from the original weights')
    max_error=max(max_error,float(np.max(np.abs(reference-actual))))
    counts.append(len(runtime.predict(image,0)))
    for _ in range(3):
        began=time.perf_counter();original.predict(image,imgsz=256,rect=False,device='cpu',verbose=False,conf=.45,max_det=4);cpu_times.append((time.perf_counter()-began)*1000)
        torch.set_num_threads(1)
        began=time.perf_counter();runtime.predict(image,0);gpu_times.append((time.perf_counter()-began)*1000)
report=dict(runtime=runtime.report,frames=len(frames),torchCpuMedianMs=statistics.median(cpu_times),
            selectedMedianMs=statistics.median(gpu_times),originalWeightsMaxError=max_error,personsPerFrame=counts)
(folder/'validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
approval=dict(schema=1,parity=True,inputSize=256,evaluationFrames=len(frames),
              originalSha256=hashlib.sha256((folder/'yolo11n-pose.pt').read_bytes()).hexdigest(),
              onnxSha256=hashlib.sha256((folder/'yolo11n-pose.onnx').read_bytes()).hexdigest(),
              originalWeightsMaxError=max_error,rtol=.002,atol=.03,
              torchCpuMedianMs=report['torchCpuMedianMs'],selectedMedianMs=report['selectedMedianMs'])
(folder/'yolo11n-pose-256.json').write_text(json.dumps(approval,indent=2),encoding='utf-8')
