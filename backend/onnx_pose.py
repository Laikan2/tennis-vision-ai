"""Bounded ONNX pose inference; DirectML is selected only after a parity/speed probe."""
import hashlib
import logging
import os
import statistics
import threading
import time
from pathlib import Path
import cv2
import numpy as np

# Initializing drivers and timing probes together made the first two camera
# workers compete during startup. Keep the probe isolated; inference stays
# independent on each camera's session after it has been selected.
_initialization_lock = threading.Lock()


def pose_tensor(image, size=256):
    h, w = image.shape[:2]
    scale = min(size / w, size / h)
    width, height = round(w * scale), round(h * scale)
    left, top = (size - width) // 2, (size - height) // 2
    canvas = np.full((size, size, 3), 114, np.uint8)
    canvas[top:top + height, left:left + width] = cv2.resize(image, (width, height))
    tensor = np.ascontiguousarray(canvas[:, :, ::-1].transpose(2, 0, 1)[None], dtype=np.float32) / 255.
    return tensor, (scale, left, top)


def decode_pose(output, transform, shape, timestamp):
    data = np.asarray(output)
    if data.ndim != 3 or data.shape[0] != 1 or data.shape[1] != 56 or not np.isfinite(data).all():
        raise ValueError('Saída de pose incompatível ou não finita.')
    rows = data[0].T
    rows = rows[rows[:, 4] >= .45]
    if not len(rows): return []
    rows = rows[np.argsort(-rows[:, 4])[:100]]
    boxes = np.column_stack((rows[:, 0] - rows[:, 2]/2, rows[:, 1] - rows[:, 3]/2, rows[:, 2:4]))
    indices = cv2.dnn.NMSBoxes(boxes.tolist(), rows[:, 4].tolist(), .45, .7)
    scale, left, top = transform
    height, width = shape[:2]
    persons = []
    for index in np.asarray(indices).reshape(-1)[:4]:
        row, box = rows[index], boxes[index]
        x, y = max(0., (float(box[0])-left)/scale), max(0., (float(box[1])-top)/scale)
        right = min(width, (float(box[0]+box[2])-left)/scale)
        bottom = min(height, (float(box[1]+box[3])-top)/scale)
        joints = row[5:].reshape(17, 3).copy()
        joints[:, 0] = (joints[:, 0]-left)/scale
        joints[:, 1] = (joints[:, 1]-top)/scale
        if right <= x or bottom <= y: continue
        persons.append(dict(x=x, y=y, width=right-x, height=bottom-y,
                            centerX=(x+right)/2, centerY=(y+bottom)/2,
                            confidence=float(row[4]), source='pose', timestamp=timestamp,
                            keypoints=joints.tolist()))
    return persons


def session(path, provider):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.enable_mem_pattern = False
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    providers = [provider, 'CPUExecutionProvider'] if provider != 'CPUExecutionProvider' else [provider]
    value = ort.InferenceSession(str(path), sess_options=options, providers=providers)
    if value.get_providers()[0] != provider: raise ValueError('O driver não ativou o provedor solicitado.')
    return value


class OnnxPose:
    def __init__(self, path):
        with _initialization_lock:
            self._initialize(path)

    def _initialize(self, path):
        import onnxruntime as ort
        self.path = path
        self.cpu = session(path, 'CPUExecutionProvider')
        self.input = self.cpu.get_inputs()[0].name
        self.size = int(self.cpu.get_inputs()[0].shape[-1])
        if self.size != 256: raise ValueError('Use o modelo de pose validado em 256 pixels.')
        self.runtime = self.cpu
        self.report = dict(provider='CPUExecutionProvider', device='CPU · ONNX', parity=None,
                           cpuMs=None, gpuMs=None, speedup=None, validated=False,
                           modelSha256=hashlib.sha256(Path(path).read_bytes()).hexdigest())
        # Never assume "DML available" means that a usable GPU is present.
        if 'DmlExecutionProvider' not in ort.get_available_providers() or os.getenv('TV_DEVICE') == 'cpu':
            self.report['reason'] = 'CPU selecionada; GPU não disponível ou desativada.'
            return
        try:
            gpu = session(path, 'DmlExecutionProvider')
            samples = [pose_tensor(np.full((256, 256, 3), value, np.uint8))[0] for value in (0, 114)]
            samples.append(pose_tensor(np.random.default_rng(17).integers(0, 256, (256,256,3), dtype=np.uint8))[0])
            cpu_ms, gpu_ms = [], []
            parity = True
            for tensor in samples:
                reference = self.cpu.run(None, {self.input:tensor})[0]
                actual = gpu.run(None, {self.input:tensor})[0]
                parity &= bool(np.allclose(reference, actual, rtol=.002, atol=.03))
                for _ in range(4):
                    began=time.perf_counter(); self.cpu.run(None,{self.input:tensor}); cpu_ms.append((time.perf_counter()-began)*1000)
                    began=time.perf_counter(); gpu.run(None,{self.input:tensor}); gpu_ms.append((time.perf_counter()-began)*1000)
            cpu, accelerated = statistics.median(cpu_ms), statistics.median(gpu_ms)
            accepted = parity and accelerated < cpu * .9
            self.report.update(parity=parity, cpuMs=cpu, gpuMs=accelerated,
                               speedup=cpu/max(.001, accelerated), validated=accepted,
                               reason='GPU aprovada por equivalência e desempenho.' if accepted else 'CPU mantida: a GPU não passou na comparação de equivalência/desempenho.')
            if accepted:
                self.runtime = gpu
                self.report.update(provider='DmlExecutionProvider', device='GPU · DirectML')
        except Exception as error:
            self.report.update(reason='GPU não ativada na validação; CPU mantida.', probeError=type(error).__name__)
            logging.getLogger('tennis.pose').warning('DirectML validation failed: %s', error)

    def predict(self, image, timestamp):
        tensor, transform = pose_tensor(image, self.size)
        try:
            output = self.runtime.run(None, {self.input:tensor})[0]
        except Exception:
            if self.runtime is self.cpu: raise
            self.runtime = self.cpu
            self.report.update(provider='CPUExecutionProvider', device='CPU · ONNX', validated=False,
                               reason='A GPU falhou durante a execução; a pose retomou na CPU.')
            output = self.cpu.run(None, {self.input:tensor})[0]
        return decode_pose(output, transform, image.shape, timestamp)
