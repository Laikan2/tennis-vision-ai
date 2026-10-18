"""Compare the same generated frames on one CI host; never a phone-FPS claim."""
import json,sys
from pathlib import Path

baseline=json.loads(Path(sys.argv[1]).read_text())
candidate=json.loads(Path(sys.argv[2]).read_text())
assert len(baseline)==len(candidate)
rows=[]
for old,new in zip(baseline,candidate):
    for key in ('name','width','height','frames','observed','contacts','signatures'):
        assert old[key]==new[key],f'Detector changed: {new["name"]} {new["width"]} {key}'
    rows.append(dict(scene=new['name'],width=new['width'],height=new['height'],frames=new['frames'],
                     baselineMedianMs=old['medianMs'],candidateMedianMs=new['medianMs'],
                     speedup=old['medianMs']/new['medianMs'],
                     baselineP95Ms=old['p95Ms'],candidateP95Ms=new['p95Ms'],identicalOutputs=True))
output=dict(scope='Same-host release-build synthetic benchmark; not physical camera FPS or match accuracy',
            allFrameOutputsIdentical=True,scenes=rows)
Path(sys.argv[3]).write_text(json.dumps(output,indent=2)+'\n')
print(json.dumps(output,indent=2))
