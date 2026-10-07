"""Explicit, auditable human review using the actual recorded image."""
import json
import math
import statistics
import time
from contextlib import nullcontext
from pathlib import Path
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from typing import Literal
from .calibration import CalibrationEngine
from .target import map_target, orient_target, court_sector
from .analytics import StatisticsEngine


class ReviewInput(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    replayId: str
    frameIndex: int = Field(ge=0)
    reason: Literal['decision_error', 'impact_position', 'false_impact', 'ball_missed', 'false_ball']
    decision: Literal['IN', 'OUT', 'REVISAR', 'IGNORAR'] | None = None
    position: tuple[float, float] | None = None
    box: tuple[float, float, float, float] | None = None


class SyncAnchor(BaseModel):
    referenceReplayId: str
    referenceFrame: int = Field(ge=0)
    secondaryReplayId: str
    secondaryFrame: int = Field(ge=0)


class SyncInput(BaseModel):
    referenceCameraId: str
    secondaryCameraId: str
    anchors: list[SyncAnchor] = Field(min_length=1, max_length=10)


def replay_evidence(store, folder, replay_id, index):
    replay = store.get('replay', replay_id)
    if not replay or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in replay_id):
        raise ValueError('Replay não encontrado.')
    path = Path(folder) / replay_id
    try:
        frames = json.loads((path/'manifest.json').read_text(encoding='utf-8'))
        frame = next(f for f in frames if f['index'] == index)
        if not math.isfinite(float(frame['timestamp'])):
            raise ValueError('Timestamp inválido.')
    except (OSError, ValueError, StopIteration, KeyError, TypeError):
        raise ValueError('Quadro indisponível. Escolha um quadro do replay concluído.')
    image=path/f'{index}.jpg'
    if not image.is_file():image=path/f'{index:05d}.jpg'
    if not image.is_file():raise ValueError('A imagem deste quadro não está mais disponível.')
    return replay, frame, image


def synchronization(store, folder, data):
    if data.referenceCameraId == data.secondaryCameraId or any(not store.get('camera', c) for c in (data.referenceCameraId, data.secondaryCameraId)):
        raise ValueError('Selecione duas câmeras diferentes e salvas.')
    offsets = []
    if len({(a.referenceReplayId,a.referenceFrame,a.secondaryReplayId,a.secondaryFrame) for a in data.anchors}) != len(data.anchors):
        raise ValueError('Use instantes diferentes para cada referência.')
    resolutions = []
    for anchor in data.anchors:
        a, fa, _ = replay_evidence(store, folder, anchor.referenceReplayId, anchor.referenceFrame)
        b, fb, _ = replay_evidence(store, folder, anchor.secondaryReplayId, anchor.secondaryFrame)
        if a.get('cameraId') != data.referenceCameraId or b.get('cameraId') != data.secondaryCameraId or a['sessionId'] != b['sessionId']:
            raise ValueError('Os quadros precisam ser da mesma sessão e das duas câmeras escolhidas.')
        offsets.append(float(fb['timestamp']) - float(fa['timestamp']))
        # Temporal resolution of the actual replay, never the requested camera FPS.
        for replay, frame in ((a, fa), (b, fb)):
            manifest = json.loads((Path(folder)/replay['id']/'manifest.json').read_text(encoding='utf-8'))
            gaps = [float(y['timestamp'])-float(x['timestamp']) for x, y in zip(manifest, manifest[1:]) if float(y['timestamp']) > float(x['timestamp'])]
            resolutions.append(statistics.median(gaps) if gaps else .1)
    offset = statistics.median(offsets)
    spread = max(offsets)-min(offsets)
    if abs(offset) > 5 or spread > .35:
        raise ValueError('Os pontos não correspondem ao mesmo instante ou a diferença varia demais. Repita com um gesto visível nas duas câmeras.')
    ids = sorted([data.referenceCameraId, data.secondaryCameraId])
    import hashlib
    return dict(id=hashlib.sha256('|'.join(ids).encode()).hexdigest()[:24],
                referenceCameraId=data.referenceCameraId, secondaryCameraId=data.secondaryCameraId,
                offsetSeconds=offset, jitterMs=spread*1000, frameUncertaintyMs=max(resolutions)*1000,
                anchors=[a.model_dump() for a in data.anchors], method='visual_reference',
                quality='provisória · use três gestos' if len(offsets)<3 else 'referências consistentes',
                updatedAt=time.time())


def review_event(store, learning, folder, event_id, data):
    event = store.get('event', event_id)
    if not event:
        raise ValueError('Impacto não encontrado.')
    replay, frame, image_path = replay_evidence(store, folder, data.replayId, data.frameIndex)
    if replay.get('sessionId') != event.get('sessionId') or event_id not in replay.get('eventIds', []):
        raise ValueError('Escolha um replay associado a este impacto.')
    resolution = frame.get('overlay', {}).get('resolution')
    if not resolution or len(resolution) != 2:
        raise ValueError('A imagem não tem uma resolução válida.')
    if data.position is not None and not (0 <= data.position[0] < resolution[0] and 0 <= data.position[1] < resolution[1]):
        raise ValueError('Marque o contato dentro da imagem.')
    if data.reason in ('ball_missed', 'false_ball'):
        if data.box is None:
            raise ValueError('Desenhe uma caixa justa sobre a bola ou objeto confundido.')
        # Let the existing reviewed dataset validate the crop, and retain the
        # original session split to prevent adjacent frames leaking into eval.
        import cv2
        image = cv2.imread(str(image_path))
        if image is None:
            raise ValueError('Imagem indisponível.')
        x, y, w, h = data.box
        if not (x>=0 and y>=0 and w>=2 and h>=2 and x+w<=image.shape[1]+.1 and y+h<=image.shape[0]+.1):
            raise ValueError('A caixa precisa ficar dentro da imagem e ter pelo menos 2 pixels por lado.')
        item = learning.capture(image_path.read_bytes(), dict(frame.get('overlay', {}), timestamp=frame['timestamp']), event['sessionId'], manual=True)
        if item is None:
            raise ValueError('A fila de exemplos está cheia. Revise os exemplos pendentes primeiro.')
        learning.review(item['id'], 1 if data.reason == 'ball_missed' else 0, data.box)
    elif data.reason == 'impact_position' and data.position is None:
        raise ValueError('Clique no local do contato no quadro selecionado.')
    elif data.reason == 'decision_error' and data.decision is None:
        raise ValueError('Escolha a decisão corrigida.')
    before = dict(event)
    if data.reason not in ('ball_missed', 'false_ball'):
        if data.reason == 'false_impact' or data.decision == 'IGNORAR':
            event.update(status='rejected', varAlert=False, inOrOut='IGNORADO', targetDecision='IGNORADO')
        else:
            if data.decision:
                event.update(inOrOut=data.decision, targetDecision=data.decision,
                             status='confirmed' if data.decision in ('IN', 'OUT') else 'candidate')
            if data.position:
                camera = store.get('camera', replay.get('cameraId')) or {}
                profile = store.get('calibration', camera.get('calibrationId', ''))
                target = orient_target(map_target(data.position[0]/resolution[0], data.position[1]/resolution[1],
                                                 camera.get('captureRegion'), camera.get('capturePolygon')),
                                       camera.get('courtOrientation', 'normal'))
                event.update(position=list(data.position), resolution=resolution, cameraId=replay.get('cameraId'),
                             courtPosition=CalibrationEngine(profile).map(*data.position, resolution),
                             targetPosition=target, courtSector=court_sector(target), positionApproximate=False)
                # Old speed was estimated at the original contact, not at the
                # human-corrected point. Invalidate it instead of moving it.
                event.update(speedBefore=None, speedAfter=None, speedMethod=None)
                if data.decision is None:
                    event.update(inOrOut='REVISAR',targetDecision='REVISAR',status='candidate')
            event.update(varAlert=False, contactLocated=True)
        event.update(manualReview=dict(replayId=replay['id'], frameIndex=data.frameIndex, cameraId=replay.get('cameraId'),
                                      reason=data.reason, timestamp=time.time(), reviewer='user'),
                     decisionAgreement='human_review',updatedAt=time.time())
        store.put('event', event)
        session=store.get('session',event['sessionId'])
        if session and session.get('endedAt'):
            stats=StatisticsEngine.summarize(store.for_session('event',event['sessionId']))
            store.put('session',dict(session,statistics=stats))
            recording=store.get('recording',session.get('recordingId',''))
            if recording:store.put('recording',dict(recording,statistics=stats))
    feedback=store.put('feedback', dict(eventId=event_id, sessionId=event['sessionId'], cameraId=replay.get('cameraId'),
                                      input=data.model_dump(), previousEvent=before, reviewer='user'))
    return dict(event=event, feedbackId=feedback['id'])


def install_review_routes(app, store, pipeline, learning, folder, control_lock):
    router=APIRouter()

    @router.get('/api/events/{event_id}/replays')
    def event_replays(event_id: str):
        if not store.get('event', event_id):
            raise HTTPException(404, 'Impacto não encontrado.')
        ready=[r for r in store.all('replay') if event_id in r.get('eventIds', [])]
        pending=[]
        for worker in getattr(pipeline, 'pipelines', {}).values():
            with worker.replay.lock:
                pending += [dict(id=j['id'],cameraId=j.get('cameraId')) for j in worker.replay.pending if event_id in j.get('eventIds', [])]
        return dict(ready=ready, pending=pending)

    @router.post('/api/events/{event_id}/review')
    def review(event_id: str, data: ReviewInput):
        with control_lock, getattr(pipeline, '_fusion_lock', nullcontext()):
            try:
                return review_event(store, learning, folder, event_id, data)
            except ValueError as e:
                raise HTTPException(400, str(e))

    @router.get('/api/camera-sync')
    def list_sync():
        return store.all('camera_sync')

    @router.post('/api/camera-sync')
    def save_sync(data: SyncInput):
        with control_lock:
            try:
                return store.put('camera_sync', synchronization(store, folder, data))
            except ValueError as e:
                raise HTTPException(400, str(e))

    app.include_router(router)
