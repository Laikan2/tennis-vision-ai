"""Bounded local review queue. Only human-reviewed crops enter model training."""
import hashlib
import json
import os
import threading
import time
import uuid
from pathlib import Path
import cv2
import numpy as np
from .appearance import patch_for, train_model
from .config import DATA


def save_json(path, value):
    temporary=path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    os.replace(temporary,path)


def session_split(group):
    # Every frame from one session stays together, including subsequent reviews.
    return 'test' if int(hashlib.sha256(group.encode()).hexdigest()[:8],16)%5==0 else 'train'


class LearningManager:
    def __init__(self,folder=None):
        self.folder=Path(folder or DATA/'learning')
        self.folder.mkdir(parents=True,exist_ok=True)
        for name in ('frames','patches','models'):(self.folder/name).mkdir(exist_ok=True)
        self.lock=threading.RLock()
        self.operation_lock=threading.RLock()
        self.busy=False;self.error=None;self.last_sample={};self.session_counts={};self.last_observed={}
        self.queue=self._read('queue.json',[])
        self.examples=self._read('examples.json',[])
        self.settings=self._read('settings.json',{'collect':True,'autoTrain':True})
        self.is_running=lambda:False

    def _read(self,name,default):
        path=self.folder/name
        return json.loads(path.read_text(encoding='utf-8')) if path.is_file() else default

    def status(self):
        with self.lock:
            last=self._read('last-training.json',None)
            reviewed=sum(bool(r.get('reviewed')) for r in self.examples)
            return dict(settings=self.settings,busy=self.busy,error=self.error,
                        active=self._read('active.json',None),lastTraining=last,
                        reviewed=reviewed,newExamples=max(0,reviewed-(last or {}).get('reviewedCount',0)),
                        pending=[r for r in self.queue if r['state']=='pending'][:120],
                        limit=120,running=self.is_running())

    def configure(self,collect,auto_train):
        with self.lock:
            self.settings=dict(collect=collect,autoTrain=auto_train)
            save_json(self.folder/'settings.json',self.settings)
        self.maybe_train()
        return self.status()

    def capture(self,jpeg,state,session_id,manual=False):
        if not jpeg or not session_id:return None
        with self.lock:
            now=time.monotonic()
            track=state.get('track')
            observed=bool(track and not track.get('predicted'))
            lost=bool(self.last_observed.get(session_id) and not observed)
            self.last_observed[session_id]=observed
            uncertain=bool(observed and track.get('appearanceScore',1)<.94)
            interval=2 if lost else (4 if uncertain else 12)
            if not manual and (not self.settings['collect'] or now-self.last_sample.get(session_id,0)<interval or
                               self.session_counts.get(session_id,0)>=40):return None
            if sum(r['state']=='pending' for r in self.queue)>=120:return None
            # Keep both uncertain crops and occasional successes. A blank frame
            # remains manually annotatable; it is never automatically a negative.
            box=([track[k] for k in ('x','y','width','height')]
                 if track and not track.get('predicted') else None)
            id=str(uuid.uuid4())
            row=dict(id=id,group=session_id,split=session_split(session_id),box=box,
                     timestamp=state.get('timestamp'),state='pending',createdAt=time.time(),
                     reason='captura manual' if manual else ('perda de acompanhamento' if lost else
                             'revisar detecção' if box else 'procurar bola não detectada'))
            (self.folder/'frames'/f'{id}.jpg').write_bytes(jpeg)
            self.queue.append(row)
            save_json(self.folder/'queue.json',self.queue)
            self.last_sample[session_id]=now
            self.session_counts[session_id]=self.session_counts.get(session_id,0)+1
            return row

    def image_path(self,id):
        with self.lock:
            if not any(r['id']==id and r['state']=='pending' for r in self.queue):
                raise ValueError('Exemplo não encontrado.')
            return self.folder/'frames'/f'{id}.jpg'

    def review(self,id,label,box):
        with self.lock:
            row=next((r for r in self.queue if r['id']==id and r['state']=='pending'),None)
            if row is None:raise ValueError('Este exemplo já foi revisado ou não existe.')
            if label is not None:
                image=cv2.imread(str(self.image_path(id)))
                if image is None:raise ValueError('Imagem indisponível.')
                if box is None:raise ValueError('Desenhe um retângulo justo sobre a bola ou sobre o objeto confundido.')
                x,y,w,h=map(float,box)
                ih,iw=image.shape[:2]
                if not np.isfinite([x,y,w,h]).all() or x<0 or y<0 or min(w,h)<2 or x+w>iw+.1 or y+h>ih+.1:
                    raise ValueError('Selecione um retângulo dentro da imagem, com pelo menos 2 pixels por lado.')
                name=f'patches/review-{id}.jpg'
                if not cv2.imwrite(str(self.folder/name),patch_for(image,[x,y,w,h])):
                    raise ValueError('Não foi possível salvar o exemplo.')
                self.examples.append(dict(id=id,group=row['group'],split=row['split'],
                                          box=[x,y,w,h],image=name,label=label,reviewed=True,
                                          reviewer='user',timestamp=row['timestamp']))
                save_json(self.folder/'examples.json',self.examples)
            row['state']='ignored' if label is None else 'reviewed'
            save_json(self.folder/'queue.json',self.queue)
            # Once reviewed, training only needs the crop. Keep the full room
            # image only while it is needed for the user's annotation.
            (self.folder/'frames'/f'{id}.jpg').unlink(missing_ok=True)
        self.maybe_train()
        return self.status()

    def maybe_train(self):
        state=self.status()
        if (state['settings']['autoTrain'] and state['newExamples']>=8 and
                not state['running'] and not state['busy']):
            self.train()

    def train(self):
        with self.operation_lock,self.lock:
            if self.busy:raise ValueError('Um treinamento já está em andamento.')
            if self.is_running():raise ValueError('Encerre a sessão para treinar sem disputar processamento com a câmera.')
            self.busy=True;self.error=None
            self.worker=threading.Thread(target=self._train,daemon=True,name='reviewed-ball-training')
            self.worker.start()
        return self.status()

    def _train(self):
        try:train_model(self.folder)
        except Exception as error:
            self.error=str(error) if isinstance(error,ValueError) else 'Treino falhou. O modelo anterior foi preservado.'
        finally:
            with self.lock:self.busy=False
