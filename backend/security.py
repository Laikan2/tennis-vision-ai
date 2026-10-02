import os
import re
from urllib.parse import quote
from cryptography.fernet import Fernet


class CameraSecrets:
    def __init__(self):
        key = os.getenv('TV_SECRET_KEY')
        self.cipher = Fernet(key.encode()) if key else None

    def encrypt(self, value):
        if not self.cipher:
            raise ValueError('Configure TV_SECRET_KEY no .env para salvar credenciais com criptografia.')
        return self.cipher.encrypt(value.encode()).decode()

    def decrypt(self, value):
        if not value:
            return ''
        if not self.cipher:
            raise ValueError('TV_SECRET_KEY indisponível.')
        return self.cipher.decrypt(value.encode()).decode()

    def url(self, camera):
        if not re.fullmatch(r'[a-zA-Z0-9.-]+', camera['host']):
            raise ValueError('Host inválido.')
        user = camera.get('_envUser') or self.decrypt(camera.get('userEncrypted'))
        password = camera.get('_envPassword') or self.decrypt(camera.get('passwordEncrypted'))
        auth = f'{quote(user, safe="")}:{quote(password, safe="")}@' if user else ''
        return f'rtsp://{auth}{camera["host"]}:{camera["port"]}{camera["path"]}'


def public_camera(camera):
    return {k: v for k, v in camera.items() if k not in ('userEncrypted', 'passwordEncrypted', '_envUser', '_envPassword')}
