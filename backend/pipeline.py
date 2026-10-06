import logging
import math
import threading
import time
import cv2
from collections import deque
from .camera import CameraCapture,FrameBuffer
from .detection import BallDetector
from .tracking import BallTracker
from .calibration import CalibrationEngine
from .speed import SpeedEstimator, measured_diameter
from .bounce import BounceDetector
from .court import CourtDetector
from .trajectory import TrajectoryEstimator
from .analytics import StatisticsEngine
from .replay import ReplayManager
from .pose import PoseEstimator
from .target import map_target, orient_target, classify_impact, court_sector
from .adaptive import AdaptiveBallSignature
from .stroke import StrokeClassifier
from .frame_publisher import LatestFramePublisher

log=logging.getLogger('tennis.pipeline')


class Pipeline:
    def __init__(self,store,event_sink=None):
        self.store=store
        self.lock=threading.RLock()
        self.stop_event=threading.Event()
        self.capture=None
        self.thread=None
        self.session=None
        self.jpeg=None
        self.raw_jpeg=None
        self.state={}
        self.frame_state={}
        self.publisher=None
        self.replay=ReplayManager(store)
        self.calibration=CalibrationEngine()
        self.error=None
        self.learning=None
        self.event_sink=event_sink

    def start(self,camera,url,session):
        if self.thread and self.thread.is_alive():
            raise ValueError('Uma análise já está em execução.')
        # Avoid OpenCV using every core while the independent pose worker and
        # video decoder need CPU time too. Does not throttle received frames.
        cv2.setNumThreads(2)
        self.buffer=FrameBuffer(camera.get('buffer',1))
        self.pose=PoseEstimator()
        self.adaptive=AdaptiveBallSignature(camera['id'])
        self.detector=BallDetector(camera.get('threshold',.65),external_pose=self.pose.available,
                                   adaptive=self.adaptive)
        # Automatic mode learns a camera-local appearance signature from a
        # coherent moving trajectory. The legacy green-only gate remains
        # available for saved profiles that explicitly request it.
        self.green_ball_mode=camera.get('ballColor','auto') == 'tennis_green'
        self.tracker=BallTracker(green_only=self.green_ball_mode)
        self.ball_diameter_m=float(camera.get('ballDiameterMm',40))/1000
        # Used only for an explicitly approximate monocular depth estimate.
        # The default is close to the main wide cameras used in the supplied
        # phone recordings and can be overridden in a saved camera profile.
        self.horizontal_fov_deg=max(35.,min(120.,float(camera.get('horizontalFovDegrees',70))))
        self.speed=SpeedEstimator(self.ball_diameter_m)
        self.stroke=StrokeClassifier(camera.get('dominantHand','right'))
        self.bounce=BounceDetector()
        self.court=CourtDetector()
        self.trajectory=TrajectoryEstimator()
        self.capture=CameraCapture(url,self.buffer)
        self.session=session
        self.camera_id=camera['id']
        self.region=camera.get('captureRegion')
        self.polygon=camera.get('capturePolygon')
        self.target_zone=camera.get('targetZone','full')
        self.court_orientation=camera.get('courtOrientation','normal')
        self.environment=camera.get('analysisMode')
        self.region_changed_at=time.time()
        self.applied_region=None
        profile=self.store.get('calibration',camera.get('calibrationId',''))
        self.calibration=CalibrationEngine(profile)
        self.stop_event.clear()
        self.events=[]; self.processed=0; self.detected_frames=0; self.error=None
        self.statistics=StatisticsEngine.summarize([])
        self.state={}; self.frame_state={}; self.jpeg=None; self.raw_jpeg=None
        self.replay=ReplayManager(self.store)
        self.publisher=LatestFramePublisher(self._publish_frame)
        self.capture.start()
        self.pose.start()
        self.thread=threading.Thread(target=self._run,daemon=True,name='vision-worker')
        self.thread.start()

    def stop(self,save_session=True):
        self.stop_event.set()
        if self.capture:
            self.capture.stop()
        if self.thread:
            self.thread.join(timeout=15)
        if hasattr(self,'pose'):
            self.pose.stop()
        if self.publisher:
            self.publisher.stop()
            self.publisher=None
        self.replay.flush()
        if self.session and save_session:
            self.store.put('session',dict(self.session,endedAt=time.time(),statistics=StatisticsEngine.summarize(getattr(self,'events',[]))))
        self.session=None
        with self.lock:
            self.jpeg=None; self.raw_jpeg=None; self.state={}; self.frame_state={}

    def snapshot(self):
        with self.lock:
            state=dict(self.state)
            state['jpegEncodingMs']=self.frame_state.get('jpegEncodingMs')
        stale=not self.capture or self.capture.last_received is None or time.time()-self.capture.last_received>2 or not self.session
        analysis_age = max(0., time.time()-state['timestamp']) if state.get('timestamp') else None
        analysis_stale = analysis_age is None or analysis_age > .5
        if stale or analysis_stale:
            state.update(track=None,persons=[],trajectory=[],speed=None,courtPosition=None)
        state.update(running=self.session is not None,session=self.session,error=self.error,
                     cameraStatus=self.capture.status if self.capture and self.session else 'offline',
                     cameraFps=self.capture.fps if self.capture and not stale else None,
                     previewFps=self.capture.preview_fps if self.capture and not stale else None,
                     previewMaxFps=self.capture.preview_max_fps if self.capture else None,
                     resolution=self.capture.resolution if self.capture else None,
                     framesReceived=self.capture.frames if self.capture else 0,
                     droppedFrames=self.buffer.dropped if hasattr(self,'buffer') else 0,
                     networkLatencyMs=None,sourceTimestampAvailable=False,
                     sourceTimingAvailable=bool(self.capture and self.capture.source_timing_available),
                     captureGapP95Ms=self.capture.arrival_gap_p95_ms if self.capture else None,
                     previewActive=bool(self.capture and time.monotonic()<self.capture.preview_requested_until),
                      detector=self.detector.status if hasattr(self,'detector') else 'não iniciado',
                      appearanceVersion=self.detector.appearance.version if hasattr(self,'detector') else None,
                      detectionStageMs=self.detector.stage_ms if hasattr(self,'detector') else None,
                      opencvThreads=cv2.getNumThreads(),
                      autoLearning=self.adaptive.status() if hasattr(self,'adaptive') else None,
                     device=self.detector.device if hasattr(self,'detector') else None,
                     ballDiameterMm=getattr(self,'ball_diameter_m',.04)*1000,
                     analysisMode=getattr(self,'environment',None) or (self.calibration.profile or {}).get('mode','room'),
                     poseStatus=self.pose.status if hasattr(self,'pose') else 'aguardando sessão',
                     calibration=self.calibration.profile,stale=stale,
                     analysisStale=analysis_stale,analysisAgeMs=analysis_age*1000 if analysis_age is not None else None,
                      captureRegion=getattr(self,'region',None),capturePolygon=getattr(self,'polygon',None),
                      targetZone=getattr(self,'target_zone','full'),
                      courtOrientation=getattr(self,'court_orientation','normal'),
                      regionChangedAt=getattr(self,'region_changed_at',0))
        return state

    def _enrich_track(self, track, resolution):
        track['frameWidth'],track['frameHeight']=resolution
        track['planePosition']=self.calibration.map(
            track.get('measuredX',track['centerX']),track.get('measuredY',track['centerY']),resolution)
        diameter=measured_diameter(track)
        track['ballDiameterPx']=diameter
        track['metersPerPixel']=self.ball_diameter_m/diameter if diameter>=3 else None
        if diameter>=3:
            focal_px=resolution[0]/(2*math.tan(math.radians(self.horizontal_fov_deg)/2))
            track['depthMeters']=focal_px*self.ball_diameter_m/diameter
            track['depthApproximate']=True
            track['depthReliable']=bool(diameter>=6 and diameter/max(track['width'],track['height'])>=.33)
        # A broad lower-image guard excludes obvious overhead turns. The
        # target may relax it for a distant court, but cannot tighten it and
        # silently erase real contacts outside the scoring rectangle.
        normalized_y=track.get('measuredY',track['centerY'])/max(1,resolution[1])
        radius_margin=diameter*.55/max(1,resolution[1])+.006
        if self.polygon:
            ys=[float(point[1]) for point in self.polygon]
            threshold=min(ys)-radius_margin
        elif self.region:
            top,bottom=float(self.region[1]),float(self.region[3])
            threshold=top-radius_margin
        else:
            threshold=.55
        # The scoring area must not crop physical contact evidence. Floor
        # outside its far edge is still floor. This is only a broad image
        # guard; descent/return and body separation establish a candidate.
        threshold=max(0.,min(.55,threshold))
        track['groundSurfaceThresholdY']=threshold
        track['groundSurfaceEligible']=bool(normalized_y>=threshold)
        return track

    def _run(self):
        processing_times=deque(maxlen=90)
        while not self.stop_event.is_set():
            frame=self.buffer.pop()
            if frame is None:
                continue
            try:
                started=time.perf_counter()
                with self.lock:
                    region_key=(tuple(self.region) if self.region else None,
                                tuple(map(tuple,self.polygon)) if self.polygon else None,
                                self.target_zone,self.region_changed_at,self.ball_diameter_m,self.environment)
                    if region_key != self.applied_region:
                        self.detector.region=self.region
                        self.detector.polygon=self.polygon
                        self.detector.target_zone=self.target_zone
                        # Editing a target must not discard the ball track.
                        # Only impact evidence needs to restart for the target.
                        self.bounce=BounceDetector()
                        self.speed=SpeedEstimator(self.ball_diameter_m)
                        self.applied_region=region_key
                persons=self.pose.update(frame.image,frame.timestamp)
                self.detector.prior=self.tracker.hint(frame.timestamp,frame.source_time,frame.source_clock)
                detections=self.detector.detect(frame.image,frame.timestamp,persons=persons)
                for candidate in detections:
                    candidate.update(sourceTime=frame.source_time,sourceClock=frame.source_clock)
                track=self.tracker.update(detections,frame.timestamp,frame.source_time,frame.source_clock)
                persons = persons or list(getattr(self.detector, 'persons', []))
                resolution=[frame.image.shape[1],frame.image.shape[0]]
                position=self.calibration.map(track['centerX'],track['centerY'],resolution) if track else None
                if track:
                    self._enrich_track(track,resolution)
                    self.adaptive.learn(frame.image,track,self.tracker.history,
                                        confirmed=self.tracker.confirmed)
                stroke_info=self.stroke.update(track,persons)
                speed=self.speed.update(track,position)
                mode = self.environment or (self.calibration.profile or {}).get('mode', 'room')
                if (mode != 'tracking' and track and not track['predicted'] and
                        self.bounce.track_id!=track['trackID']):
                    # Confirmation needs two/three frames, but those observed
                    # approach samples must still reach the contact detector.
                    history=[p for p in self.tracker.history if not p.get('predicted') and
                             p['trackID']==track['trackID'] and p['timestamp']<track['timestamp']]
                    for prior in history[-2:]:
                        self.bounce.update(self._enrich_track(dict(prior),resolution),None,mode=mode)
                # Tracking-only keeps the box, trajectory and live speed but
                # deliberately produces no impact, statistics or OUT audio.
                event=(None if mode == 'tracking' else
                       self.bounce.update(track,position,mode=mode,timestamp=frame.timestamp))
                if event:
                    ex,ey=event['position'][0]/resolution[0],event['position'][1]/resolution[1]
                    target_position=map_target(ex,ey,self.region,self.polygon)
                    if mode == 'court':
                        target_position=orient_target(target_position,self.court_orientation)
                    event['targetPosition']=target_position
                    event.update(classify_impact(event['position'],resolution,self.region,self.polygon,
                        self.target_zone,self.court_orientation if mode=='court' else 'normal',
                        event.get('ballDiameterPx',0),event.get('positionUncertaintyPx',2),
                        contact_located=event.get('contactLocated',True),
                        possible_positions=event.get('possiblePositions')))
                    event['targetMode']='surface' if self.region or self.polygon else 'image'
                    event['analysisMode']=mode
                    event['targetZone']=self.target_zone
                    event['courtOrientation']=self.court_orientation
                    event['courtSector']=court_sector(target_position) if mode == 'court' else None
                    if stroke_info:
                        event.update(stroke_info)
                    event['courtPosition']=self.calibration.map(*event['position'],resolution)
                    if event['courtPosition'] is None and mode == 'room' and event.get('metersPerPixel'):
                        # Without a plane calibration, the ball diameter can
                        # estimate speed but not the room's dimensions. Map the
                        # image into the visible room diagram so every impact
                        # remains reviewable instead of falling outside it.
                        event['courtPosition']=[(event['position'][0]/resolution[0]-.5)*5.8,
                                                (event['position'][1]/resolution[1]-.5)*3.8]
                        event['positionApproximate']=True
                        event['positionMethod']='image_normalized'
                    event['sessionId']=self.session['id']
                    event['cameraId']=self.camera_id
                    event['resolution']=resolution
                    event['confidence']=((track or {}).get('confidence') if track and track.get('source') == 'model' else None)
                    event['ballColor']='tennis_green' if track and track.get('tennisColor') else None
                    event=(self.event_sink(event) if self.event_sink else
                           self.store.put('event',event))
                    self.events.append(event)
                    self.statistics=StatisticsEngine.summarize(self.events)
                    log.info('Impact candidate: %s (%s)',event['inOrOut'],event.get('contactEvidence'))
                    try:
                        self.replay.trigger(frame.timestamp,self.session['id'],'bounce_candidate')
                    except ValueError:
                        log.info('Replay trigger skipped: capacity')
                self.processed+=1
                if track and not track['predicted']:
                    self.detected_frames+=1
                points=self.trajectory.points(self.tracker.history) if track else []
                processing_ms=(time.perf_counter()-started)*1000
                processing_times.append(frame.monotonic)
                duration=processing_times[-1]-processing_times[0]
                state=dict(timestamp=frame.timestamp,sequence=frame.sequence,track=track,trajectory=points,
                           sourceTime=frame.source_time,sourceClock=frame.source_clock,
                           resolution=resolution,
                           persons=persons,
                           poseInferenceMs=self.pose.inference_ms,
                           cameraMoving=bool(getattr(self.detector,'camera_moving',False)),
                            speed=speed,courtPosition=position,framesProcessed=self.processed,
                           detectedFrames=self.detected_frames,tracks=self.tracker.track_id,
                           inferenceMs=self.detector.inference_ms,processingMs=processing_ms,
                           frameLatencyMs=(time.monotonic()-frame.monotonic)*1000,
                           processingFps=(len(processing_times)-1)/duration if duration>0 else None,
                            autoLearning=self.adaptive.status(),
                            statistics=self.statistics,lastEvent=self.events[-1] if self.events else None)
                with self.lock:
                    self.state=state
                self.publisher.submit(frame,state,self.session['id'])
                self.error=None
            except Exception:
                self.error='Erro no processamento. Consulte data/logs/backend.log.'
                with self.lock:
                    self.state.update(track=None,trajectory=[],speed=None)
                log.exception('Processing error')
                self.stop_event.wait(.1)

    def _publish_frame(self,frame,state,session_id):
        started=time.perf_counter()
        ok,encoded=cv2.imencode('.jpg',frame.image,[cv2.IMWRITE_JPEG_QUALITY,86])
        if not ok:raise RuntimeError('JPEG encoding failed')
        jpeg=encoded.tobytes()
        with self.lock:
            self.jpeg=jpeg;self.raw_jpeg=jpeg
            self.frame_state=dict(state,jpegEncodingMs=(time.perf_counter()-started)*1000)
        overlay={key:state.get(key) for key in ('track','persons','trajectory','speed','resolution',
                                               'sourceTime','sourceClock')}
        self.replay.push(frame.timestamp,jpeg,overlay)
        if self.learning:
            self.learning.capture(jpeg,state,session_id)
