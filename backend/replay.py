"""JPEG ring and timestamped replay manifests; encoding never blocks vision."""
from collections import deque
import json
import threading
import uuid
from .config import DATA


class ReplayManager:
    def __init__(self,store,pre=5,post=3,max_bytes=64*1024*1024):
        self.store,self.pre,self.post=store,pre,post
        self.ring=deque()
        self.bytes=0
        self.max_bytes=max_bytes
        self.lock=threading.RLock()
        self.pending=[]
        self.writers=[]

    def push(self,t,jpeg,overlay):
        with self.lock:
            item=(t,jpeg,overlay)
            self.ring.append(item); self.bytes+=len(jpeg)
            while self.ring and (t-self.ring[0][0]>self.pre or self.bytes>self.max_bytes):
                self.bytes-=len(self.ring.popleft()[1])
            for job in self.pending[:]:
                if job['bytes']+len(jpeg)<=self.max_bytes:
                    job['frames'].append(item); job['bytes']+=len(jpeg)
                else:
                    job['truncated']=True
                if t>=job['end']:
                    self.pending.remove(job); self._dispatch(job)

    def trigger(self,t,session_id,reason='manual'):
        with self.lock:
            self.writers=[w for w in self.writers if w.is_alive()]
            if len(self.pending)+len(self.writers)>=3:
                raise ValueError('Limite de replays em gravação. Aguarde.')
            job=dict(id=str(uuid.uuid4()),frames=list(self.ring),end=t+self.post,sessionId=session_id,
                     reason=reason,bytes=self.bytes,truncated=False)
            self.pending.append(job)
            return job['id']

    def flush(self):
        with self.lock:
            for job in self.pending:
                job['truncated']=True
                self._dispatch(job)
            self.pending=[]
            writers=list(self.writers)
        for writer in writers:
            writer.join(timeout=10)

    def _dispatch(self,job):
        writer=threading.Thread(target=self._save,args=(job,),daemon=True)
        self.writers.append(writer); writer.start()

    def _save(self,job):
        import logging
        try:
            folder=DATA/'replays'/job['id']; folder.mkdir()
            manifest=[]
            for i,(t,jpeg,overlay) in enumerate(job['frames']):
                (folder/f'{i}.jpg').write_bytes(jpeg)
                manifest.append(dict(index=i,timestamp=t,overlay=overlay))
            (folder/'manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
            self.store.put('replay',{k:v for k,v in job.items() if k not in ('frames','bytes','end')} | dict(frames=len(manifest)))
            logging.getLogger('tennis.replay').info('Replay saved')
        except Exception:
            logging.getLogger('tennis.replay').exception('Replay save failed')
