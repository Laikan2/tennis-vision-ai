"""Keep small inference matrices from competing with video decoding."""
import os

# Set these before NumPy/OpenCV/Torch load their CPU runtimes. The appearance
# network has only 48 hidden units: a pool per matrix adds latency instead of
# useful parallel work. Explicit deployment overrides remain respected.
for _variable in ('OPENBLAS_NUM_THREADS', 'OMP_NUM_THREADS', 'MKL_NUM_THREADS'):
    os.environ.setdefault(_variable, '1')
