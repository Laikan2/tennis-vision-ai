"""Read-only live capture/analysis sample; no session changes."""
import argparse
import json
import time
import urllib.request
from pathlib import Path
import statistics

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',required=True)
    parser.add_argument('--samples',type=int,default=40)
    args=parser.parse_args()
    rows=[]
    keys=('cameraFps','processingFps','inferenceMs','processingMs','frameLatencyMs',
          'previewFps','captureGapP95Ms','sourceTimingAvailable','framesProcessed',
          'cameraStatus','detectionStageMs','previewActive')
    for _ in range(args.samples):
        with urllib.request.urlopen('http://127.0.0.1:8000/api/status',timeout=3) as response:
            data=json.load(response)
        rows.append({key:data.get(key) for key in keys})
        time.sleep(.2)
    summary={}
    for key in keys:
        values=[r[key] for r in rows if type(r[key]) in (int,float)]
        if values:
            summary[key]=dict(median=statistics.median(values),maximum=max(values))
    result=dict(summary=summary,rows=rows)
    path=ROOT/args.output
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(summary))


if __name__=='__main__':main()
