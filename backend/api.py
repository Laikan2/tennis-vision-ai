import asyncio
import csv
import io
import json
import logging
import os
import ipaddress
import socket
import time
import uuid
import threading
import re
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal
from urllib.parse import urlencode
from fastapi import FastAPI,HTTPException,WebSocket,WebSocketDisconnect,Request
from fastapi.responses import Response,FileResponse,StreamingResponse
from pydantic import BaseModel,Field,ConfigDict
import psutil
from .config import DATA,ROOT
from .database import Store
from .security import CameraSecrets,public_camera
from .multi_pipeline import PipelineManager
from .calibration import CalibrationEngine
from .analytics import StatisticsEngine
from .target import validate_polygon
from .learning import LearningManager

logging.basicConfig(level=logging.INFO,handlers=[logging.FileHandler(DATA/'logs'/'backend.log',encoding='utf-8')],
                    format='%(asctime)s %(levelname)s %(name)s %(message)s')
store=Store(); secrets=CameraSecrets(); pipeline=PipelineManager(store)
control_lock=threading.RLock()
learning=LearningManager()
learning.is_running=lambda:pipeline.session is not None
learning.operation_lock=control_lock
pipeline.learning=learning


@asynccontextmanager
async def lifespan(app):
    if os.getenv('TV_CAMERA_HOST') and not store.get('camera','env-camera'):
        store.put('camera',dict(id='env-camera',name='Hikvision · ambiente',host=os.environ['TV_CAMERA_HOST'],
                               port=int(os.getenv('TV_CAMERA_PORT','554')),path=os.getenv('TV_CAMERA_PATH','/Streaming/Channels/101'),
                               buffer=1,threshold=.65,credentialSource='environment'))
    yield
    await asyncio.to_thread(pipeline.stop)


app=FastAPI(title='Tennis Vision AI',version='0.1.0',lifespan=lifespan)
ANDROID_APP_VERSION='0.5.4'
ANDROID_APK=Path(os.getenv('TV_ANDROID_APK_PATH', str(ROOT/'mobile'/'releases'/f'Tennis-Vision-Camera-Android-v{ANDROID_APP_VERSION}.apk')))
ALLOWED_ORIGINS={'http://localhost:3000','http://127.0.0.1:3000','http://localhost:8000','http://127.0.0.1:8000'}
desktop_origin=os.getenv('TV_DESKTOP_ORIGIN','')
if re.fullmatch(r'http://127\.0\.0\.1:[0-9]{1,5}',desktop_origin) and 1<=int(desktop_origin.rsplit(':',1)[1])<=65535:
    ALLOWED_ORIGINS.add(desktop_origin)


@app.middleware('http')
async def local_guard(request:Request,call_next):
    host=request.headers.get('host','').split(':')[0]
    if host not in ('localhost','127.0.0.1','testserver'):
        return Response(status_code=403)
    origin=request.headers.get('origin')
    if origin and origin not in ALLOWED_ORIGINS:
        return Response(status_code=403)
    if request.headers.get('sec-fetch-site')=='cross-site':
        return Response(status_code=403)
    response=await call_next(request)
    response.headers['Cache-Control']='no-store'
    response.headers['X-Content-Type-Options']='nosniff'
    return response


class StrictModel(BaseModel):
    model_config=ConfigDict(allow_inf_nan=False)


class CameraInput(StrictModel):
    name:str=Field(min_length=1,max_length=100)
    host:str=Field(pattern=r'^[a-zA-Z0-9.-]+$',max_length=253)
    port:int=Field(default=554,ge=1,le=65535)
    path:str=Field(default='/Streaming/Channels/101',pattern=r'^/[a-zA-Z0-9/_-]+$')
    username:str=Field(default='',max_length=200)
    password:str=Field(default='',max_length=500)
    buffer:int=Field(default=1,ge=1,le=3)
    threshold:float=Field(default=.65,ge=.1,le=.99)
    requestedFps:int=Field(default=25,ge=1,le=240)
    codec:Literal['H.264','H.265']='H.264'
    ballDiameterMm:float=Field(default=40,ge=20,le=100)
    dominantHand:Literal['right','left']='right'
    courtOrientation:Literal['normal','opposite']='normal'


class SessionInput(StrictModel):
    name:str=Field(default='Treino',min_length=1,max_length=120)
    player1:str=Field(default='',max_length=100)
    player2:str=Field(default='',max_length=100)
    kind:Literal['Treino','Partida','Saque','Rally','Exercício']='Treino'
    cameraId:str|None=None
    cameraIds:list[str]=Field(default_factory=list,max_length=2)


class CalibrationCheck(StrictModel):
    image:tuple[float,float]
    court:tuple[float,float]


class CalibrationInput(StrictModel):
    cameraId:str
    name:str=Field(default='Quadra principal',max_length=100)
    mode:Literal['court','room']='court'
    imagePoints:list[tuple[float,float]]=Field(min_length=2,max_length=4)
    resolution:tuple[int,int]
    realDistanceM:float|None=Field(default=None,gt=.01,le=100)
    checks:list[CalibrationCheck]=Field(default_factory=list,max_length=20)


def camera_with_credentials(id):
    camera=store.get('camera',id)
    if not camera:
        raise HTTPException(404,'Câmera não encontrada.')
    if camera.get('credentialSource')=='environment':
        camera['_envUser']=os.getenv('TV_CAMERA_USER','')
        camera['_envPassword']=os.getenv('TV_CAMERA_PASSWORD','')
    return camera


def active_pipeline(camera_id=None):
    """Return one camera worker while keeping test/legacy Pipeline support."""
    if hasattr(pipeline,'get'):
        return pipeline.get(camera_id)
    if camera_id and getattr(pipeline,'camera_id',None) != camera_id:
        return None
    return pipeline


@app.get('/api/health')
def health():
    return dict(status='ok',version='0.1.0',credentialsStorageReady=secrets.cipher is not None)


@app.get('/api/cameras')
def cameras():
    return [public_camera(c) for c in store.all('camera')]


def _local_ipv4():
    """Best LAN address for a phone on the same network.

    Opening the UDP socket does not send traffic. It asks Windows which local
    interface it would use, then falls back to hostname addresses. An explicit
    override is useful on PCs with VPNs or more than one network adapter.
    """
    candidates=[]
    override=os.getenv('TV_LAN_IP','').strip()
    if override:
        candidates.append(override)
    try:
        probe=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
        try:
            probe.connect(('192.0.2.1',80))
            candidates.append(probe.getsockname()[0])
        finally:
            probe.close()
    except OSError:
        pass
    try:
        candidates.extend(item[4][0] for item in
                          socket.getaddrinfo(socket.gethostname(),None,socket.AF_INET))
    except OSError:
        pass
    for candidate in candidates:
        try:
            address=ipaddress.ip_address(candidate)
        except ValueError:
            continue
        if address.version==4 and not address.is_loopback and not address.is_unspecified:
            return str(address)
    return None


@app.get('/api/mobile-camera')
def mobile_camera():
    """Return native and browser pairing information for the local receiver."""
    address=_local_ipv4()
    display_host=address or 'IP-DO-COMPUTADOR'
    # Keep this path separate from the iPhone/Larix preset so both phones can
    # publish at the same time and be selected as camera 1 and camera 2.
    path='galaxy'
    common=dict(**{
        'video-framerate':'60','video-width':'1920','video-height':'1080',
        'video-bitrate':'12000','audio-device':'none',
    })
    moq=urlencode(common | {'video-codec':'avc3.640028','video-keyframe-interval':'30'})
    webrtc=urlencode(common | {'video-codec':'h264/90000','audio-bitrate':'32'})
    app_pairing=urlencode({
        'host':display_host,'port':'8890','path':path,'transport':'srt',
        'fps':'60','width':'1920','height':'1080','bitrate':'12000000','latency':'40',
    })
    return dict(
        available=bool(address),localIp=address,path=path,
        androidUrl=f'https://{display_host}:8892/{path}/publish?{moq}',
        webRtcUrl=f'http://{display_host}:8889/{path}/publish?{webrtc}',
        appPairingUri=f'tennisvision://pair?{app_pairing}',
        srtUrl=f'srt://{display_host}:8890?streamid=publish:{path}&latency=40&pkt_size=1316',
        androidApp=dict(version=ANDROID_APP_VERSION,available=ANDROID_APK.is_file(),
                        downloadUrl='/api/mobile-camera/android.apk',
                        sizeBytes=ANDROID_APK.stat().st_size if ANDROID_APK.is_file() else None),
        receiverUrl=f'rtsp://127.0.0.1:8554/{path}',
        cameraPreset=dict(name='Galaxy S25 Ultra · Tennis Vision',host='127.0.0.1',port=8554,
                          path=f'/{path}',requestedFps=60,codec='H.264',
                          ballDiameterMm=67,dominantHand='right',courtOrientation='normal'),
    )


@app.get('/api/mobile-camera/android.apk')
def download_android_app():
    if not ANDROID_APK.is_file():
        raise HTTPException(404,'O APK Android ainda não foi gerado neste computador.')
    return FileResponse(ANDROID_APK,media_type='application/vnd.android.package-archive',
                        filename=ANDROID_APK.name)


@app.post('/api/cameras')
def add_camera(data:CameraInput):
    try:
        obj=data.model_dump(exclude={'username','password'})
        obj.update(userEncrypted=secrets.encrypt(data.username),passwordEncrypted=secrets.encrypt(data.password))
        return public_camera(store.put('camera',obj))
    except ValueError as e:
        raise HTTPException(400,str(e))


@app.get('/api/status')
def status():
    return pipeline.snapshot() | dict(cpuPercent=psutil.cpu_percent(),ramMb=psutil.Process().memory_info().rss/1024**2,gpuPercent=None)


class BallScaleInput(StrictModel):
    ballDiameterMm:Literal[40,67]


@app.post('/api/cameras/{camera_id}/ball-scale')
def set_ball_scale(camera_id:str,data:BallScaleInput):
    with control_lock:
        camera_with_credentials(camera_id)
        saved=store.get('camera',camera_id)
        camera=store.put('camera',dict(saved,ballDiameterMm=data.ballDiameterMm))
        worker=active_pipeline(camera_id)
        if worker and worker.session:
            with worker.lock:
                worker.ball_diameter_m=data.ballDiameterMm/1000
        return public_camera(camera)


class AnalysisModeInput(StrictModel):
    analysisMode:Literal['room','court','tracking']


@app.post('/api/cameras/{camera_id}/analysis-mode')
def set_analysis_mode(camera_id:str,data:AnalysisModeInput):
    """Change impact handling without overwriting the saved target surface."""
    with control_lock:
        camera_with_credentials(camera_id)
        saved=store.get('camera',camera_id)
        camera=store.put('camera',dict(saved,analysisMode=data.analysisMode))
        worker=active_pipeline(camera_id)
        if worker and worker.session:
            with worker.lock:
                worker.environment=data.analysisMode
                # Changing from wall to floor (or to tracking only) must not
                # reuse contact evidence collected for the previous mode.
                worker.region_changed_at=time.time()
        return public_camera(camera)


@app.post('/api/sessions/start')
def start(data:SessionInput):
    with control_lock:
        return _start(data)


def _start(data:SessionInput):
    if learning.busy:
        raise HTTPException(409,'A IA está treinando. Aguarde a conclusão para iniciar a câmera.')
    if pipeline.session:
        raise HTTPException(409,'Encerre a sessão ativa antes de iniciar outra.')
    camera_ids=list(dict.fromkeys(data.cameraIds or ([data.cameraId] if data.cameraId else [])))
    if not camera_ids or len(camera_ids)>2:
        raise HTTPException(400,'Selecione uma ou duas câmeras.')
    cameras=[camera_with_credentials(camera_id) for camera_id in camera_ids]
    try:
        urls=[secrets.url(camera) for camera in cameras]
        payload=data.model_dump()
        payload.update(cameraId=camera_ids[0],cameraIds=camera_ids)
        session=dict(payload,id=str(uuid.uuid4()),createdAt=time.time(),endedAt=None)
        if hasattr(pipeline,'start_many'):
            pipeline.start_many(cameras,urls,session)
        else:
            pipeline.start(cameras[0],urls[0],session)
        return store.put('session',session)
    except Exception as e:
        # Never serialize exception details from video/model libraries (may include URLs).
        logging.getLogger('tennis.api').error('Start failed: %s',type(e).__name__)
        raise HTTPException(400,'Não foi possível iniciar. Verifique chave de credenciais e configuração do modelo.')


@app.post('/api/sessions/stop')
def stop():
    with control_lock:
        pipeline.stop()
        learning.maybe_train()
    return dict(ok=True)


@app.get('/api/sessions')
def sessions():
    return store.all('session')


class CaptureRegionInput(StrictModel):
    cameraId:str
    region:tuple[float,float,float,float]|None=None
    polygon:list[tuple[float,float]]|None=None
    analysisMode:Literal['room','court','tracking']|None=None
    targetZone:Literal['full','singles','far_half','near_half','far_left_service',
                       'far_right_service','near_left_service','near_right_service']|None=None


@app.post('/api/capture-region')
def capture_region(data:CaptureRegionInput):
    with control_lock:
        camera=camera_with_credentials(data.cameraId)
        if data.region is not None and data.polygon is not None:
            raise HTTPException(400,'Escolha um retângulo ou quatro cantos.')
        if data.polygon is not None:
            try:
                validate_polygon(data.polygon)
            except ValueError as e:
                raise HTTPException(400,str(e))
        if data.region is not None:
            x,y,right,bottom=data.region
            if not (0 <= x < right <= 1 and 0 <= y < bottom <= 1 and right-x >= .02 and bottom-y >= .02):
                raise HTTPException(400,'Selecione uma área maior dentro da imagem.')
        saved=store.get('camera',camera['id'])
        saved=dict(saved,captureRegion=data.region,capturePolygon=data.polygon)
        if data.analysisMode:
            saved['analysisMode']=data.analysisMode
        if data.targetZone:
            saved['targetZone']=data.targetZone
        store.put('camera',saved)
        worker=active_pipeline(data.cameraId)
        if worker and worker.session and worker.camera_id == data.cameraId:
            with worker.lock:
                worker.region=data.region
                worker.polygon=data.polygon
                if data.analysisMode:
                    worker.environment=data.analysisMode
                if data.targetZone:
                    worker.target_zone=data.targetZone
                worker.region_changed_at=time.time()
        return dict(captureRegion=data.region,capturePolygon=data.polygon,
                    analysisMode=saved.get('analysisMode','room'),
                    targetZone=saved.get('targetZone','full'))


@app.get('/api/events')
def events(sessionId:str|None=None):
    return store.for_session('event', sessionId) if sessionId is not None else store.all('event')


@app.get('/api/frame')
def frame(synchronized:bool=False,after:int|None=None,cameraId:str|None=None):
    worker=active_pipeline(cameraId)
    capture=worker.capture if worker else None
    if not worker or not capture or not worker.session:
        raise HTTPException(404,'Nenhum frame recente.')
    if synchronized:
        # The JPEG and overlay are committed under the same lock by the vision
        # worker. Serving that pair removes the spatial error produced by
        # combining a newer WebRTC/capture frame with older AI coordinates.
        with worker.lock:
            jpeg=worker.jpeg
            state=dict(worker.frame_state or worker.state)
        timestamp=state.get('timestamp')
        if not jpeg or timestamp is None or time.time()-timestamp > 2:
            raise HTTPException(404,'Nenhum frame analisado recente.')
        sequence=state.get('sequence')
        if after is not None and sequence == after:
            return Response(status_code=204)
        overlay={k:state.get(k) for k in ('track','persons','trajectory','speed','cameraMoving')}
        overlay['analysisSequence']=sequence
        overlay['sequence']=sequence
        overlay['resolution']=state.get('resolution') or capture.resolution or [1920,1080]
        overlay['analysisAgeMs']=0
        return Response(jpeg,media_type='image/jpeg',headers={
            'X-Frame-Metadata':json.dumps(overlay,separators=(',',':'))})
    jpeg,preview=capture.latest_preview()
    if not jpeg or preview is None or time.time()-preview.timestamp > 2:
        raise HTTPException(404,'Nenhum frame recente.')
    with worker.lock:
        state=dict(worker.state)
    overlay={k:state.get(k) for k in ('track','persons','trajectory','speed')}
    overlay['analysisSequence']=state.get('sequence')
    overlay['sequence']=preview.sequence
    overlay['resolution']=[preview.image.shape[1],preview.image.shape[0]]
    overlay['analysisAgeMs']=max(0,(preview.timestamp-state.get('timestamp',preview.timestamp))*1000)
    if after is not None and preview.sequence == after:
        return Response(status_code=204)
    track=overlay.get('track')
    age=(preview.timestamp-state.get('timestamp',preview.timestamp))
    if track and 0 < age <= .25:
        track=dict(track)
        dx=float(track.get('velocityX',0))*age
        dy=float(track.get('velocityY',0))*age
        track['centerX']=min(overlay['resolution'][0],max(0,track['centerX']+dx))
        track['centerY']=min(overlay['resolution'][1],max(0,track['centerY']+dy))
        track['x']=track.get('x',track['centerX']-track.get('width',0)/2)+dx
        track['y']=track.get('y',track['centerY']-track.get('height',0)/2)+dy
        track['previewPredicted']=True
        overlay['track']=track
        trajectory=list(overlay.get('trajectory') or [])[-49:]
        trajectory.append(dict(x=track['centerX'],y=track['centerY'],predicted=True,timestamp=preview.timestamp))
        overlay['trajectory']=trajectory
    elif age > .25:
        overlay['track']=None
        overlay['trajectory']=[]
    return Response(jpeg,media_type='image/jpeg',headers={'X-Frame-Metadata':json.dumps(overlay,separators=(',',':'))})


@app.get('/api/preview')
async def preview(request:Request,cameraId:str|None=None):
    """Native MJPEG preview sourced from capture, never from the AI frame rate."""
    worker=active_pipeline(cameraId)
    capture=worker.capture if worker else None
    if not worker or not capture or not worker.session:
        raise HTTPException(404,'Nenhum frame recente.')

    async def frames():
        previous=None
        while worker.session and not await request.is_disconnected():
            jpeg,current=capture.latest_preview()
            if (jpeg and current is not None and current.sequence!=previous and
                    time.time()-current.timestamp<=2):
                previous=current.sequence
                yield (b'--frame\r\nContent-Type: image/jpeg\r\nContent-Length: '+
                       str(len(jpeg)).encode('ascii')+b'\r\nX-Sequence: '+
                       str(current.sequence).encode('ascii')+b'\r\n\r\n'+jpeg+b'\r\n')
            else:
                await asyncio.sleep(.006)
        yield b'--frame--\r\n'

    return StreamingResponse(frames(),media_type='multipart/x-mixed-replace; boundary=frame',
                             headers={'X-Accel-Buffering':'no'})


@app.websocket('/api/live')
async def live(ws:WebSocket):
    if ws.headers.get('origin') not in ALLOWED_ORIGINS:
        await ws.close(code=1008); return
    await ws.accept()
    try:
        while True:
            await ws.send_json(pipeline.snapshot())
            await asyncio.sleep(.05)
    except (WebSocketDisconnect,RuntimeError):
        pass


@app.websocket('/api/video')
async def video(ws:WebSocket):
    """One encoded frame in flight. A slow client resumes at the latest frame."""
    if ws.headers.get('origin') not in ALLOWED_ORIGINS:
        await ws.close(code=1008);return
    await ws.accept()
    camera_id=ws.query_params.get('cameraId')
    previous=None
    receiver=asyncio.create_task(ws.receive_text())
    try:
        while True:
            worker=active_pipeline(camera_id)
            if worker is None:
                await ws.close(code=1008);return
            with worker.lock:
                state=dict(worker.frame_state or worker.state);jpeg=worker.jpeg
            sequence=state.get('sequence')
            if (not jpeg or sequence==previous or not worker.session or
                    time.time()-state.get('timestamp',0)>2):
                if receiver.done():
                    receiver.result()  # Surface a disconnected idle client.
                    await ws.close(code=1008);return
                await asyncio.sleep(.008);continue
            overlay={k:state.get(k) for k in ('track','persons','trajectory','speed','cameraMoving','timestamp')}
            overlay.update(sequence=sequence,analysisSequence=sequence,analysisAgeMs=0,
                           resolution=state.get('resolution') or worker.capture.resolution,transport='websocket')
            metadata=json.dumps(overlay,separators=(',',':')).encode('utf-8')
            await ws.send_bytes(len(metadata).to_bytes(4,'big')+metadata+jpeg)
            # The browser acknowledges only after decoding. No network buffer
            # accumulates old JPEGs, including while the surface editor is open.
            acknowledgment=await asyncio.wait_for(receiver,timeout=30)
            if acknowledgment!=str(sequence):
                await ws.close(code=1008);return
            previous=sequence
            receiver=asyncio.create_task(ws.receive_text())
    except (WebSocketDisconnect,RuntimeError,asyncio.TimeoutError):
        pass
    finally:
        receiver.cancel()
        await asyncio.gather(receiver,return_exceptions=True)


@app.get('/api/learning')
def learning_status():
    result=learning.status()
    automatic=[]
    for camera_id,worker in getattr(pipeline,'pipelines',{}).items():
        if hasattr(worker,'adaptive'):
            automatic.append(dict(cameraId=camera_id,**worker.adaptive.status()))
    result['automatic']=automatic
    return result


class LearningSettings(StrictModel):
    collect:bool
    autoTrain:bool


@app.post('/api/learning/settings')
def learning_settings(data:LearningSettings):
    return learning.configure(data.collect,data.autoTrain)


@app.post('/api/learning/capture')
def learning_capture():
    worker=active_pipeline()
    with worker.lock:
        jpeg=worker.jpeg;state=dict(worker.frame_state or worker.state);session=worker.session
    if not session or not jpeg or time.time()-state.get('timestamp',0)>2:
        raise HTTPException(409,'Inicie uma sessão e aguarde uma imagem recente.')
    item=learning.capture(jpeg,state,session['id'],manual=True)
    if item is None:raise HTTPException(409,'Revise os exemplos pendentes antes de capturar mais.')
    return item


@app.get('/api/learning/frames/{id}')
def learning_frame(id:str):
    try:return FileResponse(learning.image_path(id),media_type='image/jpeg')
    except ValueError as error:raise HTTPException(404,str(error))


class LearningReview(StrictModel):
    label:Literal[0,1]|None=None
    box:tuple[float,float,float,float]|None=None


@app.post('/api/learning/review/{id}')
def learning_review(id:str,data:LearningReview):
    try:return learning.review(id,data.label,data.box)
    except ValueError as error:raise HTTPException(400,str(error))


@app.post('/api/learning/train')
def learning_train():
    try:return learning.train()
    except ValueError as error:raise HTTPException(409,str(error))


@app.get('/api/calibrations')
def calibrations():
    return store.all('calibration')


@app.post('/api/calibrations')
def calibrate(data:CalibrationInput):
    with control_lock:
        return _calibrate(data)


def _calibrate(data:CalibrationInput):
    camera=camera_with_credentials(data.cameraId)
    worker=active_pipeline(data.cameraId)
    if worker and worker.session and worker.camera_id==data.cameraId:
        actual=worker.capture.resolution
        if actual and list(data.resolution)!=actual:
            raise HTTPException(400,'A resolução mudou. Recarregue a imagem.')
    try:
        if data.mode == 'room':
            if len(data.imagePoints) != 2 or data.realDistanceM is None:
                raise ValueError('No modo ambiente, marque dois pontos e informe a distância real entre eles.')
            profile=CalibrationEngine.fit_scale(data.imagePoints,data.realDistanceM,data.resolution,data.name)
        else:
            if len(data.imagePoints) != 4:
                raise ValueError('Na quadra, marque exatamente quatro pontos.')
            profile=CalibrationEngine.fit(data.imagePoints,[(0,0),(10.97,0),(10.97,23.77),(0,23.77)],data.resolution,data.name,
                                          [check.model_dump() for check in data.checks])
    except (ValueError,KeyError,TypeError) as e:
        raise HTTPException(400,str(e))
    profile=store.put('calibration',dict(profile,cameraId=data.cameraId))
    # Credentials copied from persistent record only, never environment secrets.
    saved=store.get('camera',data.cameraId)
    store.put('camera',dict(saved,calibrationId=profile['id']))
    if worker and worker.session and worker.camera_id==data.cameraId:
        with worker.lock:
            worker.calibration=CalibrationEngine(profile)
            worker.speed.samples.clear()
    logging.getLogger('tennis.api').info('Calibration changed')
    return profile


@app.post('/api/replays')
def mark_replay():
    worker=active_pipeline()
    if not worker.session or not worker.jpeg or worker.snapshot()['stale']:
        raise HTTPException(409,'Inicie uma sessão com frames reais.')
    try:
        id=worker.replay.trigger(time.time(),worker.session['id'])
        return dict(id=id,status='recording')
    except ValueError as e:
        raise HTTPException(409,str(e))


@app.get('/api/replays')
def replays():
    return store.all('replay')


def replay_folder(id):
    if not store.get('replay',id):
        raise HTTPException(404,'Replay não encontrado.')
    return DATA/'replays'/id


@app.get('/api/replays/{id}')
def replay_manifest(id:str):
    path=replay_folder(id)/'manifest.json'
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except OSError:
        raise HTTPException(404,'Replay indisponível: os arquivos da gravação não foram encontrados.')
    except (ValueError,UnicodeError):
        raise HTTPException(409,'Replay indisponível: a gravação está incompleta ou danificada.')


@app.get('/api/replays/{id}/frames/{index}')
def replay_frame(id:str,index:int):
    path=replay_folder(id)/f'{index}.jpg'
    if index<0 or not path.is_file():
        raise HTTPException(404,'Frame não encontrado.')
    return FileResponse(path,media_type='image/jpeg')


class AnnotationInput(StrictModel):
    reason:Literal['missed','false_positive','low_confidence','bounce_uncertain']='low_confidence'
    box:tuple[float,float,float,float]|None=None


@app.post('/api/dataset')
def dataset(data:AnnotationInput):
    worker=active_pipeline()
    with worker.lock:
        jpeg=worker.raw_jpeg
        snapshot=dict(worker.frame_state or worker.state)
    if not jpeg or worker.snapshot()['stale']:
        raise HTTPException(409,'Sem frame recente para salvar.')
    id=str(uuid.uuid4()); (DATA/'datasets'/f'{id}.jpg').write_bytes(jpeg)
    label=store.put('dataset',dict(id=id,**data.model_dump(),timestamp=snapshot.get('timestamp'),
                                  detection=snapshot.get('track'),image=f'{id}.jpg'))
    (DATA/'datasets'/f'{id}.json').write_text(json.dumps(label),encoding='utf-8')
    return label


class ValidationInput(StrictModel):
    estimated:float=Field(ge=0,le=500)
    reference:float=Field(gt=0,le=500)
    source:str=Field(min_length=3,max_length=200)


@app.post('/api/accuracy')
def add_validation(data:ValidationInput):
    return store.put('validation',data.model_dump())


@app.get('/api/accuracy')
def accuracy():
    rows=store.all('validation')
    return dict(summary=StatisticsEngine.validation(rows),measurements=rows,bounceAccuracy=None,inOutAccuracy=None)


@app.get('/api/sessions/{id}/export')
def export(id:str,format:Literal['json','csv']='json'):
    session=store.get('session',id)
    if not session:
        raise HTTPException(404,'Sessão não encontrada.')
    rows=store.for_session('event', id)
    if format=='json':
        return Response(json.dumps(dict(session=session,events=rows,statistics=StatisticsEngine.summarize(rows)),ensure_ascii=False),media_type='application/json',headers={'Content-Disposition':'attachment; filename="session.json"'})
    stream=io.StringIO(); writer=csv.writer(stream)
    writer.writerow(['timestamp','status','court_x_m','court_y_m','speed_before_kmh','speed_after_kmh','confidence','in_or_out'])
    for e in rows:
        p=e.get('courtPosition') or [None,None]
        writer.writerow([e['timestamp'],e['status'],*p,e.get('speedBefore'),e.get('speedAfter'),e.get('confidence'),e['inOrOut']])
    return Response(stream.getvalue(),media_type='text/csv',headers={'Content-Disposition':'attachment; filename="session.csv"'})
