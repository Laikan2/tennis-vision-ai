"""Receive synthetic phone-compatible SRT/H.264 at 60/120/240 fps in isolation.

This checks the Windows transport and decoder, not the physical phone camera.
The receiver binds separate localhost ports and never changes Studio data.
"""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from backend.camera import CameraCapture,FrameBuffer
from backend.mobile_receiver import receiver_status


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg',type=Path,required=True)
    parser.add_argument('--receiver',type=Path,default=ROOT/'tools/mediamtx/mediamtx.exe')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--rtsp-port',type=int,default=18554)
    parser.add_argument('--srt-port',type=int,default=18890)
    args=parser.parse_args()
    for port,kind in [(args.rtsp_port,socket.SOCK_STREAM),(args.srt_port,socket.SOCK_DGRAM)]:
        with socket.socket(socket.AF_INET,kind) as check:
            check.bind(('127.0.0.1',port))
    folder=args.output.resolve().parent
    folder.mkdir(parents=True,exist_ok=True)
    config=folder/'transport-receiver.yml'
    config.write_text(f'logLevel: warn\nrtsp: true\nrtspAddress: 127.0.0.1:{args.rtsp_port}\n'
                      f'rtspTransports: [tcp]\nsrt: true\nsrtAddress: 127.0.0.1:{args.srt_port}\n'
                      'rtmp: false\nhls: false\nwebrtc: false\nmoq: false\nplayback: false\napi: false\npaths:\n  all_others:\n',encoding='utf-8')
    flags=subprocess.CREATE_NO_WINDOW if sys.platform=='win32' else 0
    results=[]
    receiver=None
    with (folder/'transport-receiver.log').open('wb') as receiver_log:
        try:
            receiver=subprocess.Popen([str(args.receiver),str(config)],stdout=receiver_log,
                                      stderr=subprocess.STDOUT,creationflags=flags)
            deadline=time.monotonic()+10
            while not receiver_status(port=args.rtsp_port)['receiverAvailable']:
                if receiver.poll() is not None or time.monotonic()>deadline:
                    raise RuntimeError('Isolated receiver did not start')
                time.sleep(.2)
            for rate in (60,120,240):
                sender=None
                capture=None
                with (folder/f'transport-publisher-{rate}.log').open('wb') as sender_log:
                    try:
                        sender=subprocess.Popen([str(args.ffmpeg),'-hide_banner','-loglevel','warning',
                            '-re','-f','lavfi','-i',f'testsrc2=size=1280x720:rate={rate}',
                            '-an','-c:v','libx264','-preset','ultrafast','-tune','zerolatency',
                            '-profile:v','baseline','-pix_fmt','yuv420p','-g',str(rate),'-bf','0',
                            '-b:v','12M','-f','mpegts',
                            f'srt://127.0.0.1:{args.srt_port}?streamid=publish:galaxy&latency=40000&pkt_size=1316'],
                            stdout=sender_log,stderr=subprocess.STDOUT,creationflags=flags)
                        deadline=time.monotonic()+12
                        while not receiver_status(port=args.rtsp_port)['streamReady']:
                            if sender.poll() is not None or time.monotonic()>deadline:
                                raise RuntimeError(f'No published SRT video at {rate} fps')
                            time.sleep(.1)
                        capture=CameraCapture(f'rtsp://127.0.0.1:{args.rtsp_port}/galaxy',FrameBuffer())
                        capture.start()
                        deadline=time.monotonic()+12
                        while capture.frames<rate:
                            if time.monotonic()>deadline:
                                raise RuntimeError(f'Windows decoder did not receive frames at {rate} fps')
                            time.sleep(.1)
                        count=capture.frames
                        began=time.monotonic()
                        time.sleep(5)
                        elapsed=time.monotonic()-began
                        measured=(capture.frames-count)/elapsed
                        row=dict(requestedFps=rate,width=1280,height=720,receivedFrames=capture.frames-count,
                                 intervalSeconds=round(elapsed,3),decodedFps=round(measured,2),
                                 ready=receiver_status(port=args.rtsp_port)['streamReady'],
                                 sourceTimestamps=capture.source_timing_available,
                                 arrivalGapP95Ms=capture.arrival_gap_p95_ms)
                        results.append(row)
                        print(json.dumps(row),flush=True)
                    finally:
                        if capture: capture.stop()
                        stop(sender)
        finally: stop(receiver)
    report=dict(kind='synthetic H.264 over SRT; physical camera not tested',profiles=results)
    args.output.write_text(json.dumps(report,indent=2),encoding='utf-8')


if __name__=='__main__': main()
