"""Keep expensive JPEG/learning work off the ball-analysis thread."""
import logging
import threading


class LatestFramePublisher:
    def __init__(self, publish):
        self.publish=publish
        self.condition=threading.Condition()
        self.pending=None
        self.closing=False
        self.skipped=0
        self.thread=threading.Thread(target=self._run,daemon=True,name='analysis-frame-publisher')
        self.thread.start()

    def submit(self, frame, state, session_id):
        with self.condition:
            if self.closing:return
            if self.pending is not None:self.skipped+=1
            # One pending image; replace it if encoding falls behind. Its
            # metadata travels with it so overlays can never drift sideways.
            self.pending=(frame,state,session_id)
            self.condition.notify()

    def stop(self):
        with self.condition:
            self.closing=True
            self.condition.notify_all()
        self.thread.join(timeout=15)

    def _run(self):
        while True:
            with self.condition:
                self.condition.wait_for(lambda:self.closing or self.pending is not None)
                if self.pending is None:return
                item=self.pending
                self.pending=None
            try:self.publish(*item)
            except Exception:logging.getLogger('tennis.publisher').exception('Frame publication failed')
