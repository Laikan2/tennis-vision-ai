import SwiftUI
import AVFoundation

struct LocalTrainingSession: Codable,Identifiable,Sendable {
    var id=UUID().uuidString,started=Date(),key="",events=[VisionImpact]()
}
private struct LocalArchive: Codable {var settings=[String:VisionSettings](),sessions=[LocalTrainingSession]()}
/// Separate from the existing Keychain pairing and the optional PC panel.
final class LocalTrainingStore {
    private let lock=NSLock(),disk=DispatchQueue(label:"ai.tennisvision.ios.local-disk")
    private var archive=LocalArchive()
    let folder: URL
    private(set) var loadError: String?
    init(){
        folder=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("TennisLocal",isDirectory:true)
        do{
            try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
            let url=folder.appendingPathComponent("archive-v1.json")
            if FileManager.default.fileExists(atPath:url.path){let data=try Data(contentsOf:url);guard data.count<=32_000_000 else{throw CameraError.invalid("Arquivo local muito grande; exporte os dados para revisão.")};archive=try JSONDecoder().decode(LocalArchive.self,from:data)}
        }catch{loadError="Não foi possível ler os dados locais. Eles foram preservados: \(error.localizedDescription)"}
    }
    func settings(_ key: String) -> VisionSettings{lock.lock();defer{lock.unlock()};return archive.settings[key] ?? VisionSettings()}
    func history() -> [LocalTrainingSession]{lock.lock();defer{lock.unlock()};return archive.sessions.sorted{$0.started>$1.started}}
    func evidenceURL(_ session: String,_ id: Int) -> URL{folder.appendingPathComponent("evidence/\(session)/\(id).jpg")}
    func save(settings: VisionSettings?,key: String,session: LocalTrainingSession?,evidence: (Int,CGImage)?,completion: @escaping (String?)->Void){
        disk.async{[self] in
            if let loadError{DispatchQueue.main.async{completion(loadError)};return}
            do{
                lock.lock();var copy=archive;lock.unlock()
                if let settings{copy.settings[key]=settings}
                if let session{
                    if let i=copy.sessions.firstIndex(where:{$0.id==session.id}){copy.sessions[i]=session}else{copy.sessions.append(session)}
                    // Retain existing sessions; never silently delete a user's history.
                    if let (id,image)=evidence {
                        let url=evidenceURL(session.id,id)
                        try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
                        guard let data=UIImage(cgImage:image).jpegData(compressionQuality:0.88) else{throw CameraError.invalid("Não criou a evidência do contato.")}
                        try data.write(to:url,options:.atomic)
                    }
                }
                let data=try JSONEncoder().encode(copy)
                guard data.count<=32_000_000 else{throw CameraError.invalid("Histórico chegou ao limite local. Exporte antes de continuar.")}
                try data.write(to:folder.appendingPathComponent("archive-v1.json"),options:.atomic)
                lock.lock();archive=copy;lock.unlock();DispatchQueue.main.async{completion(nil)}
            }catch{DispatchQueue.main.async{completion("Não salvou os dados: \(error.localizedDescription)")}}
        }
    }
}

@MainActor final class LocalTrainingModel: ObservableObject {
    let camera=LocalCamera(),store=LocalTrainingStore()
    @Published var frame: LocalCameraFrame?
    @Published var frozen: LocalCameraFrame?
    @Published var config=VisionSettings()
    @Published var session=LocalTrainingSession()
    @Published var status="Preparando treino local"
    @Published var ready=false
    @Published var active=false
    @Published var denied=false
    @Published var cameraUnavailable=false
    @Published var error: String?
    @Published var editing=0
    @Published var draft=[VisionPoint]()
    @Published var detail=LocalDetail(rawValue:UserDefaults.standard.string(forKey:"local.detail") ?? "fluid") ?? .fluid
    @Published var lensID=UserDefaults.standard.string(forKey:"local.lens") ?? ""
    @Published var rate=UserDefaults.standard.object(forKey:"local.rate") as? Int ?? 60
    @Published var metrics="Aguardando imagens reais"
    @Published var confirmArea=false
    @Published var confirmed=false
    @Published var history=[LocalTrainingSession]()
    @Published var reviewing: VisionImpact?
    @Published var reviewImage: UIImage?
    @Published var diagnosticReport: VisionDiagnostics.Report?
    @Published var testing=false
    @Published var exportURL: URL?
    @Published var checkingUpdate=false
    @Published var updateMessage=""
    @Published var newVersion: String?
    @Published var updateDownloadURL: URL?
    private var foreground=true,permissionPending=false,key="",savingError=false,lastInfo=0.0,currentGeneration: UUID?
    private let lineVoice=LineVoicePlayer()
    @Published var inVoice=UserDefaults.standard.bool(forKey:"local.sound.in")
    @Published var audioStatus="Voz ElevenLabs · funciona sem internet. Teste IN e OUT para conferir o volume."
    private var soundCount: Int{lineVoice.startedCount}
    private var editingCourt: VisionCourt?
    private var probeStarted=0.0,probeFrames=0,probeObservations=0,probeContacts=0,probeTotalMs=0.0,probeMaxMs=0.0
    private var probeAttached=false,probeWritten=false,diagnosticLaunched=false
    private var acceptedContacts=Set<String>()
    private var opening=false,overlayStartID=0
    var visibleEvents:[VisionImpact]{session.events.filter{$0.id>overlayStartID}}
    var needsAreaCheck:Bool{frame?.result.areaNeedsConfirmation == true}
    var surfaceHint:String{
        if needsAreaCheck{return "A imagem mudou. Use Marcar área para conferir os cantos; o treino continua aberto."}
        if config.surface == .wall{return "Parede: retorno da bola gera candidato para revisão; uma câmera não comprova o contato em profundidade."}
        if config.surface == .track{return "Somente rastrear: impactos e IN/OUT estão desligados."}
        if config.court == .custom && !VisionGeometry.validLine(config.floorLine){return "Chão: falta indicar onde começa o piso. Marcar área → Quarto · limite do piso. Sem isso, o contato exige revisão."}
        return "Chão: aguarda descida e retorno da bola no piso. Perto da linha, a decisão pode exigir revisão."
    }
    private var cameraProbe: Bool{ProcessInfo.processInfo.arguments.contains("--vision-camera-check")}
    var visibleFrame: LocalCameraFrame?{frozen ?? frame}
    var visibleResult: VisionResult?{editing==0 && active ? frame?.result:nil}
    var insideCount: Int{session.events.filter{$0.decision == .inside}.count}
    var outsideCount: Int{session.events.filter{$0.decision == .outside}.count}
    var reviewCount: Int{session.events.filter{[VisionCall.review,.unmarked].contains($0.decision)}.count}
    var accuracy: String{let n=insideCount+outsideCount;return n>0 ? "\(insideCount*100/n)%":"—"}
    var appVersion: String{Bundle.main.object(forInfoDictionaryKey:"CFBundleShortVersionString") as? String ?? "0.6.7"}
    var totalContacts: Int{session.events.filter{$0.decision != .rejected}.count}
    var areaSummary: String{VisionGeometry.valid(config.polygon) ? "\(config.court == .full ? "Quadra inteira":config.court == .half ? "Meia quadra":"Área livre") · salva para esta lente" : "Área ainda não marcada"}
    var currentOffset: VisionPoint{visibleFrame?.result.cameraOffset ?? VisionPoint(0,0)}
    init(){
        lineVoice.onStatus={[weak self] message in self?.audioStatus=message}
        let store=store;camera.settingsForKey={store.settings($0)}
        camera.onFrame={[weak self] frame,events in Task{@MainActor in self?.receive(frame,events:events)}}
        camera.onState={[weak self] message,ready in Task{@MainActor in
            guard let self,self.foreground else{return};self.ready=ready
            self.cameraUnavailable = !ready;self.opening=false
            if !ready{self.active=false;self.currentGeneration=nil}
            self.status=message;UIApplication.shared.isIdleTimerDisabled=ready
        }}
        camera.onNotice={[weak self] message in Task{@MainActor in if let self,self.foreground{self.status=message}}}
        history=store.history()
        if let message=store.loadError{error=message;savingError=true}
    }
    func prepare(force:Bool=false){
        foreground=true;guard !testing,!permissionPending else{return}
        if !force && (ready || opening){return}
        if ProcessInfo.processInfo.arguments.contains("--vision-diagnostics"),!diagnosticLaunched {
            diagnosticLaunched=true;diagnostics();return
        }
        switch AVCaptureDevice.authorizationStatus(for:.video){
        case .authorized:opening=true;denied=false;ready=false;cameraUnavailable=false;status="Abrindo câmera · aguarde a imagem";currentGeneration=nil;overlayStartID=session.events.map{$0.id}.max() ?? 0;camera.start(detail:detail,lensID:lensID,rate:rate)
        case .notDetermined:
            permissionPending=true
            AVCaptureDevice.requestAccess(for:.video){[weak self] allowed in Task{@MainActor in
                guard let self else{return};self.permissionPending=false
                guard self.foreground else{return}
                if allowed{self.prepare()}else{self.denied=true;self.status="Permita a câmera para começar"}
            }}
        default:denied=true;ready=false;status="Acesso à câmera negado"
        }
    }
    func suspend(completion: (() -> Void)?=nil){
        foreground=false;opening=false;active=false;ready=false;editing=0;frozen=nil;currentGeneration=nil
        UIApplication.shared.isIdleTimerDisabled=false;camera.stop(completion:completion)
        lineVoice.stop()
    }
    func pause(){active=false;camera.setAnalysis(false);status="Treino pausado · dados no iPhone"}
    func begin(confirmed: Bool=false){
        guard ready,editing==0,!testing,!savingError,!cameraProbe else{return}
        if active{pause();return}
        if session.events.count>=2000{error="Sessão chegou a 2.000 contatos. Comece outra; o histórico será mantido.";return}
        if VisionGeometry.valid(config.polygon) && !self.confirmed && !confirmed{confirmArea=true;return}
        self.confirmed=true;active=true;camera.setAnalysis(true);status="Treino no iPhone · aguardando bola"
    }
    func receive(_ frame: LocalCameraFrame,events: [LocalCameraFrame]){
        guard foreground,!testing else{return}
        let firstRealImage = !ready
        if key != frame.key {
            pause();key=frame.key;config=store.settings(key);confirmed=false;currentGeneration=frame.generation
            session=store.history().first(where:{$0.key==key}) ?? LocalTrainingSession(key:key)
            overlayStartID=session.events.map{$0.id}.max() ?? 0
        }
        if let generation=currentGeneration,generation != frame.generation{active=false;confirmed=false;overlayStartID=session.events.map{$0.id}.max() ?? 0};currentGeneration=frame.generation
        if editing==0{self.frame=frame}
        ready=true;opening=false;cameraUnavailable=false
        if firstRealImage{status="Câmera pronta · confira a área";UIApplication.shared.isIdleTimerDisabled=true}
        // Explicit developer launch only: live pixels go through the same engine,
        // but no probe event, image or sound is added to the user's session.
        if cameraProbe {
            if !probeAttached{probeAttached=true;probeStarted=ProcessInfo.processInfo.systemUptime;camera.setAnalysis(true)}
            probeFrames+=1;probeObservations += frame.result.observed == nil ? 0:1
            probeContacts+=events.filter{$0.result.impact != nil}.count
            probeTotalMs+=frame.analysisMs;probeMaxMs=max(probeMaxMs,frame.analysisMs)
            status="VALIDAÇÃO DA CÂMERA · nenhum evento salvo no treino"
            if !probeWritten,ProcessInfo.processInfo.systemUptime-probeStarted>=20 {
                probeWritten=true
                let value: [String:Any]=[
                    "version":appVersion,"os":UIDevice.current.systemVersion,"kind":"physical-camera-probe-not-known-ball-ground-truth",
                    "width":frame.width,"height":frame.height,"requestedFPS":frame.requestedFPS,"receivedFPS":frame.fps,
                    "seconds":ProcessInfo.processInfo.systemUptime-probeStarted,"framesDeliveredToUI":probeFrames,
                    "meanAnalysisMs":probeTotalMs/Double(probeFrames),"maxAnalysisMs":probeMaxMs,
                    "lastConversionMs":frame.conversionMs,"processingToUIAgeMs":(ProcessInfo.processInfo.systemUptime-frame.startedAt)*1000,
                    "observations":probeObservations,"candidateContacts":probeContacts,"sessionWrites":0]
                DispatchQueue.global(qos:.utility).async{
                    let url=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("camera-validation.json")
                    do{try JSONSerialization.data(withJSONObject:value,options:[.prettyPrinted,.sortedKeys]).write(to:url,options:.atomic)}catch{print("Camera validation report failed: \(error)")}
                }
            }
        }
        if active && editing==0 {
            for f in events where f.generation==frame.generation && f.key==key{accept(f)}
            if frame.result.areaNeedsConfirmation{status=surfaceHint}
        }
        let now=ProcessInfo.processInfo.systemUptime
        if now-lastInfo>0.5 {
            lastInfo=now
            metrics=String(format:"%d × %d · %.0f FPS recebidos · %d solicitados\nConversão %.1f ms · análise %.1f ms\nProcessamento → tela ≈%.0f ms · exposição %.1f ms",frame.width,frame.height,frame.fps,frame.requestedFPS,frame.conversionMs,frame.analysisMs,(now-frame.startedAt)*1000,frame.exposureMs)
            if let b=frame.result.observed{metrics+="\nBola ≈\(Int(b.radius*2)) px · \(b.resolved ? "observada":"detalhe incerto")"}
            if active && editing==0{status=frame.result.message}
        }
    }
    private func accept(_ f: LocalCameraFrame){
        guard var hit=f.result.impact,session.events.count<2000 else{return}
        let contactKey="\(f.generation.uuidString):\(hit.id)"
        guard acceptedContacts.insert(contactKey).inserted else{return}
        hit.id=(session.events.map{$0.id}.max() ?? 0)+1;hit.evidenceOffset=f.evidenceOffset ?? hit.cameraOffset
        session.events.append(hit)
        store.save(settings:nil,key:key,session:session,evidence:f.evidence.map{(hit.id,$0)}){[weak self] message in
            if let message{self?.savingError=true;self?.pause();self?.error=message}
        }
        if let voice=LineVoicePolicy.voice(for:hit.decision,outEnabled:config.sound,inEnabled:inVoice){lineVoice.play(voice)}
    }
    func playOut(){lineVoice.play(.outside,test:true)}
    func playIn(){lineVoice.play(.inside,test:true)}
    func setInVoice(_ enabled: Bool){inVoice=enabled;UserDefaults.standard.set(enabled,forKey:"local.sound.in")}
    func edit(_ mode: Int,court: VisionCourt?=nil){
        guard ready,frame != nil,!testing else{return};pause();frozen=frame;editing=mode
        editingCourt=court
        let reference=frame?.result.area ?? config
        draft=mode==2 ? reference.floorLine:mode==3 ? reference.polygon:[]
        status=mode==2 ? "Toque nos dois extremos do limite parede/chão":mode==1 ? "Arraste para criar a área":draft.count==4 ? "Arraste os cantos para ajustar":"Toque nos quatro cantos ao redor do piso"
    }
    func cancelEdit(){editing=0;draft=[];frozen=nil;editingCourt=nil;status="Edição cancelada · área anterior mantida"}
    func redraw(){draft=[]}
    var canSaveArea: Bool{editing==2 ? VisionGeometry.validLine(draft):VisionGeometry.valid(draft)}
    func saveArea(){
        guard canSaveArea else{return};var value=frozen?.result.area ?? config
        value.court=editingCourt ?? value.court
        if editing==2{value.floorLine=draft;value.court = .custom;value.surface = .floor}else{value.polygon=draft}
        if value.court != .custom{value.surface = .floor}
        persist(value){[weak self] in self?.editing=0;self?.frozen=nil;self?.draft=[];self?.editingCourt=nil;self?.confirmed=true;self?.status="Área salva para esta câmera, lente e resolução"}
    }
    func persist(_ value: VisionSettings,done: (() -> Void)?=nil){
        pause();store.save(settings:value,key:key,session:nil,evidence:nil){[weak self] message in
            guard let self else{return}
            if let message{self.error=message;self.savingError=true;return}
            self.config=value;self.overlayStartID=self.session.events.map{$0.id}.max() ?? 0;self.camera.update(value);done?()
        }
    }
    func changeDetail(_ value: LocalDetail){guard editing==0 else{return};pause();detail=value;UserDefaults.standard.set(value.rawValue,forKey:"local.detail");prepare(force:true)}
    func changeLens(_ value: String){guard editing==0 else{return};pause();lensID=value;UserDefaults.standard.set(value,forKey:"local.lens");prepare(force:true)}
    func changeRate(_ value: Int){guard editing==0,[0,30,60,120,240].contains(value) else{return};pause();rate=value;UserDefaults.standard.set(value,forKey:"local.rate");prepare(force:true)}
    func newSession(){pause();overlayStartID=0;session=LocalTrainingSession(key:key);store.save(settings:nil,key:key,session:session,evidence:nil){[weak self] message in self?.error=message};history=store.history()}
    func review(_ event: VisionImpact){pause();reviewing=event;reviewImage=nil;let url=store.evidenceURL(session.id,event.id)
        DispatchQueue.global(qos:.utility).async{[weak self] in let image=UIImage(contentsOfFile:url.path);Task{@MainActor in if self?.reviewing?.id==event.id{self?.reviewImage=image}}}
    }
    func revise(_ call: VisionCall){
        guard let hit=reviewing,let i=session.events.firstIndex(where:{$0.id==hit.id}) else{return}
        var next=session;next.events[i].reviewed=call
        store.save(settings:nil,key:key,session:next,evidence:nil){[weak self] message in
            guard let self else{return}
            if let message{self.error=message;return}
            self.session=next;self.reviewing=nil;self.reviewImage=nil
        }
    }
    func exportSession(){pause();let snapshot=session
        DispatchQueue.global(qos:.utility).async{[weak self] in
            do{
                let url=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("Tennis-Vision-\(snapshot.id).json")
                try JSONEncoder().encode(snapshot).write(to:url,options:.atomic)
                Task{@MainActor in self?.exportURL=url}
            }catch{Task{@MainActor in self?.error=error.localizedDescription}}
        }
    }
    func diagnostics(){
        guard !testing,editing==0 else{return};pause();testing=true;ready=false;status="Teste com imagens simuladas · não é câmera"
        camera.stop{[weak self] in
            DispatchQueue.global(qos:.userInitiated).async{
                let report=VisionDiagnostics.run()
                let url=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("vision-diagnostics.json")
                var failure: String?
                do{try JSONEncoder().encode(report).write(to:url,options:.atomic)}catch{failure=error.localizedDescription}
                Task{@MainActor in guard let self else{return};self.testing=false;self.diagnosticReport=report;self.error=failure;self.status=report.passed ? "Testes sintéticos concluídos · confira os resultados":"Há falhas nos testes sintéticos · confira os resultados"}
            }
        }
    }
    func checkUpdate() async{
        guard !checkingUpdate else{return};checkingUpdate=true;defer{checkingUpdate=false}
        do{
            var request=URLRequest(url:UpdateSource.url);request.timeoutInterval=12;request.cachePolicy = .reloadIgnoringLocalCacheData
            let (data,response)=try await URLSession.shared.data(for:request)
            guard (response as? HTTPURLResponse)?.statusCode==200 else{throw CameraError.invalid("Fonte indisponível")}
            let release=try UpdateSource.latest(data,os:UIDevice.current.systemVersion)
            newVersion=release.newer(than:appVersion) ? release.version:nil
            updateDownloadURL=newVersion==nil ? nil:release.downloadURL
            updateMessage=newVersion.map{"Versão mobile \($0) disponível no AltStore"} ?? "Nenhuma atualização compatível disponível"
        }catch{updateMessage="Sem consulta agora. O treino local continua disponível."}
    }
    func installUpdate(){
        guard newVersion != nil,let download=updateDownloadURL,let url=UpdateSource.installURL(for:download) else{return}
        pause()
        UIApplication.shared.open(url){[weak self] opened in Task{@MainActor in
            if !opened{self?.error="Não foi possível abrir o AltStore. Instale/abra o AltStore Classic e use a fonte oficial ou o IPA do GitHub."}
        }}
    }
}
