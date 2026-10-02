import os
from pathlib import Path
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
load_dotenv(ROOT / '.env')
# FFmpeg diagnostics may echo credential-bearing RTSP URLs. Application logs use
# sanitized connection states; native verbose diagnostics stay disabled.
os.environ.setdefault('OPENCV_FFMPEG_LOGLEVEL', '-8')
os.environ.setdefault('OPENCV_LOG_LEVEL', 'SILENT')
# Keep FFmpeg from buffering a long RTSP backlog. TCP is the safer default for
# Wi-Fi cameras; set TV_RTSP_TRANSPORT=udp when the network is reliable and
# minimum latency matters more than packet recovery.
transport = os.getenv('TV_RTSP_TRANSPORT', 'tcp').lower()
if transport not in ('tcp', 'udp', 'http'):
    transport = 'tcp'
os.environ.setdefault(
    'OPENCV_FFMPEG_CAPTURE_OPTIONS',
    f'rtsp_transport;{transport}|fflags;nobuffer|flags;low_delay',
)
DATA = Path(os.getenv('TV_DATA_DIR', str(ROOT / 'data'))).resolve()
DATA.mkdir(parents=True, exist_ok=True)
for folder in ('replays', 'datasets', 'logs', 'models'):
    (DATA / folder).mkdir(exist_ok=True)
