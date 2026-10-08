#if canImport(VideoToolbox)
import Foundation
import VideoToolbox
import CoreMedia

public final class H264Encoder {
    public var onFrame: ((EncodedFrame) -> Void)?
    public var onError: ((String) -> Void)?
    public var onDrop: (() -> Void)?
    public var onDiscontinuity: (() -> Void)?
    private var session: VTCompressionSession?
    private let queue = DispatchQueue(label:"ai.tennisvision.ios.encoder",qos:.userInitiated)
    private let lock = NSLock()
    private var pending=0
    private var needsKeyframe=true
    private var stopped=false
    public init(profile: CaptureProfile, requireHardware: Bool = true, rotation: Int = 0) throws {
        let dimensions=CaptureGeometry.dimensions(width:profile.width,height:profile.height,rotation:rotation)
        let specification = [kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder:requireHardware] as CFDictionary
        let code=VTCompressionSessionCreate(allocator:kCFAllocatorDefault,width:Int32(dimensions.width),height:Int32(dimensions.height),
            codecType:kCMVideoCodecType_H264,encoderSpecification:specification,imageBufferAttributes:nil,
            compressedDataAllocator:nil,outputCallback:{ context,_,status,flags,sample in
                guard let context else{return}
                Unmanaged<H264Encoder>.fromOpaque(context).takeUnretainedValue().output(status:status,flags:flags,sample:sample)
            },refcon:Unmanaged.passUnretained(self).toOpaque(),compressionSessionOut:&session)
        guard code == noErr, let session else {
            throw CameraError.invalid("Não foi possível abrir o H.264 deste perfil (\(code)). Escolha outra resolução ou taxa.")
        }
        let values: [(CFString,CFTypeRef)] = [
            (kVTCompressionPropertyKey_RealTime,kCFBooleanTrue),
            (kVTCompressionPropertyKey_AllowFrameReordering,kCFBooleanFalse),
            (kVTCompressionPropertyKey_ProfileLevel,kVTProfileLevel_H264_High_AutoLevel),
            (kVTCompressionPropertyKey_ExpectedFrameRate,NSNumber(value:profile.fps)),
            (kVTCompressionPropertyKey_AverageBitRate,NSNumber(value:profile.bitrate)),
            (kVTCompressionPropertyKey_MaxKeyFrameInterval,NSNumber(value:profile.fps)),
            (kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration,NSNumber(value:1.0)),
        ]
        for (key,value) in values {
            let status=VTSessionSetProperty(session,key:key,value:value)
            if status != noErr {
                VTCompressionSessionInvalidate(session); self.session=nil
                throw CameraError.invalid("O H.264 recusou \(key) (\(status)). A taxa escolhida não foi alterada.")
            }
        }
        // Optional on some encoders; our own input queue still caps pending frames.
        VTSessionSetProperty(session,key:kVTCompressionPropertyKey_MaxFrameDelayCount,value:NSNumber(value:1))
        guard VTCompressionSessionPrepareToEncodeFrames(session) == noErr else {
            VTCompressionSessionInvalidate(session); self.session=nil
            throw CameraError.invalid("O codificador não conseguiu preparar este perfil.")
        }
    }
    deinit { if let session { VTCompressionSessionInvalidate(session) } }
    public func forceKeyframe() { lock.lock(); needsKeyframe=true; lock.unlock() }
    public func encode(_ buffer: CVPixelBuffer, pts: CMTime, duration: CMTime) {
        lock.lock()
        guard !stopped, pending < 3 else { lock.unlock(); onDrop?(); return }
        pending+=1; let force=needsKeyframe; needsKeyframe=false; lock.unlock()
        queue.async { [self] in
            guard let session else { releaseSlot(); return }
            let options: CFDictionary? = force ? [kVTEncodeFrameOptionKey_ForceKeyFrame:true] as CFDictionary : nil
            var flags=VTEncodeInfoFlags()
            let code=VTCompressionSessionEncodeFrame(session,imageBuffer:buffer,presentationTimeStamp:pts,
                duration:duration,frameProperties:options,sourceFrameRefcon:nil,infoFlagsOut:&flags)
            if code != noErr { releaseSlot(); onError?("O H.264 interrompeu a codificação (\(code)). A taxa escolhida foi mantida.") }
        }
    }
    public func stop() {
        lock.lock(); stopped=true; lock.unlock()
        queue.sync {
            if let session { VTCompressionSessionCompleteFrames(session,untilPresentationTimeStamp:.invalid)
                VTCompressionSessionInvalidate(session) }
            session=nil
        }
    }
    private func releaseSlot() { lock.lock(); pending=max(0,pending-1); lock.unlock() }
    private func output(status: OSStatus, flags: VTEncodeInfoFlags, sample: CMSampleBuffer?) {
        defer{releaseSlot()}
        lock.lock(); let isStopped=stopped; lock.unlock()
        guard !isStopped else{return}
        guard status == noErr else {
            onError?("O codificador H.264 retornou um erro (\(status)). Reinicie a transmissão."); return
        }
        guard !flags.contains(.frameDropped), let sample,
              CMSampleBufferDataIsReady(sample), let block=CMSampleBufferGetDataBuffer(sample),
              let format=CMSampleBufferGetFormatDescription(sample) else { outputLost(); return }
        var lengthSize: Int32=0
        var sps=Data(), pps=Data()
        for index in 0..<2 {
            var address: UnsafePointer<UInt8>?, size=0, count=0
            guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format,parameterSetIndex:index,
                parameterSetPointerOut:&address,parameterSetSizeOut:&size,parameterSetCountOut:&count,
                nalUnitHeaderLengthOut:&lengthSize) == noErr, let address else {
                onError?("O codificador não forneceu os parâmetros H.264."); return
            }
            if index == 0 { sps=Data(bytes:address,count:size) } else { pps=Data(bytes:address,count:size) }
        }
        let dataLength=CMBlockBufferGetDataLength(block)
        guard dataLength > 0, dataLength <= 16_777_216 else { outputLost(); return }
        var data=Data(count:dataLength)
        let result=data.withUnsafeMutableBytes { bytes in
            CMBlockBufferCopyDataBytes(block,atOffset:0,dataLength:dataLength,destination:bytes.baseAddress!)
        }
        guard result == noErr else { outputLost(); return }
        do {
            let nals=try AVCC.nals(data,lengthSize:Int(lengthSize))
            let keyframe=nals.contains{$0.first.map{$0 & 31 == 5} ?? false}
            onFrame?(EncodedFrame(nals:nals,pts:CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)),
                keyframe:keyframe,sps:sps,pps:pps))
        } catch { onError?(error.localizedDescription) }
    }
    private func outputLost() {
        // Losing an already encoded reference differs from skipping a raw
        // capture. Resume only at an IDR, or subsequent pictures can be blocks.
        forceKeyframe(); onDiscontinuity?(); onDrop?()
    }
}
#endif
