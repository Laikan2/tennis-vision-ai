from collections import deque
import numpy as np
from .ball_evidence import has_tennis_core, person_contact


class BallTracker:
    def __init__(self, max_gap=.72, green_only=False):
        self.max_gap = max_gap
        self.green_only = green_only
        self.track_id = 0
        self.state = None
        self.cov = np.eye(4)*100
        self.last_t = None
        self.last_seen = None
        self.last_detection = None
        self.history = deque(maxlen=180)
        self.observations = deque(maxlen=3)
        self.confirmed = False

    def _drop(self):
        self.state = None
        self.cov = np.eye(4)*100
        self.last_seen = None
        self.last_detection = None
        self.history.clear()
        self.observations.clear()
        self.confirmed = False

    def hint(self, t):
        """Non-mutating source-resolution prediction for a focused detector pass."""
        if self.state is None or self.last_t is None or self.last_seen is None:
            return None
        age=max(0.,t-self.last_seen)
        if age > self.max_gap:
            return None
        dt=max(0.,t-self.last_t)
        xy=self.state[:2]+self.state[2:]*dt
        last=self.last_detection or {}
        return dict(centerX=float(xy[0]),centerY=float(xy[1]),
                    velocityX=float(self.state[2]),velocityY=float(self.state[3]),age=age,
                    width=float(last.get('width',12)),height=float(last.get('height',12)),
                    tennisColor=bool(last.get('tennisColor')),
                    autoAppearanceVerified=bool(last.get('autoAppearanceVerified')),
                    autoAppearanceScore=last.get('autoAppearanceScore'))

    @staticmethod
    def _rank(detection):
        if detection.get('source') == 'model':
            return (2, detection.get('confidence', 0))
        score=detection.get('ballScore',
                            detection.get('roundness', 0)*min(1, detection.get('width', 0)/12))
        if detection.get('appearanceVerified'):
            score += .12*detection.get('appearanceScore',0)
        if detection.get('autoAppearanceVerified'):
            score += .58*detection.get('autoAppearanceScore',0)
            if detection.get('moving',False):
                score += .22
        if detection.get('tennisColor'):
            # Fluorescent tennis green is much more distinctive than generic
            # bright motion.  A low ranked white reflection used to keep the
            # Kalman filter locked on headphones/clothing while the real ball
            # was plainly visible elsewhere in the frame.
            score += .65
            if detection.get('tennisCorePixels') is not None:
                score += .25 if has_tennis_core(detection) else -.85
            if detection.get('moving', False):
                score += .3
            elif not detection.get('insidePerson'):
                # A green object that never moves is background evidence, not
                # a flying ball. This is the key distinction in rooms with
                # plants, bottles and coloured chair details.
                score -= .55
        if person_contact(detection) and detection.get('source') != 'model':
            # Green clothing and printed logos are valid colour proposals but
            # weak ball identity.  A real free-flight candidate must outrank
            # them immediately after release.  A very round held tennis ball
            # still scores enough to remain visible before the throw.
            score -= .75
        if (detection.get('insidePerson') and not detection.get('tennisColor') and
                not detection.get('autoAppearanceVerified')):
            score *= .78
        return (1,score)

    @staticmethod
    def _strong_tennis(detection):
        """Whether a heuristic proposal is distinctive enough to acquire."""
        size=max(detection.get('width',0),detection.get('height',0))
        held_by_player=bool(detection.get('insidePerson') and size >= 14)
        if (detection.get('autoAppearanceVerified') and
                detection.get('autoAppearanceScore',0) >= .76 and
                detection.get('roundness',0) >= .18):
            return bool(detection.get('moving') or held_by_player)
        if detection.get('appearanceVerified') and detection.get('roundness',0)>=.35:
            return bool(detection.get('moving') or held_by_player)
        if (detection.get('tennisColor') and detection.get('moving') and
                detection.get('foregroundFraction',0)>=.65 and
                detection.get('tennisFraction',0)>=.55 and
                detection.get('ballScore',0)>=.7 and detection.get('roundness',0)>=.18):
            # A clean moving streak is still a ball; require temporal
            # confirmation below instead of waiting for it to become round.
            return True
        return bool(detection.get('tennisColor') and
                    detection.get('tennisFraction',0) >= .42 and
                    detection.get('roundness',0) >= .42 and
                    detection.get('ballScore',0) >= .58 and
                    (detection.get('moving',False) or held_by_player))

    def update(self, detections, t):
        if self.last_t is not None and t <= self.last_t:
            return None
        if self.last_seen is not None and t-self.last_seen > self.max_gap:
            self._drop()
        dt = t-self.last_t if self.last_t is not None else 0
        self.last_t = t
        all_detections = [d for d in detections if not self.green_only or
                          d.get('source') == 'model' or
                          (d.get('autoAppearanceVerified') and d.get('autoAppearanceScore',0)>=.76 and
                           d.get('moving')) or
                          (d.get('tennisColor') and (d.get('ballScore',0) >= .7 or
                           (d.get('appearanceVerified') and d.get('appearanceScore',0)>=.94 and
                            d.get('moving') and d.get('tennisFraction',0)>=.4)))]
        gap_before_detection=(t-self.last_seen if self.last_seen is not None else None)
        strong_tennis=[d for d in all_detections if self._strong_tennis(d)]
        moving_tennis=[d for d in all_detections if d.get('moving',False) and
                       ((d.get('tennisColor') and d.get('tennisFraction',0) >= .3 and
                         d.get('ballScore',0) >= .52) or
                        (d.get('autoAppearanceVerified') and
                         d.get('autoAppearanceScore',0) >= .76))]
        free_flight=[d for d in moving_tennis if not person_contact(d) and
                     self._strong_tennis(d)]
        rebound_reacquired = False
        if self.state is not None:
            F = np.array([[1,0,dt,0],[0,1,0,dt],[0,0,1,0],[0,0,0,1]])
            self.state = F@self.state
            self.cov = F@self.cov@F.T + np.diag([2,2,200,200])*max(dt,.001)
            # Gate by elapsed time and covariance; no identity across long gaps.
            observed_gap = t-self.last_seen if self.last_seen is not None else dt
            radius = max(45, min(520, 80+1600*observed_gap))
            detections = [d for d in all_detections if np.linalg.norm(np.array([d['centerX'],d['centerY']])-self.state[:2]) < radius]
            if self.last_detection:
                size=max(self.last_detection['width'],self.last_detection['height'])
                detections=[d for d in detections if .3 <= max(d['width'],d['height'])/max(1,size) <= 3]
                # Once a green ball is flying, a stationary green detail cannot
                # become its next observation merely because prediction passed
                # nearby. A held ball inside the player box remains eligible.
                if ((self.last_detection.get('tennisColor') or
                     self.last_detection.get('autoAppearanceVerified')) and
                        (self.last_detection.get('moving',False) or
                         np.linalg.norm(self.state[2:]) >= 80)):
                    detections=[d for d in detections if d.get('source') == 'model' or
                                d.get('moving',False) or
                                (d.get('insidePerson') and self._strong_tennis(d))]
            if not self.confirmed:
                detections=[d for d in detections if d.get('source') == 'model' or d.get('moving',True) or
                            self._strong_tennis(d) or
                            (not d.get('insidePerson') and d.get('roundness',0) >= .72 and
                             max(d.get('width',0),d.get('height',0)) >= 18)]
            chosen = min(detections,key=lambda d:(
                np.linalg.norm(np.array([d['centerX'],d['centerY']])-self.state[:2]) -
                20*self._rank(d)[1])) if detections else None
            # A large, saturated, moving tennis contour is stronger evidence
            # than a weak colour fragment close to a stale prediction. In the
            # real room replay this selects the visible ball (scores .92–.99)
            # instead of the player's arm (scores around .50–.70).
            if moving_tennis:
                last_xy=np.array([self.last_detection['centerX'],self.last_detection['centerY']])
                reachable=max(radius,min(800,100+np.linalg.norm(self.state[2:])*observed_gap*1.6))
                plausible=[d for d in moving_tennis if
                           np.linalg.norm(np.array([d['centerX'],d['centerY']])-last_xy)<=reachable]
                elite=max(plausible,key=self._rank) if plausible else None
                stronger=max(moving_tennis,key=self._rank)
                if (stronger.get('ballScore',0)>=.75 and
                        (stronger.get('appearanceVerified') or
                         (stronger.get('ballScore',0)>=.9 and self._strong_tennis(stronger))) and
                        (elite is None or self._rank(stronger)[1]>=self._rank(elite)[1]+.05)):
                    elite=stronger  # Clean identity restart below, not a rebound.
            else:
                elite=None
            # Pose overlap is intentionally permissive so a held ball can be
            # drawn.  As soon as a distinctive moving proposal leaves the
            # body, prefer that free-flight observation over shirt texture.
            if self.last_detection and self.last_detection.get('insidePerson') and free_flight:
                free_elite=max(free_flight,key=self._rank)
                if elite is None or self._rank(free_elite)[1] >= self._rank(elite)[1]-.08:
                    elite=free_elite
            if elite is not None:
                elite_score=self._rank(elite)[1]
                chosen_score=self._rank(chosen)[1] if chosen is not None else -1
                if chosen is None or elite_score >= chosen_score+.05:
                    distance=float(np.linalg.norm(
                        np.array([elite['centerX'],elite['centerY']])-self.state[:2]))
                    previous_size=max(self.last_detection.get('width',0),
                                      self.last_detection.get('height',0))
                    quality_gain=(elite.get('ballScore',0)-
                                  self.last_detection.get('ballScore',0))
                    chosen=elite
                    discontinuous=np.linalg.norm(np.array([elite['centerX'],elite['centerY']])-last_xy)>reachable
                    same_ball=(has_tennis_core(elite) and has_tennis_core(self.last_detection) and
                               elite.get('moving') and observed_gap <= .3 and
                               .35 <= min(elite['width'],elite['height']) / max(2.,min(
                                   self.last_detection['width'],self.last_detection['height'])) <= 2.8)
                    if same_ball and discontinuous:
                        # A real 38px ball moved 244px across a 243px gate on
                        # the measured return. Account for contour extent at
                        # the gate, rather than restarting identity by 1px.
                        discontinuous=(np.linalg.norm(np.array([elite['centerX'],elite['centerY']])-last_xy) >
                                       reachable+max(25.,previous_size*1.5))
                    if discontinuous or (not same_ball and quality_gain >= .15 and distance > max(50,previous_size*2)):
                        # Re-anchor immediately but discard the wrong visual
                        # segment. Keeping confirmation avoids a two-frame hole
                        # exactly when the real ball becomes obvious.
                        # A correction to another object starts a new identity.
                        # Old and new centres must not produce a fake rebound.
                        retain_detection=self.confirmed and (elite.get('appearanceVerified') or
                            elite.get('autoAppearanceVerified') or self._strong_tennis(elite))
                        self._drop()
                        self.confirmed=bool(retain_detection)
                    elif distance > radius*.75:
                        rebound_reacquired=True
            # The supplied room replay showed a confirmed track sitting for
            # seconds on a green shelf detail while the real tennis ball flew
            # through the image. Prefer a genuinely moving green proposal and
            # start a clean trail instead of drawing a line between both.
            current_speed=float(np.linalg.norm(self.state[2:])) if self.state is not None else 0
            current_static=bool(self.last_detection and
                                not self.last_detection.get('moving',False) and
                                current_speed < 80)
            if current_static and moving_tennis:
                replacement=max(moving_tennis,key=self._rank)
                distance=float(np.linalg.norm(
                    np.array([replacement['centerX'],replacement['centerY']])-self.state[:2]))
                current_size=max(self.last_detection.get('width',0),self.last_detection.get('height',0))
                if distance > max(35,current_size*2):
                    chosen=replacement
                    self._drop()
            # A false bright detail can remain close to the prediction forever.
            # If a distinctive green tennis ball appears, start a clean track
            # on it even when it lies outside the old prediction gate.
            if (strong_tennis and self.last_detection and
                    not (self.last_detection.get('tennisColor') or
                         self.last_detection.get('autoAppearanceVerified'))):
                chosen=max(strong_tennis,key=self._rank)
                self._drop()
            # On a wall hit the Kalman prediction continues forward while the
            # real ball immediately travels back. Reacquire a strong moving
            # candidate near the last measured point without changing track ID.
            observed_gap = t-self.last_seen if self.last_seen is not None else None
            if chosen is None and self.confirmed and self.last_detection and observed_gap and observed_gap <= self.max_gap:
                last_xy=np.array([self.last_detection['centerX'],self.last_detection['centerY']])
                previous_size=max(self.last_detection['width'],self.last_detection['height'])
                rebound_radius=max(160,min(900,140+2400*observed_gap))
                rebounds=[]
                for detection in all_detections:
                    size=max(detection['width'],detection['height'])
                    score=self._rank(detection)
                    ratio=size/max(1,previous_size)
                    size_ok=(.15 <= ratio <= 6 if detection.get('tennisColor') else .3 <= ratio <= 3)
                    keeps_colour=(not (self.last_detection.get('tennisColor') or
                                       self.last_detection.get('autoAppearanceVerified')) or
                                  detection.get('tennisColor') or
                                  detection.get('autoAppearanceVerified'))
                    has_motion=(detection.get('source') == 'model' or
                                detection.get('moving',False) or observed_gap <= .14)
                    if (has_motion or
                            self._strong_tennis(detection)) and score[1] >= .58 \
                            and size_ok \
                            and keeps_colour \
                            and np.linalg.norm(np.array([detection['centerX'],detection['centerY']])-last_xy) < rebound_radius:
                        rebounds.append(detection)
                if rebounds:
                    chosen=max(rebounds,key=lambda d:(
                        self._rank(d)[1]-.45*np.linalg.norm(
                            np.array([d['centerX'],d['centerY']])-last_xy)/rebound_radius))
                    rebound_reacquired=True
            # An unconfirmed false highlight must not lock the tracker while a
            # better moving ball appears elsewhere in the image.
            if chosen is None and not self.confirmed:
                alternatives=[d for d in all_detections if d.get('source') == 'model' or d.get('moving',True)]
                self._drop()
                chosen=max(alternatives,key=self._rank) if alternatives else None
        else:
            detections = [d for d in all_detections if d.get('source') == 'model' or
                          d.get('moving',True) or self._strong_tennis(d)]
            chosen = max(detections,key=self._rank) if detections else None
        if chosen:
            self.observations.append(chosen)
            z = np.array([chosen['centerX'],chosen['centerY']])
            if self.state is None:
                self.track_id += 1
                self.state = np.array([*z,0.,0.])
                self.cov = np.diag([5.,5.,10000.,10000.])
            else:
                observed_gap=t-self.last_seen if self.last_seen is not None else None
                measured_velocity=((z-np.array([self.last_detection['centerX'],self.last_detection['centerY']]))/observed_gap
                                   if self.last_detection and observed_gap and observed_gap > 0 else None)
                predicted_velocity=self.state[2:].copy()
                hard_turn=False
                if measured_velocity is not None:
                    a,b=np.linalg.norm(measured_velocity),np.linalg.norm(predicted_velocity)
                    hard_turn=(min(a,b) > 80 and np.dot(measured_velocity,predicted_velocity)/(a*b) < -.35
                               and np.linalg.norm(z-self.state[:2]) > 10)
                if rebound_reacquired or hard_turn:
                    velocity=measured_velocity if measured_velocity is not None else np.zeros(2)
                    self.state=np.array([*z,*velocity])
                    self.cov=np.diag([5.,5.,1000.,1000.])
                else:
                    H = np.array([[1,0,0,0],[0,1,0,0]])
                    K = self.cov@H.T@np.linalg.inv(H@self.cov@H.T+np.eye(2)*4)
                    self.state += K@(z-H@self.state)
                    self.cov = (np.eye(4)-K@H)@self.cov
            if gap_before_detection is not None and gap_before_detection > .28:
                self.history.clear()
            self.last_seen = t
            self.last_detection = chosen
            if (chosen.get('source') == 'model' or
                    (chosen.get('autoAppearanceVerified') and
                     chosen.get('autoAppearanceScore',0)>=.86 and
                     chosen.get('moving') and chosen.get('roundness',0)>=.42) or
                    (chosen.get('appearanceVerified') and chosen.get('appearanceScore',0)>=.95 and
                     chosen.get('roundness',0)>=.55 and chosen.get('tennisFraction',0)>=.6 and
                     chosen.get('moving'))):
                self.confirmed = True
            elif ((chosen.get('appearanceVerified') or chosen.get('autoAppearanceVerified') or
                   (self._strong_tennis(chosen) and chosen.get('moving'))) and
                  len(self.observations)>=2 and
                  all(p.get('appearanceVerified') or p.get('autoAppearanceVerified') or
                      (self._strong_tennis(p) and p.get('moving') and
                       p.get('tennisFraction',0)>=.55 and p.get('ballScore',0)>=.7)
                      for p in list(self.observations)[-2:])):
                # Two independently observed, learned ball crops acquire faster
                # than the three-frame colour-only path. Never confirm predictions.
                first,last=list(self.observations)[-2:]
                elapsed=last['timestamp']-first['timestamp']
                distance=np.hypot(last['centerX']-first['centerX'],last['centerY']-first['centerY'])
                max_size=max(first['width'],first['height'],last['width'],last['height'])
                if 0<elapsed<=.15 and distance<=min(700,max(160,max_size*2.5,elapsed*5000)):
                    self.confirmed=True
        if self.state is None:
            return None
        # A Kalman state is useful for gating and predicting the next frame, but
        # its smoothed position visibly trails a fast nearby ball. Whenever a
        # real contour exists, render the measured centre exactly on that ball;
        # use the state position only during a short occlusion.
        display_xy=(np.array([self.last_detection['centerX'],self.last_detection['centerY']])
                    if chosen else self.state[:2])
        prediction_age=0. if chosen else max(0.,t-self.last_seen)
        result = dict(self.last_detection, centerX=float(display_xy[0]), centerY=float(display_xy[1]),
                      measuredX=self.last_detection['centerX'], measuredY=self.last_detection['centerY'],
                      velocityX=float(self.state[2]), velocityY=float(self.state[3]),
                      trackID=self.track_id, predicted=not bool(chosen),
                      predictionAge=prediction_age,timestamp=t)
        if chosen or prediction_age <= .24:
            self.history.append(result)
        if result['source'] != 'model' and not self.confirmed:
            if len(self.observations) < 3:
                return None
            positions = np.array([[p['centerX'], p['centerY']] for p in self.observations])
            sizes = [max(p['width'], p['height']) for p in self.observations]
            steps = np.diff(positions, axis=0)
            lengths = np.linalg.norm(steps, axis=1)
            held_ball = (all(p.get('roundness',0) >= .72 and
                             (not p.get('insidePerson') or self._strong_tennis(p))
                             for p in self.observations) and max(sizes) >= 18)
            held_tennis = all(self._strong_tennis(p) for p in self.observations)
            coherent_motion = min(lengths) >= 3 and np.dot(*steps)/(lengths[0]*lengths[1]) >= .7
            if max(sizes)/max(1,min(sizes)) > 1.8 or not (held_ball or held_tennis or coherent_motion):
                # Keep the newest observation as a fresh hypothesis. This
                # avoids confirming a static shirt/logo after many attempts.
                newest = chosen
                self._drop()
                if newest is not None:
                    z = np.array([newest['centerX'],newest['centerY']])
                    self.track_id += 1
                    self.state = np.array([*z,0.,0.])
                    self.cov = np.diag([5.,5.,10000.,10000.])
                    self.last_seen = t
                    self.last_detection = newest
                    self.observations.append(newest)
                return None
            self.confirmed = True
        return result
