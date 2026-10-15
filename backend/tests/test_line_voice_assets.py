"""Validate the delivered audio, rather than a successful TTS message."""
import hashlib,json,runpy,os,shutil
from pathlib import Path
import pytest

ROOT=Path(__file__).resolve().parents[2]
AUDIO=runpy.run_path(str(ROOT/'scripts/prepare-line-voices.py'))
MANIFEST=json.loads((ROOT/'mobile/shared/line-audio/manifest.json').read_text())
FFMPEG=os.environ.get('TENNIS_TEST_FFMPEG') or shutil.which('ffmpeg')

@pytest.mark.parametrize('call',['IN','OUT'])
def test_offline_assets_are_identical_on_every_platform(call):
    item=MANIFEST['calls'][call];canonical=ROOT/'mobile/shared/line-audio'/item['filename']
    data=canonical.read_bytes()
    assert len(data)==item['bytes']
    assert hashlib.sha256(data).hexdigest()==item['sha256']
    variant=MANIFEST.get('assetVersion','v1')
    for path in [ROOT/f'mobile/ios/App/Resources/{call}.mp3',ROOT/f'mobile/android/app/src/main/res/raw/{call.lower()}_natural.mp3',ROOT/f'frontend/public/audio/{call.lower()}-eleven-v4-{variant}.mp3']:
        assert path.read_bytes()==data
@pytest.mark.parametrize('call',['IN','OUT'])
def test_decoded_audio_has_usable_duration_level_and_headroom(call):
    if not FFMPEG:pytest.skip('Set TENNIS_TEST_FFMPEG to validate decoded audio')
    item=MANIFEST['calls'][call];canonical=ROOT/'mobile/shared/line-audio'/item['filename']
    actual=AUDIO['metrics'](AUDIO['decode'](FFMPEG,canonical))
    assert actual=={key:item[key] for key in actual}
    assert .3<=actual['durationSeconds']<=.8
    assert -24<=actual['rmsDbfs']<=-15
    assert actual['peakDbfs']<=-.7
    if 'peak limiter' in MANIFEST['processing']:
        assert abs(actual['rmsDbfs']-MANIFEST['targetRmsDbfs'])<=1

def test_distinct_calls_and_real_provider_metadata():
    assert MANIFEST['provider']=='ElevenLabs'
    assert MANIFEST['calls']['IN']['sha256']!=MANIFEST['calls']['OUT']['sha256']
    assert 'offline' in MANIFEST['playback']
