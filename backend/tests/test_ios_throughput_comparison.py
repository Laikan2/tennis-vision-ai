"""Velocity allowance must never hide changed ball/contact evidence."""
from pathlib import Path
import copy,runpy,struct
import pytest

COMPARE=runpy.run_path(str(Path(__file__).resolve().parents[2]/'scripts/compare-ios-throughput.py'))['compare']
def bits(value):return format(struct.unpack('>Q',struct.pack('>d',value))[0],'x')
def sample(signature):return [dict(name='known',width=320,height=240,frames=24,observed=12,contacts=1,medianMs=2.0,p95Ms=3.0,signatures=[signature])]

def test_identical_signatures_keep_strict_legacy_result():
    a=sample('Bola|ball|x=1|speed|'+bits(10.0)+'|trail')
    result=COMPARE(a,copy.deepcopy(a))
    assert result['allFrameOutputsIdentical'] and result['velocityOnlyChangedFrames']==0

def test_velocity_change_requires_explicit_allowance():
    with pytest.raises(AssertionError):COMPARE(sample('Bola|speed|'+bits(10.0)),sample('Bola|speed|'+bits(11.0)))

def test_only_velocity_can_change_with_allowance():
    a=sample('Bola|ball|x=1|speed|'+bits(10.0)+'|trail')
    b=sample('Bola|ball|x=1|speed|'+bits(11.0)+'|trail')
    result=COMPARE(a,b,True)
    assert result['allDetectionOutputsIdentical'] and not result['allFrameOutputsIdentical']
    assert result['velocityOnlyChangedFrames']==1

def test_changed_ball_evidence_is_rejected_even_with_velocity_allowance():
    with pytest.raises(AssertionError):COMPARE(sample('Bola|ball|x=1|speed|'+bits(10.0)),sample('Bola|ball|x=2|speed|'+bits(11.0)),True)

@pytest.mark.parametrize('value',[float('nan'),251.0])
def test_invalid_velocity_is_not_silently_ignored(value):
    with pytest.raises(ValueError):COMPARE(sample('Bola|speed|'+bits(10.0)),sample('Bola|speed|'+bits(value)),True)

def test_only_incoming_velocity_field_may_change_inside_contact_record():
    prefix='Bola|impact|1|a|b|c|d|e|inside|observado|'
    result=COMPARE(sample(prefix+bits(10.0)),sample(prefix+bits(11.0)),True)
    assert result['velocityOnlyChangedFrames']==1
    with pytest.raises(AssertionError):COMPARE(sample(prefix+bits(10.0)),sample(prefix.replace('|inside|','|outside|')+bits(11.0)),True)
