"""Pixel and pose evidence shared by ball association and contact detection."""
import cv2
import numpy as np


def has_tennis_core(candidate):
    # Legacy/model observations without this measurement keep their own
    # evidence. A measured weak core must not be rescued by an HSV memory of
    # the surrounding skin, wall or floor.
    pixels = candidate.get('tennisCorePixels')
    return bool(candidate.get('tennisColor') and
                (pixels is None or (pixels >= 6 and candidate.get('tennisCoreFraction', 0) >= .06)))


def annotate_ball_evidence(image, candidates, persons):
    height, width = image.shape[:2]
    for c in candidates:
        if c.get('tennisColor'):
            x, y = max(0, int(c['x'])), max(0, int(c['y']))
            right = min(width, int(np.ceil(c['x'] + c['width'])))
            bottom = min(height, int(np.ceil(c['y'] + c['height'])))
            crop = image[y:bottom, x:right]
            if crop.size:
                hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
                core = cv2.inRange(hsv, np.array([24, 85, 65]), np.array([58, 255, 255]))
                c['tennisCorePixels'] = int(cv2.countNonZero(core))
                c['tennisCoreFraction'] = float(np.mean(core > 0))
        if c.get('insidePerson'):
            c['clearOfBody'] = clear_of_body(c, persons)
    return candidates


def clear_of_body(ball, persons):
    """A player rectangle includes empty space around/between their legs.

    Keep held balls and clothing excluded. A distinctive ball can pass through
    that empty space when reliable joints locate the actual body elsewhere.
    Missing joints stay conservative; they do not mean that a limb is absent.
    """
    if not persons or not has_tennis_core(ball) or not ball.get('moving'):
        return False
    point = np.array([ball['centerX'], ball['centerY']], np.float32)
    radius = max(3., float(ball.get('ballDiameterPx', min(ball['width'], ball['height']))) * .5)
    for person in persons:
        joints = np.asarray(person.get('keypoints', []), np.float32)
        age=max(0.,float(person.get('poseAgeMs',0)))/1000
        # A hand may extend beyond a stale body box. Check its exclusion zone
        # before dismissing a person whose rectangle does not overlap.
        if joints.shape==(17,3) and age<=2.2:
            for wrist in (9,10):
                reliable_wrist=joints[wrist,2]>=.35
                guard=radius+max(8.,person['height']*.025) if reliable_wrist else (
                    radius+person['height']*(.05+.16*age))
                if ((reliable_wrist or (age<=.9 and np.any(joints[wrist,:2]))) and
                        np.linalg.norm(point-joints[wrist,:2])<=guard):
                    return False
        margin = max(radius * 3, person['width'] * .08)
        if not (person['x'] - margin <= point[0] <= person['x'] + person['width'] + margin and
                person['y'] - margin <= point[1] <= person['y'] + person['height'] + margin):
            continue
        if joints.shape != (17, 3) or person.get('poseAgeMs', 0) > 2200:
            return False
        reliable = joints[:, 2] >= .35
        for wrist in (9, 10):
            if reliable[wrist] and np.linalg.norm(point-joints[wrist,:2]) <= radius+max(8.,person['height']*.025):
                return False
        # A lost optical-flow wrist still has its last measured coordinates.
        # For a contour OUTSIDE the actual body rectangle, use those only as
        # enlarged exclusion zones for a short time, never as visible joints.
        # One missing wrist otherwise erased a fast, clearly separated ball.
        wrists_clear=True
        for wrist in (9,10):
            if reliable[wrist]:
                continue
            guard=radius+person['height']*(.05+.16*age)
            if (age>.9 or not np.any(joints[wrist,:2]) or
                    np.linalg.norm(point-joints[wrist,:2])<=guard):
                wrists_clear=False
        if (wrists_clear and
                (point[0]<person['x']-radius*.35 or
                 point[0]>person['x']+person['width']+radius*.35 or
                 point[1]>person['y']+person['height']+radius*.35)):
            continue
        lower = reliable[11] and reliable[12] and point[1]>max(joints[11,1],joints[12,1])+radius
        required=(11,12,13,14,15,16) if lower else (5,6,9,10,11,12,13,14,15,16)
        if not all(reliable[i] for i in required):
            return False
        if not lower:
            torso = joints[[5, 6, 12, 11], :2].copy()
            if cv2.pointPolygonTest(torso, tuple(map(float, point)), True) >= -radius:
                return False
        limb = max(4., person['height'] * .027)
        for a, b in ((5, 7), (7, 9), (6, 8), (8, 10),
                     (11, 13), (13, 15), (12, 14), (14, 16)):
            if not reliable[a] or not reliable[b]:
                if lower and a<11:
                    continue
                return False
            start, delta = joints[a, :2], joints[b, :2] - joints[a, :2]
            u = np.clip(np.dot(point - start, delta) / max(1., np.dot(delta, delta)), 0, 1)
            if np.linalg.norm(point - (start + u * delta)) <= radius + limb:
                return False
        head = joints[:5][reliable[:5], :2]
        if len(head) and np.linalg.norm(point - head.mean(axis=0)) <= radius + person['height'] * .085:
            return False
    # The heuristic's large streak margin may mark a ball insidePerson even
    # when it is outside every nearby body/hand envelope.
    return True


def person_contact(point):
    return bool(point.get('insidePerson') and not point.get('clearOfBody'))
