"""Known pixel scenarios shared by Kotlin and Swift, never personal recordings."""
import json
from pathlib import Path

root=Path(__file__).resolve().parents[1]
cases=[]
for label,skin in [('light','ffe2b49a'),('medium','ffbc8062'),('dim','ff3b2921')]:
    for action in ['hold','carry','fingers']:
        cases.append(dict(name=f'{action}_{label}',family='hand',action=action,skin=skin,
                          radius=9 if action=='fingers' else 6,calls=[],minLocated=19 if action=='fingers' else 20 if action=='hold' else 14))
for fps in [30,60,120]:
    for out in [False,True]:
        cases.append(dict(name=f'release_{"out" if out else "in"}_{fps}',family='hand',action='release',fps=fps,outside=out,calls=['outside' if out else 'inside'],minLocated=24))
for label,bg,fingers in [('green','ff73943e',False),('wood','ffbc9062',False),('fingers','ff315372',True),('fingers_green','ff73943e',True)]:
    cases.append(dict(name='release_'+label,family='hand',action='release',background=bg,radius=9 if fingers else 6,fingers=fingers,calls=['inside'],minLocated=24))
for action,n in [('fall',19),('hidden',0),('no_ball',0)]:
    cases.append(dict(name=action,family='hand',action=action,outside=True,calls=[],minLocated=n))
for diameter in [3,6,12]:
    for dx in [20,40,80]:
        cases.append(dict(name=f'fast_{diameter}px_{dx}',family='fast',diameter=diameter,dx=dx,calls=['review' if diameter==3 else 'inside'],minLocated=11 if diameter==3 else 12))
for blur in [2,4,6]:
    cases.append(dict(name=f'blur_{blur}',family='fast',diameter=6,dx=40,blur=blur,calls=['inside'],minLocated=11 if blur==6 else 12))
for phase in [.25,.5,.75]:
    cases.append(dict(name=f'phase_{phase}',family='fast',diameter=6,dx=40,phase=phase,calls=['inside'],minLocated=12))
cases += [dict(name='out_fast',family='fast',diameter=6,dx=30,outside=True,calls=['outside'],minLocated=12),
          dict(name='hidden_contact',family='fast',diameter=6,dx=30,outside=True,hideContact=True,calls=['review'],minLocated=11),
          dict(name='air_crossing',family='fast',diameter=6,dx=60,bounce=False,calls=[],minLocated=12),
          dict(name='30fps_fast',family='fast',diameter=6,dx=80,fps=30,calls=['inside'],minLocated=12)]
value=dict(schema=1,kind='synthetic-known-pixels-not-real-tennis',cases=cases)
path=root/'mobile/shared/vision-cases.json'
path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
print(f'{len(cases)} common scenarios')
