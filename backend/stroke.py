"""Pose-assisted, explicitly approximate forehand/backhand classification."""
import numpy as np


class StrokeClassifier:
    """Remember the strongest stroke-side evidence for the active ball track.

    COCO pose labels anatomical left/right shoulders and wrists. Projecting the
    ball and dominant wrist onto that shoulder axis keeps the decision stable
    when a camera is placed at either end of the court. This is a pose
    heuristic, not racket recognition, so the event exposes its confidence and
    method instead of claiming a validated shot label.
    """

    def __init__(self, dominant_hand='right'):
        self.dominant_hand = dominant_hand if dominant_hand in ('left', 'right') else 'right'
        self.track_id = None
        self.best = None

    def _reset(self, track_id):
        self.track_id = track_id
        self.best = None

    @staticmethod
    def _joint(keypoints, index):
        if not keypoints or len(keypoints) <= index:
            return None
        value = np.asarray(keypoints[index], float)
        if value.shape[0] < 3 or not np.isfinite(value[:3]).all() or value[2] < .28:
            return None
        return value

    def _classify(self, track, person):
        joints = person.get('keypoints')
        left_shoulder = self._joint(joints, 5)
        right_shoulder = self._joint(joints, 6)
        dominant_wrist = self._joint(joints, 9 if self.dominant_hand == 'left' else 10)
        if left_shoulder is None or right_shoulder is None or dominant_wrist is None:
            return None
        dominant_shoulder = left_shoulder if self.dominant_hand == 'left' else right_shoulder
        other_shoulder = right_shoulder if self.dominant_hand == 'left' else left_shoulder
        shoulder_axis = dominant_shoulder[:2] - other_shoulder[:2]
        shoulder_width = float(np.linalg.norm(shoulder_axis))
        if shoulder_width < 8:
            return None
        shoulder_axis /= shoulder_width
        torso = (left_shoulder[:2] + right_shoulder[:2]) / 2
        ball = np.array([track.get('measuredX', track['centerX']),
                         track.get('measuredY', track['centerY'])], float)
        # Only learn the stroke while the ball is within racket reach. The
        # later landing on the opposite side must not overwrite this evidence.
        reach = float(np.linalg.norm(ball - torso)) / shoulder_width
        if reach > 3.2:
            return None
        ball_side = float(np.dot(ball - torso, shoulder_axis) / shoulder_width)
        wrist_side = float(np.dot(dominant_wrist[:2] - torso, shoulder_axis) / shoulder_width)
        score = .68 * ball_side + .32 * wrist_side
        if abs(score) < .16:
            return None
        pose_conf = float(min(left_shoulder[2], right_shoulder[2], dominant_wrist[2]))
        proximity = max(.35, 1 - max(0., reach - 1.) / 3.)
        confidence = float(np.clip((.5 + min(.4, abs(score) * .22)) * pose_conf * proximity,
                                   .05, .94))
        return dict(strokeType='forehand' if score > 0 else 'backhand',
                    strokeConfidence=confidence,
                    strokeMethod='pose_side_heuristic',
                    dominantHand=self.dominant_hand)

    def update(self, track, persons):
        if not track:
            return self.best
        track_id = track.get('trackID')
        if track_id != self.track_id:
            self._reset(track_id)
        if track.get('predicted'):
            return self.best
        candidates = []
        for person in persons or []:
            result = self._classify(track, person)
            if result:
                candidates.append(result)
        if candidates:
            strongest = max(candidates, key=lambda item: item['strokeConfidence'])
            if self.best is None or strongest['strokeConfidence'] > self.best['strokeConfidence']:
                self.best = strongest
        return self.best
