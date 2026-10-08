import SwiftUI
import AVFoundation

final class PreviewSurface: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    var onFocus: ((CGPoint)->Void)?
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationObservation: NSKeyValueObservation?
    func coordinateRotation() {
        guard let device=(previewLayer.session?.inputs.first as? AVCaptureDeviceInput)?.device else { return }
        guard rotationCoordinator == nil else { return }
        let coordinator=AVCaptureDevice.RotationCoordinator(device:device,previewLayer:previewLayer)
        rotationCoordinator=coordinator
        rotationObservation=coordinator.observe(\.videoRotationAngleForHorizonLevelPreview,options:[.initial,.new]) { [weak self] value,_ in
            DispatchQueue.main.async {
                guard let connection=self?.previewLayer.connection else{return}
                let angle=value.videoRotationAngleForHorizonLevelPreview
                if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle=angle }
            }
        }
    }
    override init(frame: CGRect) {
        super.init(frame:frame)
        backgroundColor = .black
        addGestureRecognizer(UITapGestureRecognizer(target:self,action:#selector(tapped(_:))))
        isAccessibilityElement=true
        accessibilityLabel="Prévia da câmera traseira. Toque para ajustar o foco."
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    @objc private func tapped(_ gesture: UITapGestureRecognizer) {
        onFocus?(previewLayer.captureDevicePointConverted(fromLayerPoint:gesture.location(in:self)))
    }
}
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    var onFocus: (CGPoint)->Void
    func makeUIView(context: Context) -> PreviewSurface {
        let view=PreviewSurface()
        view.previewLayer.session=session
        view.previewLayer.videoGravity = .resizeAspect
        view.onFocus=onFocus
        view.coordinateRotation()
        return view
    }
    func updateUIView(_ view: PreviewSurface,context: Context) { view.onFocus=onFocus; view.coordinateRotation() }
}
