"""Measure live snapshots against a long history in an isolated temporary database."""
import argparse
import json
import statistics
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from backend.database import Store
from backend.multi_pipeline import PipelineManager


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='tennis-history-') as folder:
        store = Store(Path(folder) / 'history.sqlite')
        rows = [('event', str(i), json.dumps(dict(
            id=str(i), sessionId='live' if i < 500 else f'past-{i // 500}',
            timestamp=float(i), cameraId='a', status='candidate',
            inOrOut='IN' if i % 3 else 'OUT', speedBefore=40.,
            targetPosition=[.4, .6], kind='possible_bounce',
        ))) for i in range(10000)]
        with store.db:
            store.db.executemany('INSERT INTO objects VALUES (?,?,?)', rows)
        manager = PipelineManager(store)
        manager.session = dict(id='live')
        manager.camera_id = 'a'
        manager.pipelines = {'a': SimpleNamespace(snapshot=lambda: dict(
            cameraStatus='online', stale=False, framesReceived=1000,
            framesProcessed=1000, droppedFrames=0, detectedFrames=900,
        ))}
        durations = []
        for _ in range(30):
            start = time.perf_counter()
            state = manager.snapshot()
            durations.append((time.perf_counter() - start) * 1000)
            assert state['statistics']['impacts'] == 500
        report = dict(historyEvents=10000, sessionEvents=500, calls=len(durations),
                      firstMs=durations[0], medianMs=statistics.median(durations),
                      p95Ms=sorted(durations)[int(len(durations) * .95)])
        store.db.close()
    print(json.dumps(report))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
