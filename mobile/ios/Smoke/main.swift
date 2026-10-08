import Foundation
import CoreVideo
import CoreMedia
import TennisVisionCore

// This CI probe is not part of the iPhone app. Software encoding is allowed only here.
let finished=DispatchSemaphore(value:0)
let queue=DispatchQueue(label:"ai.tennisvision.smoke")
let configuration=try PairingConfiguration(host:"127.0.0.1",path:"ios-ci",
    profile:CaptureProfile(width:1280,height:720,fps:30,bitrate:2_000_000))
let encoder=try H264Encoder(profile:configuration.profile,requireHardware:false)
let publisher=RTSPPublisher()
var announced=false, publishing=false, count=0, sent=0, failure: String?
let timer=DispatchSource.makeTimerSource(queue:queue)
publisher.requestKeyframe={encoder.forceKeyframe()}
publisher.onState={state in queue.async {
    if case .publishing = state { publishing=true; print("RTSP publishing ready") }
    if case .failed(let error) = state { failure=error; finished.signal() }
}}
publisher.onSent={_,_ in queue.async { sent+=1; if sent == 300 { finished.signal() } }}
encoder.onError={error in queue.async {failure=error; finished.signal()}}
encoder.onFrame={frame in queue.async {
    if !announced {
        guard frame.keyframe else { encoder.forceKeyframe(); return }
        announced=true; publisher.start(configuration:configuration,sps:frame.sps,pps:frame.pps)
    } else if publishing { publisher.enqueue(frame) }
}}
timer.schedule(deadline:.now(),repeating:1.0/30)
timer.setEventHandler {
    var pixelBuffer: CVPixelBuffer?
    let result=CVPixelBufferCreate(kCFAllocatorDefault,1280,720,kCVPixelFormatType_32BGRA,
        [kCVPixelBufferIOSurfacePropertiesKey:[:]] as CFDictionary,&pixelBuffer)
    guard result == kCVReturnSuccess, let imageBuffer=pixelBuffer else {
        failure="Pixel buffer unavailable"; finished.signal(); return
    }
    CVPixelBufferLockBaseAddress(imageBuffer,[])
    if let pointer=CVPixelBufferGetBaseAddress(imageBuffer) {
        memset(pointer,Int32((count*3)%255),CVPixelBufferGetDataSize(imageBuffer))
    }
    CVPixelBufferUnlockBaseAddress(imageBuffer,[])
    encoder.encode(imageBuffer,pts:CMTime(value:Int64(count),timescale:30),duration:CMTime(value:1,timescale:30))
    count+=1
}
timer.resume()
let timeout=finished.wait(timeout:.now()+25)
timer.cancel()
queue.sync {}
encoder.stop(); publisher.stop()
if timeout == .timedOut { fatalError("No video reached the local receiver within 25s") }
if let failure { fatalError(failure) }
guard sent >= 300 else { fatalError("Not enough frames sent: \(sent)") }
print("Encoded and published \(sent) frames through RTSP/TCP")
