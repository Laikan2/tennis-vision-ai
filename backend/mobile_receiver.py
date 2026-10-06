"""Small, bounded RTSP check of the local phone receiver; no video decoding."""
import re
import socket
import time


def receiver_status(path='galaxy', port=8554, timeout=.8):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', path):
        raise ValueError('Invalid receiver path')
    result=dict(receiverAvailable=False,streamReady=False,announcedFps=None,
                message='O receptor não está aberto. Reinicie o Tennis Vision Studio.')
    deadline=time.monotonic()+timeout
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=timeout) as peer:
            result.update(receiverAvailable=True,
                          message='Receptor pronto. Toque em Transmitir para o PC no celular.')
            peer.sendall((f'DESCRIBE rtsp://127.0.0.1:{port}/{path} RTSP/1.0\r\n'
                          'CSeq: 1\r\nAccept: application/sdp\r\n\r\n').encode('ascii'))
            data=b''
            body_length=None
            while len(data)<32_768:
                remaining=deadline-time.monotonic()
                if remaining<=0:
                    break
                peer.settimeout(remaining)
                chunk=peer.recv(min(4096,32_768-len(data)))
                if not chunk:
                    break
                data+=chunk
                if b'\r\n\r\n' in data:
                    header,body=data.split(b'\r\n\r\n',1)
                    if not re.match(rb'RTSP/1\.0 200(?: |\r)',header):
                        break
                    match=re.search(rb'(?im)^Content-Length:\s*(\d+)\s*$',header)
                    body_length=int(match[1]) if match else 0
                    if len(body)>=body_length:
                        sdp=body[:body_length].decode('utf-8',errors='replace')
                        if re.search(r'(?m)^m=video ',sdp):
                            result.update(streamReady=True,message='Vídeo do celular recebido. Conecte para abrir o vídeo e iniciar a análise.')
                            fps=re.search(r'(?m)^a=framerate:([0-9.]+)\s*$',sdp)
                            if fps:
                                rate=float(fps[1])
                                if 0<rate<=240:
                                    result['announcedFps']=rate
                        break
    except (OSError,ValueError):
        pass
    return result
