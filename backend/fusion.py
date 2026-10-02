from dataclasses import dataclass
import cv2
import numpy as np


@dataclass
class CameraObservation:
    camera_id: str
    timestamp: float  # Must share a calibrated source clock, not host arrival time.
    point: tuple
    projection: np.ndarray


class MultiCameraFusion:
    """Geometry adapter only; identity association and clock sync require validation."""
    def triangulate(self,a,b,synchronized=False,same_ball=False,tolerance=.008):
        if not synchronized or not same_ball or a.camera_id==b.camera_id or abs(a.timestamp-b.timestamp)>tolerance:
            return None
        point=cv2.triangulatePoints(a.projection,b.projection,np.array(a.point,dtype=float).reshape(2,1),np.array(b.point,dtype=float).reshape(2,1))[:,0]
        if abs(point[3])<1e-9:
            return None
        point=point/point[3]
        if any((p@point)[2]<=0 for p in (a.projection,b.projection)):
            return None
        return point[:3].tolist()
