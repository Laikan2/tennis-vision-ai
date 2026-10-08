import SwiftUI
import AVFoundation

@MainActor final class CameraViewModel: ObservableObject {
    let controller=CameraController()
    @Published var snapshot=CameraSnapshot()
    @Published var host=""
    @Published var port="8554"
    @Published var path="iphone"
    @Published var profile=CaptureProfile.standard
    @Published var message: String?
    @Published var denied=false
    @Published var scannerPresented=false
    @Published var fullscreen=false
    @Published var grid=true
    @Published var exposure=0
    private var permissionRequestInFlight=false
    private let settingsKey="tennisvision.ios.pairing.v1"

    init() {
        if let data=UserDefaults.standard.data(forKey:settingsKey), let config=try? PairingConfiguration.restore(data) {
            host=config.host; port=String(config.port); path=config.path; profile=config.profile
        }
        controller.onSnapshot = { [weak self] value in
            Task { @MainActor [weak self] in self?.snapshot=value }
        }
    }
    var profiles: [CaptureProfile] { snapshot.profiles }
    var selectedSupported: Bool { profiles.contains{$0.id == profile.id} }
    var destination: String { (try? configuration().rtspURL) ?? "Configure o endereço do PC" }
    func prepare() {
        guard !snapshot.transmitting, !scannerPresented, !permissionRequestInFlight else{return}
        switch AVCaptureDevice.authorizationStatus(for:.video) {
        case .authorized: denied=false; controller.prepare(profile:profile)
        case .notDetermined:
            permissionRequestInFlight=true
            AVCaptureDevice.requestAccess(for:.video) { [weak self] granted in Task { @MainActor in
                guard let self else{return}
                self.permissionRequestInFlight=false
                if granted { self.prepare() } else { self.denied=true }
            }}
        default: denied=true
        }
    }
    func chooseProfile(_ value: CaptureProfile) {
        guard !snapshot.transmitting else{return}
        profile=value; exposure=0; saveIfValid(); prepare()
    }
    func transmit() {
        do {
            guard AVCaptureDevice.authorizationStatus(for:.video) == .authorized else { prepare(); return }
            let config=try configuration(); message=nil; exposure=0
            UserDefaults.standard.set(try JSONEncoder().encode(config),forKey:settingsKey)
            controller.start(config)
        } catch { message=error.localizedDescription }
    }
    func save() {
        do { let config=try configuration(); UserDefaults.standard.set(try JSONEncoder().encode(config),forKey:settingsKey); message="Endereço do PC salvo." }
        catch { message=error.localizedDescription }
    }
    func applyPairing(_ value: String) {
        do {
            guard !snapshot.transmitting else { throw CameraError.invalid("Pare a transmissão antes de alterar o pareamento.") }
            let config=try PairingConfiguration.parse(value)
            host=config.host; port=String(config.port); path=config.path; profile=config.profile; exposure=0
            UserDefaults.standard.set(try JSONEncoder().encode(config),forKey:settingsKey)
            message="PC configurado. Confira o perfil e toque em Transmitir."
            prepare()
        } catch { message=error.localizedDescription; prepare() }
    }
    func openScanner() {
        guard !snapshot.transmitting else { message="Pare a transmissão para ler outro QR."; return }
        guard AVCaptureDevice.authorizationStatus(for:.video) == .authorized else { prepare(); return }
        controller.pause { [weak self] in self?.scannerPresented=true }
    }
    func scannerFinished(_ value: String?) {
        scannerPresented=false
        if let value { applyPairing(value) } else { prepare() }
    }
    func suspend() { controller.pause(); fullscreen=false }
    func changeExposure(_ value: Int) { exposure=value; controller.setExposure(value) }
    func configuration() throws -> PairingConfiguration {
        guard let number=Int(port), String(number)==port else { throw CameraError.invalid("Informe uma porta válida, por exemplo 8554.") }
        return try PairingConfiguration(host:host,port:number,path:path,profile:profile)
    }
    private func saveIfValid() { if let config=try? configuration(), let data=try? JSONEncoder().encode(config) { UserDefaults.standard.set(data,forKey:settingsKey) } }
}
