"""Video time measures motion; reception time orders live UI and replay events."""
import numpy as np


def observation_times(points):
    source=[p.get('sourceTime') for p in points]
    clocks=[p.get('sourceClock') for p in points]
    if (source and all(t is not None and np.isfinite(t) for t in source) and
            clocks[0] is not None and all(c==clocks[0] for c in clocks) and
            np.all(np.diff(source)>0)):
        return np.asarray(source,dtype=float)
    return np.array([p['timestamp'] for p in points],dtype=float)


def observation_delta(first, second):
    times=observation_times([first,second])
    return float(times[1]-times[0])
