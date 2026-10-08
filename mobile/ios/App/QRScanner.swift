import SwiftUI
import AVFoundation

struct QRScanner: UIViewControllerRepresentable {
    var onResult: (String?)->Void
    func makeUIViewController(context: Context) -> ScannerController { ScannerController(onResult:onResult) }
    func updateUIViewController(_ uiViewController: ScannerController,context: Context) {}
    static func dismantleUIViewController(_ uiViewController: ScannerController,coordinator: ()) { uiViewController.stop() }
}
final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let session=AVCaptureSession()
    private let queue=DispatchQueue(label:"ai.tennisvision.ios.qr",qos:.userInitiated)
    private var layer: AVCaptureVideoPreviewLayer?
    private var finished=false
    private let onResult: (String?)->Void
    init(onResult: @escaping (String?)->Void) { self.onResult=onResult; super.init(nibName:nil,bundle:nil) }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .black
        let preview=AVCaptureVideoPreviewLayer(session:session); preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview); layer=preview
        let heading=UILabel(); heading.text="Leia o QR iPhone / iOS do Windows"; heading.textColor = .white
        heading.font = .preferredFont(forTextStyle:.headline); heading.numberOfLines=0; heading.textAlignment = .center
        heading.translatesAutoresizingMaskIntoConstraints=false; view.addSubview(heading)
        let close=UIButton(type:.system); close.setTitle("Cancelar",for:.normal); close.tintColor = .white
        close.backgroundColor=UIColor.black.withAlphaComponent(0.7); close.layer.cornerRadius=14
        close.translatesAutoresizingMaskIntoConstraints=false; close.addTarget(self,action:#selector(cancel),for:.touchUpInside)
        view.addSubview(close)
        NSLayoutConstraint.activate([
            heading.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor,constant:28),
            heading.leadingAnchor.constraint(equalTo:view.leadingAnchor,constant:24),heading.trailingAnchor.constraint(equalTo:view.trailingAnchor,constant:-24),
            close.bottomAnchor.constraint(equalTo:view.safeAreaLayoutGuide.bottomAnchor,constant:-28),
            close.centerXAnchor.constraint(equalTo:view.centerXAnchor),close.widthAnchor.constraint(equalToConstant:160),close.heightAnchor.constraint(equalToConstant:52),
        ])
        queue.async { [weak self] in
            guard let self else{return}
            do {
                guard let device=AVCaptureDevice.default(for:.video) else {throw CameraError.invalid("Câmera indisponível.")}
                let input=try AVCaptureDeviceInput(device:device); let output=AVCaptureMetadataOutput()
                self.session.beginConfiguration()
                self.session.sessionPreset = .high
                guard self.session.canAddInput(input), self.session.canAddOutput(output) else {
                    self.session.commitConfiguration(); throw CameraError.invalid("O leitor QR não conseguiu abrir a câmera.")
                }
                self.session.addInput(input); self.session.addOutput(output)
                output.setMetadataObjectsDelegate(self,queue:.main)
                guard output.availableMetadataObjectTypes.contains(.qr) else {
                    self.session.commitConfiguration(); throw CameraError.invalid("Leitura QR indisponível nesta câmera.")
                }
                output.metadataObjectTypes=[.qr]; self.session.commitConfiguration()
                self.session.startRunning()
            } catch {
                DispatchQueue.main.async {
                    let alert=UIAlertController(title:"Não foi possível ler o QR",message:error.localizedDescription,preferredStyle:.alert)
                    alert.addAction(UIAlertAction(title:"Voltar",style:.default){_ in self.finish(nil)})
                    self.present(alert,animated:true)
                }
            }
        }
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews(); layer?.frame=view.bounds
        if let connection=layer?.connection, connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle=90 }
    }
    func metadataOutput(_ output: AVCaptureMetadataOutput,didOutput metadataObjects: [AVMetadataObject],from connection: AVCaptureConnection) {
        guard !finished, let object=metadataObjects.compactMap({$0 as? AVMetadataMachineReadableCodeObject}).first,
              let value=object.stringValue else{return}
        finish(value)
    }
    @objc private func cancel() { finish(nil) }
    private func finish(_ value: String?) {
        guard !finished else{return}; finished=true
        // Wait until this camera session has released its input before restarting the video session.
        queue.async { [weak self] in
            guard let self else{return}
            if self.session.isRunning { self.session.stopRunning() }
            DispatchQueue.main.async { self.onResult(value) }
        }
    }
    func stop() { queue.async { [weak self] in if self?.session.isRunning == true { self?.session.stopRunning() } } }
}
