"""Locally trained neural appearance verifier; scores are not calibrated probabilities."""
import json
import os
import time
from pathlib import Path
import cv2
import numpy as np
from .config import DATA


def patch_for(image, box):
    x,y,w,h=box
    side=max(14,int(max(w,h)*1.8))
    return cv2.getRectSubPix(image,(side,side),(float(x+w/2),float(y+h/2)))


def verification_patch(image, box):
    """Keep a fast ball's narrow coloured core visible to the verifier.

    Fitting a 100x6 streak into a large square then 16x16 pixels washes the
    green core into the wall. Retain a central portion of elongated proposals;
    motion, full-contour geometry and body exclusion are still checked separately.
    """
    x,y,w,h=box
    minor=max(1.,min(w,h))
    if max(w,h)/minor<3:
        return patch_for(image,box)
    side=max(14,int(min(max(w,h)*1.8,minor*4)))
    return cv2.getRectSubPix(image,(side,side),(float(x+w/2),float(y+h/2)))


def features(patch):
    small=cv2.resize(patch,(16,16),interpolation=cv2.INTER_AREA)
    hsv=cv2.cvtColor(small,cv2.COLOR_BGR2HSV)
    hist=cv2.calcHist([hsv],[0,1],None,[12,4],[0,180,0,256]).ravel()/256
    # The classifier is specifically for green tennis balls. Remove neutral
    # context so a familiar wall/skin texture cannot become a shortcut label.
    mask=cv2.inRange(hsv,np.array([22,50,55]),np.array([65,255,255]))/255
    core=small.astype(np.float32)/255*mask[:,:,None]
    # A green ball proposal must contain green at its measured centre. This
    # rejects a neutral object with green furniture somewhere in its context.
    support=cv2.inRange(hsv,np.array([24,70,55]),np.array([65,255,255]))[6:10,6:10].mean()/255
    return np.concatenate([core.ravel(),mask.ravel(),hist,[support]]).astype(np.float32)


def votes(model, values):
    x=np.asarray(values,np.float32)
    supported=x[:,-1]>=.06
    for i in range(3):
        x=x@model[f'w{i}']+model[f'b{i}']
        if i<2:x=np.maximum(0,x)
    return (1/(1+np.exp(-np.clip(x,-30,30)))).ravel()*supported


def load_model(path):
    with np.load(path,allow_pickle=False) as weights:
        model = {k:weights[k] for k in weights.files}
    shapes = dict(w0=(1073,48), b0=(48,), w1=(48,16), b1=(16,), w2=(16,1), b2=(1,))
    if any(key not in model or model[key].shape != shape or
           not np.issubdtype(model[key].dtype, np.floating) or
           not np.isfinite(model[key]).all() for key, shape in shapes.items()):
        raise ValueError('Modelo de aparência incompleto ou incompatível.')
    return model


def augment(patch, rng, brightness_range=(.65,1.15)):
    image=cv2.resize(patch,(40,40),interpolation=cv2.INTER_LINEAR)
    image=cv2.warpAffine(image,cv2.getRotationMatrix2D((20,20),float(rng.uniform(-180,180)),float(rng.uniform(.82,1.15))),(40,40),borderMode=cv2.BORDER_REFLECT)
    hsv=cv2.cvtColor(image,cv2.COLOR_BGR2HSV).astype(np.float32)
    hsv[:,:,0]=(hsv[:,:,0]+rng.uniform(-4,4))%180
    hsv[:,:,1]*=rng.uniform(.5,1.25)
    hsv[:,:,2]=hsv[:,:,2]*rng.uniform(*brightness_range)+rng.uniform(-8,8)
    image=cv2.cvtColor(np.clip(hsv,0,255).astype(np.uint8),cv2.COLOR_HSV2BGR)
    length=int(rng.choice([1,3,5,7,9]))
    if length>1:
        kernel=np.zeros((length,length),np.float32)
        cv2.line(kernel,(0,int(rng.integers(0,length))),(length-1,int(rng.integers(0,length))),1,1)
        image=cv2.filter2D(image,-1,kernel/max(1,kernel.sum()))
    size=int(rng.integers(7,30))
    image=cv2.resize(image,(size,size),interpolation=cv2.INTER_AREA)
    ok,encoded=cv2.imencode('.jpg',image,[cv2.IMWRITE_JPEG_QUALITY,int(rng.integers(35,94))])
    return cv2.imdecode(encoded,cv2.IMREAD_COLOR) if ok else image


def metrics(labels, scores, threshold=.75):
    y=np.asarray(labels); p=np.asarray(scores)>=threshold
    tp=int(np.sum(p & (y==1))); fp=int(np.sum(p & (y==0)))
    fn=int(np.sum(~p & (y==1))); tn=int(np.sum(~p & (y==0)))
    precision=tp/max(1,tp+fp); recall=tp/max(1,tp+fn)
    return dict(tp=tp,fp=fp,fn=fn,tn=tn,precision=precision,recall=recall,
                f1=2*precision*recall/max(1e-9,precision+recall))


def should_promote(result,previous):
    return (result['precision']>=.9 and result['recall']>=.85 and
            (previous is None or (result['f1']>=previous['f1'] and result['precision']>=previous['precision'] and
                                 result['recall']>=previous['recall'])))


def stress_metrics(model, patches, labels, threshold):
    transforms = {
        'darker': lambda image: np.clip(image.astype(float)*.7,0,255).astype(np.uint8),
        'brighter': lambda image: np.clip(image.astype(float)*1.25,0,255).astype(np.uint8),
        'small': lambda image: cv2.resize(image,(10,10),interpolation=cv2.INTER_AREA),
        'horizontal_blur': lambda image: cv2.filter2D(image,-1,np.ones((1,5),np.float32)/5),
    }
    return {name:metrics(labels,votes(model,[features(transform(patch)) for patch in patches]),threshold)
            for name,transform in transforms.items()}


def train_model(folder, brightness_range=(.65,1.15)):
    folder=Path(folder)
    rows=json.loads((folder/'examples.json').read_text(encoding='utf-8'))
    reviewed=[r for r in rows if r.get('reviewed') and r.get('label') in (0,1)]
    training=[r for r in reviewed if r.get('split')=='train']
    testing=[r for r in reviewed if r.get('split')=='test']
    if sum(r['label']==1 for r in training)<10 or sum(r['label']==0 for r in training)<20:
        raise ValueError('Revise pelo menos 10 exemplos com bola e 20 sem bola para treinar.')
    if sum(r['label']==1 for r in testing)<5 or sum(r['label']==0 for r in testing)<20:
        raise ValueError('A validação separada precisa de pelo menos 5 bolas e 20 negativos revisados.')
    if {r['group'] for r in training} & {r['group'] for r in testing}:
        raise ValueError('Uma mesma sessão não pode estar no treino e na validação.')
    rng=np.random.default_rng(417)
    x=[];y=[]
    for row in training:
        patch=cv2.imread(str(folder/row['image']))
        if patch is None: raise ValueError('Exemplo de treinamento ausente.')
        x.append(features(patch));y.append(row['label'])
        for _ in range(40 if row['label'] else 4):
            x.append(features(augment(patch,rng,brightness_range)));y.append(row['label'])
        if row['label']:
            # Neutral, blurred highlights must not inherit the green-ball label.
            gray=cv2.cvtColor(cv2.cvtColor(patch,cv2.COLOR_BGR2GRAY),cv2.COLOR_GRAY2BGR)
            for _ in range(4):x.append(features(augment(gray,rng,brightness_range)));y.append(0)
    import torch
    torch.set_num_threads(2);torch.manual_seed(417)
    network=torch.nn.Sequential(torch.nn.Linear(len(x[0]),48),torch.nn.ReLU(),
                                torch.nn.Linear(48,16),torch.nn.ReLU(),torch.nn.Linear(16,1))
    optimizer=torch.optim.AdamW(network.parameters(),lr=.002,weight_decay=.005)
    xx=torch.tensor(np.asarray(x,np.float32));yy=torch.tensor(y,dtype=torch.float32).reshape(-1,1)*.94+.03
    loss_fn=torch.nn.BCEWithLogitsLoss(pos_weight=torch.tensor([max(1,(len(y)-sum(y))/sum(y))]))
    for epoch in range(120):
        order=torch.randperm(len(y))
        for indices in order.split(128):
            optimizer.zero_grad();loss=loss_fn(network(xx[indices]),yy[indices]);loss.backward();optimizer.step()
    model={}
    for i,layer in enumerate([network[0],network[2],network[4]]):
        model[f'w{i}']=layer.weight.detach().numpy().T.copy();model[f'b{i}']=layer.bias.detach().numpy().copy()
    tx=[]; test_patches=[]
    for row in testing:
        patch=cv2.imread(str(folder/row['image']))
        if patch is None: raise ValueError('Exemplo de validação ausente. O modelo atual foi preservado.')
        tx.append(features(patch))
        test_patches.append(patch)
    labels=[r['label'] for r in testing]
    threshold=.85
    result=metrics(labels,votes(model,tx),threshold)
    versions=folder/'models';versions.mkdir(exist_ok=True)
    version=str(time.time_ns())
    filename=f'models/{version}.npz'
    np.savez_compressed(folder/filename,**model)
    stressed=stress_metrics(model,test_patches,labels,threshold)
    previous=None; previous_stress=None
    active=folder/'active.json'
    if active.is_file():
        info=json.loads(active.read_text())
        old=load_model(folder/info['file'])
        previous=metrics(labels,votes(old,tx),info['threshold'])
        previous_stress=stress_metrics(old,test_patches,labels,info['threshold'])
    improved=(previous is None or result['f1'] > previous['f1'] or
              (previous_stress is not None and any(
                  stressed[name]['f1'] > baseline['f1'] for name,baseline in previous_stress.items())))
    accepted=improved and should_promote(result,previous) and (previous_stress is None or all(
        stressed[name]['precision'] >= baseline['precision'] and
        stressed[name]['recall'] >= baseline['recall'] for name,baseline in previous_stress.items()))
    report=dict(version=version,file=filename,threshold=threshold,accepted=accepted,createdAt=time.time(),
                trainExamples=len(training),trainPositives=sum(r['label'] for r in training),
                augmentedExamples=len(x),testExamples=len(testing),testPositives=sum(labels),
                metrics=result,previousMetrics=previous,reviewedCount=len(reviewed),
                stressMetrics=stressed,previousStressMetrics=previous_stress,
                brightnessRange=list(brightness_range),
                scope='Validação em recortes revisados de trechos separados das gravações do quarto; não mede precisão de velocidade ou de impactos, nem generalização para outras quadras.',
                validationGroups=sorted({r['group'] for r in testing}))
    (versions/f'{version}.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    temporary_report=folder/'last-training.tmp'
    temporary_report.write_text(json.dumps(report,indent=2),encoding='utf-8')
    os.replace(temporary_report,folder/'last-training.json')
    if accepted:
        temporary=folder/'active.tmp'
        temporary.write_text(json.dumps(report,indent=2),encoding='utf-8')
        os.replace(temporary,active)
    return report


class AppearanceModel:
    def __init__(self, folder=None):
        self.folder=Path(folder or DATA/'learning')
        self.model=None;self.version=None;self.threshold=.85;self.checked=0
        self.reload()

    def reload(self):
        if time.monotonic()-self.checked<5:return
        self.checked=time.monotonic()
        path=self.folder/'active.json'
        if not path.is_file():
            self.model=None;self.version=None;return
        try:
            info=json.loads(path.read_text())
            if info['version']!=self.version:
                model=load_model(self.folder/info['file'])
                threshold=float(info['threshold'])
                if not np.isfinite(threshold) or not 0 < threshold <= 1:
                    return
                self.model=model
                self.version=info['version'];self.threshold=threshold
        except (OSError,ValueError,KeyError,TypeError):
            # Preserve a working model if an interrupted local file write is
            # encountered; frame processing must continue with the old version.
            return

    def scores(self, patches):
        self.reload()
        if self.model is None or not patches:return np.zeros(len(patches))
        return votes(self.model,[features(p) for p in patches])
