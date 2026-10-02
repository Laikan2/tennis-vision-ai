from collections import deque
from itertools import combinations
import numpy as np
from .speed import estimate_segment_speed
from .ball_evidence import has_tennis_core, person_contact
from .observation_time import observation_times, observation_delta


class BounceDetector:
    """Temporal candidate finder. A single image reversal cannot prove ground contact."""
    cooldown_seconds = .28
    minimum_impact_speed_kmh = 3.
    def __init__(self):
        self.points = deque(maxlen=9)
        self.last_event = -1e9
        self.track_id = None
        self.pending_contact = None
        self.release_anchor = None
        self.last_ground_contact = -1e9

    @staticmethod
    def _identity(point):
        return bool(point.get('tennisColor') or point.get('source') == 'model' or
                    point.get('appearanceVerified') or point.get('autoAppearanceVerified'))

    @staticmethod
    def _away_from_edge(point):
        x,y=point.get('measuredX',point['centerX']),point.get('measuredY',point['centerY'])
        radius=max(4.,min(point.get('width',8),point.get('height',8))*.6)
        w,h=point.get('frameWidth'),point.get('frameHeight')
        return not ((w and (x<radius or x>w-radius)) or (h and (y<radius or y>h-radius)))

    def update(self, track, court_position, mode='court', timestamp=None):
        if mode == 'tracking':
            self.points.clear()
            self.pending_contact = None
            self.release_anchor = None
            return None
        # Loss of a ball is not a wall collision. Keep a short measured history
        # for reacquisition, but never score or extrapolate an unobserved hit.
        if track is None:
            max_gap = .42 if mode == 'court' else .32
            if (timestamp is not None and self.points and
                    timestamp-self.points[-1]['timestamp'] > max_gap):
                self.points.clear()
            if (timestamp is not None and self.release_anchor and
                    timestamp-self.release_anchor['timestamp'] > .45):
                self.release_anchor = None
            return None
        event=self._update(track,court_position,mode)
        if event:
            if mode == 'court' and event['timestamp'] <= self.last_ground_contact + .04:
                return None
            point=min(self.points,key=lambda p:abs(p['timestamp']-event['timestamp']),default=track or {})
            diameter=point.get('ballDiameterPx',min(point.get('width',0),point.get('height',0)))
            event.setdefault('ballDiameterPx',diameter)
            event.setdefault('positionUncertaintyPx',max(2.,diameter*.12))
            event.setdefault('contactEvidence','measured_turn')
            event['positionApproximate']=True
            if mode == 'court':
                self.last_ground_contact=event['timestamp']
                # Keep the upward leg for the next descent, never the old
                # contact. A cooldown based on arrival time counted the same
                # recorded bounce two or three times as more frames arrived.
                self.points=deque((p for p in self.points if p['timestamp']>event['timestamp']),maxlen=9)
            if mode == 'room':
                # Do not reuse the contact leg. New measured observations can
                # form the next throw even if pose missed the hand offscreen.
                self.points.clear()
        return event

    def _turn_event(self, before, after, position, timestamp, evidence='measured_turn'):
        speed_before,method=estimate_segment_speed(before)
        speed_after,_=estimate_segment_speed(after)
        measured=[value for value in (speed_before,speed_after) if value is not None]
        if measured and max(measured) < self.minimum_impact_speed_kmh:
            return None
        point=before[-1]
        return dict(timestamp=float(timestamp),position=np.asarray(position).tolist(),
                    trackID=point['trackID'],confidence=None,status='candidate',
                    kind='possible_impact',contactEvidence=evidence,
                    reason='Trajetórias de ida e volta indicam possível contato; confira a marca no replay.',
                    speedBefore=speed_before,speedAfter=speed_after,speedMethod=method,
                    metersPerPixel=point.get('metersPerPixel'),courtPosition=None,
                    inOrOut='INCONCLUSIVO')

    def _sparse_turn(self):
        """Localize a contact missed between two observed trajectory legs."""
        pts=list(self.points)[-4:]
        if len(pts)<4 or not all(self._identity(p) for p in pts): return None
        if any(p.get('insidePerson') for p in pts): return None
        t=np.array([p['timestamp'] for p in pts]); t-=t[0]
        if np.any(np.diff(t)<=0) or np.max(np.diff(t))>.22: return None
        xy=np.array([[p.get('measuredX',p['centerX']),p.get('measuredY',p['centerY'])] for p in pts])
        a=(xy[1]-xy[0])/(t[1]-t[0]); b=(xy[3]-xy[2])/(t[3]-t[2])
        sa,sb=np.linalg.norm(a),np.linalg.norm(b)
        # Sparse vertical reversals can be the apex of free flight. A lateral
        # reversal is needed when there is no observed sample at contact.
        if min(sa,sb)<60 or np.dot(a,b)/(sa*sb)>-.25 or a[0]*b[0]>=0: return None
        # Linear extrapolation through a mostly vertical arc overshoots its
        # apex by tens of pixels. Only lateral, nearly straight legs can use
        # this intersection; depth/observed turns handle the vertical case.
        if min(abs(a[0])/sa,abs(b[0])/sb)<.70: return None
        delta=a-b
        contact_t=float(np.dot(xy[2]-b*t[2]-xy[0],delta)/np.dot(delta,delta))
        margin=(t[2]-t[1])*.08
        if not t[1]+margin<contact_t<t[2]-margin: return None
        left=xy[0]+a*contact_t; right=xy[2]+b*(contact_t-t[2])
        mismatch=float(np.linalg.norm(left-right))
        size=min(pts[1].get('width',10),pts[1].get('height',10))
        if mismatch>max(4.,size*.4): return None
        event=self._turn_event(pts[:2],pts[2:],(left+right)*.5,
                               pts[0]['timestamp']+contact_t,'bracketed_turn')
        if event is None: return None
        event['positionUncertaintyPx']=max(3.,size*.15,mismatch*.5,max(sa,sb)*(t[2]-t[1])*.06)
        event['impactEstimatedBetweenFrames']=True
        return event

    def _measured_turn(self, pts):
        t=np.array([p['timestamp'] for p in pts])
        xy=np.array([[p.get('measuredX',p['centerX']),p.get('measuredY',p['centerY'])] for p in pts])
        steps=np.diff(xy,axis=0)
        if any(p.get('insidePerson') for p in pts): return None
        if not self._consistent_size(pts) or not self._away_from_edge(pts[2]): return None
        for leg in (steps[:2],steps[2:]):
            lengths=np.linalg.norm(leg,axis=1)
            if min(lengths)<3 or np.dot(*leg)/(lengths[0]*lengths[1])<.5: return None
        sizes=[max(p.get('width',1),p.get('height',1)) for p in pts]
        if max(sizes)/max(1,min(sizes))>3: return None
        before=(xy[2]-xy[0])/(t[2]-t[0]); after=(xy[4]-xy[2])/(t[4]-t[2])
        a,b=np.linalg.norm(before),np.linalg.norm(after)
        if min(a,b)<60: return None
        cosine=float(np.dot(before,after)/(a*b))
        if cosine>-.6:
            # Oblique hits need an abrupt corner, with stable motion on both
            # sides. This rejects a smooth flight apex without penalizing OUT.
            if not all(self._identity(p) for p in pts) or cosine>.82: return None
            v=steps/np.diff(t)[:,None]
            change=np.linalg.norm(v[2]-v[1])
            normal_variation=max(np.linalg.norm(v[1]-v[0]),np.linalg.norm(v[3]-v[2]))
            if change<max(min(a,b)*.65,normal_variation*2.5): return None
        return self._turn_event(pts[:3],pts[2:],xy[2],pts[2]['timestamp'])

    @staticmethod
    def _consistent_size(pts):
        sizes=np.array([p.get('ballDiameterPx',min(p.get('width',1),p.get('height',1))) for p in pts])
        ratios=sizes[1:]/np.maximum(1.,sizes[:-1])
        return bool(np.all((ratios>=.55)&(ratios<=1.8)))

    def _observed_wall_turn(self):
        """A measured corner plus a second return sample, without extrapolation."""
        pts=list(self.points)[-4:]
        if len(pts)<4 or not all(self._identity(p) for p in pts):return None
        if any(p.get('insidePerson') for p in pts) or not self._consistent_size(pts[:3]):return None
        if not self._away_from_edge(pts[1]):return None
        t=np.array([p['timestamp'] for p in pts]);dt=np.diff(t)
        if np.any(dt<=0) or np.max(dt)>.22:return None
        xy=np.array([[p.get('measuredX',p['centerX']),p.get('measuredY',p['centerY'])] for p in pts])
        v=np.diff(xy,axis=0)/dt[:,None];lengths=np.linalg.norm(v,axis=1)
        if min(lengths)<60:return None
        # A smooth vertical flight apex does not reverse sideways. Requiring a
        # coherent return avoids mistaking centroid jitter for a wall bounce.
        if v[0,0]*v[1,0]>=0 or min(abs(v[0,0])/lengths[0],abs(v[1,0])/lengths[1])<.25:return None
        if np.dot(v[0],v[1])/(lengths[0]*lengths[1])>-.4:return None
        if np.dot(v[1],v[2])/(lengths[1]*lengths[2])<.8:return None
        depth=[p.get('depthMeters') for p in pts[:3]]
        if all(z is not None for z in depth) and all(p.get('depthReliable') for p in pts[:3]):
            # Fast return blur changes contour thickness slightly. Depth must
            # support the approach and must not contradict the spatial turn;
            # the fourth sample confirms direction, not a second size peak.
            if depth[1]<depth[0]*1.025 or depth[1]<depth[2]*.94:return None
        event=self._turn_event(pts[:2],pts[1:],xy[1],pts[1]['timestamp'],'observed_wall_turn')
        if event:
            diameter=pts[1].get('ballDiameterPx',min(pts[1]['width'],pts[1]['height']))
            travel=min(np.linalg.norm(xy[1]-xy[0]),np.linalg.norm(xy[2]-xy[1]))
            event['positionUncertaintyPx']=max(3.,diameter*.18,travel*.25)
        return event

    @classmethod
    def _ground_identity(cls, point):
        """Identity evidence strong enough to participate in a floor bounce."""
        if not cls._identity(point):
            return False
        if point.get('source') == 'model':
            return True
        if point.get('tennisColor'):
            if not has_tennis_core(point):
                return False
            vivid=point.get('tennisVividPixels')
            if point.get('tennisCorePixels') is None and vivid is not None and vivid<20:
                return False
            return bool(point.get('tennisFraction', 0) >= .3 and
                        point.get('ballScore', 0) >= .52)
        # A camera-local signature may support another ball colour after it
        # has learned a clean, free-flight track. The older generic appearance
        # classifier alone is intentionally insufficient for physical contact.
        return bool(point.get('autoAppearanceVerified') and
                    point.get('autoAppearanceScore',0)>=.86 and
                    point.get('moving') and point.get('roundness',0)>=.35)

    @staticmethod
    def _ground_surface(point):
        # This broad image guard is independent of the IN/OUT target. Physical
        # evidence comes from the measured descending/ascending legs.
        return point.get('groundSurfaceEligible') is True

    @classmethod
    def _can_bridge_ground_contact(cls, before, contact):
        """Keep one measured descent sample when the ball clears the body.

        The next free-flight observation does not have to be on the floor.  It
        only seeds the approach; a contact still needs a measured floor sample
        and two observations on the return leg before it can be emitted.
        """
        if not before or not cls._ground_identity(before) or not cls._ground_identity(contact):
            return False
        if person_contact(contact):
            return False
        dt=observation_delta(before,contact)
        if not .001 < dt <= .42:
            return False
        bx,by=before.get('measuredX',before['centerX']),before.get('measuredY',before['centerY'])
        cx,cy=contact.get('measuredX',contact['centerX']),contact.get('measuredY',contact['centerY'])
        diameter=max(4.,float(contact.get('ballDiameterPx',min(contact.get('width',8),contact.get('height',8)))))
        downward=cy-by
        distance=float(np.hypot(cx-bx,downward))
        return bool(downward >= max(10.,diameter*.2) and downward/dt >= 60 and
                    distance <= max(180.,min(900.,dt*5000)))

    @classmethod
    def _continuous_ground_ball(cls, before, after):
        """Preserve physical observations across a short tracker reacquisition.

        A changed Kalman ID is not evidence that a measured return vanished.
        Both observations must independently identify a moving free ball.
        This only keeps evidence; descent/return geometry still proves contact.
        """
        if not before or not all(cls._ground_identity(p) and p.get('moving') and
                                 not person_contact(p) for p in (before,after)):
            return False
        if any(p.get('source')!='model' and
               (p.get('ballScore',0)<.75 or p.get('tennisFraction',0)<.45)
               for p in (before,after)):
            return False
        dt=observation_delta(before,after)
        if not .025<=dt<=.32:
            return False
        sizes=[max(3.,p.get('ballDiameterPx',min(p['width'],p['height'])))
               for p in (before,after)]
        if not .5<=sizes[1]/sizes[0]<=2:
            return False
        a=np.array([before.get('measuredX',before['centerX']),before.get('measuredY',before['centerY'])])
        b=np.array([after.get('measuredX',after['centerX']),after.get('measuredY',after['centerY'])])
        return bool(np.linalg.norm(b-a)<=min(900.,dt*5000+max(sizes)*3))

    @staticmethod
    def _ground_leg_continuous(first, second):
        return (first.get('trackID')==second.get('trackID') or
                second.get('groundAssociationBridged',False))

    def _ground_turn(self):
        """Confirm a floor bounce with one descent and two measured returns.

        A fast ball is often absent from the last frame before contact.  The
        former seven-point fit therefore discarded the real room bounces even
        though contact and two upward samples were plainly visible.  This
        detector localises the marker on the measured lowest point and still
        requires a coherent physical return near the selected floor.
        """
        pts=list(self.points)
        if len(pts)<4:
            return None
        located=self._ground_intersection(pts)
        if located:
            return located
        # Reconsider the last few possible contacts so one noisy return sample
        # does not permanently hide an otherwise measured bounce.
        for k in range(len(pts)-3,max(0,len(pts)-6),-1):
            if k<1 or k+2>=len(pts):
                continue
            contact=pts[k]; after=pts[k+1:k+3]
            if (any(person_contact(p) for p in [contact,*after]) or
                    not self._ground_surface(contact) or
                    not all(self._ground_identity(p) for p in [pts[k-1],contact,*after])):
                continue
            # Only the optional release/approach anchor may have the previous
            # track ID. Contact and both return samples must be one identity.
            if not all(self._ground_leg_continuous(a,b)
                       for a,b in zip([contact,*after],after)):
                continue
            times=observation_times(pts)
            if (times[k]-times[k-1]>.42 or times[k+1]-times[k]>.25 or
                    times[k+2]-times[k+1]>.25):
                continue
            xy=np.array([[p.get('measuredX',p['centerX']),p.get('measuredY',p['centerY'])]
                         for p in pts],dtype=float)
            diameter=float(contact.get('ballDiameterPx',min(contact.get('width',8),contact.get('height',8))))
            diameter=max(4.,diameter)
            sizes=np.array([max(2.,p.get('ballDiameterPx',min(p.get('width',2),p.get('height',2))))
                            for p in [contact,*after]],dtype=float)
            ratios=sizes[1:]/np.maximum(1.,sizes[:-1])
            if np.any(ratios<.28) or np.any(ratios>3.3):
                continue

            # Use up to two approach observations. A small last-frame movement
            # is acceptable when the preceding sample still proves descent.
            approach=None
            for j in range(k-1,max(-1,k-3),-1):
                if not self._ground_identity(pts[j]):
                    continue
                dt=times[k]-times[j]
                if not .001<dt<=.55:
                    continue
                drop=xy[k,1]-xy[j,1]
                if drop>=max(8.,diameter*.18) and drop/dt>=60:
                    intermediate=xy[j:k+1,1]
                    if np.max(intermediate)<=xy[k,1]+max(5.,diameter*.12):
                        approach=j
                        break
            if approach is None:
                continue

            rise_first=xy[k,1]-xy[k+1,1]
            rise_total=xy[k,1]-xy[k+2,1]
            return_dt=times[k+2]-times[k]
            tolerance=max(7.,diameter*.24)
            if (rise_first<max(9.,diameter*.18) or
                    rise_total<max(13.,diameter*.32) or
                    rise_total/return_dt<70 or
                    xy[k+2,1]>xy[k+1,1]+tolerance):
                continue

            before_points=pts[approach:k+1]
            after_points=pts[k:k+3]
            speed_before,method=estimate_segment_speed(before_points)
            speed_after,after_method=estimate_segment_speed(after_points)
            measured=[value for value in (speed_before,speed_after) if value is not None]
            if measured and max(measured)<self.minimum_impact_speed_kmh:
                continue
            # With only one pre-contact sample, the measured return gives a
            # better speed estimate than inventing an inbound position.
            displayed_speed=(speed_before if speed_before is not None and
                             speed_before>=self.minimum_impact_speed_kmh else speed_after)
            displayed_method=method if displayed_speed==speed_before else after_method
            # The lowest measured centre is the start of a contact corridor.
            # A fast phone stream can skip the actual bottom of the V, so move
            # the display marker a bounded distance along the measured inbound
            # direction and retain both endpoints for conservative IN/OUT.
            inbound=xy[k]-xy[approach]
            inbound_length=float(np.linalg.norm(inbound))
            return_length=float(np.linalg.norm(xy[k+1]-xy[k]))
            extra=min(inbound_length,return_length)*.55
            extra=min(extra,180.,diameter*6.)
            projected=xy[k].copy()
            # Dense, small steps already observe the bottom accurately. Only
            # form a corridor when the skipped travel is materially larger
            # than centroid noise and the ball footprint.
            located=True
            if inbound_length>0 and extra>max(12.,diameter*.35):
                candidate=xy[k]+inbound/inbound_length*extra
                frame_height=float(contact.get('frameHeight') or 0)
                if not frame_height or candidate[1]<=frame_height-diameter*.15:
                    projected=candidate
                else:
                    # The skipped contact could be below the visible frame.
                    # Keep the bounce for review without claiming IN/OUT at
                    # the last airborne point.
                    located=False
            possible=[xy[k].tolist()]
            if float(np.linalg.norm(projected-xy[k]))>1:
                possible.append(projected.tolist())
            uncertainty=max(3.,diameter*.22,
                            abs(projected[0]-xy[k,0])*.12,
                            abs(xy[k+1,0]-xy[k,0])*.12)
            return dict(timestamp=float(contact['timestamp']),position=projected.tolist(),
                        trackID=contact['trackID'],confidence=None,status='candidate',
                        kind='possible_bounce',contactEvidence='ground_measured_bounce',
                        reason='Quicada confirmada pela descida e por duas leituras de retorno; contato localizado na faixa entre quadros.',
                        speedBefore=displayed_speed,speedAfter=speed_after,
                        speedMethod=displayed_method,
                        speedObservedLeg=('approach' if displayed_speed==speed_before else 'return'),
                        metersPerPixel=contact.get('metersPerPixel'),
                        ballDiameterPx=diameter,positionUncertaintyPx=float(uncertainty),
                        possiblePositions=possible,contactRangePx=float(extra),
                        contactLocated=located,impactEstimatedBetweenFrames=True,
                        positionApproximate=True,courtPosition=None,
                        inOrOut='INCONCLUSIVO')

        # Upward-only motion can be a lifted/held ball. It proves no contact.
        return None

    def _ground_intersection(self, pts):
        """Locate contact between two measured descending and ascending frames.

        Work in timestamp space, not uniform frame indices: the phone replays
        contain irregular 80-280ms gaps. No prediction or scoring-area crossing
        can supply either observed leg.
        """
        window=list(pts)[-7:]
        candidates=[]
        for indexes in combinations(range(len(window)),4):
            # The fourth observation confirms the return. Older complete
            # brackets are reconsidered for at most two noisy later samples.
            if indexes[3] < len(window)-3:
                continue
            sample=[window[index] for index in indexes]
            if len(sample)!=4 or not all(self._ground_identity(p) for p in sample):
                continue
            if any(person_contact(p) for p in sample[1:]):
                continue
            # Reacquisition may change identity exactly at contact. Each
            # measured leg must be coherent, but the two legs need not share
            # a tracker ID.
            if (not self._ground_leg_continuous(sample[0],sample[1]) or
                    not self._ground_leg_continuous(sample[2],sample[3])):
                continue
            ts=observation_times(sample);ts-=ts[0]
            gaps=np.diff(ts)
            if (np.any(gaps<=.005) or gaps[0]>.32 or gaps[1]>.45 or gaps[2]>.32):
                continue
            xy=np.array([[p.get('measuredX',p['centerX']),p.get('measuredY',p['centerY'])]
                         for p in sample],float)
            sizes=np.array([max(3.,p.get('ballDiameterPx',min(p['width'],p['height']))) for p in sample])
            if np.max(sizes)/np.min(sizes)>2.5:
                continue
            diameter=float(np.median(sizes))
            inbound=(xy[1]-xy[0])/gaps[0]
            outbound=(xy[3]-xy[2])/gaps[2]
            if (inbound[1]<80 or outbound[1]>-80 or
                    xy[1,1]-xy[0,1]<max(9.,diameter*.25) or
                    xy[2,1]-xy[3,1]<max(9.,diameter*.25)):
                continue
            # Intersection of the two observed legs at the same instant.
            contact_t=(xy[2,1]-outbound[1]*ts[2]-xy[0,1])/(inbound[1]-outbound[1])
            if not ts[1]-.008<=contact_t<=ts[2]+.008:
                continue
            a=xy[0]+inbound*contact_t
            b=xy[2]+outbound*(contact_t-ts[2])
            if abs(a[0]-b[0])>max(12.,diameter*1.4):
                continue
            position=(a+b)*.5
            # A subsampled pair may skip the real lowest observation. Never
            # replace a measured deeper point with an intersection above it;
            # the measured corridor fallback can represent that contact.
            bracket=window[indexes[1]:indexes[2]+1]
            deepest=max(p.get('measuredY',p['centerY']) for p in bracket)
            if deepest>position[1]+max(8.,diameter*.35):
                continue
            thresholds=[p.get('groundSurfaceThresholdY') for p in sample[1:3]]
            frame_height=sample[1].get('frameHeight',0)
            if all(v is not None for v in thresholds) and frame_height:
                surface=position[1]/frame_height>=min(thresholds)
            else:
                surface=any(self._ground_surface(p) for p in sample[1:3])
            if not surface or (frame_height and position[1]>frame_height-diameter*.25):
                continue
            stamp=float(np.interp(contact_t,ts,[p['timestamp'] for p in sample]))
            if stamp<=self.last_ground_contact+.04:
                continue
            before,method=estimate_segment_speed(sample[:2])
            after,_=estimate_segment_speed(sample[2:])
            if max(float(before or 0),float(after or 0))<self.minimum_impact_speed_kmh:
                continue
            # Perspective and acceleration between samples remain uncertain.
            travel=min(np.linalg.norm(inbound)*(contact_t-ts[1]),
                       np.linalg.norm(outbound)*(ts[2]-contact_t))
            uncertainty=max(3.,diameter*.25,abs(a[0]-b[0])*.5,max(0.,travel)*.3)
            event=dict(timestamp=stamp,position=position.tolist(),trackID=sample[2]['trackID'],
                       confidence=None,status='candidate',kind='possible_bounce',
                       contactEvidence='ground_interpolated_bounce',
                       reason='Contato estimado entre quadros a partir da descida e subida medidas.',
                       speedBefore=before,speedAfter=after,speedMethod=method,
                       speedObservedLeg='approach',approachObserved=True,
                       metersPerPixel=sample[2].get('metersPerPixel'),ballDiameterPx=diameter,
                       positionUncertaintyPx=float(uncertainty),impactEstimatedBetweenFrames=True,
                       contactLocated=True,courtPosition=None,inOrOut='INCONCLUSIVO')
            # The floor contact is the deepest physically valid bracket. This
            # avoids choosing a slow intermediate detection above the actual
            # bounce when the stream has irregular analysis intervals.
            candidates.append((float(position[1]),-float(uncertainty),event))
        if candidates:
            return max(candidates,key=lambda item:(item[0],item[1]))[2]

    def _depth_turn(self,pts):
        """A shrinking ball must measurably grow again on a coherent return."""
        if not all(self._identity(p) and p.get('depthReliable',False) and
                   self._away_from_edge(p) for p in pts): return None
        t=np.array([p['timestamp'] for p in pts],dtype=float);t-=t[0]
        z=np.array([p.get('depthMeters',np.nan) for p in pts],dtype=float)
        if not np.isfinite(z).all() or np.any(np.diff(t)<=0):return None
        k=int(np.argmax(z))
        if k<1 or k>len(pts)-3:return None
        # Require actual approach and two return samples. Size changes caused
        # by a hand/occlusion or a switch to a poster are usually nonmonotonic.
        delta=np.diff(z); tolerance=.025*z[k]
        if np.any(delta[:k]<-tolerance) or np.any(delta[k:]>tolerance):return None
        if min(z[k]-z[0],z[k]-z[-1])/max(.1,z[k])<.09:return None
        sizes=np.array([p.get('ballDiameterPx',min(p.get('width',1),p.get('height',1))) for p in pts])
        ratios=sizes[1:]/np.maximum(1.,sizes[:-1])
        if np.any(ratios<.55) or np.any(ratios>1.8):return None
        xy=np.array([[p.get('measuredX',p['centerX']),p.get('measuredY',p['centerY'])] for p in pts])
        steps=np.diff(xy,axis=0); lengths=np.linalg.norm(steps,axis=1)
        # Flickering objects stay on one pixel, then jump to a different one.
        if np.max(lengths)>max(18.,np.median(sizes)) and np.min(lengths)<1.5:return None
        for leg in (steps[:k],steps[k:]):
            for first,second in zip(leg,leg[1:]):
                sa,sb=np.linalg.norm(first),np.linalg.norm(second)
                if min(sa,sb)>3 and np.dot(first,second)/(sa*sb)<.4:return None
        before=float(np.polyfit(t[:k+1],z[:k+1],1)[0])
        after=float(np.polyfit(t[k:],z[k:],1)[0])
        if before<.22 or after>-.22:return None
        point=pts[k]
        # A wall-plane projection cannot measure motion toward the camera.
        # Use the explicitly approximate ball-size estimate for this evidence.
        depth_points=[dict(p,planePosition=None) for p in pts]
        speed_before,method=estimate_segment_speed(depth_points[:k+1],include_depth=True)
        speed_after,_=estimate_segment_speed(depth_points[k:],include_depth=True)
        if max(float(speed_before or 0),float(speed_after or 0))<self.minimum_impact_speed_kmh:return None
        # Anchor to a real ball observation, with the unsampled travel stated
        # as uncertainty. Never advance the marker on a predicted trajectory.
        uncertainty=max(3.,sizes[k]*.18,min(lengths[k-1],lengths[k])*.35)
        return dict(timestamp=point['timestamp'],position=xy[k].tolist(),trackID=point['trackID'],
                    confidence=None,status='candidate',kind='possible_depth_impact',
                    reason='Aproximação e retorno medidos; marca no quadro mais próximo do contato.',
                    speedBefore=speed_before,speedAfter=speed_after,speedMethod=method,
                    metersPerPixel=point.get('metersPerPixel'),courtPosition=None,inOrOut='INCONCLUSIVO',
                    contactEvidence='depth_turn',ballDiameterPx=float(sizes[k]),
                    positionUncertaintyPx=float(uncertainty))

    def _update(self, track, court_position, mode='court'):
        if not track:
            self.points.clear()
            self.pending_contact = None
            return None
        if track['predicted']:
            # Keep the measured approach when one analysis frame misses the
            # fast ball. Clearing it here made a single skipped frame exactly
            # at the wall erase all evidence of the impact.
            return None
        if self.points and track['timestamp']<=self.points[-1]['timestamp']:
            return None
        # Tracking a held ball is useful for the overlay and for automatic
        # appearance learning, but body motion is never contact evidence.
        if person_contact(track):
            if mode=='court' and self._ground_identity(track) and track.get('moving'):
                self.release_anchor=dict(track,groundReleaseAnchor=True)
            self.points.clear()
            self.pending_contact = None
            self.track_id = track['trackID']
            return None
        previous=self.points[-1] if self.points else self.release_anchor
        identity_changed=track['trackID'] != self.track_id or track.get('associationReset')
        if identity_changed:
            bridge=(mode=='court' and self._can_bridge_ground_contact(previous,track))
            continuous=(mode=='court' and self._continuous_ground_ball(previous,track))
            if continuous and self.points:
                track=dict(track,groundAssociationBridged=True)
            else:
                self.points.clear()
            self.pending_contact = None
            self.track_id = track['trackID']
            if bridge and not self.points:
                self.points.append(previous)
                self.release_anchor=None
        elif mode=='court' and not self.points and self._can_bridge_ground_contact(self.release_anchor,track):
            self.points.append(self.release_anchor)
            self.release_anchor=None
        if self.points and (track['timestamp']<=self.points[-1]['timestamp'] or
                            track['timestamp']-self.points[-1]['timestamp']>(.42 if mode=='court' else .32)):
            # A missing approach/return cannot become contact evidence by
            # connecting two distant measurements after reacquisition.
            self.points.clear();self.pending_contact=None
        if mode=='court' and not self.points:
            track=dict(track,groundSequenceStart=True)
        self.points.append(track)
        # Crossing the scoring rectangle is not physical contact. Only a
        # measured turn or coherent depth reversal can score.
        if mode == 'room' and track['timestamp']-self.last_event >= self.cooldown_seconds:
            observed=self._observed_wall_turn()
            if observed:
                self.last_event=track['timestamp']
                return observed
            sparse=self._sparse_turn()
            if sparse:
                self.pending_contact=None
                self.last_event=track['timestamp']
                return sparse
        if mode=='room' and len(self.points)>=4 and track['timestamp']-self.last_event>=self.cooldown_seconds:
            for count in (5,4):
                if len(self.points)<count:continue
                sample=list(self.points)[-count:]
                if sample[-1]['timestamp']-sample[0]['timestamp']>.7:continue
                event=self._depth_turn(sample)
                if event:
                    self.last_event=track['timestamp'];self.pending_contact=None
                    return event
        if mode=='court':
            event=self._ground_turn()
            if event and track['timestamp']-self.last_event>=self.cooldown_seconds:
                self.last_event=track['timestamp']
                self.pending_contact=None
                self.release_anchor=None
                return event
            return None
        required = 5
        if len(self.points)<required:
            return None
        if track['timestamp']-self.last_event < self.cooldown_seconds:
            return None
        pts = list(self.points)[-required:]
        if pts[-1]['timestamp']-pts[0]['timestamp'] > (.7 if mode == 'room' else .5):
            return None
        if mode == 'room':
            event=self._measured_turn(pts)
            if event:
                self.last_event=track['timestamp']
                self.pending_contact=None
            return event
        return None
