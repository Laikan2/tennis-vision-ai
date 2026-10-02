"""Capture a read-only diagnostic snapshot and contact sheets for a recording."""
import argparse
import json
from pathlib import Path
from urllib.request import urlopen
import cv2
import numpy as np

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('video')
    parser.add_argument('--folder',required=True)
    parser.add_argument('--step',type=float,default=1.)
    args=parser.parse_args()
    folder=ROOT/args.folder
    folder.mkdir(parents=True,exist_ok=True)
    def get(path):
        with urlopen('http://127.0.0.1:8000/api/'+path,timeout=8) as response:
            return json.load(response)
    status=get('status')
    session_id=(status.get('session') or {}).get('id')
    for name,value in [('status',status),('events',get('events?sessionId='+session_id) if session_id else []),
                       ('replays',[r for r in get('replays') if r.get('sessionId')==session_id])]:
        (folder/(name+'.json')).write_text(json.dumps(value,ensure_ascii=False),encoding='utf-8')
    video=cv2.VideoCapture(args.video)
    fps=video.get(cv2.CAP_PROP_FPS)
    frames=int(video.get(cv2.CAP_PROP_FRAME_COUNT))
    meta=dict(fps=fps,frames=frames,duration=frames/fps,width=int(video.get(cv2.CAP_PROP_FRAME_WIDTH)),
              height=int(video.get(cv2.CAP_PROP_FRAME_HEIGHT)),sessionId=session_id)
    (folder/'metadata.json').write_text(json.dumps(meta),encoding='utf-8')
    cells=[]
    for seconds in np.arange(0,frames/fps,args.step):
        video.set(cv2.CAP_PROP_POS_MSEC,float(seconds)*1000)
        ok,frame=video.read()
        if not ok:break
        cv2.imwrite(str(folder/f'screen-{seconds:05.1f}.jpg'),frame)
        thumb=cv2.resize(frame,(640,360))
        cv2.rectangle(thumb,(0,0),(145,25),(0,0,0),-1)
        cv2.putText(thumb,f'{seconds:.1f}s',(6,18),cv2.FONT_HERSHEY_SIMPLEX,.55,(255,255,255),1)
        cells.append(thumb)
    for page,start in enumerate(range(0,len(cells),16),1):
        batch=cells[start:start+16]
        while len(batch)%4:batch.append(np.zeros_like(batch[0]))
        sheet=np.concatenate([np.concatenate(batch[i:i+4],axis=1) for i in range(0,len(batch),4)],axis=0)
        cv2.imwrite(str(folder/f'overview-{page}.jpg'),sheet)
    video.release()
    print(json.dumps(meta))


if __name__=='__main__':main()
