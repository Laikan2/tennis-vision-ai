"""Original-video analysis, isolated from live capture and existing profiles."""
import asyncio
import logging
import math
import threading
import time
import uuid
import cv2
from fastapi import HTTPException, Request
from fastapi.responses import FileResponse
from .pipeline import Pipeline
from .analytics import StatisticsEngine


class Recordings:
    max_bytes=1024**3
    def __init__(self, app, store, live, learning, folder, control_lock):
        self.store,self.live,self.learning,self.folder,self.control_lock=store,live,learning,folder,control_lock
        folder.mkdir(exist_ok=True)
        self.worker=None
        self.current=None
        self.cancel=threading.Event()
        # A terminated application must not leave old work shown as running.
        for job in store.all('recording'):
            if job.get('status')=='running':store.put('recording',dict(job,status='interrupted',error='A análise foi interrompida. O vídeo original foi preservado.'))

        @app.get('/api/recordings')
        def list_recordings():return store.all('recording')[:100]

        @app.post('/api/recordings')
        async def upload(request:Request,cameraId:str,name:str='Gravação'):
            if not store.get('camera',cameraId):raise HTTPException(404,'Escolha uma câmera salva para usar suas áreas e escala.')
            if len(name)>150:raise HTTPException(400,'Nome muito longo.')
            id=str(uuid.uuid4());path=folder/f'{id}.mp4';size=0
            try:
                with path.open('xb') as output:
                    async for chunk in request.stream():
                        size+=len(chunk)
                        if size>self.max_bytes:raise ValueError('O vídeo precisa ter no máximo 1 GB.')
                        await asyncio.to_thread(output.write,chunk)
                metadata=await asyncio.to_thread(self.metadata,path)
                return store.put('recording',dict(id=id,cameraId=cameraId,name=name,sizeBytes=size,status='uploaded',**metadata))
            except (ValueError,OSError) as e:
                path.unlink(missing_ok=True)
                raise HTTPException(400,str(e) if isinstance(e,ValueError) else 'Não foi possível salvar o vídeo.')
            except BaseException:
                path.unlink(missing_ok=True);raise

        @app.post('/api/recordings/{id}/analyze')
        def analyze(id:str):
            with control_lock:
                if self.busy or live.session or learning.busy:
                    raise HTTPException(409,'Encerre a sessão ou espere a análise/treinamento terminar para analisar o vídeo sem disputar recursos.')
                job=self.get(id)
                self.cancel.clear();self.current=id
                job=store.put('recording',dict(job,status='running',framesProcessed=0,error=None,startedAt=time.time()))
                self.worker=threading.Thread(target=self._analyze,args=(job,),daemon=True,name='original-recording-analysis')
                self.worker.start()
                return job

        @app.post('/api/recordings/{id}/cancel')
        def cancel(id:str):
            with control_lock:
                self.get(id)
                if self.current==id:self.cancel.set()
                return dict(cancelRequested=self.current==id)

        @app.get('/api/recordings/{id}/frame')
        def preview(id:str):
            self.get(id);cap=cv2.VideoCapture(str(folder/f'{id}.mp4'))
            try:ok,image=cap.read()
            finally:cap.release()
            if not ok:raise HTTPException(404,'Imagem indisponível.')
            from fastapi.responses import Response
            return Response(cv2.imencode('.jpg',image)[1].tobytes(),media_type='image/jpeg')

        @app.get('/api/recordings/{id}/original')
        def original(id:str):
            self.get(id)
            return FileResponse(folder/f'{id}.mp4',media_type='video/mp4',filename=f'TennisVision-{id}.mp4')

    @property
    def busy(self):return bool(self.worker and self.worker.is_alive())

    def get(self,id):
        try:valid=str(uuid.UUID(id))==id
        except (ValueError,TypeError):valid=False
        job=self.store.get('recording',id)
        if not valid or not job or not (self.folder/f'{id}.mp4').is_file():
            raise HTTPException(404,'Gravação não encontrada.')
        return job

    def stop(self):
        self.cancel.set()
        if self.worker:self.worker.join(timeout=30)

    @staticmethod
    def metadata(path):
        cap=cv2.VideoCapture(str(path))
        try:
            ok,image=cap.read();fps=float(cap.get(cv2.CAP_PROP_FPS));frames=int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
            if not ok or not math.isfinite(fps) or not (1<=fps<=1000) or not (1<=frames<=150000) or frames/fps>600:
                raise ValueError('Use um vídeo decodificável de até 10 minutos, com FPS e timestamps disponíveis.')
            return dict(sourceFps=fps,sourceFrames=frames,durationSeconds=frames/fps,resolution=[image.shape[1],image.shape[0]])
        finally:cap.release()

    def _analyze(self,job):
        child=Pipeline(self.store)
        session=None
        try:
            camera=self.store.get('camera',job['cameraId'])
            profile=self.store.get('calibration',camera.get('calibrationId',''))
            session=self.store.put('session',dict(name=f"Vídeo · {job['name']}",kind='Treino',cameraId=camera['id'],cameraIds=[camera['id']],
                                                source='recording',recordingId=job['id'],sourceEpoch=job['createdAt']-job['durationSeconds'],endedAt=None))
            job.update(sessionId=session['id'],calibrationWarning='A resolução difere da calibração. A escala antiga não será usada.' if profile and profile.get('resolution')!=job['resolution'] else None)
            child.start(dict(camera,_fileAnalysis=True,buffer=2),str(self.folder/f"{job['id']}.mp4"),session)
            last_progress=0.;eof_at=None
            while not self.cancel.wait(.1):
                if child.capture.failed or child.error:raise ValueError(child.error or child.capture.error)
                if child.capture.eof:
                    eof_at=eof_at or time.monotonic()
                    if child.frame_state.get('sequence',0)>=child.capture.frames:break
                    if time.monotonic()-eof_at>30:raise ValueError('A análise não conseguiu concluir todos os quadros.')
                if time.monotonic()-last_progress>=1:
                    job.update(framesProcessed=child.processed,progress=min(100,100*child.processed/max(1,job['sourceFrames'])))
                    self.store.put('recording',job);last_progress=time.monotonic()
            actual_frames=child.capture.frames
            processed=child.processed
            child.stop()
            rows=self.store.for_session('event',session['id'])
            job.update(status='cancelled' if self.cancel.is_set() else 'complete',framesProcessed=processed,
                       decodedFrames=actual_frames,statistics=StatisticsEngine.summarize(rows),progress=100 if not self.cancel.is_set() else job.get('progress',0),endedAt=time.time())
            if not self.cancel.is_set() and actual_frames<job['sourceFrames']:
                job['error']='O decodificador terminou antes do total informado pelo arquivo. Confira se o vídeo está completo.'
        except Exception:
            logging.getLogger('tennis.recordings').exception('Recording analysis failed')
            try:child.stop()
            except Exception:logging.getLogger('tennis.recordings').exception('Recording cleanup failed')
            job.update(status='error',error='A análise foi interrompida. O vídeo e os resultados parciais foram preservados.',endedAt=time.time())
        finally:
            if session:
                self.store.put('session',dict(session,endedAt=time.time(),statistics=StatisticsEngine.summarize(self.store.for_session('event',session['id']))))
            self.store.put('recording',job);self.current=None
