"""Conservative, camera-local ball appearance memory learned from motion tracks.

This is deliberately separate from the human-reviewed neural model.  A bad
pseudo-label can therefore never contaminate validated training data.  The
signature only observes measured, coherent tracks and persists a compact HSV
descriptor for each camera.
"""
from collections import deque
import hashlib
import json
import os
import threading
import time
from pathlib import Path

import cv2
import numpy as np

from .config import DATA


DESCRIPTOR_LENGTH = 24 * 8 + 12


def _crop(image, box, padding=.08):
    """Return a bounded crop around a candidate box, or ``None``."""
    x, y, width, height = map(float, box)
    if not np.isfinite([x, y, width, height]).all() or min(width, height) < 2:
        return None
    ih, iw = image.shape[:2]
    px, py = width * padding, height * padding
    left = max(0, int(np.floor(x - px)))
    top = max(0, int(np.floor(y - py)))
    right = min(iw, int(np.ceil(x + width + px)))
    bottom = min(ih, int(np.ceil(y + height + py)))
    if right - left < 2 or bottom - top < 2:
        return None
    return image[top:bottom, left:right]


def appearance_descriptor(image, box):
    """Describe colour without assuming that the ball is green or white."""
    patch = _crop(image, box)
    if patch is None:
        return None
    small = cv2.resize(patch, (32, 32), interpolation=cv2.INTER_AREA)
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    mask = np.zeros((32, 32), np.uint8)
    cv2.ellipse(mask, (16, 16), (13, 13), 0, 0, 360, 255, -1)
    hs = cv2.calcHist([hsv], [0, 1], mask, [24, 8], [0, 180, 0, 256]).ravel()
    value = cv2.calcHist([hsv], [2], mask, [12], [0, 256]).ravel()
    if hs.sum() <= 0 or value.sum() <= 0:
        return None
    # The parts have explicit weights so value changes from exposure and motion
    # blur cannot overpower the more stable hue/saturation signature.
    result = np.concatenate([hs / hs.sum() * .82, value / value.sum() * .18])
    return result.astype(np.float32)


def descriptor_similarity(first, second):
    """Bhattacharyya overlap in the range 0..1."""
    if first is None or second is None:
        return 0.
    a = np.asarray(first, np.float32)
    b = np.asarray(second, np.float32)
    if a.shape != (DESCRIPTOR_LENGTH,) or b.shape != (DESCRIPTOR_LENGTH,):
        return 0.
    a = np.maximum(a, 0); b = np.maximum(b, 0)
    a /= max(1e-9, float(a.sum())); b /= max(1e-9, float(b.sum()))
    return float(np.clip(np.sqrt(a * b).sum(), 0, 1))


def _colour_profile(image, box):
    patch = _crop(image, box)
    if patch is None:
        return None
    hsv = cv2.cvtColor(cv2.resize(patch, (32, 32), interpolation=cv2.INTER_AREA),
                       cv2.COLOR_BGR2HSV)
    yy, xx = np.ogrid[:32, :32]
    pixels = hsv[((xx - 15.5) ** 2 + (yy - 15.5) ** 2) <= 13 ** 2]
    if len(pixels) < 20:
        return None
    saturation = float(np.median(pixels[:, 1]))
    value = float(np.median(pixels[:, 2]))
    colourful = pixels[pixels[:, 1] >= max(18, saturation * .45)]
    hues = colourful[:, 0].astype(float) if len(colourful) >= 10 else pixels[:, 0].astype(float)
    angles = hues / 180 * 2 * np.pi
    hue = float((np.arctan2(np.sin(angles).mean(), np.cos(angles).mean())
                 / (2 * np.pi) * 180) % 180)
    hue_delta = np.abs((hues - hue + 90) % 180 - 90)
    return np.array([
        hue, saturation, value,
        max(4., float(np.percentile(hue_delta, 80))),
        max(8., float(np.percentile(np.abs(pixels[:, 1].astype(float) - saturation), 80))),
        max(10., float(np.percentile(np.abs(pixels[:, 2].astype(float) - value), 80))),
    ], np.float32)


class AdaptiveBallSignature:
    """Self-updating appearance signature, isolated per physical camera."""

    # Version 3 invalidates signatures learned before person-overlap was
    # checked across the whole trajectory.  In the 17 September room replay,
    # the old learner memorised the green shirt and then gave it almost the
    # same score as the tennis ball.
    signature_version = 3
    ready_samples = 5
    verify_threshold = .72

    def __init__(self, camera_id='default', folder=None):
        self.camera_id = str(camera_id)
        self.folder = Path(folder or os.getenv('TV_AUTO_LEARNING_DIR') or
                           DATA / 'learning' / 'automatic')
        self.folder.mkdir(parents=True, exist_ok=True)
        key = hashlib.sha256(self.camera_id.encode('utf-8')).hexdigest()[:24]
        self.path = self.folder / f'{key}.json'
        self.lock = threading.RLock()
        self.samples = 0
        self.descriptor = None
        self.profile = None
        self.updated_at = None
        self.last_similarity = None
        self.last_sample_t = -1e9
        self.last_sample_xy = None
        self.bootstrap_track_id = None
        self.novel = deque(maxlen=10)
        self._load()

    @property
    def ready(self):
        return self.samples >= self.ready_samples and self.descriptor is not None

    def _load(self):
        if not self.path.is_file():
            return
        try:
            data = json.loads(self.path.read_text(encoding='utf-8'))
            # Version 2 adds continuous-flight validation. Discard older
            # signatures because they may have been bootstrapped from a
            # flickering background detail; five new frames relearn safely.
            if data.get('version') != self.signature_version:
                return
            descriptor = np.asarray(data.get('descriptor'), np.float32)
            profile = np.asarray(data.get('profile'), np.float32)
            if (descriptor.shape != (DESCRIPTOR_LENGTH,) or profile.shape != (6,) or
                    not np.isfinite(descriptor).all() or not np.isfinite(profile).all()):
                return
            self.samples = max(0, int(data.get('samples', 0)))
            self.descriptor = descriptor
            self.profile = profile
            self.updated_at = data.get('updatedAt')
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            return

    def _save(self):
        if self.descriptor is None or self.profile is None:
            return
        payload = dict(version=self.signature_version, cameraId=self.camera_id, samples=self.samples,
                       descriptor=self.descriptor.tolist(), profile=self.profile.tolist(),
                       updatedAt=self.updated_at)
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(payload, separators=(',', ':')), encoding='utf-8')
        os.replace(temporary, self.path)

    def status(self):
        with self.lock:
            return dict(enabled=True, learnedWithoutLabels=True, sampleCount=self.samples,
                        ready=self.ready, lastSimilarity=self.last_similarity,
                        updatedAt=self.updated_at,
                        stage=('adaptando à bola em movimento' if not self.ready else
                               'assinatura automática ativa'))

    def score(self, image, box):
        with self.lock:
            if not self.ready:
                return None
            candidate = appearance_descriptor(image, box)
            value = descriptor_similarity(self.descriptor, candidate)
            self.last_similarity = value
            return value

    def score_candidates(self, image, candidates):
        """Annotate proposals; never discard one solely on appearance."""
        if not self.ready:
            return candidates
        for candidate in candidates:
            size = max(float(candidate.get('width', 0)), float(candidate.get('height', 0)))
            if size < 2 or size > min(image.shape[:2]) * .45:
                continue
            score = self.score(image, [candidate[k] for k in ('x', 'y', 'width', 'height')])
            if score is None:
                continue
            candidate['autoAppearanceScore'] = score
            if score >= self.verify_threshold:
                candidate['autoAppearanceVerified'] = True
                candidate['autoAppearanceVersion'] = self.samples
                geometry = min(1., max(0., float(candidate.get('roundness', 0))))
                motion = 1. if candidate.get('moving') or candidate.get('source') == 'model' else 0.
                candidate['ballScore'] = max(float(candidate.get('ballScore', 0)),
                                             .58 * score + .22 * geometry + .12 * motion)
        return candidates

    @staticmethod
    def _coherent(track, history):
        if (not track or track.get('predicted') or track.get('insidePerson') or
                (not track.get('moving') and track.get('source') != 'model' and
                 not track.get('appearanceVerified'))):
            return None
        track_id = track.get('trackID')
        points = [p for p in history if p.get('trackID') == track_id and not p.get('predicted')][-6:]
        if len(points) < 4:
            return None
        # A crop from a shirt, hand or logo must never teach the automatic
        # appearance memory.  Checking only the newest point allowed a track
        # that had spent most of its life on the player's torso to poison the
        # saved signature as soon as pose briefly expired.
        if any(p.get('insidePerson') for p in points):
            return None
        timestamps = np.asarray([p['timestamp'] for p in points], float)
        if np.any(np.diff(timestamps) <= 0):
            return None
        elapsed = float(timestamps[-1] - timestamps[0])
        if elapsed < .025 or elapsed > .8:
            return None
        xy = np.asarray([[p.get('measuredX', p['centerX']),
                          p.get('measuredY', p['centerY'])] for p in points], float)
        steps = np.linalg.norm(np.diff(xy, axis=0), axis=1)
        sizes = np.asarray([max(float(p.get('width', 0)), float(p.get('height', 0)))
                            for p in points], float)
        if min(sizes) < 2 or max(sizes) / max(1., min(sizes)) > 3.2:
            return None
        frame_width = float(track.get('frameWidth', 0))
        frame_height = float(track.get('frameHeight', 0))
        if frame_width and frame_height:
            margin_x = max(min(frame_width, frame_height) * .04, frame_width * .10)
            margin_y = max(min(frame_width, frame_height) * .04, frame_height * .07)
            # OSD meters, timestamps and app controls live on image edges and
            # can animate coherently. They may be detected, but never become
            # the remembered ball identity.
            if any(p.get('centerX', 0) < margin_x or p.get('centerY', 0) < margin_y or
                   p.get('centerX', 0) > frame_width - margin_x or
                   p.get('centerY', 0) > frame_height - margin_y for p in points):
                return None
        path = float(steps.sum())
        displacement = float(np.linalg.norm(xy[-1] - xy[0]))
        movement_floor = max(2., float(np.median(sizes)) * .12)
        # A flying ball advances on almost every measured frame and follows a
        # locally smooth path. Compression flicker on a poster instead sits on
        # one pixel for several frames and then jumps to another detail. That
        # pattern previously looked fast enough to poison the automatic
        # signature even though it was not one moving object.
        meaningful = int(np.count_nonzero(steps >= movement_floor))
        required = max(3, len(steps) - 1)
        if meaningful < required or displacement / max(1e-6, path) < .68:
            return None
        speed = path / elapsed
        if (path < max(12., float(np.median(sizes)) * 1.1) or
                speed < max(80., frame_width * .03)):
            return None
        return points, speed

    @staticmethod
    def _trusted(points, track, confirmed):
        if track.get('source') == 'model' or track.get('appearanceVerified'):
            return True
        vivid=track.get('tennisVividPixels')
        if (track.get('tennisColor') and (vivid is None or vivid>=20) and
                track.get('ballScore', 0) >= .62 and
                track.get('roundness', 0) >= .30):
            return True
        recent = points[-4:]
        return bool(confirmed and len(recent) >= 4 and
                    all(p.get('moving') for p in recent) and
                    np.median([p.get('roundness', 0) for p in recent]) >= .48 and
                    np.median([p.get('ballScore', 0) for p in recent]) >= .55)

    def _replace_from_novel(self):
        if len(self.novel) < 8:
            return False
        descriptors = [item[1] for item in self.novel]
        centre = np.mean(descriptors, axis=0)
        centre /= max(1e-9, float(centre.sum()))
        if min(descriptor_similarity(centre, value) for value in descriptors) < .78:
            return False
        profiles = np.asarray([item[2] for item in self.novel])
        self.descriptor = centre.astype(np.float32)
        self.profile = np.median(profiles, axis=0).astype(np.float32)
        self.samples = self.ready_samples
        self.updated_at = time.time()
        self.novel.clear()
        self._save()
        return True

    def learn(self, image, track, history, confirmed=False):
        """Learn only from a temporally coherent measured ball hypothesis."""
        evidence = self._coherent(track, history)
        if evidence is None:
            return False
        points, _ = evidence
        if not self._trusted(points, track, confirmed):
            return False
        timestamp = float(track.get('timestamp', 0))
        xy = np.array([track.get('measuredX', track['centerX']),
                       track.get('measuredY', track['centerY'])], float)
        size = max(float(track.get('width', 0)), float(track.get('height', 0)))
        if (timestamp - self.last_sample_t < .08 and self.last_sample_xy is not None and
                np.linalg.norm(xy - self.last_sample_xy) < max(3., size * .3)):
            return False
        descriptor = appearance_descriptor(image, [track[k] for k in ('x', 'y', 'width', 'height')])
        profile = _colour_profile(image, [track[k] for k in ('x', 'y', 'width', 'height')])
        if descriptor is None or profile is None:
            return False
        with self.lock:
            similarity = descriptor_similarity(self.descriptor, descriptor) if self.descriptor is not None else 1.
            self.last_similarity = similarity
            if not self.ready and self.descriptor is not None and (
                    self.bootstrap_track_id != track.get('trackID') or similarity < .58):
                # Bootstrap must come from one continuous, visually consistent
                # flight. Never average unrelated hypotheses merely because
                # they happened in the same session.
                self.samples = 1
                self.descriptor = descriptor
                self.profile = profile
                self.bootstrap_track_id = track.get('trackID')
                self.updated_at = time.time()
                self.last_sample_t = timestamp
                self.last_sample_xy = xy
                return True
            # Once established, reject a lone mismatch. Eight coherent and
            # mutually similar mismatches switch the remembered ball safely,
            # allowing a new colour without asking the user to label it.
            if self.ready and similarity < .62:
                self.novel.append((timestamp, descriptor, profile))
                while self.novel and timestamp - self.novel[0][0] > 3.0:
                    self.novel.popleft()
                return self._replace_from_novel()
            if self.ready and similarity < .72 and track.get('source') != 'model':
                return False
            self.novel.clear()
            alpha = 1. / min(12, self.samples + 1) if self.samples < self.ready_samples else .08
            if self.descriptor is None:
                self.descriptor = descriptor
                self.profile = profile
                self.bootstrap_track_id = track.get('trackID')
            else:
                self.descriptor = ((1 - alpha) * self.descriptor + alpha * descriptor).astype(np.float32)
                self.descriptor /= max(1e-9, float(self.descriptor.sum()))
                hue_delta = (float(profile[0]) - float(self.profile[0]) + 90) % 180 - 90
                blended = (1 - alpha) * self.profile + alpha * profile
                blended[0] = (float(self.profile[0]) + alpha * hue_delta) % 180
                self.profile = blended.astype(np.float32)
            self.samples += 1
            self.updated_at = time.time()
            self.last_sample_t = timestamp
            self.last_sample_xy = xy
            if self.samples >= self.ready_samples and (
                    self.samples == self.ready_samples or self.samples % 3 == 0):
                self._save()
            return True

    def colour_mask(self, hsv):
        """Create a broad learned-colour mask for focused reacquisition."""
        with self.lock:
            if not self.ready or self.profile is None:
                return None
            hue, saturation, value, hue_spread, sat_spread, value_spread = map(float, self.profile)
        sat_low = max(0, int(saturation - max(42, sat_spread * 2.4)))
        sat_high = min(255, int(saturation + max(52, sat_spread * 2.4)))
        value_low = max(35, int(value - max(62, value_spread * 2.5)))
        value_high = min(255, int(value + max(62, value_spread * 2.5)))
        sv = cv2.inRange(hsv, np.array([0, sat_low, value_low]),
                        np.array([179, sat_high, value_high]))
        # Hue carries little information for a white/gray ball.
        if saturation < 42:
            return sv
        radius = min(44, max(9, int(hue_spread * 2.5)))
        low, high = int(hue) - radius, int(hue) + radius
        if low < 0:
            hm = cv2.bitwise_or(cv2.inRange(hsv, np.array([0, 0, 0]), np.array([high, 255, 255])),
                                cv2.inRange(hsv, np.array([180 + low, 0, 0]), np.array([179, 255, 255])))
        elif high > 179:
            hm = cv2.bitwise_or(cv2.inRange(hsv, np.array([low, 0, 0]), np.array([179, 255, 255])),
                                cv2.inRange(hsv, np.array([0, 0, 0]), np.array([high - 180, 255, 255])))
        else:
            hm = cv2.inRange(hsv, np.array([low, 0, 0]), np.array([high, 255, 255]))
        return cv2.bitwise_and(hm, sv)
