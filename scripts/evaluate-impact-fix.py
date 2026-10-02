"""Offline replay regression; uses stored annotations, never modifies live data."""
import argparse
import importlib.util
import json
import os
import sys
import time
from pathlib import Path
import cv2
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
os.environ['TV_MODEL_PATH']=''
from backend.config import DATA
from backend.detection import BallDetector
from backend.tracking import BallTracker


def historical(name):
    spec=importlib.util.spec_from_file_location('backend.baseline_'+name,DATA/'diagnostics'/'before-impact-fix'/f'{name}.py')
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def run(baseline):
    Detector=historical('detection').BallDetector if baseline else BallDetector
    Tracker=historical('tracking').BallTracker if baseline else BallTracker
    reference=json.loads((DATA/'learning/replay-evaluation.json').read_text())['baseline']['details']
    annotations={item['frame']:item['labelBox'] for item in reference}
    results={}
    for rid in ['0533c57e-d0f3-4f99-92d1-0953f3d8abf4','912d44ab-8b2d-4a45-856e-4b8ba62b9f82']:
        folder=DATA/'replays'/rid
        frames=json.loads((folder/'manifest.json').read_text())
        detector=Detector(external_pose=True); tracker=Tracker()
        durations=[]; matched=0; detected=0; wrong=0; anchors=0; details=[]
        for frame in frames:
            image=cv2.imread(str(folder/f"{frame['index']}.jpg")); t=frame['timestamp']
            detector.prior=tracker.hint(t)
            before=time.perf_counter()
            detections=detector.detect(image,t,persons=frame['overlay'].get('persons',[]))
            track=tracker.update(detections,t)
            durations.append((time.perf_counter()-before)*1000)
            if rid.startswith('0533') and frame['index'] in annotations:
                x,y,w,h=annotations[frame['index']]
                close=lambda p:x-5<=p['centerX']<=x+w+5 and y-5<=p['centerY']<=y+h+5
                got=bool(track and not track.get('predicted') and close(track))
                matched+=int(got); detected+=int(any(close(p) for p in detections))
                wrong+=int(bool(track and not track.get('predicted') and not close(track)))
                details.append(dict(frame=frame['index'],matched=got))
            if rid.startswith('912d') and track and not track.get('predicted'):
                anchors+=int(any(np.hypot(track['centerX']-x,track['centerY']-y)<30
                                 for x,y in [(479,591),(670,547),(889,681)]))
        results[rid]=dict(frames=len(frames),annotatedFrames=len(annotations) if rid.startswith('0533') else 0,
                          ballProposalsMatched=detected,trackedMatched=matched,trackedWrong=wrong,
                          staticDecorTrackedFrames=anchors,medianMs=float(np.median(durations)),
                          p95Ms=float(np.percentile(durations,95)),details=details)
    output=DATA/'diagnostics'/('impact-baseline.json' if baseline else 'impact-after.json')
    output.write_text(json.dumps(results,indent=2),encoding='utf-8')
    print(json.dumps({key:{k:v for k,v in item.items() if k!='details'} for key,item in results.items()},indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--baseline',action='store_true')
    run(parser.parse_args().baseline)
