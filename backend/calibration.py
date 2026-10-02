import cv2
import numpy as np


class CalibrationEngine:
    def __init__(self, profile=None):
        self.profile = profile
        self.H = np.array(profile['homography']) if profile and profile.get('homography') is not None else None

    @staticmethod
    def fit(image_points, court_points, resolution, name, checks=None):
        src, dst = np.asarray(image_points, np.float64), np.asarray(court_points, np.float64)
        if src.shape != (4,2) or dst.shape != (4,2) or not np.isfinite(src).all() or not np.isfinite(dst).all():
            raise ValueError('Marque quatro pontos finitos e distintos.')
        if len(set(map(tuple,src))) != 4 or cv2.contourArea(cv2.convexHull(src.astype(np.float32))) < 100:
            raise ValueError('Pontos colineares ou muito próximos.')
        if not cv2.isContourConvex(src.astype(np.float32)):
            raise ValueError('Marque os cantos em ordem, sem cruzar as linhas.')
        width,height = resolution
        if width < 1 or height < 1 or (src<0).any() or (src[:,0]>width).any() or (src[:,1]>height).any():
            raise ValueError('Pontos fora da imagem.')
        H = cv2.getPerspectiveTransform(src.astype(np.float32), dst.astype(np.float32))
        if not np.isfinite(H).all() or abs(np.linalg.det(H)) < 1e-12:
            raise ValueError('Transformação degenerada.')
        error = None
        if checks and len(checks) >= 2:
            p = np.array([x['image'] for x in checks], np.float64)
            expected = np.array([x['court'] for x in checks], np.float64)
            if p.shape != (len(checks),2) or expected.shape != p.shape or not np.isfinite(p).all() or not np.isfinite(expected).all():
                raise ValueError('Pontos de verificação inválidos.')
            if any(np.linalg.norm(p[:,None,:]-src[None,:,:],axis=2).min(axis=1)<5):
                raise ValueError('Use pontos de verificação independentes dos quatro cantos.')
            mapped = cv2.perspectiveTransform(p.reshape(-1,1,2),H).reshape(-1,2)
            error = float(np.sqrt(np.mean(np.sum((mapped-expected)**2,axis=1))))
        quality = 'não validada' if error is None else ('Boa' if error<.15 else 'Regular' if error<.4 else 'Ruim')
        return dict(name=name, mode='court', homography=H.tolist(), resolution=resolution, imagePoints=image_points,
                    courtPoints=court_points, checks=checks or [], rmseMeters=error, quality=quality,
                    method='planar', lensCorrected=False)

    @staticmethod
    def fit_scale(image_points, distance_m, resolution, name):
        """Fit a simple pixel-to-metre scale for a room or table test.

        This is intentionally an approximate plane calibration: two points
        define a known distance, while the origin is arbitrary because only
        displacement is used for speed.
        """
        src = np.asarray(image_points, np.float64)
        if src.shape != (2, 2) or not np.isfinite(src).all():
            raise ValueError('No modo ambiente, marque exatamente dois pontos finitos.')
        if len(set(map(tuple, src))) != 2:
            raise ValueError('Os dois pontos precisam ser diferentes.')
        pixel_distance = float(np.linalg.norm(src[1] - src[0]))
        if pixel_distance < 8:
            raise ValueError('Afaste os pontos: a distância precisa ocupar mais pixels na imagem.')
        if not np.isfinite(distance_m) or distance_m <= 0 or distance_m > 100:
            raise ValueError('Informe uma distância real entre 0,01 e 100 metros.')
        width, height = resolution
        if width < 1 or height < 1 or (src < 0).any() or (src[:, 0] > width).any() or (src[:, 1] > height).any():
            raise ValueError('Pontos fora da imagem.')
        return dict(name=name, mode='room', homography=None, resolution=resolution,
                    imagePoints=image_points, courtPoints=[[0, 0], [float(distance_m), 0]],
                    checks=[], rmseMeters=None, quality='escala manual', method='scale',
                    scaleMetersPerPixel=float(distance_m) / pixel_distance,
                    imageOrigin=src[0].tolist(), dimensions=[float(distance_m)],
                    scaleDistanceM=float(distance_m))

    def map(self, x,y,resolution):
        if not self.profile or list(resolution) != list(self.profile['resolution']):
            return None
        if self.profile.get('mode') == 'room' or self.profile.get('method') == 'scale':
            scale = self.profile.get('scaleMetersPerPixel')
            origin = self.profile.get('imageOrigin', [0, 0])
            if scale is None:
                return None
            p = [(float(x) - origin[0]) * scale, (float(y) - origin[1]) * scale]
            return p if np.isfinite(p).all() else None
        if self.H is None:
            return None
        v = self.H@np.array([x,y,1.])
        if abs(v[2])<1e-9:
            return None
        p = v[:2]/v[2]
        return p.tolist() if np.isfinite(p).all() else None
