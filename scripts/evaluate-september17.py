"""Compare fixes on the local raw replays; does not write live events/models."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import time
import cv2
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
os.environ['TV_MODEL_PATH']=''
from backend.detection import BallDetector
from backend.tracking import BallTracker
from backend.bounce import BounceDetector

DIAG=ROOT/'data/diagnostics/impact-2026-09-17'
REPLAYS=ROOT/'data/replays'


def historic(name):
    spec=importlib.util.spec_from_file_location('backend.old_'+name,DIAG/'baseline'/f'{name}.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def enrich(p):
    p=dict(p);size=p.get('ballDiameterPx',min(p['width'],p['height']))
    p.update(frameWidth=1920,frameHeight=1080,metersPerPixel=.067/size,
             depthMeters=91.86/size,depthReliable=size>=6 and size/max(p['width'],p['height'])>=.33)
    return p


def run(baseline=False):
    detector=(historic('detection').BallDetector if baseline else BallDetector)(external_pose=True)
    tracker=(historic('tracking').BallTracker if baseline else BallTracker)()
    bounce=(historic('bounce').BounceDetector if baseline else BounceDetector)()
    frames={};annotations={}
    selected={'31e07bc3-bcf4-47f2-a6a4-ebb41d95b6b3':[42,43,44,45],
              'eb2e755a-2ad7-4d36-b4aa-981cb697f126':[37,38,39,40,41],
              '122f53b8-b01f-47f1-aef8-e148dec8bc4f':[38,39,40,41],
              '76531551-8410-4136-ba08-055de790cfad':[42,43,44,45,46]}
    for replay in json.loads((DIAG/'replays.json').read_text())[:6]:
        folder=REPLAYS/replay['id']
        for f in json.loads((folder/'manifest.json').read_text()):
            frames[f['timestamp']]=(folder/f"{f['index']}.jpg",f)
            if f['index'] in selected.get(replay['id'],[]):
                # These centers were visually checked against the raw images.
                p=f['overlay']['track']
                annotations[f['timestamp']]=(p['centerX'],p['centerY'])
    matched=wrong=background=0;durations=[];events=[];details=[]
    for timestamp,(path,f) in sorted(frames.items()):
        image=cv2.imread(str(path))
        detector.prior=tracker.hint(timestamp)
        start=time.perf_counter()
        detections=detector.detect(image,timestamp,persons=f['overlay'].get('persons',[]))
        track=tracker.update(detections,timestamp)
        durations.append((time.perf_counter()-start)*1000)
        if track:
            track=enrich(track)
            if not track['predicted'] and bounce.track_id!=track['trackID']:
                for prior in list(tracker.history)[-3:-1]:
                    if not prior.get('predicted') and prior['trackID']==track['trackID']:
                        bounce.update(enrich(prior),None,mode='room')
        event=bounce.update(track,None,mode='room',timestamp=timestamp)
        if event:events.append(event)
        actual=annotations.get(timestamp)
        measured=track and not track['predicted']
        if actual:
            distance=float(np.hypot(track['centerX']-actual[0],track['centerY']-actual[1])) if measured else None
            matched+=int(distance is not None and distance<35)
            wrong+=int(distance is not None and distance>=35)
            details.append(dict(timestamp=timestamp,expected=actual,track=track,distance=distance))
        if measured:
            background+=int(any(np.hypot(track['centerX']-x,track['centerY']-y)<16
                                for x,y in [(1240,927.5),(1196,913)]))
    result=dict(frames=len(frames),annotatedVisibleFrames=len(annotations),matched=matched,
                wrong=wrong,posterTrackedFrames=background,medianMs=float(np.median(durations)),
                events=events,details=details)
    name='baseline' if baseline else 'after'
    (DIAG/f'{name}-reprocessed.json').write_text(json.dumps(result,indent=2))
    print(name,{k:v for k,v in result.items() if k not in ('events','details')})
    for event in events:print(round(event['timestamp']%3600,3),[round(x,1) for x in event['position']],event.get('contactEvidence'))


if __name__=='__main__':
    cv2.setNumThreads(2)
    run('--baseline' in sys.argv)
