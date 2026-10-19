"""Compare generated frames, retaining strict checks on all detector evidence."""
import argparse,json,math,struct
from pathlib import Path

def checked_speed(value):
    if not value or len(value)>16 or any(c not in '0123456789abcdef' for c in value):
        raise ValueError('Malformed speed signature')
    speed=struct.unpack('>d',int(value,16).to_bytes(8,'big'))[0]
    if not math.isfinite(speed) or not .8<=speed<=250:
        raise ValueError('Speed signature is outside the estimator contract')

def detection_signature(signature):
    parts=signature.split('|')
    if 'speed' in parts:
        assert parts.count('speed')==1,'Duplicate speed field'
        index=parts.index('speed');assert index+1<len(parts),'Missing speed value'
        checked_speed(parts[index+1]);del parts[index:index+2]
    if 'impact' in parts:
        index=parts.index('impact')
        assert parts.count('impact')==1 and len(parts)-index==10,'Malformed impact fields'
        if parts[-1]!='nil':checked_speed(parts[-1])
        parts.pop()  # Only the incoming-speed field; every contact field remains.
    return parts

def compare(baseline,candidate,allow_velocity_change=False):
    assert len(baseline)==len(candidate)
    rows=[]
    for old,new in zip(baseline,candidate):
        for key in ('name','width','height','frames','observed','contacts'):
            assert old[key]==new[key],f'Detector changed: {new["name"]} {new["width"]} {key}'
        assert len(old['signatures'])==len(new['signatures'])
        changed=0
        for before,after in zip(old['signatures'],new['signatures']):
            if before==after:continue
            assert allow_velocity_change and detection_signature(before)==detection_signature(after),f'Detector evidence changed: {new["name"]} {new["width"]}'
            changed+=1
        for data in (old,new):
            assert data['medianMs']>0 and data['p95Ms']>0
        rows.append(dict(scene=new['name'],width=new['width'],height=new['height'],frames=new['frames'],
                         baselineMedianMs=old['medianMs'],candidateMedianMs=new['medianMs'],speedup=old['medianMs']/new['medianMs'],
                         baselineP95Ms=old['p95Ms'],candidateP95Ms=new['p95Ms'],identicalOutputs=changed==0,
                         identicalDetectionOutputs=True,velocityOnlyChangedFrames=changed))
    return dict(scope='Same-host Release synthetic timings; not physical camera FPS or match accuracy',
                allFrameOutputsIdentical=all(r['identicalOutputs'] for r in rows),allDetectionOutputsIdentical=True,
                velocityChangeExplicitlyAllowed=allow_velocity_change,velocityOnlyChangedFrames=sum(r['velocityOnlyChangedFrames'] for r in rows),scenes=rows)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline',type=Path);parser.add_argument('candidate',type=Path);parser.add_argument('output',type=Path)
    parser.add_argument('--allow-velocity-change',action='store_true',help='Permit only the separately tested temporal speed-estimator change; retain every ball/contact/uncertainty field')
    args=parser.parse_args()
    output=compare(json.loads(args.baseline.read_text()),json.loads(args.candidate.read_text()),args.allow_velocity_change)
    args.output.write_text(json.dumps(output,indent=2)+'\n')
    print(json.dumps(output,indent=2))
