"""Offline replay evaluation. Never modifies sessions or the live learner."""
import os
os.environ.setdefault('OPENBLAS_NUM_THREADS', '1')
os.environ.setdefault('OMP_NUM_THREADS', '1')
import sys
import json
import time
import argparse
from pathlib import Path
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from backend.detection import BallDetector
from backend.tracking import BallTracker
from backend.bounce import BounceDetector
from backend.pipeline import Pipeline
from backend.calibration import CalibrationEngine
from backend.adaptive import AdaptiveBallSignature
from backend.target import classify_impact


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--name', default='baseline')
    parser.add_argument('--limit', type=int, default=0)
    parser.add_argument('--folder', default='data/diagnostics/ground-evening-2026-09-17')
    parser.add_argument('--state', default=None,
                        help='Optional status JSON. Accepts a status object or {"Status": ...}.')
    args = parser.parse_args()
    folder = ROOT / args.folder
    state_path=ROOT / args.state if args.state else folder / 'status.json'
    status = json.loads(state_path.read_text(encoding='utf-8-sig'))
    status = status.get('Status', status)
    replays = json.loads((folder / 'replays.json').read_text(encoding='utf-8-sig'))
    frames = {}
    for replay in replays:
        if replay['sessionId'] != status['session']['id']:
            continue
        base = ROOT / 'data/replays' / replay['id']
        for frame in json.loads((base / 'manifest.json').read_text()):
            frames[frame['timestamp']] = dict(frame, path=str(base / f"{frame['index']:05d}.jpg"))
    frames = sorted(frames.values(), key=lambda f: f['timestamp'])
    if args.limit:
        frames = frames[:args.limit]
    cv2.setNumThreads(2)
    p = Pipeline(None)
    p.calibration = CalibrationEngine()
    p.ball_diameter_m = .067
    p.horizontal_fov_deg = 70
    p.region = status.get('captureRegion')
    p.polygon = status.get('capturePolygon')
    p.environment = 'court'
    adaptive = AdaptiveBallSignature(status['session']['cameraId'])
    detector = BallDetector(external_pose=True, adaptive=adaptive)
    detector.region, detector.polygon = p.region, p.polygon
    tracker, bounce = BallTracker(), BounceDetector()
    rows, events, durations = [], [], []
    previous = None
    for i, frame in enumerate(frames):
        t = frame['timestamp']
        path = Path(frame['path'])
        if not path.exists():
            path = path.with_name(str(frame['index']) + '.jpg')
        image = cv2.imread(str(path))
        if image is None:
            raise RuntimeError(path)
        if previous is not None and t - previous > 1:
            tracker, bounce = BallTracker(), BounceDetector()
        previous = t
        persons = frame.get('overlay', {}).get('persons') or []
        started = time.perf_counter()
        source_time = frame.get('overlay', {}).get('sourceTime')
        source_clock = frame.get('overlay', {}).get('sourceClock')
        detector.prior = tracker.hint(t, source_time, source_clock)
        detections = detector.detect(image, t, persons=persons)
        for candidate in detections:
            candidate.update(sourceTime=frame.get('overlay',{}).get('sourceTime'),
                             sourceClock=frame.get('overlay',{}).get('sourceClock'))
        track = tracker.update(detections, t, source_time, source_clock)
        resolution = [image.shape[1], image.shape[0]]
        if track:
            p._enrich_track(track, resolution)
        if track and not track['predicted'] and bounce.track_id != track['trackID']:
            history = [x for x in tracker.history if not x.get('predicted') and
                       x['trackID'] == track['trackID'] and x['timestamp'] < t]
            for x in history[-2:]:
                bounce.update(p._enrich_track(dict(x), resolution), None, mode='court')
        event = bounce.update(track, None, mode='court', timestamp=t)
        durations.append((time.perf_counter() - started) * 1000)
        if event:
            event.update(classify_impact(event['position'], resolution, p.region, p.polygon,
                                        target_zone=status.get('targetZone','full'),
                                        orientation=status.get('courtOrientation','normal'),
                                        ball_diameter_px=event.get('ballDiameterPx', 0),
                                        uncertainty_px=event.get('positionUncertaintyPx', 2),
                                        contact_located=event.get('contactLocated', True),
                                        possible_positions=event.get('possiblePositions')))
            events.append(event)
        rows.append(dict(timestamp=t, path=str(path), track=track, candidates=detections,
                         persons=persons, old=frame.get('overlay', {}).get('track'), event=event,
                         stages=detector.stage_ms))
        if i % 100 == 0:
            print(f'{i}/{len(frames)} frames, {len(events)} contacts', flush=True)
    result = dict(frames=rows, events=events, summary=dict(
        frames=len(rows), detected=sum(bool(r['track'] and not r['track']['predicted']) for r in rows),
        events=len(events), medianMs=float(np.median(durations)), p95Ms=float(np.percentile(durations, 95))))
    (folder / f'{args.name}.json').write_text(json.dumps(result), encoding='utf-8')
    print(json.dumps(result['summary']), flush=True)


if __name__ == '__main__':
    main()
