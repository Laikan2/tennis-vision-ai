"""Prepare the two user-generated ElevenLabs calls for offline playback.

Only trims outer silence and applies one constant gain. No pitch/time change,
voice synthesis, credentials, network requests or inference at playback time.
"""
import argparse
import array
import hashlib
import json
import math
import re
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
RATE = 44100


def decode(ffmpeg, path):
    result = subprocess.run([str(ffmpeg), '-nostdin', '-v', 'error', '-i', str(path),
                             '-ac', '1', '-ar', str(RATE), '-f', 's16le', '-'],
                            check=True, capture_output=True)
    samples = array.array('h', result.stdout)
    if sys.byteorder != 'little':
        samples.byteswap()
    if not samples or len(samples) > RATE * 10:
        raise ValueError('Expected a short non-empty spoken call')
    return samples


def db(value):
    return 20 * math.log10(max(value, 1e-10))


def metrics(samples):
    peak = max(abs(s) for s in samples) / 32768
    rms = math.sqrt(sum(s * s for s in samples) / len(samples)) / 32768
    return dict(durationSeconds=round(len(samples)/RATE, 6),
                rmsDbfs=round(db(rms), 2), peakDbfs=round(db(peak), 2))


def prepare(ffmpeg, source, destination, lead_ms=30, tail_ms=60, target_rms=-18):
    if not 0 <= lead_ms <= 300 or not 0 <= tail_ms <= 300 or not -26 <= target_rms <= -16:
        raise ValueError('Invalid silence padding or voice level')
    samples = decode(ffmpeg, source)
    step = RATE // 100
    voiced = [i for i in range(0, len(samples), step)
              if db(math.sqrt(sum(s*s for s in samples[i:i+step]) /
                              len(samples[i:i+step])) / 32768) > -43]
    if not voiced:
        raise ValueError('The input contains no audible voice')
    first=max(0, voiced[0]-int(RATE*lead_ms/1000))
    last=min(len(samples), voiced[-1]+step+int(RATE*tail_ms/1000))
    trimmed=samples[first:last]
    before=metrics(trimmed)
    gain=min(10**((target_rms-before['rmsDbfs'])/20),
             10**((-1.5-before['peakDbfs'])/20))
    result=array.array('h', [round(s*gain) for s in trimmed])
    ramp=int(RATE*.003)
    for i in range(min(ramp,len(result)//2)):
        result[i]=round(result[i]*i/ramp)
        result[-i-1]=round(result[-i-1]*i/ramp)
    if sys.byteorder != 'little':
        result.byteswap()
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(ffmpeg), '-nostdin', '-y', '-v', 'error',
                    '-f', 's16le', '-ar', str(RATE), '-ac', '1', '-i', '-',
                    '-map_metadata', '-1', '-c:a', 'libmp3lame', '-b:a', '128k',
                    '-write_xing', '1', str(destination)], input=result.tobytes(),
                   check=True, capture_output=True)
    final=metrics(decode(ffmpeg,destination))
    if not .15 <= final['durationSeconds'] <= 1.2 or final['peakDbfs'] > -.7 or final['rmsDbfs'] < -25:
        raise ValueError(f'Call failed audio checks: {final}')
    return final | dict(filename=destination.name, bytes=destination.stat().st_size,
                        sha256=hashlib.sha256(destination.read_bytes()).hexdigest(),
                        sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                        trimStartMs=round(first/RATE*1000, 1),
                        appliedGainDb=round(db(gain), 2))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--inside', type=Path, required=True)
    parser.add_argument('--voice',default='Daniel - Steady Broadcaster')
    parser.add_argument('--pack',default='elevenlabs-daniel-v4-1')
    parser.add_argument('--asset-version',default='v1')
    parser.add_argument('--stability',type=float,default=.5)
    parser.add_argument('--delivery-tag',default='')
    parser.add_argument('--lead-ms',type=float,default=30)
    parser.add_argument('--tail-ms',type=float,default=60)
    parser.add_argument('--target-rms',type=float,default=-18)
    args=parser.parse_args()
    folder=ROOT/'mobile/shared/line-audio'
    if not re.fullmatch(r'v[1-9][0-9]*',args.asset_version) or not 0 <= args.stability <= 1:
        raise ValueError('Invalid asset version or requested stability')
    calls={call:prepare(args.ffmpeg,source,folder/(call.lower()+'.mp3'),args.lead_ms,args.tail_ms,args.target_rms)
           for call,source in [('OUT',args.out),('IN',args.inside)]}
    for call, item in calls.items():
        data=(folder/item['filename']).read_bytes()
        for path in [ROOT/f'mobile/ios/App/Resources/{call}.mp3',
                     ROOT/f'mobile/android/app/src/main/res/raw/{call.lower()}_natural.mp3',
                     ROOT/f'frontend/public/audio/{call.lower()}-eleven-v4-{args.asset_version}.mp3']:
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes(data)
    manifest=dict(schema=1,pack=args.pack,assetVersion=args.asset_version,provider='ElevenLabs',
                  model='Eleven v4',voice=args.voice,language='en',
                  requestedUISettings=dict(stability=args.stability,similarity=.75),source='Generated in the user-authorized ElevenLabs workspace',
                  deliveryTag=args.delivery_tag,
                  processing='Outer silence trim; constant gain; 3 ms edge fade; mono MP3 44100 Hz / 128 kbps; no time or pitch change',
                  paddingMs=dict(leading=args.lead_ms,trailing=args.tail_ms),targetRmsDbfs=args.target_rms,
                  playback='Bundled offline; no ElevenLabs account, network or API key required',calls=calls)
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    timing=ROOT/'mobile/android/app/src/main/res/values/line_voice_durations.xml'
    timing.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'+''.join(
        f'    <integer name="line_voice_{call.lower()}_ms">{math.ceil(item["durationSeconds"]*1000)+30}</integer>\n'
        for call,item in calls.items())+'</resources>\n',encoding='utf-8')
    print(json.dumps(manifest,indent=2))


if __name__=='__main__':
    main()
