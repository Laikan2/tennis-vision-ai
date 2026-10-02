"""Verify a running packaged app using an explicitly isolated smoke report."""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import websockets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding='utf-8-sig'))
    assert Path(report['dataPath']).parent.name.startswith('desktop-smoke'), 'Use isolated test data only'
    assert report['bridgeReady'] and report['ready']
    origin = report['url']

    def request(endpoint, body=None, custom_origin=None):
        headers = {'Origin':custom_origin or origin, 'Content-Type':'application/json'}
        payload = json.dumps(body).encode() if body is not None else None
        with urlopen(Request(origin + '/api' + endpoint, data=payload, headers=headers), timeout=20) as response:
            return json.load(response)

    assert request('/health')['credentialsStorageReady']
    try:
        request('/health', custom_origin='https://foreign.invalid')
        raise AssertionError('Foreign browser origin was accepted')
    except HTTPError as error: assert error.code == 403
    phone = request('/mobile-camera')
    with urlopen(origin + phone['androidApp']['downloadUrl'], timeout=10) as download:
        apk_hash = hashlib.sha256(download.read()).hexdigest()
    source = Path(__file__).parents[1] / f"mobile/releases/Tennis-Vision-Camera-Android-v{phone['androidApp']['version']}.apk"
    assert apk_hash == hashlib.sha256(source.read_bytes()).hexdigest()
    cameras = request('/cameras')
    assert len(cameras) == 2
    assert any(camera.get('capturePolygon') for camera in cameras), 'Save one area in the UI first'
    end = time.monotonic() + 25
    while True:
        state = request('/status')
        if all(feed.get('poseStatus') == 'pose ativa · 17 pontos' for feed in state['cameraFeeds']): break
        if time.monotonic() > end: raise AssertionError('Bundled pose model did not load')
        time.sleep(.5)
    assert state['onlineCameras'] == 2
    assert all(feed['framesProcessed'] > 0 and feed['framesReceived'] > 0 for feed in state['cameraFeeds'])

    async def sockets():
        async with websockets.connect(origin.replace('http:','ws:') + '/api/live', origin=origin) as live:
            message = json.loads(await asyncio.wait_for(live.recv(), 5))
            assert message['running']
        async with websockets.connect(origin.replace('http:','ws:') + '/api/video?cameraId=' + cameras[0]['id'], origin=origin, max_size=12*1024*1024) as video:
            # The ordered packet carries a metadata length, JSON, then JPEG.
            packet = await asyncio.wait_for(video.recv(), 5)
            assert isinstance(packet, bytes)
            size = int.from_bytes(packet[:4], 'big')
            metadata = json.loads(packet[4:4+size])
            image = packet[4+size:]
            assert image[:2] == b'\xff\xd8' and metadata['resolution'] == [1280,720]
            await video.send(str(metadata['sequence']))
            newer = await asyncio.wait_for(video.recv(), 5)
            next_size = int.from_bytes(newer[:4], 'big')
            next_meta = json.loads(newer[4:4+next_size])
            assert next_meta['sequence'] > metadata['sequence']
            await video.send(str(next_meta['sequence']))
    asyncio.run(sockets())
    result = {'version':report['version'], 'bridgeReady':True, 'startupMs':report['startupMs'],
              'onlineCameras':state['onlineCameras'], 'apkSha256':apk_hash,
              'foreignOriginBlocked':True, 'analysisAndVideoWebsockets':True,
              'savedAreaCamera':next(camera['id'] for camera in cameras if camera.get('capturePolygon')),
              'feeds':[{'name':feed['cameraName'], 'captureFps':feed['cameraFps'],
                        'processingFps':feed['processingFps'], 'frameLatencyMs':feed['frameLatencyMs'],
                        'poseStatus':feed['poseStatus'], 'persons':len(feed.get('persons', []))}
                       for feed in state['cameraFeeds']]}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__': main()
