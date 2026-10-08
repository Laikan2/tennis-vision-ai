"""Exercise paired iPhone control against an isolated packaged Windows Studio."""
import argparse,asyncio,http.cookiejar,json,subprocess,time
from pathlib import Path
from urllib.request import Request,urlopen,build_opener,HTTPCookieProcessor
from urllib.parse import urlparse,parse_qs
import websockets

parser=argparse.ArgumentParser()
parser.add_argument('--startup',required=True,type=Path)
parser.add_argument('--ffmpeg',required=True,type=Path)
args=parser.parse_args()
folder=args.startup.resolve().parent
startup=json.loads(args.startup.read_text(encoding='utf-8'))
assert not startup['projectPath'] and Path(startup['dataPath']).parent==folder
assert folder.name.startswith('desktop-smoke-')
local=startup['url']
def local_api(path,body=None):
    req=Request(local+'/api'+path,data=json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json','Origin':local})
    with urlopen(req,timeout=20) as r:return json.load(r)
assert local_api('/cameras')==[]
assert not local_api('/mobile-camera/status?platform=ios')['streamReady'], 'Existing iPhone publisher: do not replace it'
metadata=local_api('/mobile-camera?platform=ios')
query=parse_qs(urlparse(metadata['appPairingUri']).query)
panel='http://127.0.0.1:'+query['panelPort'][0]
jar=http.cookiejar.CookieJar()
opener=build_opener(HTTPCookieProcessor(jar))
with opener.open(Request(panel+'/mobile/pair',method='POST',headers={'Authorization':'Bearer '+query['panelToken'][0]}),timeout=10) as r:
    assert r.status==200 and b'Tennis Vision Studio' in r.read()
def panel_api(path,body=None):
    req=Request(panel+'/api'+path,data=json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json','Origin':panel})
    with opener.open(req,timeout=30) as r:return json.load(r)
assert panel_api('/health')['status']=='ok'
publisher=None
try:
    with (folder/'synthetic-iphone.log').open('wb') as log:
        publisher=subprocess.Popen([str(args.ffmpeg),'-hide_banner','-loglevel','warning','-re','-f','lavfi','-i',
            'testsrc2=size=1280x720:rate=60','-an','-c:v','libx264','-preset','ultrafast','-tune','zerolatency',
            '-pix_fmt','yuv420p','-g','60','-bf','0','-b:v','8M','-rtsp_transport','tcp','-f','rtsp','rtsp://127.0.0.1:8554/iphone'],
            stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        deadline=time.monotonic()+20
        while not panel_api('/mobile-camera/status?platform=ios')['streamReady']:
            assert publisher.poll() is None and time.monotonic()<deadline,'No incoming synthetic stream'
            time.sleep(.2)
        first=panel_api('/mobile-camera/connect?platform=ios',{})
        camera=first['camera']['id']
        assert panel_api('/mobile-camera/connect?platform=ios',{})['alreadyRunning']
        polygon=[[.15,.3],[.85,.3],[.85,.9],[.15,.9]]
        panel_api('/capture-region',dict(cameraId=camera,polygon=polygon,analysisMode='court'))
        assert local_api('/cameras')[0]['capturePolygon']==polygon
        deadline=time.monotonic()+35
        while panel_api('/status').get('framesProcessed',0)<4:
            assert time.monotonic()<deadline,'Analysis did not start'
            time.sleep(.3)
        cookie='; '.join(c.name+'='+c.value for c in jar)
        async def verify_video():
            async with websockets.connect(panel.replace('http:','ws:')+'/api/video?cameraId='+camera,
                additional_headers={'Cookie':cookie},origin=panel,max_size=12*1024*1024) as ws:
                packet=await asyncio.wait_for(ws.recv(),8)
                assert isinstance(packet,bytes)
                size=int.from_bytes(packet[:4],'big')
                frame=json.loads(packet[4:4+size])
                assert packet[4+size:4+size+2]==b'\xff\xd8'
                await ws.send(str(frame['sequence']))
                return frame['sequence']
        sequence=asyncio.run(verify_video())
        result=dict(version=startup['version'],isolated=True,pairedPanel=True,camera=camera,areaSavedOnPC=True,
            videoMetadataSequence=sequence,iosVersion=metadata['iosApp']['version'],syntheticSourceFPS=60)
        (folder/'panel-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        print(json.dumps(result),flush=True)
        # A separate headless UI test inspects the live session, then releases it.
        deadline=time.monotonic()+300
        while not (folder/'continue').exists() and time.monotonic()<deadline: time.sleep(.25)
        saved=local_api('/cameras')[0]['capturePolygon']
        panel_api('/sessions/stop',{})
        panel_api('/mobile-camera/connect?platform=ios',{})
        assert local_api('/cameras')[0]['capturePolygon']==saved
        panel_api('/sessions/stop',{})
        result['areaPreservedOnRestart']=True
        (folder/'panel-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        print('Packaged paired panel and persistence passed.',flush=True)
finally:
    try:local_api('/sessions/stop',{})
    except Exception:pass
    if publisher and publisher.poll() is None:publisher.terminate();publisher.wait(timeout=10)
