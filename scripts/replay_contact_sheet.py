"""Render existing offline replay observations without changing session data."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import cv2
import numpy as np


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('result')
    parser.add_argument('--start',required=True)
    parser.add_argument('--seconds',type=float,default=5)
    parser.add_argument('--name',default='detail')
    args=parser.parse_args()
    path=Path(args.result)
    result=json.loads(path.read_text(encoding='utf-8'))
    start=datetime.fromisoformat(args.start).timestamp()
    rows=[r for r in result['frames'] if start<=r['timestamp']<=start+args.seconds]
    cells=[]
    for row in rows:
        frame=cv2.imread(row['path'])
        for c in row['candidates']:
            if c.get('tennisColor'):
                cv2.circle(frame,(round(c['centerX']),round(c['centerY'])),16,(0,140,255),2)
        track=row.get('track')
        text='NONE'
        if track:
            x,y=round(track.get('measuredX',track['centerX'])),round(track.get('measuredY',track['centerY']))
            cv2.circle(frame,(x,y),25,(0,255,0),3)
            text=f"id{track['trackID']} {x},{y} P{int(track.get('predicted',False))} body{int(track.get('insidePerson',False))}/{int(track.get('clearOfBody',False))}"
        frame=frame[300:1080,430:1430]
        frame=cv2.resize(frame,(600,468))
        cv2.rectangle(frame,(0,0),(600,48),(0,0,0),-1)
        cv2.putText(frame,f"{row['timestamp']-start:.3f}s {text}",(6,18),cv2.FONT_HERSHEY_SIMPLEX,.45,(255,255,255),1)
        if row.get('event'):
            event=row['event']
            cv2.putText(frame,f"HIT {event['inOrOut']} {event['contactEvidence']}",(6,39),cv2.FONT_HERSHEY_SIMPLEX,.43,(80,255,160),1)
        cells.append(frame)
    for page,start_index in enumerate(range(0,len(cells),12),1):
        batch=cells[start_index:start_index+12]
        while len(batch)%3:batch.append(np.zeros_like(batch[0]))
        sheet=np.concatenate([np.concatenate(batch[i:i+3],axis=1) for i in range(0,len(batch),3)],axis=0)
        cv2.imwrite(str(path.parent/f'{args.name}-{page}.jpg'),sheet)
    print(f'{len(rows)} observations, {(len(rows)+11)//12} pages')


if __name__=='__main__':main()
