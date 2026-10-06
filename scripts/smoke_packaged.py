"""Exercise a fresh, isolated packaged Studio with two synthetic RTSP publishers."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import time
from urllib.request import Request, urlopen

import cv2
import numpy as np
from websockets.sync.client import connect


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--startup',type=Path,required=True)
    parser.add_argument('--ffmpeg',type=Path,required=True)
    parser.add_argument('--hold',action='store_true')
    args=parser.parse_args()
    folder=args.startup.resolve().parent
    startup=json.loads(args.startup.read_text(encoding='utf-8'))
    if startup['projectPath'] or not Path(startup['dataPath']).resolve().is_relative_to(folder):
        raise ValueError('Only an isolated smoke instance may be used.')
    origin=startup['url']
    def api(path,body=None,timeout=60):
        data=json.dumps(body).encode() if body is not None else None
        request=Request(origin+'/api'+path,data=data,headers={'Content-Type':'application/json','Origin':origin})
        with urlopen(request,timeout=timeout) as response:
            return json.load(response)
    assert api('/cameras')==[], 'Smoke database must be empty.'
    # Real encoded moving pixels; no fake API status or detector output.
    avi=folder/'synthetic.avi'; mp4=folder/'synthetic.mp4'
    writer=cv2.VideoWriter(str(avi),cv2.VideoWriter_fourcc(*'MJPG'),60.,(1280,720))
    for i in range(180):
        frame=np.full((720,1280,3),(42,38,33),np.uint8)
        cv2.rectangle(frame,(120,250),(1160,640),(100,110,120),2)
        x=200+int(i*8)%850
        y=round(560-abs((i/60%.8)-.4)*700)
        cv2.circle(frame,(x,y),12,(40,240,200),-1)
        writer.write(frame)
    writer.release()
    hidden=subprocess.CREATE_NO_WINDOW if hasattr(subprocess,'CREATE_NO_WINDOW') else 0
    subprocess.run([str(args.ffmpeg),'-hide_banner','-loglevel','error','-y','-i',str(avi),
                    '-an','-c:v','libx264','-preset','ultrafast','-tune','zerolatency','-g','30',
                    '-bf','0','-pix_fmt','yuv420p',str(mp4)],check=True,creationflags=hidden)
    publishers=[]; cameras=[]; session=None
    def publish(index):
        log=(folder/f'publisher-{index}.log').open('ab')
        process=subprocess.Popen([str(args.ffmpeg),'-hide_banner','-loglevel','warning','-re',
            '-stream_loop','-1','-i',str(mp4),'-an','-c:v','copy','-f','rtsp',
            '-rtsp_transport','tcp',f'rtsp://127.0.0.1:8554/october_audit_{index}'],
            stdout=log,stderr=log,creationflags=hidden)
        log.close()
        return process
    try:
        publishers=[publish(i) for i in range(2)]
        time.sleep(1)
        for i in range(2):
            camera=api('/cameras',dict(name=f'Teste isolado {i+1}',host='127.0.0.1',port=8554,
                path=f'/october_audit_{i}',requestedFps=60,ballDiameterMm=67))
            polygon=[[.1+i*.02,.2],[.9,.2],[.9,.9],[.1,.9]]
            api('/capture-region',dict(cameraId=camera['id'],polygon=polygon,analysisMode='court'))
            cameras.append(dict(camera,polygon=polygon))
        session=api('/sessions/start',dict(cameraIds=[c['id'] for c in cameras],name='Validação isolada de duas câmeras'))
        until=time.monotonic()+60
        while time.monotonic()<until:
            status=api('/status')
            feeds=status.get('cameraFeeds',[])
            if len(feeds)==2 and all(f.get('framesProcessed',0)>45 and f.get('cameraFps',0)>40 for f in feeds):
                break
            time.sleep(.5)
        else:
            raise AssertionError('Both streams did not become healthy: '+json.dumps(status))
        def receive_frames(camera):
            with connect(origin.replace('http:','ws:')+'/api/video?cameraId='+camera['id'],origin=origin,max_size=8_000_000) as ws:
                sequences=[]
                for _ in range(3):
                    packet=ws.recv(timeout=12); size=int.from_bytes(packet[:4],'big')
                    meta=json.loads(packet[4:4+size]); jpeg=packet[4+size:]
                    assert meta['sequence']==meta['analysisSequence']
                    assert cv2.imdecode(np.frombuffer(jpeg,np.uint8),cv2.IMREAD_COLOR).shape[:2]==(720,1280)
                    sequences.append(meta['sequence']); ws.send(str(meta['sequence']))
                assert sequences==sorted(set(sequences))
                return sequences
        with ThreadPoolExecutor(2) as pool:
            sequences=list(pool.map(receive_frames,cameras))
        with urlopen(origin+'/api/mobile-camera/android.apk',timeout=30) as response:
            apk_hash=hashlib.sha256(response.read()).hexdigest()
        report=dict(version=startup['version'],startupMs=startup['startupMs'],isolated=True,
            feeds=[{k:f.get(k) for k in ('cameraName','cameraFps','processingFps','frameLatencyMs','processingMs','captureGapP95Ms','sourceTimingAvailable','poseStatus')}
                   for f in feeds],videoSequences=sequences,apkSha256=apk_hash)
        (folder/'live-result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print(json.dumps(report),flush=True)
        if args.hold:
            print('Ready for UI inspection. Create continue file in smoke folder to finish.',flush=True)
            while not (folder/'continue').exists(): time.sleep(.25)
        # Terminate and resume one publisher without changing its saved camera.
        publishers[1].terminate(); publishers[1].wait(timeout=10)
        time.sleep(3)
        before=api('/status')['cameraFeeds'][1]['framesReceived']
        publishers[1]=publish(1)
        until=time.monotonic()+45
        while time.monotonic()<until:
            feed=api('/status')['cameraFeeds'][1]
            if feed['cameraStatus']=='online' and feed['framesReceived']>before+60:break
            time.sleep(.5)
        else:raise AssertionError('Camera did not recover after publisher restart.')
        report['cameraReconnect']=True
        api('/sessions/stop',{})
        saved=api('/cameras')
        assert all(next(s for s in saved if s['id']==c['id'])['capturePolygon']==c['polygon'] for c in cameras)
        assert next(s for s in api('/sessions') if s['id']==session['id'])['endedAt']
        report['areasPersisted']=report['sessionPersisted']=True
        (folder/'audit-result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print('Packaged smoke passed.',flush=True)
    finally:
        if session:
            try:api('/sessions/stop',{})
            except Exception:pass
        for process in publishers:
            if process.poll() is None:
                process.terminate(); process.wait(timeout=10)


if __name__=='__main__':
    main()
