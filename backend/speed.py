from collections import deque
import numpy as np
from .observation_time import observation_times


def measured_diameter(track):
    """Use contour thickness when blur is diagonal, not its axis-aligned box."""
    return float(track.get('ballDiameterPx') or min(float(track.get('width',0)),
                                                  float(track.get('height',0))))


def robust_velocity(times, points):
    times=np.asarray(times,dtype=float); points=np.asarray(points,dtype=float)
    if len(times)<2 or not np.isfinite(points).all() or np.any(np.diff(times)<=0):
        return None
    slopes=[(points[j]-points[i])/(times[j]-times[i]) for i in range(len(times))
            for j in range(i+1,len(times)) if times[j]-times[i]>.001]
    return np.median(slopes,axis=0) if slopes else None


def estimate_segment_speed(points, include_depth=False):
    """Estimate speed on one measured leg, never across an impact or prediction."""
    if len(points)<2 or any(p.get('predicted') for p in points):
        return None,None
    times=observation_times(points)
    if np.any(np.diff(times)>.32) or times[-1]-times[0]>.8:
        return None,None
    plane=[p.get('planePosition') for p in points]
    if all(p is not None for p in plane):
        velocity=robust_velocity(times,plane)
        method='ground_plane_projection'
    else:
        scales=[p.get('metersPerPixel') for p in points]
        if any(s is None or not np.isfinite(s) or s<=0 for s in scales):
            return None,None
        xy=[[p.get('measuredX',p['centerX']),p.get('measuredY',p['centerY'])] for p in points]
        velocity=robust_velocity(times,xy)
        if velocity is not None: velocity=velocity*float(np.median(scales))
        method='ball_diameter_estimate'
        depths=[p.get('depthMeters') for p in points]
        if (include_depth and len(points)>=3 and velocity is not None and
                all(p.get('depthReliable',False) for p in points) and
                all(z is not None and np.isfinite(z) for z in depths)):
            vz=robust_velocity(times,depths)
            if vz is not None:
                # Includes sideways motion as well as approach to the camera.
                # Focal length/diameter remain approximate, not measured 3-D.
                velocity=np.append(velocity,vz)
                method='monocular_depth_estimate'
    kmh=float(np.linalg.norm(velocity)*3.6) if velocity is not None else None
    return (kmh,method) if kmh is not None and np.isfinite(kmh) and kmh<=250 else (None,None)


class SpeedEstimator:
    """Homography yields a ground-plane PROJECTION, never airborne 3D speed."""
    def __init__(self, ball_diameter_m=.04):
        self.samples = deque(maxlen=6)
        self.track_id = None
        self.ball_diameter_m = ball_diameter_m
        self.mode = None
        self.time_basis = None

    def update(self, track, position):
        if track and track['predicted']:
            # A short occlusion must not erase all observed samples. Never
            # calculate a new speed from the predicted location itself.
            return None
        if not track:
            self.samples.clear()
            return None
        if track['trackID'] != self.track_id or track.get('associationReset'):
            self.samples.clear()
            self.track_id = track['trackID']
        calibrated = position is not None
        mode = 'calibrated' if calibrated else 'diameter'
        if self.mode != mode:
            self.samples.clear()
            self.mode = mode
        basis=track.get('sourceClock') if track.get('sourceTime') is not None else None
        if basis!=self.time_basis:
            self.samples.clear()
            self.time_basis=basis
        t = track['sourceTime'] if basis is not None else track['timestamp']
        if self.samples and (t <= self.samples[-1][0] or t-self.samples[-1][0] > .35):
            self.samples.clear()
        if calibrated:
            point = position
            metres_per_pixel = 1.0
        else:
            point = [track.get('measuredX', track['centerX']), track.get('measuredY', track['centerY'])]
            diameter = measured_diameter(track)
            if diameter < 3:
                self.samples.clear()
                return None
            metres_per_pixel = self.ball_diameter_m/diameter
        if len(self.samples)>=2:
            a=np.array(self.samples[-1][1])-np.array(self.samples[-2][1])
            b=np.array(point)-np.array(self.samples[-1][1])
            norm=np.linalg.norm(a)*np.linalg.norm(b)
            if norm>1e-8 and np.dot(a,b)/norm < -.35:
                # Fit approach and return separately instead of cancelling the
                # velocities around an impact into a misleading near-zero value.
                previous=self.samples[-1]
                self.samples.clear();self.samples.append(previous)
        depth=track.get('depthMeters') if track.get('depthReliable') and not calibrated else None
        if depth is not None and len(self.samples)>=2:
            earlier,latest=self.samples[-2],self.samples[-1]
            if (earlier[3] is not None and latest[3] is not None and
                    (latest[3]-earlier[3])*(depth-latest[3])<0 and
                    min(abs(latest[3]-earlier[3]),abs(depth-latest[3]))>.04):
                self.samples.clear(); self.samples.append(latest)
        self.samples.append((t,point,metres_per_pixel,depth))
        if len(self.samples)<3:
            return None
        times = np.array([v[0] for v in self.samples])
        times -= times[0]
        xy = np.array([v[1] for v in self.samples])
        # Median pairwise slopes reduce the effect of one jittery centroid.
        slopes=[(xy[j]-xy[i])/(times[j]-times[i]) for i in range(len(times))
                for j in range(i+1,len(times)) if times[j]-times[i]>.001]
        if not slopes:return None
        initial_velocity=np.median(slopes,axis=0)
        intercept=np.median(xy-times[:,None]*initial_velocity,axis=0)
        residual=np.linalg.norm(xy-(intercept+times[:,None]*initial_velocity),axis=1)
        tolerance=max(.015 if calibrated else 3.,float(np.median(residual))*3)
        keep=residual<=tolerance
        if keep.sum()<3:return None
        times=times[keep];xy=xy[keep]
        velocity = np.linalg.lstsq(np.column_stack([times,np.ones(len(times))]),xy,rcond=None)[0][0]
        scale = 1.0 if calibrated else float(np.median([v[2] for v in self.samples]))
        magnitude=np.linalg.norm(velocity)*scale
        depth_velocity=None
        depths=[sample[3] for sample in self.samples]
        if not calibrated and all(z is not None for z in depths):
            depth_velocity=robust_velocity([v[0] for v in self.samples],depths)
            if depth_velocity is not None:
                magnitude=np.hypot(magnitude,depth_velocity)
        kmh = float(magnitude*3.6)
        if not np.isfinite(kmh) or kmh > 250:
            return None
        return dict(projectedKmh=kmh, physicalKmh=None,
                    # This is a projection estimate; detector confidence is not
                    # a calibrated speed uncertainty.
                    confidence=None,
                    approximate=not calibrated,
                    method=('ground_plane_projection' if track.get('source') == 'model' else 'heuristic_ground_plane_projection')
                    if calibrated else ('monocular_depth_estimate' if depth_velocity is not None else 'ball_diameter_estimate'),
                    directionDegrees=float(np.degrees(np.arctan2(velocity[1],velocity[0]))))
