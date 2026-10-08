"""Specialized YOLO adapter plus explicitly uncertain motion/color proposals."""
import os
import time
import cv2
import numpy as np
from .config import DATA
from .target import contains_target
from .appearance import AppearanceModel, patch_for, verification_patch
from .ball_evidence import annotate_ball_evidence


class BallDetector:
    def __init__(self, threshold=.65, external_pose=False, adaptive=None):
        self.threshold = threshold
        try:
            # The lightweight colour/motion detector still runs on every
            # processed frame.  Full YOLO person inference is deliberately
            # less frequent: running it on every 120 fps frame took ~215 ms
            # and reduced ball analysis to 4 fps on the supplied computer.
            self.model_interval = max(1, int(os.getenv('TV_MODEL_INTERVAL', '12')))
        except ValueError:
            self.model_interval = 12
        try:
            # Keep the 1080p preview untouched while analysing a lighter copy.
            # Proposal extraction is reduced; learned crop verification and
            # prior-guided reacquisition still use original pixels. 640px kept
            # all 23 impact candidates in the 18/09 replay while reducing the
            # detector median from 23.36ms to 13.82ms on this CPU. Native
            # follow-up crops still preserve detail after the ball is acquired.
            self.analysis_max_width = max(640, int(os.getenv('TV_ANALYSIS_MAX_WIDTH', '640')))
        except ValueError:
            self.analysis_max_width = 640
        try:
            self.model_conf = min(.6, max(.12, float(os.getenv('TV_MODEL_CONF', '.24'))))
        except ValueError:
            self.model_conf = .24
        try:
            self.person_conf = min(.95, max(.25, float(os.getenv('TV_PERSON_CONF', '.6'))))
        except ValueError:
            self.person_conf = .6
        self.background = cv2.createBackgroundSubtractorMOG2(history=180, varThreshold=16, detectShadows=False)
        self.model = None
        self.frames = 0
        self.device = 'cpu'
        self.status = 'heurística temporal — tênis verde e bola clara; modelo ausente'
        self.inference_ms = None
        self.stage_ms = {}
        self.persons = []
        self.person_count = 0
        self.last_model_persons = []
        self.last_person_frame = -100000
        self.previous_gray = None
        self.motion_mask = None
        self.foreground_mask = None
        self.camera_moving = False
        self.analysis_hsv = None
        self.region = None
        self.polygon = None
        self.target_zone = 'full'
        self.external_persons = []
        self.region_key = None
        self.prior = None
        self.adaptive = adaptive
        self.appearance = AppearanceModel(os.getenv('TV_APPEARANCE_DIR'))
        # Use the bundled first-run general model when present. A custom
        # tennis-ball model can override it with TV_MODEL_PATH.
        path = os.getenv('TV_MODEL_PATH', str(DATA / 'models' / 'yolo11n.pt'))
        if external_pose and 'TV_MODEL_PATH' not in os.environ:
            path = ''
            self.status = 'bola por cor e movimento · pose em paralelo'
        if not os.path.isfile(path):
            path = ''
        if path:
            # The model file may be present in a packaged project before the
            # optional Ultralytics runtime has been installed. Keep the
            # heuristic detector available instead of failing the whole API.
            try:
                from ultralytics import YOLO
                import torch
                cv2.setNumThreads(2)
            except ImportError:
                path = ''
            if path:
                requested = os.getenv('TV_DEVICE', 'auto')
                if requested == 'gpu' and not torch.cuda.is_available():
                    raise ValueError('GPU solicitada, mas CUDA indisponível.')
                self.device = '0' if requested != 'cpu' and torch.cuda.is_available() else 'cpu'
                self.model = YOLO(path)
                self.status = 'modelo + rastreador temporal para tênis verde e bola clara'
                # Pay the one-time graph/kernel initialization cost before the
                # live capture starts. Otherwise the first real frame stalls
                # for roughly two seconds and looks like a frozen camera.
                self.model.predict(np.zeros((640,640,3),np.uint8),conf=self.model_conf,
                                   device=self.device,imgsz=640,verbose=False,max_det=1)
                if self.device == 'cpu':torch.set_num_threads(1)

    def detect(self, image, timestamp, persons=None):
        started=time.perf_counter()
        self.appearance.reload()
        height, width = image.shape[:2]
        region = self.region
        key = (width, height)
        if key != self.region_key:
            self.region_key = key
            self.background = cv2.createBackgroundSubtractorMOG2(history=180, varThreshold=16, detectShadows=False)
            self.previous_gray = None
            self.motion_mask = None
            self.camera_moving = False
            self.frames = 0
            self.last_model_persons = []
            self.last_person_frame = -100000
        # The selected rectangle represents the wall/target zone. Analyse the
        # full frame so the tracker sees the complete approach and return path;
        # a hard crop used to erase the ball before it reached the wall.
        scale = min(1.0, self.analysis_max_width / max(1, width))
        analysis = (cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
                    if scale < 1 else image)
        self.external_persons = [dict(p, **{k:p[k]*scale for k in
                                  ('x','y','width','height','centerX','centerY')}) for p in (persons or [])]
        self.analysis_hsv = cv2.cvtColor(analysis, cv2.COLOR_BGR2HSV)
        prepared=time.perf_counter()
        candidates = self._detect(analysis, timestamp)
        if self.appearance.model is not None:
            candidates.extend(self._appearance_proposals(analysis,timestamp))
        proposed=time.perf_counter()
        inverse = 1.0 / scale
        def translate(item):
            translated = dict(item, x=item['x']*inverse, y=item['y']*inverse,
                              width=item['width']*inverse, height=item['height']*inverse,
                              centerX=item['centerX']*inverse, centerY=item['centerY']*inverse)
            if item.get('ballDiameterPx') is not None:
                translated['ballDiameterPx']=item['ballDiameterPx']*inverse
            if region or self.polygon:
                tx,ty=translated['centerX']/width,translated['centerY']/height
                # Impact detection uses the whole mapped surface. The optional
                # tennis zone is a later IN/OUT decision, so an alley or wrong
                # service box still produces a visible OUT event.
                translated['insideTarget'] = contains_target(
                    tx,ty,region,self.polygon,'full')
                translated['insideZone'] = contains_target(
                    tx,ty,region,self.polygon,self.target_zone)
            return translated
        self.persons = [translate(p) for p in self.persons]
        self.person_count = len(self.persons)
        translated=[translate(c) for c in candidates]
        self._refine_tennis_candidates(image, translated)
        # A 6–10px distant ball disappears below the coarse contour thresholds.
        # Motion points to a few cheap native tiles even before a track exists.
        small=self._small_fast_candidates(image,scale,timestamp,translated)
        # Once acquired, inspect a small native-resolution window around the
        # predicted ball. This preserves tiny/blurred detail without paying the
        # cost of full-frame 1080p segmentation on every 120 fps input frame.
        focused = small+self._prior_tennis_candidates(image,timestamp)
        focused.extend(self._prior_adaptive_candidates(image,timestamp))
        focused_at=time.perf_counter()
        for candidate in focused:
            if region or self.polygon:
                tx,ty=candidate['centerX']/width,candidate['centerY']/height
                candidate['insideTarget']=contains_target(tx,ty,region,self.polygon,'full')
                candidate['insideZone']=contains_target(
                    tx,ty,region,self.polygon,self.target_zone)
            # The native contour refines the coarse centre/diameter. Previously
            # it was thrown away whenever the downsampled proposal overlapped.
            candidate['nativeResolution']=True
            translated.append(candidate)
        # Measure novelty on the actual coloured pixels. A hand moving beside
        # a poster can activate its bounding box without the poster moving.
        # Such background fragments must never inherit the flying ball track.
        translated=self._foreground_candidates(translated,analysis,scale)
        annotate_ball_evidence(image, translated, persons or self.persons)
        filtered_at=time.perf_counter()
        if self.appearance.model is not None:
            proposals=[c for c in translated if c.get('tennisColor')]
            scores=self.appearance.scores([verification_patch(image,[c[k] for k in ('x','y','width','height')]) for c in proposals])
            elongated=[i for i,c in enumerate(proposals) if
                       max(c['width'],c['height'])/max(1,min(c['width'],c['height']))>=3]
            if elongated:
                # Keep a positive from the original training crop too: the
                # ball can lie near one end of a blur, outside its central crop.
                contextual=self.appearance.scores([patch_for(image,[proposals[i][k] for k in
                                                  ('x','y','width','height')]) for i in elongated])
                for i,score in zip(elongated,contextual):scores[i]=max(scores[i],score)
            for c,score in zip(proposals,scores):
                c['appearanceScore']=float(score)
                c['appearanceVersion']=self.appearance.version
                if score >= self.appearance.threshold:
                    c['appearanceVerified']=True
                    # Appearance supplements geometry; it must not turn every
                    # green rectangular poster into an equally strong ball.
                    c['ballScore']=max(c.get('ballScore',0),
                        .48*min(1,c.get('roundness',0))+.3*min(1,c.get('tennisFraction',0))+
                        .22*float(score))
            translated=[c for c in translated if c.get('source')=='model' or
                        (c.get('appearanceProposal') and c.get('appearanceVerified')) or
                        (not c.get('appearanceProposal') and c.get('appearanceScore',1)>=.35)]
            self.status='tênis verde · rede treinada localmente + rastreamento temporal'
        verified_at=time.perf_counter()
        if self.adaptive is not None:
            self.adaptive.score_candidates(image, translated)
            if self.adaptive.ready:
                if self.appearance.model is not None:
                    self.status='assinatura automática + rede revisada + rastreamento temporal'
                elif self.model is not None:
                    self.status='modelo + assinatura automática + rastreamento temporal'
                else:
                    self.status='assinatura automática da bola + cor e movimento'
        # Prefer the learned proposal when two contours describe the same ball.
        unique=[]
        for c in sorted(translated,key=lambda c:(bool(c.get('autoAppearanceVerified')),
                                                  bool(c.get('appearanceVerified')),
                                                  bool(c.get('nativeResolution')),
                                                  c.get('ballScore',0)),reverse=True):
            if not any(np.hypot(c['centerX']-p['centerX'],c['centerY']-p['centerY']) <
                       max(5,min(c['width'],c['height'],p['width'],p['height'])*.65) for p in unique):
                unique.append(c)
        self.inference_ms=(time.perf_counter()-started)*1000
        self.stage_ms=dict(prepare=(prepared-started)*1000,proposals=(proposed-prepared)*1000,
                           focused=(focused_at-proposed)*1000,foreground=(filtered_at-focused_at)*1000,
                           appearance=(verified_at-filtered_at)*1000,
                           adaptive=(time.perf_counter()-verified_at)*1000,candidates=len(unique))
        return unique

    def _refine_tennis_candidates(self, image, candidates):
        """Refine small/blurred proposals in original pixels, even after loss.

        Native search previously required an already correct Kalman prior.
        A new ball or a rebound outside that prior now gets the same detail.
        Limit work to eight small crops; the full preview stays untouched.
        """
        height,width=image.shape[:2]
        proposals=sorted((c for c in candidates if c.get('tennisColor')),
                         key=lambda c:(c.get('moving',False),c.get('ballScore',0)),reverse=True)[:8]
        for c in proposals:
            if min(c['width'],c['height'])>35 and max(c['width'],c['height'])<min(c['width'],c['height'])*1.5:
                continue
            margin=max(6.,max(c['width'],c['height'])*.25)
            left=max(0,int(c['x']-margin));top=max(0,int(c['y']-margin))
            right=min(width,int(c['x']+c['width']+margin)+1)
            bottom=min(height,int(c['y']+c['height']+margin)+1)
            hsv=cv2.cvtColor(image[top:bottom,left:right],cv2.COLOR_BGR2HSV)
            mask=cv2.inRange(hsv,np.array([22,60,55]),np.array([62,255,255]))
            contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
            valid=[]
            for contour in contours:
                x,y,w,h=cv2.boundingRect(contour)
                if min(w,h)<3 or cv2.contourArea(contour)<7:
                    continue
                distance=np.hypot(left+x+w/2-c['centerX'],top+y+h/2-c['centerY'])
                if distance>max(5.,min(c['width'],c['height'])*.6):
                    continue
                if not .4<=min(w,h)/max(1.,min(c['width'],c['height']))<=2:
                    continue
                valid.append((distance,x,y,w,h,contour))
            if valid:
                _,x,y,w,h,contour=min(valid,key=lambda v:v[0])
                c.update(x=float(left+x),y=float(top+y),width=float(w),height=float(h),
                         centerX=float(left+x+w/2),centerY=float(top+y+h/2),
                         ballDiameterPx=self._diameter(contour),nativeResolution=True)

    def _small_fast_candidates(self,image,scale,timestamp,existing):
        if scale>=1 or self.camera_moving or self.motion_mask is None:
            return []
        # Spend the native search budget on acquisition or loss, not on an
        # already clear contour beside the current track. Tiny balls still
        # invoke it on every frame because they have no usable coarse contour.
        prior=self.prior
        if prior and prior.get('tennisColor') and prior.get('age',1)<.12:
            for candidate in existing:
                if (candidate.get('tennisColor') and (candidate.get('nativeResolution') or
                        min(candidate['width'],candidate['height'])>=24) and
                        candidate.get('moving') and candidate.get('ballScore',0)>=.75 and
                        np.hypot(candidate['centerX']-prior['centerX'],candidate['centerY']-prior['centerY']) <
                        max(40.,min(candidate['width'],candidate['height'])*3)):
                    return []
        # No opening operation: a 2px coarse silhouette must survive here.
        green=cv2.inRange(self.analysis_hsv,np.array([24,65,65]),np.array([58,255,255]))
        moving=cv2.bitwise_and(green,self.motion_mask)
        count,_,stats,centers=cv2.connectedComponentsWithStats(moving,8)
        tiles=[]
        for i in range(1,count):
            x,y,w,h,area=stats[i]
            if area<2 or min(w,h)>4 or max(w,h)>64 or area/(w*h)<.18:
                continue
            cx,cy=centers[i]/scale
            if any(c.get('tennisColor') and c['x']<=cx<=c['x']+c['width'] and
                   c['y']<=cy<=c['y']+c['height'] for c in existing):
                continue
            tiles.append((int(area),x,y,w,h))
        height,width=image.shape[:2];found=[]
        for _,x,y,w,h in sorted(tiles,reverse=True)[:4]:
            margin=16
            left=max(0,int(x/scale)-margin);top=max(0,int(y/scale)-margin)
            right=min(width,int(np.ceil((x+w)/scale))+margin)
            bottom=min(height,int(np.ceil((y+h)/scale))+margin)
            hsv=cv2.cvtColor(image[top:bottom,left:right],cv2.COLOR_BGR2HSV)
            mask=cv2.inRange(hsv,np.array([24,60,55]),np.array([58,255,255]))
            contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
            for contour in contours:
                bx,by,bw,bh=cv2.boundingRect(contour)
                # Reject fragments of larger foreground objects clipped by the tile.
                if bx==0 or by==0 or bx+bw>=right-left or by+bh>=bottom-top:
                    continue
                area=cv2.contourArea(contour);perimeter=cv2.arcLength(contour,True)
                roundness=4*np.pi*area/max(1,perimeter*perimeter)
                fraction=float(np.mean(mask[by:by+bh,bx:bx+bw]>0))
                if not (3<=min(bw,bh)<=18 and max(bw,bh)<=210 and
                        area>=6 and fraction>=.48 and roundness>=.14):
                    continue
                px=left+bx+bw/2;py=top+by+bh/2
                if px<8 or py<8 or px>width-8 or py>height-8:
                    continue
                score=min(.95,.28*roundness+.32*fraction+.36)
                if score<.7:continue
                found.append(self._box(left+bx,top+by,bw,bh,.4,timestamp,'heuristic',
                    moving=True,tennisColor=True,tennisFraction=float(fraction),
                    roundness=float(roundness),ballScore=float(score),
                    ballDiameterPx=self._diameter(contour),nativeResolution=True,
                    smallFastProposal=True,insidePerson=self._inside_person(self.persons,px,py,max(bw,bh)),
                    label='small moving tennis ball · native pixels'))
        return sorted(found,key=lambda c:c['ballScore'],reverse=True)[:4]

    def _foreground_candidates(self,candidates,analysis,scale):
        hsv=self.analysis_hsv
        green=cv2.inRange(hsv,np.array([18,50,55]),np.array([70,255,255]))
        # The broad green/yellow range above preserves a dim blurred ball, but
        # it also includes wood, tan fur and skin.  Keep a second measurement
        # for a saturated tennis-felt core.  It is evidence, not a hard
        # detector threshold, and is measured at the inexpensive analysis
        # resolution then reported in native-pixel equivalents.
        vivid=cv2.inRange(hsv,np.array([12,100,100]),np.array([55,255,255]))
        output=[]
        h,w=green.shape
        for item in candidates:
            if not item.get('tennisColor') or item.get('source')=='model':
                output.append(item);continue
            x1=max(0,int(item['x']*scale));y1=max(0,int(item['y']*scale))
            x2=min(w,max(x1+1,int(np.ceil((item['x']+item['width'])*scale))))
            y2=min(h,max(y1+1,int(np.ceil((item['y']+item['height'])*scale))))
            core=green[y1:y2,x1:x2]>0
            count=int(core.sum())
            vivid_count=int(np.count_nonzero(vivid[y1:y2,x1:x2]))
            item['tennisVividPixels']=float(vivid_count/max(.01,scale*scale))
            item['tennisVividFraction']=float(vivid_count/max(1,(y2-y1)*(x2-x1)))
            if self.foreground_mask is None:
                output.append(item);continue
            novelty=float(np.count_nonzero(core & (self.foreground_mask[y1:y2,x1:x2]>0))/max(1,count))
            item['foregroundFraction']=novelty
            # Still show a held ball when supported by the person detector;
            # no impact evidence can originate there.
            if count>=4 and novelty<.18 and not item.get('insidePerson'):
                continue
            output.append(item)
        return output

    def _appearance_proposals(self,image,timestamp):
        """Relax shape constraints for tiny or elongated balls, then verify pixels."""
        if self.camera_moving:return []
        hsv=self.analysis_hsv
        mask=cv2.inRange(hsv,np.array([18,35,50]),np.array([70,255,255]))
        contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
        candidates=[]
        height,width=image.shape[:2]
        for contour in contours:
            x,y,w,h=cv2.boundingRect(contour)
            if min(w,h)<2 or max(w,h)<4 or max(w,h)>min(180,height*.3):continue
            area=cv2.contourArea(contour)
            perimeter=cv2.arcLength(contour,True)
            roundness=4*np.pi*area/max(1,perimeter**2)
            if min(w,h)<2 or max(w,h)<4 or max(w,h)>min(180,height*.3) or area<3 or area/(w*h)<.25 or roundness<.18:continue
            cx,cy=x+w/2,y+h/2
            moving=self.motion_mask is not None and float(np.mean(self.motion_mask[y:y+h,x:x+w]>0))>.025
            within=self._inside_person(self.persons,cx,cy,max(w,h))
            if not moving and not within:continue
            candidates.append(self._box(x,y,w,h,None,timestamp,'appearance',
                              ballDiameterPx=self._diameter(contour),
                              moving=bool(moving),insidePerson=within,tennisColor=True,
                              tennisFraction=float(area/(w*h)),roundness=float(roundness),
                              ballScore=.5,appearanceProposal=True,label='bola verde · verificação treinada'))
        return sorted(candidates,key=lambda c:(c['moving'],min(c['width'],c['height'])),reverse=True)[:60]

    def _prior_tennis_candidates(self,image,timestamp):
        prior=self.prior
        if (not prior or not prior.get('tennisColor') or
                prior.get('age',1) > .72 or self.camera_moving):
            return []
        height,width=image.shape[:2]
        speed=float(np.hypot(prior.get('velocityX',0),prior.get('velocityY',0)))
        expected=max(6,prior.get('width',6),prior.get('height',6))
        radius=int(max(75,min(460,expected*5+prior.get('age',0)*min(speed,1800)*.45)))
        cx,cy=prior['centerX'],prior['centerY']
        x1=max(0,int(cx-radius)); y1=max(0,int(cy-radius))
        x2=min(width,int(cx+radius)); y2=min(height,int(cy+radius))
        if x2-x1 < 8 or y2-y1 < 8:
            return []
        roi=image[y1:y2,x1:x2]
        hsv=cv2.cvtColor(roi,cv2.COLOR_BGR2HSV)
        # The supplied tennis ball has a stable fluorescent core around H35
        # with clearly higher saturation than skin, gray walls and furniture.
        # The former H14/S30 range also classified moving arms and compression
        # noise as green, which made the trail jump across the room.
        mask=cv2.inRange(hsv,np.array([18,65,65]),np.array([62,255,255]))
        mask=cv2.morphologyEx(mask,cv2.MORPH_CLOSE,np.ones((3,3),np.uint8))
        motion_mask=self.motion_mask
        if motion_mask is not None:
            motion_mask=cv2.dilate(motion_mask,np.ones((5,5),np.uint8))
            motion_scale_x=motion_mask.shape[1]/max(1,width)
            motion_scale_y=motion_mask.shape[0]/max(1,height)
        contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
        found=[]
        predicted=np.array([cx,cy])
        source_border=max(10,min(height,width)*.025)
        for contour in contours:
            x,y,w,h=cv2.boundingRect(contour)
            area=cv2.contourArea(contour); perimeter=cv2.arcLength(contour,True)
            if not (3 <= min(w,h) and 8 <= max(w,h) <= max(300,expected*6) and
                    .12 <= w/h <= 8 and area >= 7 and area/(w*h) >= .18):
                continue
            px=x1+x+w/2; py=y1+y+h/2
            if (px < source_border or py < source_border or
                    px > width-source_border or py > height-source_border):
                continue
            distance=float(np.linalg.norm(np.array([px,py])-predicted))
            if distance > radius:
                continue
            roundness=4*np.pi*area/(perimeter*perimeter) if perimeter else 0
            occupancy=float(np.mean(mask[y:y+h,x:x+w] > 0))
            motion_fraction=0.
            if motion_mask is not None:
                mx1=max(0,int((x1+x)*motion_scale_x)); my1=max(0,int((y1+y)*motion_scale_y))
                mx2=min(motion_mask.shape[1],max(mx1+1,int(np.ceil((x1+x+w)*motion_scale_x))))
                my2=min(motion_mask.shape[0],max(my1+1,int(np.ceil((y1+y+h)*motion_scale_y))))
                motion_fraction=float(np.mean(motion_mask[my1:my2,mx1:mx2] > 0))
            moving=motion_fraction >= .025
            # The focused colour pass used to label every green object near the
            # prediction as moving. Shelves and chair details then stole the
            # track as soon as the ball crossed the wall. A fast track may only
            # attach to pixels with real frame-to-frame motion.
            if speed > 80 and not moving:
                continue
            aspect=min(w,h)/max(w,h)
            proximity=max(0.,1-distance/max(1,radius))
            score=(.22*min(1.,roundness)+.16*aspect+.20*occupancy+
                   .24*proximity+.18*min(1.,motion_fraction*4))
            within=self._inside_person(self.persons,px,py,max(w,h))
            found.append(self._box(x1+x,y1+y,w,h,.38,timestamp,'heuristic',moving=moving,
                                   ballDiameterPx=self._diameter(contour),
                                   roundness=float(roundness),motionOnly=True,
                                   ballScore=float(score),tennisColor=True,
                                   tennisFraction=occupancy,motionFraction=motion_fraction,
                                   insidePerson=within,
                                   label='predicted green tennis candidate'))
        return sorted(found,key=lambda item:item['ballScore'],reverse=True)[:5]

    def _prior_adaptive_candidates(self,image,timestamp):
        """Reacquire the remembered ball colour near the temporal prediction."""
        prior=self.prior
        if (self.adaptive is None or not self.adaptive.ready or not prior or
                prior.get('age',1) > .72 or self.camera_moving):
            return []
        height,width=image.shape[:2]
        speed=float(np.hypot(prior.get('velocityX',0),prior.get('velocityY',0)))
        expected=max(5,prior.get('width',6),prior.get('height',6))
        radius=int(max(70,min(480,expected*5+prior.get('age',0)*min(speed,2000)*.48)))
        cx,cy=prior['centerX'],prior['centerY']
        x1=max(0,int(cx-radius)); y1=max(0,int(cy-radius))
        x2=min(width,int(cx+radius)); y2=min(height,int(cy+radius))
        if x2-x1 < 8 or y2-y1 < 8:
            return []
        roi=image[y1:y2,x1:x2]
        mask=self.adaptive.colour_mask(cv2.cvtColor(roi,cv2.COLOR_BGR2HSV))
        if mask is None:
            return []
        mask=cv2.morphologyEx(mask,cv2.MORPH_OPEN,np.ones((3,3),np.uint8))
        mask=cv2.morphologyEx(mask,cv2.MORPH_CLOSE,np.ones((3,3),np.uint8))
        motion_mask=self.motion_mask
        if motion_mask is not None:
            motion_mask=cv2.dilate(motion_mask,np.ones((5,5),np.uint8))
            motion_scale_x=motion_mask.shape[1]/max(1,width)
            motion_scale_y=motion_mask.shape[0]/max(1,height)
        contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
        found=[]; predicted=np.array([cx,cy])
        for contour in contours:
            x,y,w,h=cv2.boundingRect(contour)
            area=cv2.contourArea(contour); perimeter=cv2.arcLength(contour,True)
            if not (2 <= min(w,h) and 5 <= max(w,h) <= max(280,expected*6) and
                    .11 <= w/h <= 9 and area >= 5 and area/max(1,w*h) >= .16):
                continue
            px=x1+x+w/2; py=y1+y+h/2
            distance=float(np.linalg.norm(np.array([px,py])-predicted))
            if distance > radius:
                continue
            motion_fraction=0.
            if motion_mask is not None:
                mx1=max(0,int((x1+x)*motion_scale_x)); my1=max(0,int((y1+y)*motion_scale_y))
                mx2=min(motion_mask.shape[1],max(mx1+1,int(np.ceil((x1+x+w)*motion_scale_x))))
                my2=min(motion_mask.shape[0],max(my1+1,int(np.ceil((y1+y+h)*motion_scale_y))))
                motion_fraction=float(np.mean(motion_mask[my1:my2,mx1:mx2] > 0))
            moving=motion_fraction >= .02
            if speed > 80 and not moving:
                continue
            roundness=4*np.pi*area/max(1,perimeter*perimeter)
            occupancy=float(np.mean(mask[y:y+h,x:x+w] > 0))
            aspect=min(w,h)/max(w,h)
            proximity=max(0.,1-distance/max(1,radius))
            score=.2*min(1.,roundness)+.18*aspect+.24*occupancy+.22*proximity+.16*min(1.,motion_fraction*5)
            within=self._inside_person(self.persons,px,py,max(w,h))
            found.append(self._box(x1+x,y1+y,w,h,.4,timestamp,'adaptive',moving=moving,
                                   ballDiameterPx=self._diameter(contour),
                                   roundness=float(roundness),ballScore=float(score),
                                   insidePerson=within,motionFraction=motion_fraction,
                                   label='candidate from automatic ball signature'))
        return sorted(found,key=lambda item:item['ballScore'],reverse=True)[:6]

    def _detect(self, image, timestamp):
        started = time.perf_counter()
        self.frames += 1
        self.camera_moving = False
        candidates = []
        cached_persons = (self.last_model_persons if
                          self.frames-self.last_person_frame <= self.model_interval*3 else [])
        self.persons = list(self.external_persons or cached_persons)
        # Refresh colour, background and motion on every processed image,
        # including the frames where YOLO also recognizes a ball.
        heuristic = self._heuristic_candidates(image, timestamp)
        if self.model:
            # YOLO is the expensive stage on a CPU RTSP host. Running it at a
            # lower cadence keeps the latest-frame queue fresh; motion/color
            # proposals still run on every frame for the tiny ping-pong ball.
            run_model = self.frames == 1 or self.frames % self.model_interval == 0
            if not run_model:
                return self._finish(heuristic, started)
            # Run all classes so a general YOLO model can identify people while a
            # tennis-ball model can identify the ball. The ball tracker receives
            # only the ball candidates; persons are a separate overlay channel.
            model_persons = []
            result = self.model.predict(image, conf=min(self.threshold, self.model_conf), device=self.device,
                                        imgsz=640, verbose=False)[0]
            names = getattr(self.model, 'names', {})
            configured_ball = os.getenv('TV_BALL_CLASS', '')
            configured_person = os.getenv('TV_PERSON_CLASS', '0')
            for box in result.boxes:
                x1, y1, x2, y2 = box.xyxy[0].cpu().tolist()
                cls = int(box.cls[0]) if getattr(box, 'cls', None) is not None else -1
                label = str(names.get(cls, cls)).lower() if isinstance(names, dict) else str(cls)
                conf = float(box.conf[0])
                is_person = label in ('person','people','human') or str(cls) == configured_person
                is_ball = (configured_ball != '' and str(cls) == configured_ball) or any(token in label for token in ('tennis','ball'))
                item = self._box(x1, y1, x2-x1, y2-y1, conf, timestamp, 'model', label=label, classID=cls)
                if is_person and conf >= self.person_conf:
                    model_persons.append(item)
                elif is_ball or (configured_ball == '' and cls == int(os.getenv('TV_MODEL_CLASS', '0')) and label not in ('person','people','human')):
                    candidates.append(item)
            model_persons = sorted(model_persons, key=lambda x:x['confidence'], reverse=True)[:30]
            if model_persons:
                self.last_model_persons = model_persons
                self.last_person_frame = self.frames
            elif not cached_persons:
                self.last_model_persons = []
            # Pose is already propagated to this image. Never cache it as a
            # YOLO result or replace it with an older body box between passes.
            # YOLO boxes remain a brief fallback when pose is unavailable.
            self.persons = list(self.external_persons or model_persons or cached_persons)
            # Small balls can escape the general model. Keep the independent
            # motion proposals even when YOLO recognizes another ball.
            candidates.extend(heuristic)
        else:
            candidates = heuristic
        return self._finish(candidates, started)

    def _finish(self, candidates, started):
        self.inference_ms = (time.perf_counter()-started)*1000
        return candidates

    def _heuristic_candidates(self, image, timestamp):
        # Work at native resolution: tiny balls are lost by aggressive downsampling.
        height, width = image.shape[:2]
        self.max_ball_size = max(60, min(240, height*.32))
        motion = self.background.apply(image)
        self.foreground_mask=motion.copy()
        hsv = self.analysis_hsv
        color = cv2.inRange(hsv, np.array([18, 55, 65]), np.array([95, 255, 255]))
        # Fluorescent yellow-green tennis felt needs its own cue. Giving this
        # range a higher temporal rank keeps the ball ahead of white captions,
        # skin reflections and room highlights.
        tennis_green = cv2.inRange(hsv, np.array([18, 65, 75]), np.array([55, 255, 255]))
        color = cv2.bitwise_or(color, tennis_green)
        # A fast tennis ball becomes a dim yellow-green streak. Its saturation
        # and value fall below the normal felt range, so retain a broader cue
        # only where frame-to-frame motion independently confirms it.
        tennis_blur = cv2.inRange(hsv, np.array([18, 50, 55]), np.array([65, 255, 255]))
        # Ping-pong balls are often white and only a few pixels wide in RTSP
        # footage. Keep this auxiliary cue conservative and mark its score as
        # uncertain; it is never treated as a trained detector probability.
        neutral = cv2.inRange(hsv, np.array([0, 0, 125]), np.array([180, 45, 255]))
        cool_white = cv2.inRange(hsv, np.array([85, 0, 125]), np.array([130, 90, 255]))
        # Under the room's warm lighting the supplied white ping-pong ball is
        # cream coloured (roughly H 11–23, S 47–74), so a neutral-white mask
        # alone misses most of it. This range excludes the low-saturation desk
        # while retaining the ball's shaded edge.
        warm_white = cv2.inRange(hsv, np.array([8, 20, 120]), np.array([38, 125, 255]))
        white = cv2.bitwise_or(cv2.bitwise_or(neutral, cool_white), warm_white)
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        previous = self.previous_gray
        self.previous_gray = gray
        if previous is None or previous.shape != gray.shape:
            self.motion_mask = None
            return []
        # Phone auto-exposure and indoor flicker shift most pixels together.
        # Subtract a robust global brightness offset before measuring motion;
        # otherwise every exposure step masquerades as a camera pan and drops
        # the complete ball trajectory, including the contact and rebound.
        sample_delta=gray[::8,::8].astype(np.int16)-previous[::8,::8].astype(np.int16)
        exposure_shift=float(np.median(sample_delta))
        reference=(cv2.add(previous,exposure_shift) if 4 <= abs(exposure_shift) <= 64 else previous)
        changed = cv2.inRange(cv2.absdiff(gray, reference), 12, 255)
        # A fixed viewpoint is required to distinguish a flying 40 mm ball
        # from the whole room moving across the image. During a pan or phone
        # rotation, suppress heuristic proposals instead of turning lamps,
        # furniture and clothing into dozens of false ball tracks.
        changed_fraction = cv2.countNonZero(changed) / max(1, changed.size)
        self.camera_moving = changed_fraction > .30
        if self.camera_moving:
            self.motion_mask = None
            return []
        # Segment the current ball colour before extracting contours, so a
        # moving hand does not swallow the ball into a person-sized blob.
        motion = cv2.bitwise_and(motion, cv2.dilate(changed, np.ones((3, 3), np.uint8)))
        # All passes use foreground novelty, not only JPEG flicker on a static
        # green poster. The native/learned passes used to bypass this gate.
        self.motion_mask = motion
        # Extract the entire current silhouette, not only its changed edge.
        # Motion is evidence for acquiring a track; a held ball can then remain
        # visible without its contour disappearing into the background model.
        silhouette = cv2.bitwise_or(color, white)
        silhouette = cv2.morphologyEx(silhouette, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
        silhouette = cv2.morphologyEx(silhouette, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
        candidates = []
        border=max(6,min(height,width)*.025)
        def near_border(cx,cy):
            return cx < border or cy < border or cx > width-border or cy > height-border
        def inside_person(cx, cy, diameter):
            # Clothing graphics and hands created the persistent false tracks
            # seen in the supplied room recordings. A heuristic cannot safely
            # distinguish those pixels from a white ball. Acquire the ball as
            # soon as it leaves the person box; a trained model ball remains
            # eligible even while held.
            return self._inside_person(self.persons,cx,cy,diameter)
        if self.frames > 15:
            # Segment green independently. Unioning green with a white-wall
            # mask merged the ball into the wall and erased its contour.
            green_mask=cv2.morphologyEx(tennis_green,cv2.MORPH_CLOSE,np.ones((3,3),np.uint8))
            green_contours,_=cv2.findContours(green_mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
            for contour in green_contours:
                x,y,w,h=cv2.boundingRect(contour)
                if min(w,h)<4 or max(w,h)>self.max_ball_size or not .2<=w/h<=5:
                    continue
                area=cv2.contourArea(contour); perimeter=cv2.arcLength(contour,True)
                circularity=4*np.pi*area/(perimeter*perimeter) if perimeter else 0
                occupancy=area/max(1,w*h)
                cx,cy=x+w/2,y+h/2
                if not (4 <= min(w,h) and max(w,h) <= self.max_ball_size and
                        .2 <= w/h <= 5 and area >= 9 and occupancy >= .42 and circularity >= .3):
                    continue
                moving=float(np.mean(motion[y:y+h,x:x+w] > 0)) > .025
                within=inside_person(cx,cy,(w+h)/2)
                if near_border(cx,cy) or not (moving or within):
                    continue
                score=.42*min(1,circularity)+.23*min(1,(w+h)/36)+.45*occupancy
                if score >= .7:
                    candidates.append(self._box(x,y,w,h,.45,timestamp,'heuristic',moving=moving,
                                               ballDiameterPx=self._diameter(contour),
                                               roundness=float(circularity),ballScore=float(score),
                                               insidePerson=within,tennisColor=True,tennisFraction=float(occupancy),
                                               label='isolated green tennis ball'))
            contours, _ = cv2.findContours(silhouette, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            for c in contours:
                x,y,w,h = cv2.boundingRect(c)
                if min(w,h)<5 or max(w,h)>self.max_ball_size or not .4<=w/h<=2.5:
                    continue
                area = cv2.contourArea(c)
                perimeter = cv2.arcLength(c, True)
                circularity = 4*np.pi*area/(perimeter*perimeter) if perimeter else 0
                if (5 <= min(w,h) and max(w,h) <= self.max_ball_size and .4 <= w/h <= 2.5
                        and area >= 12 and circularity >= .45):
                    color_fraction = max(float(np.mean(color[y:y+h,x:x+w] > 0)),
                                         float(np.mean(white[y:y+h,x:x+w] > 0)))
                    tennis_fraction = float(np.mean(tennis_green[y:y+h,x:x+w] > 0))
                    is_tennis = tennis_fraction >= .45
                    # White OSD digits and tiny specular highlights were the
                    # remaining false tracks. A genuinely green tennis ball is
                    # allowed down to five pixels; other colours need 9 px.
                    if min(w,h) < 9 and not is_tennis:
                        continue
                    # A heuristic score is NOT a calibrated probability.
                    score = min(.49, .10 + .39*float(color_fraction)*min(1,circularity*2))
                    # Bright clothing fragments and skin highlights are often
                    # irregular or have low colour occupancy. Do not promote
                    # every changed pixel into a ball candidate.
                    if score >= .35 and color_fraction >= .65 and area/(w*h) >= .45:
                        moving = float(np.mean(motion[y:y+h,x:x+w] > 0)) > .025
                        cx, cy = x+w/2, y+h/2
                        diameter = (w+h)/2
                        if near_border(cx,cy):
                            continue
                        ball_score = (.42*min(1.0, max(0.0, circularity)) +
                                      .23*min(1.0, diameter/18) +
                                      .15*min(1.0, color_fraction) +
                                      .30*min(1.0, tennis_fraction))
                        within_person = inside_person(cx, cy, diameter)
                        # The ball often passes in front of the player's torso.
                        # Keep only a strong, moving, round proposal there and
                        # mark it so the tracker cannot confirm a static shirt
                        # highlight as a held ball.
                        strong_tennis = (is_tennis and tennis_fraction >= .55 and
                                         circularity >= .48 and diameter >= 8)
                        if within_person and not (strong_tennis or
                                                  (moving and circularity >= .68 and
                                                   color_fraction >= .75 and ball_score >= .65)):
                            continue
                        candidates.append(self._box(x,y,w,h,score,timestamp,'heuristic',
                                                    ballDiameterPx=self._diameter(c),
                                                    label=('green tennis ball candidate' if is_tennis else 'bright ball candidate'),
                                                    moving=moving, roundness=float(circularity),
                                                    ballScore=float(ball_score),insidePerson=within_person,
                                                    tennisColor=is_tennis,tennisFraction=tennis_fraction))
            # Against a white wall the ball's colour joins the wall. Use a
            # separate temporal silhouette for these frames, including motion
            # blur. These proposals still need temporal tracker confirmation.
            temporal=cv2.bitwise_and(motion,cv2.bitwise_or(cv2.bitwise_or(color,white),tennis_blur))
            temporal=cv2.morphologyEx(temporal,cv2.MORPH_CLOSE,np.ones((3,3),np.uint8))
            contours,_=cv2.findContours(temporal,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
            for contour in contours:
                x,y,w,h=cv2.boundingRect(contour)
                if (min(w,h)<4 or max(w,h)<8 or max(w,h)>self.max_ball_size or
                        not .125<=w/h<=8):
                    continue
                area=cv2.contourArea(contour); perimeter=cv2.arcLength(contour,True)
                roundness=4*np.pi*area/(perimeter*perimeter) if perimeter else 0
                blur_fraction=float(np.mean(tennis_blur[y:y+h,x:x+w] > 0))
                blurred_tennis=blur_fraction >= .22
                if not (4 <= min(w,h) and max(w,h) >= 8 and max(w,h) <= self.max_ball_size
                        and .125 <= w/h <= 8 and area >= 8
                        and area/(w*h) >= (.22 if blurred_tennis else .35)
                        and roundness >= (.12 if blurred_tennis else .2)):
                    continue
                cx,cy=x+w/2,y+h/2
                if any(abs(c['centerX']-cx)<max(w,c['width'])/2 and abs(c['centerY']-cy)<max(h,c['height'])/2 for c in candidates):
                    continue
                diameter=(w+h)/2
                within_person=inside_person(cx,cy,diameter)
                if near_border(cx,cy) or (within_person and not blurred_tennis):
                    continue
                aspect=min(w,h)/max(w,h)
                ball_score=(.42*min(1.0,roundness)+.25*min(1.0,diameter/18)+
                            .13*aspect+.30*min(1.0,blur_fraction))
                candidates.append(self._box(x,y,w,h,.36,timestamp,'heuristic',moving=True,
                                           ballDiameterPx=self._diameter(contour),
                                           roundness=float(roundness),motionOnly=True,
                                           ballScore=float(ball_score),tennisColor=blurred_tennis,
                                           tennisFraction=blur_fraction,
                                           insidePerson=within_person,
                                           label=('moving green tennis blur' if blurred_tennis else
                                                  'moving blurred ball candidate')))
        return sorted(candidates, key=lambda x:(x['moving'],x.get('ballScore',0)), reverse=True)[:40]

    @staticmethod
    def _diameter(contour):
        # A diagonal streak has a large width AND height. The thin dimension
        # of its rotated rectangle preserves ball scale under motion blur.
        return max(2.,float(min(cv2.minAreaRect(contour)[1]))+1.)

    @staticmethod
    def _inside_person(persons,cx,cy,diameter):
        """Include hands and a held ball just outside the body rectangle."""
        for person in persons:
            body_scale=min(float(person.get('width',0)),float(person.get('height',0)))
            margin=min(80.,max(8.,float(diameter)*1.6,body_scale*.06))
            if (person['x']-margin <= cx <= person['x']+person['width']+margin and
                    person['y']-margin <= cy <= person['y']+person['height']+margin):
                return True
        return False

    @staticmethod
    def _box(x,y,w,h,confidence,timestamp,source,**extra):
        return dict(x=float(x), y=float(y), width=float(w), height=float(h), centerX=float(x+w/2),
                    centerY=float(y+h/2), confidence=confidence, timestamp=timestamp, source=source, **extra)
