"""Exercise disconnect/backpressure with the real ASGI streaming response."""
import asyncio
import time
from types import SimpleNamespace

from starlette.requests import Request


def test_preview_has_one_disconnect_reader_and_releases_on_disconnect(monkeypatch):
    from backend import api

    async def exercise():
        disconnected = asyncio.Event()
        readers = maximum = sequence = 0
        chunks = []

        async def receive():
            nonlocal readers, maximum
            readers += 1
            maximum = max(maximum, readers)
            try:
                await disconnected.wait()
                return {'type': 'http.disconnect'}
            finally:
                readers -= 1

        def latest():
            nonlocal sequence
            sequence += 1
            return b'jpeg-test', SimpleNamespace(sequence=sequence, timestamp=time.time())

        async def send(message):
            if message['type'] == 'http.response.body' and message.get('more_body'):
                chunks.append(message['body'])
                # Model transport backpressure and give the disconnect listener
                # time to run, as with an actual phone or desktop client.
                await asyncio.sleep(.005)
                if len(chunks) == 5:
                    disconnected.set()

        pipe = SimpleNamespace(session={'id': 'test'}, capture=SimpleNamespace(latest_preview=latest))
        monkeypatch.setattr(api, 'active_pipeline', lambda camera: pipe)
        scope = {'type': 'http', 'method': 'GET', 'headers': [], 'asgi': {'spec_version': '2.3'}}
        response = await api.preview(Request(scope, receive))
        await asyncio.wait_for(response(scope, receive, send), 1)
        assert maximum == 1, 'Two consumers must not cancel competing reads from the same socket'
        assert readers == 0
        assert 5 <= len(chunks) <= 6
        assert b'X-Sequence: 1' in chunks[0] and b'X-Sequence: 5' in chunks[4]

    asyncio.run(exercise())
