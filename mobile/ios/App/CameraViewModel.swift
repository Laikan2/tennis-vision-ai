import SwiftUI
import AVFoundation

@MainActor final class CameraViewModel: ObservableObject {
    let controller=CameraController()
    @Published var snapshot=CameraSnapshot()
    @Published var host=""
    @Published var port="8554"
    @Published var path="iphone"
    @Published var profile=CaptureProfile.standard
    @Published var automatic=UserDefaults.standard.object(forKey:"camera.automatic") as? Bool ?? true
    @Published var message: String?
    @Published var denied=false
    @Published var scannerPresented=false
    @Published var fullscreen=false
    @Published var grid=true
    @Published var exposure=0
    @Published var starting=false
    @Published var page=0
    @Published var panelConfiguration: PairingConfiguration?
    @Published var panelReload=0
    @Published var updateMessage="Atualizações pelo GitHub com o AltStore."
    @Published var checkingUpdate=false
    @Published var newVersion: String?
    private var permissionRequestInFlight=false
    private let settingsKey="tennisvision.ios.pairing.v1"
    private var paired: PairingConfiguration?
    var appVersion: String { Bundle.main.object(forInfoDictionaryKey:"CFBundleShortVersionString") as? String ?? "0.2.1" }

    init() {
        if let data=PairingStore.read() ?? UserDefaults.standard.data(forKey:settingsKey),
           let config=try? PairingConfiguration.restore(data) {
            host=config.host; port=String(config.port); path=config.path; profile=config.profile
            paired=config; panelConfiguration=config.panelURL == nil ? nil : config
        }
        controller.onSnapshot = { [weak self] value in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.snapshot=value; self.starting=false
                if self.automatic, let active=value.activeProfile { self.profile=active }
                UIApplication.shared.isIdleTimerDisabled=value.transmitting
            }
        }
    }
    var profiles: [CaptureProfile] { CameraPolicy.ranked(snapshot.profiles) }
    var selectedSupported: Bool { profiles.contains{$0.id == profile.id} }
    var destination: String { (try? configuration().rtspURL) ?? "Configure o endereço do PC" }
    var deviceLabel: String {
        var system=utsname(); uname(&system)
        let capacity=MemoryLayout.size(ofValue:system.machine)
        let id=withUnsafePointer(to:&system.machine) { pointer in
            pointer.withMemoryRebound(to:CChar.self,capacity:capacity) { String(cString:$0) }
        }
        return ["iPhone16,1":"iPhone 15 Pro","iPhone16,2":"iPhone 15 Pro Max"][id] ?? UIDevice.current.model
    }
    func prepare() {
        guard !snapshot.transmitting, !starting, !scannerPresented, !permissionRequestInFlight else{return}
        switch AVCaptureDevice.authorizationStatus(for:.video) {
        case .authorized: denied=false; controller.prepare(profile:profile,automatic:automatic)
        case .notDetermined:
            permissionRequestInFlight=true
            AVCaptureDevice.requestAccess(for:.video) { [weak self] granted in Task { @MainActor in
                guard let self else{return}; self.permissionRequestInFlight=false
                if granted { self.prepare() } else { self.denied=true }
            }}
        default: denied=true
        }
    }
    func chooseAutomatic(_ enabled: Bool) {
        guard !snapshot.transmitting else { return }
        automatic=enabled; UserDefaults.standard.set(enabled,forKey:"camera.automatic"); prepare()
    }
    func chooseProfile(_ value: CaptureProfile) {
        guard !snapshot.transmitting else{return}
        automatic=false; UserDefaults.standard.set(false,forKey:"camera.automatic")
        profile=value; exposure=0; saveIfValid(); prepare()
    }
    func transmit() {
        do {
            guard !starting else { return }
            guard AVCaptureDevice.authorizationStatus(for:.video) == .authorized else { prepare(); return }
            let config=try configuration(); message=nil; exposure=0
            try persist(config); starting=true
            controller.start(config,automatic:automatic)
        } catch { starting=false; message=error.localizedDescription }
    }
    private func persist(_ config: PairingConfiguration) throws {
        try PairingStore.save(JSONEncoder().encode(config))
        UserDefaults.standard.removeObject(forKey:settingsKey)
        paired=config; panelConfiguration=config.panelURL == nil ? nil : config
    }
    func save() {
        do { try persist(configuration()); message="Endereço do PC salvo." }
        catch { message=error.localizedDescription }
    }
    func applyPairing(_ value: String) {
        do {
            guard !snapshot.transmitting else { throw CameraError.invalid("Pare a transmissão antes de alterar o pareamento.") }
            let config=try PairingConfiguration.parse(value)
            try persist(config)
            host=config.host; port=String(config.port); path=config.path; profile=config.profile; exposure=0
            panelReload+=1
            message=config.panelURL == nil ? "Câmera conectada. Atualize o Windows e leia o novo QR para abrir o painel Treino." : "PC pareado. Toque em Transmitir e abra Treino para acompanhar a análise."
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
    func suspend() { controller.pause(); fullscreen=false; UIApplication.shared.isIdleTimerDisabled=false }
    func changeExposure(_ value: Int) { exposure=value; controller.setExposure(value) }
    func configuration() throws -> PairingConfiguration {
        guard let number=Int(port), String(number)==port else { throw CameraError.invalid("Informe uma porta válida, por exemplo 8554.") }
        // A manual host edit must never send the old PC's secret to a different destination.
        let samePC=host.trimmingCharacters(in:.whitespacesAndNewlines) == paired?.host
        return try PairingConfiguration(host:host,port:number,path:path,profile:profile,
            panelPort:samePC ? paired?.panelPort : nil,panelToken:samePC ? paired?.panelToken : nil)
    }
    private func saveIfValid() { if let config=try? configuration() { do { try persist(config) } catch { message=error.localizedDescription } } }
    func checkUpdates() async {
        guard !checkingUpdate else { return }
        checkingUpdate=true; defer { checkingUpdate=false }
        do {
            var request=URLRequest(url:UpdateSource.url); request.timeoutInterval=12; request.cachePolicy = .reloadIgnoringLocalCacheData
            let (data,response)=try await URLSession.shared.data(for:request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw CameraError.invalid("Fonte indisponível. Tente novamente com internet.") }
            let latest=try UpdateSource.latest(data,os:UIDevice.current.systemVersion)
            newVersion=latest.newer(than:appVersion) ? latest.version : nil
            updateMessage=newVersion.map { "Versão \($0) disponível. Abra o AltStore para atualizar mantendo seu pareamento." } ?? "Você está na versão mais recente da fonte oficial."
        } catch { updateMessage="Não foi possível verificar agora. \(error.localizedDescription)" }
    }
    func openUpdates() {
        UIApplication.shared.open(UpdateSource.addURL,options:[:]) { [weak self] opened in
            if !opened { Task { @MainActor in self?.updateMessage="Abra AltStore → Sources → + e cole a URL da fonte abaixo." } }
        }
    }
}
