import statistics
import threading
import time

from .analytics import StatisticsEngine
from .pipeline import Pipeline
from .target import court_sector


class PipelineManager:
    """Run up to two independent cameras and fuse their impact observations.

    Each camera keeps its own detector, tracker, selected surface and preview.
    Only impact events are combined: observations from different cameras that
    arrive in a short time window become one throw instead of two statistics.
    This is temporal fusion, not 3-D triangulation; physical triangulation
    would additionally require calibrated camera poses and synchronized clocks.
    """

    # The supplied two-camera recordings show 0.8–1.6 s of end-to-end offset
    # between the phone relay and the IP camera.  Match by time *and* court
    # position so that one physical bounce is counted once despite that delay.
    fusion_window_seconds = 1.7
    max_fusion_distance = .85

    def __init__(self, store):
        self.store = store
        self._idle = Pipeline(store)
        self.pipelines = {}
        self.camera_names = {}
        self.session = None
        self.camera_id = None
        self.learning = None
        self._fusion_lock = threading.RLock()
        self._fused_events = []

    @property
    def primary(self):
        if self.camera_id and self.camera_id in self.pipelines:
            return self.pipelines[self.camera_id]
        return next(iter(self.pipelines.values()), self._idle)

    @property
    def lock(self):
        return self.primary.lock

    def __getattr__(self, name):
        # Keeps the existing single-camera API surface available for learning,
        # replay and old clients while multi-camera aware endpoints use get().
        return getattr(self.primary, name)

    def get(self, camera_id=None):
        if camera_id:
            return self.pipelines.get(camera_id)
        return self.primary

    def start_many(self, cameras, urls, session):
        if self.session:
            raise ValueError('Uma análise já está em execução.')
        if not cameras or len(cameras) != len(urls) or len(cameras) > 2:
            raise ValueError('Selecione uma ou duas câmeras.')
        ids = [camera['id'] for camera in cameras]
        if len(set(ids)) != len(ids):
            raise ValueError('Selecione câmeras diferentes.')

        self.session = session
        self.camera_id = ids[0]
        self.camera_names = {camera['id']: camera.get('name', 'Câmera') for camera in cameras}
        self._fused_events = []
        started = []
        try:
            for index, (camera, url) in enumerate(zip(cameras, urls)):
                child = Pipeline(self.store, event_sink=self._record_event)
                # Collect learning frames from the primary view only. Running
                # duplicate auto-label capture on two views would mix unrelated
                # boxes in the same review queue and double CPU/IO pressure.
                child.learning = self.learning if index == 0 else None
                self.pipelines[camera['id']] = child
                # Register before start: it may fail after opening capture,
                # pose or publication workers that also need to be released.
                started.append(child)
                child.start(camera, url, session)
                if len(cameras) > 1 and child.capture:
                    # Two 1600 px JPEG encoders plus two inference pipelines
                    # competed for the same CPU and made both previews bursty.
                    # Detection still receives the original 1080p frame; only
                    # each half-width browser preview is reduced.
                    child.capture.preview_max_width = min(child.capture.preview_max_width, 1120)
                    if index > 0:
                        child.pose.inference_interval = max(child.pose.inference_interval, 1.7)
        except Exception as error:
            for cleanup_error in self._stop_children(started):
                error.add_note(f'Camera cleanup: {cleanup_error}')
            self._clear_session()
            raise

    @staticmethod
    def _stop_children(children):
        errors = []
        for child in children:
            try:
                child.stop(save_session=False)
            except Exception as error:
                errors.append(error)
        return errors

    def _clear_session(self):
        self.pipelines = {}
        self.camera_names = {}
        self.session = None
        self.camera_id = None
        self._fused_events = []

    def stop(self):
        session = self.session
        children = list(self.pipelines.values())
        errors = self._stop_children(children)
        try:
            if session:
                events = self._session_events(session['id'])
                self.store.put('session', dict(
                    session,
                    endedAt=time.time(),
                    statistics=StatisticsEngine.summarize(events),
                ))
        except Exception as error:
            errors.append(error)
        finally:
            self._clear_session()
        if errors:
            raise ExceptionGroup('Falha ao encerrar uma ou mais câmeras.', errors)

    @staticmethod
    def _observation(event):
        keys = (
            'cameraId', 'timestamp', 'position', 'resolution', 'targetPosition',
            'targetMode', 'targetZone', 'inOrOut', 'speedBefore', 'speedAfter', 'speedMethod',
            'confidence', 'kind', 'status', 'reason', 'courtSector', 'courtOrientation',
            'strokeType', 'strokeConfidence', 'strokeMethod', 'dominantHand',
            'ballDiameterPx','positionUncertaintyPx','decisionMarginPx','decisionReason',
            'decisionMarginsPx','possiblePositions','contactRangePx','contactEvidence','contactLocated',
            'impactEstimatedBetweenFrames','positionApproximate','trackID',
        )
        return {key: event.get(key) for key in keys if key in event}

    @staticmethod
    def _target_distance(a, b):
        if not a or not b or len(a) != 2 or len(b) != 2:
            return None
        return ((float(a[0])-float(b[0]))**2+(float(a[1])-float(b[1]))**2)**.5

    @staticmethod
    def _observation_quality(observation):
        """Lower is better; prefer a measured turn over a lost-ball guess."""
        evidence_rank = {
            'ground_measured_bounce': 0.,
            'ground_interpolated_bounce': .02,
            'measured_turn': .04,
            'observed_wall_turn': .06,
            'bracketed_turn': .10,
            'depth_turn': .18,
            'approach_loss': .55,
            'sparse_approach_loss': .85,
        }
        rank=evidence_rank.get(observation.get('contactEvidence'), .45)
        if observation.get('contactLocated') is False:
            rank+=.5
        resolution=observation.get('resolution') or []
        scale=min((float(value) for value in resolution if value),default=1.)
        uncertainty=float(observation.get('positionUncertaintyPx') or 12.)/max(1.,scale)
        if observation.get('targetPosition') is None:
            rank += 2.
        return rank+uncertainty*4.

    @staticmethod
    def _decision(observations):
        if any(item.get('inOrOut')=='REVISAR' for item in observations):
            return 'REVISAR','boundary_uncertainty'
        decisions = {item.get('inOrOut') for item in observations
                     if item.get('inOrOut') in ('IN', 'OUT')}
        if len(decisions) > 1:
            return 'REVISAR', 'conflict'
        if decisions:
            value = next(iter(decisions))
            cameras = {item.get('cameraId') for item in observations
                       if item.get('inOrOut') == value}
            return value, 'multi_camera' if len(cameras) > 1 else 'single_camera'
        return 'INCONCLUSIVO', 'insufficient_evidence'

    @staticmethod
    def _stroke_decision(observations):
        evidence=[item for item in observations
                  if item.get('strokeType') in ('forehand','backhand')]
        if not evidence:
            return None, None
        types={item['strokeType'] for item in evidence}
        if len(types)==1:
            return next(iter(types)), max(float(item.get('strokeConfidence') or 0)
                                          for item in evidence)
        ranked=sorted(evidence,key=lambda item:float(item.get('strokeConfidence') or 0),reverse=True)
        first=float(ranked[0].get('strokeConfidence') or 0)
        second=float(ranked[1].get('strokeConfidence') or 0)
        return ((ranked[0]['strokeType'],first) if first-second >= .18 else (None,max(first,second)))

    def _record_event(self, event):
        """Persist one fused throw while retaining every camera observation."""
        incoming = dict(event)
        camera_id = incoming.get('cameraId')
        timestamp = float(incoming.get('timestamp', time.time()))
        with self._fusion_lock:
            candidates = []
            for previous in reversed(self._fused_events):
                age = abs(timestamp - float(previous.get('timestamp', timestamp)))
                if age > self.fusion_window_seconds:
                    continue
                if previous.get('sessionId') != incoming.get('sessionId'):
                    continue
                if camera_id in (previous.get('cameraIds') or [previous.get('cameraId')]):
                    continue
                previous_observations=previous.get('observations') or [self._observation(previous)]
                distances=[self._target_distance(incoming.get('targetPosition'),item.get('targetPosition'))
                           for item in previous_observations]
                distances=[distance for distance in distances if distance is not None]
                distance=min(distances) if distances else None
                if distance is not None and distance > self.max_fusion_distance:
                    continue
                candidates.append((age+.45*(distance if distance is not None else .5),previous))
            match=min(candidates,key=lambda item:item[0])[1] if candidates else None

            if match is None:
                incoming['cameraIds'] = [camera_id] if camera_id else []
                incoming['observations'] = [self._observation(incoming)]
                incoming['observationCount'] = 1
                incoming['throwNumber'] = len(self._fused_events) + 1
                incoming['decisionAgreement'] = 'single_camera'
                incoming['varAlert'] = incoming.get('inOrOut') == 'OUT'
                saved = self.store.put('event', incoming)
                self._fused_events.append(saved)
                return saved

            observations = list(match.get('observations') or [self._observation(match)])
            observations.append(self._observation(incoming))
            camera_ids = list(dict.fromkeys(
                [item.get('cameraId') for item in observations if item.get('cameraId')]
            ))
            decision, agreement = self._decision(observations)
            target_positions=[item['targetPosition'] for item in observations
                              if item.get('targetPosition') is not None and
                              len(item['targetPosition']) == 2]
            located=[item for item in observations if item.get('targetPosition') is not None and
                     len(item['targetPosition']) == 2]
            best_observation=min(located,key=self._observation_quality) if located else None
            fused_position=([float(best_observation['targetPosition'][0]),
                             float(best_observation['targetPosition'][1])]
                            if best_observation else None)
            spatial_spread=None
            if len(target_positions)>1:
                spatial_spread=max(
                    (((float(a[0])-float(b[0]))**2+
                      (float(a[1])-float(b[1]))**2)**.5
                     for index,a in enumerate(target_positions)
                     for b in target_positions[index+1:]),
                    default=0.)
            if spatial_spread is not None and spatial_spread > .20:
                decision,agreement='REVISAR','spatial_conflict'
            stroke_type,stroke_confidence=self._stroke_decision(observations)
            speeds = [float(item['speedBefore']) for item in observations
                      if item.get('speedBefore') is not None]
            confidences = [float(item['confidence']) for item in observations
                           if item.get('confidence') is not None]
            match.update(
                cameraIds=camera_ids,
                observations=observations,
                observationCount=len(observations),
                multiCamera=len(camera_ids) > 1,
                inOrOut=decision,
                targetDecision=decision,
                decisionAgreement=agreement,
                varAlert=decision == 'OUT',
                speedBefore=statistics.median(speeds) if speeds else None,
                confidence=max(confidences) if confidences else None,
                targetPosition=fused_position,
                positionSourceCameraId=(best_observation or {}).get('cameraId'),
                courtSector=court_sector(fused_position) if fused_position is not None else None,
                spatialSpread=spatial_spread,
                strokeType=stroke_type,
                strokeConfidence=stroke_confidence,
                strokeMethod='multi_camera_pose_vote' if stroke_type else None,
                updatedAt=time.time(),
            )
            saved = self.store.put('event', match)
            index = self._fused_events.index(match)
            self._fused_events[index] = saved
            return saved

    def _session_events(self, session_id):
        return [event for event in self.store.all('event')
                if event.get('sessionId') == session_id]

    def snapshot(self):
        if not self.pipelines:
            state = self._idle.snapshot()
            state.update(cameraFeeds=[], cameraCount=0, onlineCameras=0)
            return state

        feeds = []
        for camera_id, child in self.pipelines.items():
            feed = child.snapshot()
            feed.update(cameraId=camera_id,
                        cameraName=self.camera_names.get(camera_id, 'Câmera'))
            feeds.append(feed)

        primary = dict(next((feed for feed in feeds if feed['cameraId'] == self.camera_id), feeds[0]))
        events = self._session_events(self.session['id']) if self.session else []
        latest = max(events, key=lambda item: (
            float(item.get('timestamp', 0)), float(item.get('updatedAt', 0))
        ), default=None)
        numeric = lambda key: [feed.get(key) for feed in feeds if feed.get(key) is not None]
        errors = list(dict.fromkeys(feed.get('error') for feed in feeds if feed.get('error')))
        primary.update(
            session=self.session,
            running=self.session is not None,
            cameraFeeds=feeds,
            cameraCount=len(feeds),
            onlineCameras=sum(feed.get('cameraStatus') == 'online' for feed in feeds),
            cameraStatus=('online' if any(feed.get('cameraStatus') == 'online' for feed in feeds)
                          else 'connecting' if any(feed.get('cameraStatus') == 'connecting' for feed in feeds)
                          else 'offline'),
            stale=all(feed.get('stale', True) for feed in feeds),
            framesReceived=sum(feed.get('framesReceived', 0) for feed in feeds),
            droppedFrames=sum(feed.get('droppedFrames', 0) for feed in feeds),
            framesProcessed=sum(feed.get('framesProcessed', 0) for feed in feeds),
            detectedFrames=sum(feed.get('detectedFrames', 0) for feed in feeds),
            captureFpsTotal=sum(numeric('cameraFps')) if numeric('cameraFps') else None,
            processingFpsTotal=sum(numeric('processingFps')) if numeric('processingFps') else None,
            statistics=StatisticsEngine.summarize(events),
            lastEvent=latest,
            error=' · '.join(errors) if errors else None,
            fusionWindowMs=int(self.fusion_window_seconds * 1000),
        )
        return primary
