import AVFoundation
import VideoToolbox
import UIKit

struct CameraSnapshot {
    var profiles=[CaptureProfile]()
    var activeProfile: CaptureProfile?
    var previewReady=false
    var transmitting=false
    var state="Pronto para conectar"
    var error: String?
    var captureFPS=0.0, encodedFPS=0.0, sentFPS=0.0, mbps=0.0
    var encoderDrops=0, networkDrops=0
    var torchAvailable=false, torchOn=false
    var focusLocked=false
    var exposure="Automática"
    var thermal="Normal"
    var adaptation: String?
    var cameraName="Câmera traseira"
}

/// Session, camera and stream state live on one queue. UI and network callbacks never block capture.
final class CameraController: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session=AVCaptureSession()
    var onSnapshot: ((CameraSnapshot)->Void)?
    private let queue=DispatchQueue(label:"ai.tennisvision.ios.camera",qos:.userInitiated)
    private var device: AVCaptureDevice?
    private var output=AVCaptureVideoDataOutput()
    private var formats=[String:AVCaptureDevice.Format]()
    private var encoder: H264Encoder?
    private var publisher: RTSPPublisher?
    private var generation=UUID()
    private var snapshot=CameraSnapshot()
    private var configuration: PairingConfiguration?
    private var captureMeter=FrameRateMeter(), encodeMeter=FrameRateMeter(), sentMeter=FrameRateMeter()
    private var startedAt=0.0, lastCaptured=0.0, lastEncoded=0.0
    private var bytesSinceReport=0, lastReport=0.0
    private var timer: DispatchSourceTimer?
    private var notifications=[NSObjectProtocol]()
    private var automatic=false
    private var profileHealth=ProfileHealth()

    override init() {
        super.init()
        notifications.append(NotificationCenter.default.addObserver(forName:.AVCaptureSessionRuntimeError,object:session,queue:nil) { [weak self] note in
            let message=(note.userInfo?[AVCaptureSessionErrorKey] as? NSError)?.localizedDescription ?? "A câmera foi interrompida."
            self?.queue.async { self?.fail("A câmera foi interrompida: \(message). Toque em Transmitir para tentar novamente.") }
        })
        notifications.append(NotificationCenter.default.addObserver(forName:.AVCaptureSessionWasInterrupted,object:session,queue:nil) { [weak self] _ in
            self?.queue.async { self?.fail("A câmera está sendo usada ou foi interrompida pelo iOS. Volte ao aplicativo e tente novamente.") }
        })
        notifications.append(NotificationCenter.default.addObserver(forName:ProcessInfo.thermalStateDidChangeNotification,object:nil,queue:nil) { [weak self] _ in
            self?.queue.async {
                guard let self else { return }
                self.snapshot.thermal=self.thermalText()
                if ProcessInfo.processInfo.thermalState == .critical {
                    self.fail("O iPhone aqueceu demais. A transmissão foi parada; aguarde esfriar.")
                } else { self.emit() }
            }
        })
    }
    deinit { notifications.forEach(NotificationCenter.default.removeObserver); timer?.cancel() }

    func prepare(profile: CaptureProfile, automatic: Bool = false) {
        queue.async { [weak self] in
            guard let self, !self.snapshot.transmitting else { return }
            do {
                if self.device == nil { try self.configureDevice() }
                guard let previewProfile=self.snapshot.profiles.first(where:{$0.fps <= 60}) ?? self.snapshot.profiles.first else {
                    throw CameraError.invalid("Nenhum perfil disponível para a prévia.")
                }
                let selected=automatic ? CameraPolicy.best(self.snapshot.profiles) ?? previewProfile : self.formats[profile.id] != nil ? profile : previewProfile
                try self.applyProfile(selected)
                if !self.session.isRunning { self.session.startRunning() }
                self.snapshot.previewReady=self.session.isRunning
                self.snapshot.error = !automatic && self.formats[profile.id] == nil ? "O perfil \(profile.label) não está disponível nesta câmera. Escolha um dos perfis detectados." : nil
                self.snapshot.adaptation=nil
                self.snapshot.state="Prévia da câmera"
                self.emit()
            } catch { self.fail(error.localizedDescription) }
        }
    }
    func start(_ configuration: PairingConfiguration, automatic: Bool = false, adaptation: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            self.stopStream()
            self.automatic=automatic; self.configuration=configuration
            self.profileHealth=ProfileHealth(); self.snapshot.adaptation=adaptation
            do {
                if self.device == nil { try self.configureDevice() }
                guard self.formats[configuration.profile.id] != nil else {
                    throw CameraError.invalid("A câmera não oferece \(configuration.profile.label). Escolha um perfil detectado; a taxa não foi alterada.")
                }
                try self.applyProfile(configuration.profile)
                let codec=try H264Encoder(profile:configuration.profile)
                let stream=RTSPPublisher()
                let current=UUID(); self.generation=current
                self.encoder=codec; self.publisher=stream; self.configuration=configuration
                self.captureMeter=FrameRateMeter(); self.encodeMeter=FrameRateMeter(); self.sentMeter=FrameRateMeter()
                self.startedAt=ProcessInfo.processInfo.systemUptime; self.lastCaptured=0; self.lastEncoded=0
                self.lastReport=self.startedAt; self.bytesSinceReport=0
                self.snapshot.encoderDrops=0; self.snapshot.networkDrops=0
                self.snapshot.transmitting=true; self.snapshot.error=nil; self.snapshot.state="Preparando H.264"
                var announced=false
                codec.onFrame = { [weak self, weak codec, weak stream] frame in
                    self?.queue.async {
                        guard let self, let codec, let stream, self.generation==current, self.snapshot.transmitting else { return }
                        let now=ProcessInfo.processInfo.systemUptime
                        self.lastEncoded=now; self.encodeMeter.observe(now)
                        if !announced {
                            guard frame.keyframe else { codec.forceKeyframe(); return }
                            announced=true
                            stream.start(configuration:configuration,sps:frame.sps,pps:frame.pps)
                        } else { stream.enqueue(frame) }
                    }
                }
                codec.onDrop = { [weak self] in self?.queue.async {
                    guard let self, self.generation==current else {return}; self.snapshot.encoderDrops+=1
                }}
                codec.onError = { [weak self] message in self?.queue.async {
                    guard let self, self.generation==current else{return}; self.adaptOrFail(message)
                }}
                stream.requestKeyframe = { [weak codec] in codec?.forceKeyframe() }
                stream.onSent = { [weak self] bytes, time in self?.queue.async {
                    guard let self, self.generation==current else{return}
                    self.sentMeter.observe(time); self.bytesSinceReport+=bytes
                }}
                stream.onState = { [weak self] state in self?.queue.async {
                    guard let self, self.generation==current else{return}
                    switch state {
                    case .connecting: self.snapshot.state="Conectando ao Windows"
                    case .publishing: self.snapshot.state="Transmitindo ao Windows"
                    case .reconnecting(let attempt): self.snapshot.state="Reconectando · tentativa \(attempt)/3"
                    case .failed(let message): self.fail(message); return
                    case .stopped: return
                    }
                    self.emit()
                }}
                if !self.session.isRunning { self.session.startRunning() }
                self.snapshot.previewReady=self.session.isRunning
                self.timer=DispatchSource.makeTimerSource(queue:self.queue)
                self.timer?.schedule(deadline:.now()+1,repeating:1)
                self.timer?.setEventHandler { [weak self] in self?.report() }
                self.timer?.resume(); self.emit()
            } catch { self.adaptOrFail(error.localizedDescription) }
        }
    }
    func stop() { queue.async { [weak self] in self?.stopStream(); self?.snapshot.state="Transmissão parada"; self?.emit() } }
    func pause(completion: (() -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self else {return}
            self.stopStream()
            if self.session.isRunning { self.session.stopRunning() }
            self.snapshot.previewReady=false; self.snapshot.state="Câmera pausada"; self.emit()
            if let completion { DispatchQueue.main.async(execute:completion) }
        }
    }
    func clearError() { queue.async { [weak self] in self?.snapshot.error=nil; self?.emit() } }
    func focus(at point: CGPoint, lockFocus: Bool) {
        queue.async { [weak self] in
            guard let self, let device=self.device else{return}
            do {
                try device.lockForConfiguration(); defer{device.unlockForConfiguration()}
                if device.isFocusPointOfInterestSupported { device.focusPointOfInterest=point }
                let mode: AVCaptureDevice.FocusMode=lockFocus ? .locked : .autoFocus
                if device.isFocusModeSupported(mode) { device.focusMode=mode }
                self.snapshot.focusLocked=lockFocus; self.emit()
            } catch { self.snapshot.error=error.localizedDescription; self.emit() }
        }
    }
    func setTorch(_ enabled: Bool) {
        queue.async { [weak self] in
            guard let self, let device=self.device, device.hasTorch else{return}
            do {
                try device.lockForConfiguration(); defer{device.unlockForConfiguration()}
                if enabled { try device.setTorchModeOn(level:0.5) } else { device.torchMode = .off }
                self.snapshot.torchOn=enabled; self.emit()
            } catch { self.snapshot.error="A lanterna não está disponível: \(error.localizedDescription)"; self.emit() }
        }
    }
    func setExposure(_ denominator: Int) {
        queue.async { [weak self] in
            guard let self, let device=self.device else{return}
            do {
                try device.lockForConfiguration(); defer{device.unlockForConfiguration()}
                if denominator == 0, device.isExposureModeSupported(.continuousAutoExposure) {
                    device.exposureMode = .continuousAutoExposure; self.snapshot.exposure="Automática"
                } else if denominator > 0, device.isExposureModeSupported(.custom) {
                    let seconds=1.0/Double(denominator)
                    let minimum=CMTimeGetSeconds(device.activeFormat.minExposureDuration)
                    let maximum=min(CMTimeGetSeconds(device.activeFormat.maxExposureDuration),1.0/Double(self.snapshot.activeProfile?.fps ?? 60))
                    guard seconds >= minimum, seconds <= maximum else {
                        throw CameraError.invalid("Esta exposição não é suportada no perfil atual.")
                    }
                    device.setExposureModeCustom(duration:CMTimeMakeWithSeconds(seconds,preferredTimescale:1_000_000),
                        iso:min(device.activeFormat.maxISO,max(device.activeFormat.minISO,device.iso)),completionHandler:nil)
                    self.snapshot.exposure="1/\(denominator) s"
                } else { throw CameraError.invalid("A câmera não oferece controle de exposição neste modo.") }
                self.emit()
            } catch { self.snapshot.error=error.localizedDescription; self.emit() }
        }
    }
    private func configureDevice() throws {
        guard AVCaptureDevice.authorizationStatus(for:.video) == .authorized else {
            throw CameraError.invalid("Permita o acesso à câmera nos Ajustes do iPhone.")
        }
        guard let camera=AVCaptureDevice.default(.builtInWideAngleCamera,for:.video,position:.back) else {
            throw CameraError.invalid("A câmera traseira não está disponível.")
        }
        var profiles=[CaptureProfile]()
        for format in camera.formats {
            let dimensions=CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            guard [(1280,720),(1920,1080)].contains(where:{$0.0 == dimensions.width && $0.1 == dimensions.height}) else {continue}
            for fps in [30,60,120,240] where format.videoSupportedFrameRateRanges.contains(where:{$0.minFrameRate <= Double(fps) && Double(fps) <= $0.maxFrameRate}) {
                let profile=CaptureProfile(width:Int(dimensions.width),height:Int(dimensions.height),fps:fps,
                    bitrate:fps > 60 ? 20_000_000 : (dimensions.height == 1080 ? 12_000_000 : 8_000_000))
                if formats[profile.id] == nil { formats[profile.id]=format; profiles.append(profile) }
            }
        }
        guard !profiles.isEmpty else { throw CameraError.invalid("Nenhum perfil compatível foi encontrado nesta câmera.") }
        let input=try AVCaptureDeviceInput(device:camera)
        session.beginConfiguration(); defer{session.commitConfiguration()}
        session.sessionPreset = .inputPriority
        guard session.canAddInput(input), session.canAddOutput(output) else {
            throw CameraError.invalid("O iOS recusou a sessão de câmera.")
        }
        session.addInput(input)
        output.alwaysDiscardsLateVideoFrames=true
        output.videoSettings=[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]
        output.setSampleBufferDelegate(self,queue:queue)
        session.addOutput(output)
        if let connection=output.connection(with:.video) {
            if connection.isVideoRotationAngleSupported(0) { connection.videoRotationAngle=0 }
            if connection.isVideoStabilizationSupported { connection.preferredVideoStabilizationMode = .off }
            connection.automaticallyAdjustsVideoMirroring=false; connection.isVideoMirrored=false
        }
        device=camera
        snapshot.cameraName=camera.localizedName
        snapshot.profiles=profiles.sorted { $0.fps == $1.fps ? $0.height > $1.height : $0.fps < $1.fps }
        snapshot.torchAvailable=camera.hasTorch; snapshot.thermal=thermalText()
    }
    private func applyProfile(_ profile: CaptureProfile) throws {
        guard let device, let format=formats[profile.id] else { throw CameraError.invalid("Perfil de captura indisponível.") }
        session.beginConfiguration(); defer{session.commitConfiguration()}
        try device.lockForConfiguration(); defer{device.unlockForConfiguration()}
        device.activeFormat=format
        let duration=CMTime(value:1,timescale:CMTimeScale(profile.fps))
        device.activeVideoMinFrameDuration=duration; device.activeVideoMaxFrameDuration=duration
        device.automaticallyAdjustsVideoHDREnabled=false
        if device.isVideoHDREnabled { device.isVideoHDREnabled=false }
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
        snapshot.activeProfile=profile; snapshot.focusLocked=false; snapshot.exposure="Automática"
    }
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard snapshot.transmitting, let encoder, let buffer=CMSampleBufferGetImageBuffer(sampleBuffer) else{return}
        let now=ProcessInfo.processInfo.systemUptime
        lastCaptured=now; captureMeter.observe(now)
        encoder.encode(buffer,pts:CMSampleBufferGetPresentationTimeStamp(sampleBuffer),duration:CMSampleBufferGetDuration(sampleBuffer))
    }
    func captureOutput(_ output: AVCaptureOutput, didDrop sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if snapshot.transmitting { snapshot.encoderDrops+=1 }
    }
    private func stopStream() {
        generation=UUID(); timer?.cancel(); timer=nil
        encoder?.stop(); encoder=nil; publisher?.stop(); publisher=nil; configuration=nil
        snapshot.transmitting=false; snapshot.captureFPS=0; snapshot.encodedFPS=0; snapshot.sentFPS=0; snapshot.mbps=0
    }
    private func fail(_ message: String) { stopStream(); snapshot.error=message; snapshot.state="Atenção necessária"; emit() }
    private func adaptOrFail(_ message: String) {
        guard automatic, let current=configuration,
              let next=CameraPolicy.next(after:current.profile,in:snapshot.profiles),
              let config=try? PairingConfiguration(host:current.host,port:current.port,path:current.path,profile:next,
                  panelPort:current.panelPort,panelToken:current.panelToken) else { fail(message); return }
        let reason="\(current.profile.label) não se manteve estável. Automático ajustado para \(next.label). \(message)"
        stopStream(); snapshot.adaptation=reason; snapshot.state="Ajustando o perfil automático"; emit()
        start(config,automatic:true,adaptation:reason)
    }
    private func report() {
        let now=ProcessInfo.processInfo.systemUptime
        if now-startedAt > 8 && (lastCaptured == 0 || now-lastCaptured > 3) {
            adaptOrFail("A câmera parou de produzir quadros neste perfil."); return
        }
        if now-startedAt > 8 && (lastEncoded == 0 || now-lastEncoded > 3) {
            adaptOrFail("O codificador H.264 parou neste perfil."); return
        }
        snapshot.captureFPS=captureMeter.value(now:now); snapshot.encodedFPS=encodeMeter.value(now:now)
        snapshot.sentFPS=sentMeter.value(now:now); snapshot.mbps=Double(bytesSinceReport)*8/max(0.1,now-lastReport)/1_000_000
        bytesSinceReport=0; lastReport=now; snapshot.networkDrops=publisher?.outbox.droppedFrames ?? 0
        if automatic, let profile=configuration?.profile,
           profileHealth.sample(elapsed:now-startedAt,requested:profile.fps,capture:snapshot.captureFPS,encoded:snapshot.encodedFPS) {
            adaptOrFail("A captura/codificação ficou abaixo de 65% da taxa por 6 medições consecutivas."); return
        }
        emit()
    }
    private func thermalText() -> String {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal:return "Normal"; case .fair:return "Aquecendo"; case .serious:return "Quente"; case .critical:return "Muito quente"
        @unknown default:return "Indisponível"
        }
    }
    private func emit() { onSnapshot?(snapshot) }
}
