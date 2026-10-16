import AVFoundation
import CoreImage
import UIKit

struct LocalCameraFrame {
    var image: CGImage,width: Int,height: Int,time: Double,result: VisionResult,key: String,generation: UUID
    var fps: Double,requestedFPS: Int,conversionMs: Double,analysisMs: Double,startedAt: Double
    var exposureMs: Double,profile: String,lens: String,evidence: CGImage?,evidenceOffset: VisionPoint?
}
struct LocalLensChoice: Identifiable {var id: String,name: String,device: AVCaptureDevice}
enum LocalDetail: String,CaseIterable {case fluid,detail,economy
    var title: String{switch self{case .fluid:return "Fluidez · 720p";case .detail:return "Bola distante · 1080p";case .economy:return "Economia · até 30 FPS"}}
}

/// Latest available camera images. No encoder, relay, HTTP or PC in local mode.
final class LocalCamera: NSObject,AVCaptureVideoDataOutputSampleBufferDelegate {
    var settingsForKey: ((String)->VisionSettings)?
    var onFrame: ((LocalCameraFrame,[LocalCameraFrame])->Void)?
    var onState: ((String,Bool)->Void)?
    var onNotice: ((String)->Void)?
    private let queue=DispatchQueue(label:"ai.tennisvision.ios.local-camera",qos:.userInitiated)
    private let session=AVCaptureSession(),output=AVCaptureVideoDataOutput()
    private let context=CIContext(options:[.cacheIntermediates:false])
    private var core=LocalVision(),device: AVCaptureDevice?,coordinator: AVCaptureDevice.RotationCoordinator?
    private let preview=VisionPreview()
    private var pixelStorage=[UInt32](),running=false,analyzing=false,key="",generation=UUID()
    private var activeProfile=CaptureProfile.standard,rotation=0,lastFrame=0.0,meter=FrameRateMeter(),lastReport=0.0
    private var timer: DispatchSourceTimer?,observers=[NSObjectProtocol]()
    private struct Evidence{var time: Double,image: CGImage,offset: VisionPoint}
    private var evidence=[Evidence]()
    private let deliveryLock=NSLock()
    private var latest: LocalCameraFrame?,events=[LocalCameraFrame](),deliveryScheduled=false
    private var statusWasStale=false
    override init(){
        super.init()
        for name in [NSNotification.Name.AVCaptureSessionRuntimeError,.AVCaptureSessionWasInterrupted] {
            observers.append(NotificationCenter.default.addObserver(forName:name,object:session,queue:nil){[weak self] _ in
                self?.queue.async{self?.fail("Câmera interrompida. Feche outros apps de câmera e toque em Reabrir.")}
            })
        }
        observers.append(NotificationCenter.default.addObserver(forName:ProcessInfo.thermalStateDidChangeNotification,object:nil,queue:nil){[weak self] _ in
            if ProcessInfo.processInfo.thermalState == .critical{self?.queue.async{self?.fail("iPhone muito quente. Aguarde esfriar antes de reabrir a câmera.")}}
        })
    }
    deinit{observers.forEach(NotificationCenter.default.removeObserver);timer?.cancel()}
    static func lenses() -> [LocalLensChoice] {
        AVCaptureDevice.DiscoverySession(deviceTypes:[.builtInWideAngleCamera,.builtInUltraWideCamera,.builtInTelephotoCamera],mediaType:.video,position:.back).devices.map{d in
            let label=d.deviceType == .builtInTelephotoCamera ? "Detalhe · teleobjetiva":d.deviceType == .builtInUltraWideCamera ? "Ampla · ultra-angular":"Equilibrada · principal"
            return LocalLensChoice(id:d.uniqueID,name:label,device:d)
        }
    }
    static func rates(detail: LocalDetail,lensID: String?) -> [Int] {
        let choices=lenses()
        guard let lens=choices.first(where:{$0.id==lensID}) ?? choices.first(where:{$0.device.deviceType == .builtInWideAngleCamera}) ?? choices.first else{return []}
        let height=detail == .detail ? 1080:720
        return [30,60,120,240].filter{fps in
            (detail != .economy || fps==30) && lens.device.formats.contains{f in
                let d=CMVideoFormatDescriptionGetDimensions(f.formatDescription)
                return [720,1080].contains(Int(d.height)) && Int(d.height)<=height && f.videoSupportedFrameRateRanges.contains{$0.minFrameRate<=Double(fps) && $0.maxFrameRate>=Double(fps)}
            }
        }
    }
    func start(detail: LocalDetail,lensID: String?,rate: Int=60) {
        queue.async{[weak self] in
            guard let self else{return};self.closeHardware();self.generation=UUID();self.core.reset();self.evidence=[]
            self.statusWasStale=false;self.rotation=0
            let requestedGeneration=self.generation
            do {
                guard AVCaptureDevice.authorizationStatus(for:.video) == .authorized else{throw CameraError.invalid("Permita a câmera nos Ajustes do iPhone.")}
                let choices=Self.lenses()
                guard let lens=choices.first(where:{$0.id==lensID}) ?? choices.first(where:{$0.device.deviceType == .builtInWideAngleCamera}) ?? choices.first else{throw CameraError.invalid("Nenhuma câmera traseira disponível.")}
                let device=lens.device,preferred=detail == .detail ? 1080:720,limit=detail == .economy ? 30:240
                var options=[(AVCaptureDevice.Format,CaptureProfile)]()
                for format in device.formats {
                    let d=CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                    guard [720,1080].contains(Int(d.height)),Int(d.height)<=preferred else{continue}
                    for fps in [240,120,60,30] where fps<=limit && format.videoSupportedFrameRateRanges.contains(where:{$0.minFrameRate<=Double(fps) && $0.maxFrameRate>=Double(fps)}) {
                        options.append((format,CaptureProfile(width:Int(d.width),height:Int(d.height),fps:fps,bitrate:8_000_000)))
                    }
                }
                let desired=detail == .economy ? 30:rate
                guard let profile=LocalCapturePolicy.select(options.map{$0.1},height:preferred,rate:desired),let selected=options.first(where:{$0.1==profile}) else{throw CameraError.invalid("Esta lente/resolução não oferece a taxa escolhida. Selecione uma taxa disponível ou Até 60 FPS.")}
                self.session.beginConfiguration();self.session.sessionPreset = .inputPriority
                defer{self.session.commitConfiguration()}
                let input=try AVCaptureDeviceInput(device:device)
                guard self.session.canAddInput(input),self.session.canAddOutput(self.output) else{throw CameraError.invalid("O iOS recusou a câmera local.")}
                self.session.addInput(input);self.session.addOutput(self.output)
                self.output.videoSettings=[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]
                self.output.alwaysDiscardsLateVideoFrames=true;self.output.setSampleBufferDelegate(self,queue:self.queue)
                try device.lockForConfiguration()
                device.activeFormat=selected.0;let duration=CMTime(value:1,timescale:CMTimeScale(selected.1.fps))
                device.activeVideoMinFrameDuration=duration;device.activeVideoMaxFrameDuration=duration
                device.automaticallyAdjustsVideoHDREnabled=false;if device.isVideoHDREnabled{device.isVideoHDREnabled=false}
                if device.isFocusModeSupported(.continuousAutoFocus){device.focusMode = .continuousAutoFocus}
                if device.isExposureModeSupported(.continuousAutoExposure){device.exposureMode = .continuousAutoExposure}
                device.unlockForConfiguration()
                self.coordinator=AVCaptureDevice.RotationCoordinator(device:device,previewLayer:nil)
                let angle=CaptureGeometry.rightAngle(Double(self.coordinator?.videoRotationAngleForHorizonLevelCapture ?? 0))
                if let connection=self.output.connection(with:.video){
                    if connection.isVideoRotationAngleSupported(CGFloat(angle)){connection.videoRotationAngle=CGFloat(angle);self.rotation=angle}
                    if connection.isVideoStabilizationSupported{connection.preferredVideoStabilizationMode = .off}
                    connection.automaticallyAdjustsVideoMirroring=false;connection.isVideoMirrored=false
                }
                self.activeProfile=selected.1;self.device=device;self.meter=FrameRateMeter();self.lastFrame=ProcessInfo.processInfo.systemUptime
                self.key="\(device.uniqueID):\(selected.1.width)x\(selected.1.height):\(self.rotation)"
                self.core=LocalVision(settings:self.settingsForKey?(self.key) ?? VisionSettings());self.preview.reset();self.running=true;self.analyzing=false
                // Commit before startRunning, which must never run inside configuration.
                self.queue.async{[weak self] in
                    guard let self,self.running,self.generation==requestedGeneration else{return}
                    self.session.startRunning()
                    let timer=DispatchSource.makeTimerSource(queue:self.queue);timer.schedule(deadline:.now()+0.5,repeating:0.5)
                    timer.setEventHandler{[weak self] in self?.watchdog()};self.timer=timer;timer.resume()
                }
            } catch{self.fail(error.localizedDescription)}
        }
    }
    func update(_ settings: VisionSettings){queue.async{[weak self] in self?.core.settings=settings;self?.core.reset();self?.preview.reset();self?.evidence=[]}}
    func setAnalysis(_ value: Bool){queue.async{[weak self] in self?.analyzing=value;self?.core.clearTrack();self?.preview.reset();self?.evidence=[]}}
    func stop(completion: (() -> Void)?=nil){queue.async{[weak self] in self?.generation=UUID();self?.closeHardware();if let completion{DispatchQueue.main.async(execute:completion)}}}
    private func closeHardware(){
        running=false;analyzing=false;timer?.cancel();timer=nil
        output.setSampleBufferDelegate(nil,queue:nil)
        if session.isRunning{session.stopRunning()}
        session.beginConfiguration();session.inputs.forEach(session.removeInput);session.outputs.forEach(session.removeOutput);session.commitConfiguration()
        device=nil;coordinator=nil;evidence=[]
        deliveryLock.lock();latest=nil;events=[];deliveryLock.unlock()
    }
    private func state(_ message: String,_ ready: Bool){DispatchQueue.main.async{[weak self] in self?.onState?(message,ready)}}
    private func fail(_ message: String){closeHardware();state(message,false)}
    private func watchdog(){
        guard running else{return};let age=ProcessInfo.processInfo.systemUptime-lastFrame
        if age>5{fail("A câmera parou de produzir imagens. Toque em Reabrir câmera.")}
        else if age>0.5 && !statusWasStale{statusWasStale=true;core.clearTrack();DispatchQueue.main.async{[weak self] in self?.onNotice?("Aguardando imagem · o treino retoma quando os quadros voltarem") }}
    }
    func captureOutput(_ output: AVCaptureOutput,didOutput sampleBuffer: CMSampleBuffer,from connection: AVCaptureConnection){
        guard running,let buffer=CMSampleBufferGetImageBuffer(sampleBuffer) else{return}
        autoreleasepool{
            let started=ProcessInfo.processInfo.systemUptime;lastFrame=started;meter.observe(started)
            if statusWasStale{statusWasStale=false;state("Imagem retomada · treino preservado",true)}
            let w=CVPixelBufferGetWidth(buffer),h=CVPixelBufferGetHeight(buffer)
            if pixelStorage.count != w*h{pixelStorage=Array(repeating:0,count:w*h)}
            pixelStorage.withUnsafeMutableBytes{raw in
                guard let base=raw.baseAddress else{return}
                context.render(CIImage(cvPixelBuffer:buffer),toBitmap:base,rowBytes:w*4,bounds:CGRect(x:0,y:0,width:w,height:h),format:.BGRA8,colorSpace:CGColorSpaceCreateDeviceRGB())
            }
            let converted=ProcessInfo.processInfo.systemUptime,time=CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
            let result=analyzing ? core.process(pixelStorage,width:w,height:h,time:time):preview.process(pixelStorage,width:w,height:h,time:time,area:core.activeSettings,offset:core.offset)
            let analyzed=ProcessInfo.processInfo.systemUptime
            let data=pixelStorage.withUnsafeBytes{Data($0)}
            guard let provider=CGDataProvider(data:data as CFData),let image=CGImage(width:w,height:h,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:w*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.premultipliedFirst.rawValue|CGBitmapInfo.byteOrder32Little.rawValue),provider:provider,decode:nil,shouldInterpolate:true,intent:.defaultIntent) else{return}
            if analyzing{evidence.append(Evidence(time:time,image:image,offset:result.cameraOffset));if evidence.count>6{evidence.removeFirst()}}else{evidence=[]}
            let contact=result.impact.flatMap{hit in evidence.min{abs($0.time-hit.time)<abs($1.time-hit.time)}}
            deliver(LocalCameraFrame(image:image,width:w,height:h,time:time,result:result,key:key,generation:generation,fps:meter.value(now:started),requestedFPS:activeProfile.fps,conversionMs:(converted-started)*1000,analysisMs:(analyzed-converted)*1000,startedAt:started,exposureMs:device.map{CMTimeGetSeconds($0.exposureDuration)*1000} ?? 0,profile:activeProfile.label,lens:device?.localizedName ?? "Câmera",evidence:contact?.image,evidenceOffset:contact?.offset))
        }
    }
    private func deliver(_ frame: LocalCameraFrame){
        deliveryLock.lock();latest=frame;if frame.result.impact != nil{events.append(frame)}
        let schedule = !deliveryScheduled;deliveryScheduled=true;deliveryLock.unlock()
        if schedule{DispatchQueue.main.async{[weak self] in
            guard let self else{return};self.deliveryLock.lock();let latest=self.latest,events=self.events
            self.latest=nil;self.events=[];self.deliveryScheduled=false;self.deliveryLock.unlock()
            if let latest{self.onFrame?(latest,events)}
        }}
    }
}
