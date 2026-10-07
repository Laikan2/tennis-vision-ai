"""Optional image-space target; it never crops the ball detector input."""
import cv2
import numpy as np


def target_polygon(region=None, polygon=None):
    if polygon:
        return np.asarray(polygon, dtype=np.float32)
    if region:
        x, y, right, bottom = region
        return np.array([[x, y], [right, y], [right, bottom], [x, bottom]], np.float32)
    return None


def validate_polygon(points):
    polygon = np.asarray(points, dtype=np.float32)
    if (polygon.shape != (4, 2) or not np.isfinite(polygon).all() or
            np.any(polygon < 0) or np.any(polygon > 1) or
            not cv2.isContourConvex(polygon) or abs(cv2.contourArea(polygon)) < .002):
        raise ValueError('Marque quatro cantos ao redor da superfície, sem cruzar as linhas.')
    return polygon.tolist()


def contains(x, y, region=None, polygon=None):
    target = target_polygon(region, polygon)
    return target is None or cv2.pointPolygonTest(target, (float(x), float(y)), False) >= 0


def map_target(x, y, region=None, polygon=None):
    target = target_polygon(region, polygon)
    if target is None:
        return [float(x), float(y)]
    matrix = cv2.getPerspectiveTransform(target, np.array([[0, 0], [1, 0], [1, 1], [0, 1]], np.float32))
    result = cv2.perspectiveTransform(np.array([[[x, y]]], np.float32), matrix)[0, 0]
    return result.tolist() if np.isfinite(result).all() else None


# Normalized dimensions of an official doubles court.  The mapped target is
# always 0..1, so the same zones work for a trapezoid seen in perspective.
SINGLES_INSET = 1.37 / 10.97
SERVICE_LINE = .5 - 6.40 / 23.77
TARGET_ZONES = {
    'full': (0., 0., 1., 1.),
    'singles': (SINGLES_INSET, 0., 1. - SINGLES_INSET, 1.),
    'far_half': (0., 0., 1., .5),
    'near_half': (0., .5, 1., 1.),
    'far_left_service': (SINGLES_INSET, SERVICE_LINE, .5, .5),
    'far_right_service': (.5, SERVICE_LINE, 1. - SINGLES_INSET, .5),
    'near_left_service': (SINGLES_INSET, .5, .5, 1. - SERVICE_LINE),
    'near_right_service': (.5, .5, 1. - SINGLES_INSET, 1. - SERVICE_LINE),
}


def orient_target(position, orientation='normal'):
    """Put cameras at opposite baselines into one global court coordinate."""
    if position is None:
        return None
    x, y = map(float, position)
    return [1. - x, 1. - y] if orientation == 'opposite' else [x, y]


def contains_mapped(position, target_zone='full', tolerance=.006):
    """Test a point already mapped into the normalized global court."""
    if position is None:
        return False
    x, y = map(float, position)
    left, top, right, bottom = TARGET_ZONES.get(target_zone, TARGET_ZONES['full'])
    return (left - tolerance <= x <= right + tolerance and
            top - tolerance <= y <= bottom + tolerance)


def court_sector(position):
    """Name the physical court sector for placement statistics."""
    if position is None:
        return 'unlocated'
    x, y = map(float, position)
    if not (0 <= x <= 1 and 0 <= y <= 1):
        return 'outside_court'
    if x < SINGLES_INSET:
        return 'left_alley'
    if x > 1 - SINGLES_INSET:
        return 'right_alley'
    side = 'left' if x < .5 else 'right'
    if y < SERVICE_LINE:
        depth = 'far_deep'
    elif y < .5:
        depth = 'far_service'
    elif y <= 1 - SERVICE_LINE:
        depth = 'near_service'
    else:
        depth = 'near_deep'
    return f'{depth}_{side}'


def contains_target(x, y, region=None, polygon=None, target_zone='full', tolerance=.006):
    """Test both the selected quadrilateral and its optional tennis zone."""
    mapped = map_target(x, y, region, polygon)
    if mapped is None:
        return False
    if not (region or polygon):
        return True
    return contains_mapped(mapped, target_zone, tolerance)


def classify_impact(position, resolution, region=None, polygon=None, target_zone='full',
                    orientation='normal', ball_diameter_px=0., uncertainty_px=2., contact_located=True,
                    possible_positions=None):
    """Classify the ball footprint in image pixels, with a boundary review band.

    Perspective distorts distances in normalized court coordinates. Measuring
    distance to the projected zone avoids a fixed normalized tolerance, and a
    ball that overlaps the painted target edge is considered inside.
    """
    surface=target_polygon(region,polygon)
    if surface is None:
        return dict(inOrOut='INCONCLUSIVO',varAlert=False,
                    decisionReason='Marque uma área para decidir dentro ou fora.')
    # An invalid observation or corrupt saved target must never become a
    # confident line call (or abort the complete video worker in OpenCV).
    try:
        positions_array=np.asarray([position,*(possible_positions or [])],dtype=float)
        size=np.asarray(resolution,dtype=float)
        radius=float(ball_diameter_px or 0.)*.5
        uncertainty=float(uncertainty_px if uncertainty_px is not None else 2.)
        valid=(surface.shape==(4,2) and np.isfinite(surface).all() and
               cv2.isContourConvex(surface) and abs(cv2.contourArea(surface))>=.002 and
               positions_array.ndim==2 and positions_array.shape[1]==2 and
               np.isfinite(positions_array).all() and size.shape==(2,) and
               np.isfinite(size).all() and np.min(size)>0 and
               np.isfinite([radius,uncertainty]).all() and radius>=0 and uncertainty>=0)
    except (ValueError,TypeError):
        valid=False
    if not valid:
        return dict(inOrOut='REVISAR',targetDecision='REVISAR',varAlert=False,
                    decisionReason='Dados do contato ou da área inválidos; revise a seleção e o replay.')
    left,top,right,bottom=TARGET_ZONES.get(target_zone,TARGET_ZONES['full'])
    zone=np.array([[left,top],[right,top],[right,bottom],[left,bottom]],np.float32)
    if orientation=='opposite': zone=1.-zone
    matrix=cv2.getPerspectiveTransform(np.array([[0,0],[1,0],[1,1],[0,1]],np.float32),surface)
    pixels=cv2.perspectiveTransform(zone[None],matrix)[0]*np.array(resolution,np.float32)
    positions=[position]
    for candidate in possible_positions or []:
        if (candidate and len(candidate)==2 and
                not any(np.linalg.norm(np.asarray(candidate,float)-np.asarray(value,float))<.5
                        for value in positions)):
            positions.append(candidate)
    margins=[float(cv2.pointPolygonTest(pixels,(float(value[0]),float(value[1])),True))
             for value in positions]
    margin=margins[0]
    uncertainty=max(1.,uncertainty)
    edges=[value+radius for value in margins]
    if not contact_located:
        decision='REVISAR'; reason='Quicada possível; faltam quadros para localizar o contato e decidir dentro ou fora.'
    elif all(edge>uncertainty for edge in edges):
        decision='IN'; reason=('Toda a faixa medida do contato está dentro da área.' if len(edges)>1 else
                               'A marca da bola está dentro ou toca a borda da área.')
    elif (all(edge < -uncertainty for edge in edges) and
          (len(positions)==1 or _envelope_clearance(positions,pixels)>radius+uncertainty)):
        decision='OUT'; reason=('Toda a faixa medida do contato está fora da área.' if len(edges)>1 else
                                'A marca inteira da bola está fora da área.')
    else:
        decision='REVISAR'; reason=('A faixa possível do contato cruza a linha; revise o replay.' if len(edges)>1 else
                                    'Muito perto da borda para decidir com a incerteza deste contato.')
    return dict(inOrOut=decision,targetDecision=decision,varAlert=decision=='OUT',
                decisionReason=reason,decisionMarginPx=margin,
                decisionMarginsPx=margins,positionUncertaintyPx=uncertainty,
                targetPolygonPx=pixels.tolist())


def _envelope_clearance(positions, polygon):
    """Distance of the whole possible-contact envelope to a convex target.

    Two outside endpoints can bracket an inside contact across a corner or
    the entire court. Checking endpoints alone falsely announced OUT.
    """
    hull=cv2.convexHull(np.asarray(positions,np.float32)).reshape(-1,2)
    if (any(cv2.pointPolygonTest(polygon,tuple(map(float,p)),False)>=0 for p in hull) or
            (len(hull)>=3 and cv2.pointPolygonTest(hull,tuple(map(float,polygon[0])),False)>=0)):
        return 0.
    def cross(a,b):return float(a[0]*b[1]-a[1]*b[0])
    def point_segment(p,a,b):
        d=b-a; length=float(d@d)
        t=float(np.clip((p-a)@d/length,0,1)) if length>1e-12 else 0.
        return float(np.linalg.norm(p-(a+t*d)))
    distance=float('inf')
    for i,a in enumerate(hull):
        b=hull[(i+1)%len(hull)]
        for j,c in enumerate(polygon):
            d=polygon[(j+1)%len(polygon)]
            delta=b-a;edge=d-c;den=cross(delta,edge)
            if abs(den)>1e-8:
                u=cross(c-a,edge)/den;v=cross(c-a,delta)/den
                if 0<=u<=1 and 0<=v<=1:return 0.
            distance=min(distance,point_segment(a,c,d),point_segment(b,c,d),
                         point_segment(c,a,b),point_segment(d,a,b))
    return distance
