"""Generate a labelled synthetic source for Android MediaCodec -> detector -> UI tests.
No user recordings. Three contacts: IN, OUT, near-line REVIEW. Then hand, air and static negatives.
"""
from pathlib import Path
import argparse
import subprocess
import numpy as np
import cv2

parser = argparse.ArgumentParser()
parser.add_argument('--ffmpeg', required=True)
args = parser.parse_args()
destination = Path(__file__).resolve().parents[1] / 'mobile/android/app/src/androidTest/assets/known-bounces.mp4'
destination.parent.mkdir(parents=True, exist_ok=True)
encoder = subprocess.Popen([args.ffmpeg, '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'bgr24',
    '-s', '320x240', '-r', '30', '-i', '-', '-an', '-c:v', 'libx264', '-crf', '18', '-pix_fmt', 'yuv420p', str(destination)], stdin=subprocess.PIPE)
def frame(x=None, y=None, hand=False):
    image = np.full((240,320,3), [50,37,32], dtype=np.uint8)
    if x is not None:
        if hand: cv2.rectangle(image, (x-18,y-18), (x+18,y+18), (98,128,188), -1)
        cv2.circle(image,(x,y),6,(77,235,199),-1)
    encoder.stdin.write(image.tobytes())

ys = [60,72,87,105,125,145,166,144,124,105,90,80,76,80]
for x in (140,275,231):
    for _ in range(12): frame()
    for y in ys: frame(x,y)
for _ in range(12): frame()
for y in ys: frame(275,y,True)
for _ in range(12): frame()
for x in range(30,285,10): frame(x,125)
for _ in range(12): frame()
for _ in range(25): frame(275,160)
for _ in range(12): frame()
encoder.stdin.close()
assert encoder.wait() == 0
print(destination)
