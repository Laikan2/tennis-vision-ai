import json
import sqlite3
import threading
import time
import uuid
from .config import DATA


class Store:
    def __init__(self, path=DATA / 'tennis.sqlite3'):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('CREATE TABLE IF NOT EXISTS objects (kind TEXT, id TEXT, payload TEXT, PRIMARY KEY(kind,id))')
        self.db.commit()

    def put(self, kind, obj):
        obj = dict(obj)
        obj.setdefault('id', str(uuid.uuid4()))
        obj.setdefault('createdAt', time.time())
        with self.lock, self.db:
            self.db.execute('INSERT OR REPLACE INTO objects VALUES (?,?,?)', (kind, obj['id'], json.dumps(obj, allow_nan=False)))
        return obj

    def get(self, kind, id):
        with self.lock:
            row = self.db.execute('SELECT payload FROM objects WHERE kind=? AND id=?', (kind, id)).fetchone()
        return json.loads(row[0]) if row else None

    def all(self, kind):
        with self.lock:
            rows = self.db.execute('SELECT payload FROM objects WHERE kind=? ORDER BY rowid DESC', (kind,)).fetchall()
        return [json.loads(r[0]) for r in rows]
