"""Optional physical floor boundary, independent of the IN/OUT target."""
import math


def validate_floor_boundary(points):
    if points is None:
        return None
    if (not 2 <= len(points) <= 6 or any(len(p) != 2 for p in points) or
            any(not math.isfinite(v) or not 0 <= v <= 1 for p in points for v in p)):
        raise ValueError('Marque de dois a seis pontos onde o piso encontra a parede, dentro da imagem.')
    ordered = sorted(points, key=lambda p: p[0])
    if (ordered[-1][0] - ordered[0][0] < .08 or
            any(b[0]-a[0] < .02 for a,b in zip(ordered,ordered[1:]))):
        raise ValueError('Afaste os dois pontos horizontalmente para definir o limite do piso.')
    return [list(p) for p in ordered]


def on_floor_side(position, resolution, boundary, diameter=0.):
    """The contact centre may be a radius above the visible floor boundary.

    This excludes the wall above the floor, including OUT positions outside
    the scoring target. It does not prove contact for a ball flying over floor.
    """
    if not boundary:
        return True
    w, h = resolution
    x, y = position
    normalized_x = x / w
    # A room corner has two visible wall-floor edges. Extend the end segments
    # and interpolate the marked polyline instead of imposing one flat wall.
    segment = min(len(boundary)-2, max(0, sum(normalized_x > p[0] for p in boundary)-1))
    (ax, ay), (bx, by) = boundary[segment:segment+2]
    line_y = (ay + (normalized_x - ax) * (by - ay) / (bx - ax)) * h
    return bool(y + max(3., diameter * .55) + h * .006 >= line_y)
