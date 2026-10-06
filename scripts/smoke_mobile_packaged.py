"""Check the phone flow against an isolated, compiled Windows Studio."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
from urllib.request import Request,urlopen


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--startup',type=Path,required=True)
    parser.add_argument('--ffmpeg',type=Path,required=True)
    parser.add_argument('--hold',action='store_true')
    args=parser.parse_args()
    folder=args.startup.resolve().parent
    startup=json.loads(args.startup.read_text(encoding='utf-8'))
    if startup['projectPath'] or not Path(startup['dataPath']).resolve().is_relative_to(folder):
        raise ValueError('Only an isolated smoke instance may be used')
    origin=startup['url']
    def api(path,body=None):
        payload=json.dumps(body).encode() if body is not None else None
        request=Request(origin+'/api'+path,data=payload,headers={'Content-Type':'application/json','Origin':origin})
        with urlopen(request,timeout=45) as response:
            return json.load(response)
    assert api('/cameras')==[], 'Smoke database must be empty'
    assert not api('/mobile-camera/status')['streamReady'], 'Do not replace an existing phone publisher'
    flags=getattr(subprocess,'CREATE_NO_WINDOW',0)
    publisher=None
    report=dict(version=startup['version'],isolated=True)
    with (folder/'phone-publisher.log').open('wb') as log:
        try:
            publisher=subprocess.Popen([str(args.ffmpeg),'-hide_banner','-loglevel','warning',
                '-re','-f','lavfi','-i','testsrc2=size=1280x720:rate=240','-an',
                '-c:v','libx264','-preset','ultrafast','-tune','zerolatency',
                '-profile:v','baseline','-pix_fmt','yuv420p','-g','240','-bf','0','-b:v','12M',
                '-f','mpegts','srt://127.0.0.1:8890?streamid=publish:galaxy&latency=40000&pkt_size=1316'],
                stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            until=time.monotonic()+15
            while not api('/mobile-camera/status')['streamReady']:
                if publisher.poll() is not None or time.monotonic()>until:
                    raise AssertionError('Windows did not recognize incoming phone video')
                time.sleep(.2)
            result=api('/mobile-camera/connect',{})
            camera=result['camera']
            session=result['session']
            assert not result['alreadyRunning']
            again=api('/mobile-camera/connect',{})
            assert again['alreadyRunning'] and again['session']['id']==session['id']
            assert len(api('/cameras'))==1 and len(api('/sessions'))==1
            polygon=[[.1,.2],[.9,.2],[.9,.9],[.1,.9]]
            api('/capture-region',dict(cameraId=camera['id'],polygon=polygon,analysisMode='court'))
            until=time.monotonic()+40
            while True:
                status=api('/status')
                # This is a connection/persistence test, not a throughput test.
                # An instantaneous decoder FPS sample under full vision load
                # cannot prove sustained source throughput; use the isolated
                # transport benchmark for that measurement.
                if status.get('framesProcessed',0)>5 and status.get('cameraFps',0)>0:break
                if time.monotonic()>until:raise AssertionError('Phone session did not start capture and analysis')
                time.sleep(.25)
            with urlopen(origin+'/api/mobile-camera/android.apk',timeout=15) as response:
                report['apkSha256']=hashlib.sha256(response.read()).hexdigest()
            report.update(phoneReceived=True,connectStartedSession=True,idempotent=True,
                          syntheticSourceFps=240,
                          cameraFps=status['cameraFps'],processingFps=status.get('processingFps'),
                          androidVersion=api('/mobile-camera')['androidApp']['version'])
            (folder/'phone-live-result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
            print(json.dumps(report),flush=True)
            if args.hold:
                print('Ready for UI inspection; create continue in the smoke folder to finish.',flush=True)
                while not (folder/'continue').exists():time.sleep(.25)
            api('/sessions/stop',{})
            restarted=api('/mobile-camera/connect',{})
            assert restarted['camera']['id']==camera['id'] and not restarted['alreadyRunning']
            assert next(c for c in api('/cameras') if c['id']==camera['id'])['capturePolygon']==polygon
            assert len(api('/cameras'))==1
            report['savedAreaPreserved']=True
            report['savedCameraReused']=True
            api('/sessions/stop',{})
            assert len(api('/sessions'))==2 and all(s['endedAt'] for s in api('/sessions'))
            (folder/'phone-result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
            print('Packaged phone flow passed.',flush=True)
        finally:
            try:api('/sessions/stop',{})
            except Exception:pass
            if publisher and publisher.poll() is None:
                publisher.terminate()
                publisher.wait(timeout=10)


if __name__=='__main__': main()
