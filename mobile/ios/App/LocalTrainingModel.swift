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
    private var sessionSettings=[String:VisionSettings]()
    let folder: URL
    private(set) var loadError: String?
    init(){
        folder=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("TennisLocal",isDirectory:true)
        do{
            try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
            let url=folder.appendingPathComponent("archive-v1.json")
            if FileManager.default.fileExists(atPath:url.path){
                let data=try Data(contentsOf:url);guard data.count<=32_000_000 else{throw CameraError.invalid("Arquivo local muito grande; exporte os dados para revisão.")}
                archive=try JSONDecoder().decode(LocalArchive.self,from:data)
                // Migrate legacy areas without deleting sessions, evidence or preferences.
                let clean=archive.settings.mapValues{$0.withoutSessionArea}
                if clean != archive.settings{archive.settings=clean;try JSONEncoder().encode(archive).write(to:url,options:.atomic)}
            }
        }catch{loadError="Não foi possível ler os dados locais. Eles foram preservados: \(error.localizedDescription)"}
    }
    func settings(_ key: String) -> VisionSettings{lock.lock();defer{lock.unlock()};return sessionSettings[key] ?? archive.settings[key]?.withoutSessionArea ?? VisionSettings()}
    func clearAreas(){lock.lock();sessionSettings.removeAll();lock.unlock()}
    func history() -> [LocalTrainingSession]{lock.lock();defer{lock.unlock()};return archive.sessions.sorted{$0.started>$1.started}}
    func evidenceURL(_ session: String,_ id: Int) -> URL{folder.appendingPathComponent("evidence/\(session)/\(id).jpg")}
    func save(settings: VisionSettings?,key: String,session: LocalTrainingSession?,evidence: (Int,CGImage)?,completion: @escaping (String?)->Void){
        if let settings{lock.lock();sessionSettings[key]=settings;lock.unlock()}
        disk.async{[self] in
            if let loadError{DispatchQueue.main.async{completion(loadError)};return}
            do{
                lock.lock();var copy=archive;lock.unlock()
                if let settings{copy.settings[key]=settings.withoutSessionArea}
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
    @Published var debugOverlay=UserDefaults.standard.bool(forKey:"local.debug-overlay") {didSet{UserDefaults.standard.set(debugOverlay,forKey:"local.debug-overlay")}}
    @Published var markerDuration=UserDefaults.standard.integer(forKey:"local.marker-seconds") {didSet{UserDefaults.standard.set(markerDuration,forKey:"local.marker-seconds")}}
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
    @Published private(set) var homeSetup: VisionHomeAreaSetup?
    @Published private(set) var savingArea=false
    private var editorConfig: VisionSettings?
    var editingArea: VisionSettings?{homeSetup?.settings ?? editorConfig}
    var saveAreaTitle:String{homeSetup?.stage == .area && homeSetup?.surface == .floor ? "Próximo: limite do piso":"Salvar área"}
    var editorHint:String{
        if homeSetup?.surface == .floor {
            return editing==2 ? "Passo 2/2 · "+VisionFloorBoundaryDraft.hint(draft):"Passo 1/2 · arraste sobre a área do chão e ajuste os quatro cantos. Depois indique onde o piso começa."
        }
        if homeSetup?.surface == .wall{return "Parede · arraste sobre a área da parede e ajuste os quatro cantos. A marcação do chão será mantida."}
        return editing==2 ? VisionFloorBoundaryDraft.hint(draft):editing==1 ? "Arraste para expandir; depois ajuste os quatro cantos.":"\(draft.count) / 4 pontos · arraste para ajustar"
    }
    private var probeStarted=0.0,probeFrames=0,probeObservations=0,probeContacts=0,probeTotalMs=0.0,probeMaxMs=0.0
    private var probeNearHand=0,probeUnresolved=0,probeMoving=0,probeAreaSuspended=0
    private var probeContactDetails=[[String:Any]]()
    private var probeTrace=[[String:Any]](),probeInitialThermal=0
    private var probeAttached=false,probeWritten=false,diagnosticLaunched=false
    private var acceptedContacts=Set<String>()
    private var contactClock=VisionContactClock()
    var contactAnimationTimes:[Int:TimeInterval]{contactClock.arrivals}
    private var settingsRevision=0
    private var opening=false,overlayStartID=0
    var visibleEvents:[VisionImpact]{session.events.filter{$0.id>overlayStartID && $0.surface==config.surface && ($0.cameraRevision==nil || $0.cameraRevision==frame?.result.cameraRevision)}}
    var needsAreaCheck:Bool{visibleResult?.areaNeedsConfirmation == true}
    var lastVisibleContact:VisionImpact?{visibleEvents.last(where:{$0.decision != .rejected})}
    var surfaceTitle:String{
        if editing>0{return "EDITANDO \(editingArea?.surface == .wall ? "PAREDE":"CHÃO") · APLICAR AO CONCLUIR"}
        switch config.surface{case .floor:return "CHÃO · QUICADAS";case .wall:return "PAREDE · CONTATOS PARA REVISÃO";case .track:return "RASTREAMENTO · SEM IMPACTOS"}
    }
    var surfaceHint:String{
        if needsAreaCheck{return "Área a conferir: com a câmera parada, batidas observadas geram marcas para revisão. Confira os cantos para habilitar IN/OUT."}
        if config.surface == .wall{return "Parede: retorno da bola gera candidato para revisão; uma câmera não comprova o contato em profundidade."}
        if config.surface == .track{return "Somente rastrear: impactos e IN/OUT estão desligados."}
        if config.court == .custom && !VisionGeometry.validLine(config.floorLine){return "Chão: use Marcar chão e conclua os dois passos: área e limite do piso. Sem a referência, o contato exige revisão."}
        return "Chão: filme a queda de lado, com piso e espaço acima visíveis. Retorno visto só pelo tamanho da bola exige revisão."
    }
    private var cameraProbe: Bool{ProcessInfo.processInfo.arguments.contains("--vision-camera-check")}
    var visibleFrame: LocalCameraFrame?{frozen ?? frame}
    var visibleResult: VisionResult?{editing==0 ? frame?.result.referenced(to:config):nil}
    private var currentArea:VisionSettings{frame?.result.referenced(to:config).area ?? config}
    var insideCount: Int{session.events.filter{$0.decision == .inside}.count}
    var outsideCount: Int{session.events.filter{$0.decision == .outside}.count}
    var reviewCount: Int{session.events.filter{[VisionCall.review,.unmarked].contains($0.decision)}.count}
    var accuracy: String{let n=insideCount+outsideCount;return n>0 ? "\(insideCount*100/n)%":"—"}
    var appVersion: String{Bundle.main.object(forInfoDictionaryKey:"CFBundleShortVersionString") as? String ?? "0.6.17"}
    var totalContacts: Int{session.events.filter{$0.decision != .rejected}.count}
    var areaSummary: String{VisionGeometry.projectedPolygon(config.polygon) ? "\(config.surface == .wall ? "Parede":config.court == .full ? "Quadra inteira":config.court == .half ? "Meia quadra":"Chão") · área temporária desta sessão" : "\(config.surface == .wall ? "Parede":"Chão") · área ainda não marcada"}
    var currentOffset: VisionPoint{visibleResult?.cameraOffset ?? VisionPoint(0,0)}
    init(){
        lineVoice.onStatus={[weak self] message in self?.audioStatus=message}
        let store=store;camera.settingsForKey={store.settings($0)}
        // LocalCamera delivers this closure on DispatchQueue.main after coalescing.
        // Handle that frame synchronously; a second Task would recreate a stale UI queue.
        camera.onFrame={[weak self] frame,events in MainActor.assumeIsolated{self?.receive(frame,events:events)}}
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
        #if targetEnvironment(simulator)
        if LocalLayoutAudit.enabled{LocalLayoutAudit.prepare(self);return}
        #endif
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
        settingsRevision+=1;savingArea=false;confirmed=false
        foreground=false;opening=false;active=false;ready=false;editing=0;frozen=nil;currentGeneration=nil
        homeSetup=nil;editorConfig=nil;editingCourt=nil;draft=[]
        UIApplication.shared.isIdleTimerDisabled=false;camera.stop(completion:completion)
        lineVoice.stop()
    }
    func pause(){active=false;camera.setAnalysis(false);status="Treino pausado · dados no iPhone"}
    func begin(confirmed: Bool=false){
        guard ready,editing==0,!testing,!savingError,!cameraProbe else{return}
        if active{pause();return}
        if session.events.count>=2000{error="Sessão chegou a 2.000 contatos. Comece outra; o histórico será mantido.";return}
        if VisionGeometry.projectedPolygon(config.polygon) && !self.confirmed && !confirmed{confirmArea=true;return}
        self.confirmed=true;active=true;camera.setAnalysis(true);status="Treino no iPhone · aguardando bola"
    }
    func receive(_ frame: LocalCameraFrame,events: [LocalCameraFrame]){
        guard foreground,!testing else{return}
        let firstRealImage = !ready
        if key != frame.key {
            settingsRevision+=1
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
            if !probeAttached{probeAttached=true;probeStarted=ProcessInfo.processInfo.systemUptime;probeInitialThermal=ProcessInfo.processInfo.thermalState.rawValue;camera.setAnalysis(true)}
            probeFrames+=1;probeObservations += frame.result.observed == nil ? 0:1
            probeContacts+=events.filter{$0.result.impact != nil}.count
            if let b=frame.result.observed{if b.nearHand{probeNearHand+=1};if !b.resolved{probeUnresolved+=1}}
            if frame.result.movingCamera{probeMoving+=1};if frame.result.areaNeedsConfirmation{probeAreaSuspended+=1}
            // Explicit developer probe only. Bounded scalar observations let a
            // failed release be inspected without saving camera images/video.
            if !probeWritten && probeTrace.count<5000 {
                var sample:[String:Any]=["time":frame.time,"analysisMs":frame.analysisMs,"exposureMs":frame.exposureMs,"movingCamera":frame.result.movingCamera,"areaSuspended":frame.result.areaNeedsConfirmation]
                if let b=frame.result.observed{sample["ball"]=["x":b.x,"y":b.y,"radius":b.radius,"confidence":b.confidence,"feltChroma":b.feltChroma,"greenDominant":b.greenDominant,"nearHand":b.nearHand,"occluded":b.occluded,"colorUncertain":b.colorUncertain,"motionSupport":b.motionSupport,"diameterX":b.diameterX,"diameterY":b.diameterY] as [String:Any]}
                probeTrace.append(sample)
            }
            for f in events where probeContactDetails.count<32 {
                if let hit=f.result.impact{
                    var detail:[String:Any]=["time":hit.time,"radiusPixels":hit.radius,"uncertaintyPixels":hit.uncertainty,"call":hit.call.rawValue,"reason":hit.reason]
                    if let b=f.result.observed{detail["outgoingRadiusPixels"]=b.radius;detail["outgoingFeltChroma"]=b.feltChroma;detail["outgoingGreenDominant"]=b.greenDominant;detail["outgoingNearHand"]=b.nearHand;detail["outgoingMotionSupport"]=b.motionSupport}
                    probeContactDetails.append(detail)
                }
            }
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
                    "observations":probeObservations,"candidateContacts":probeContacts,"sessionWrites":0,
                    "nearHandFrames":probeNearHand,"unresolvedFrames":probeUnresolved,
                    "movingCameraFrames":probeMoving,"areaSuspendedFrames":probeAreaSuspended,
                    "surface":config.surface.rawValue,"areaPresent":VisionGeometry.projectedPolygon(config.polygon),
                    "contactDetails":probeContactDetails,"sampledDeliveredFrames":probeTrace,
                    "initialThermalState":probeInitialThermal,"finalThermalState":ProcessInfo.processInfo.thermalState.rawValue,
                    "lowPowerMode":ProcessInfo.processInfo.isLowPowerModeEnabled]
                DispatchQueue.global(qos:.utility).async{
                    let url=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("camera-validation.json")
                    do{try JSONSerialization.data(withJSONObject:value,options:[.prettyPrinted,.sortedKeys]).write(to:url,options:.atomic)}catch{print("Camera validation report failed: \(error)")}
                }
            }
        }
        if active && editing==0 {
            // Old marks must not reappear at new physical positions after moving.
            // Keep them in history; only current-view contacts remain overlaid.
            for f in events where f.generation==frame.generation && f.key==key{accept(f)}
            if frame.result.movingCamera{overlayStartID=session.events.map{$0.id}.max() ?? 0}
            if needsAreaCheck && !frame.result.movingCamera{status=surfaceHint}
        }
        let now=ProcessInfo.processInfo.systemUptime
        if now-lastInfo>0.5 {
            lastInfo=now
            metrics=String(format:"%d × %d · %.0f FPS recebidos · %d solicitados\nConversão %.1f ms · análise %.1f ms\nProcessamento → tela ≈%.0f ms · exposição %.1f ms",frame.width,frame.height,frame.fps,frame.requestedFPS,frame.conversionMs,frame.analysisMs,(now-frame.startedAt)*1000,frame.exposureMs)
            if let b=frame.result.observed{metrics+="\nBola ≈\(Int(b.radius*2)) px · \(b.resolved ? "observada":"detalhe incerto")"}
            if active{metrics+=String(format:"\n%.0f FPS analisados",frame.processedFPS)}
            if debugOverlay{metrics+="\n"+frame.result.diagnostics.summary+"\nEscore visual = suporte da silhueta; não é probabilidade calibrada"}
            if active && editing==0{status=visibleResult?.message ?? "Atualizando área"}
        }
    }
    private func accept(_ f: LocalCameraFrame){
        guard var hit=f.result.referenced(to:config).impact,session.events.count<2000 else{return}
        let contactKey="\(f.generation.uuidString):\(hit.id)"
        guard acceptedContacts.insert(contactKey).inserted else{return}
        hit.id=(session.events.map{$0.id}.max() ?? 0)+1;hit.evidenceOffset=f.evidenceOffset ?? hit.cameraOffset
        let arrival=ProcessInfo.processInfo.systemUptime
        // Marker lifetime and the 480 ms pulse share an origin, not a lifetime.
        // Keep arrival times until the session is reset (bounded to 2,000 events).
        contactClock.record(hit.id,at:arrival)
        session.events.append(hit)
        store.save(settings:nil,key:key,session:session,evidence:f.evidence.map{(hit.id,$0)}){[weak self] message in
            if let message{self?.savingError=true;self?.pause();self?.error=message}
        }
        if f.result.cameraRevision==frame?.result.cameraRevision,frame?.result.movingCamera != true,let voice=LineVoicePolicy.voice(for:hit.decision,outEnabled:config.sound,inEnabled:inVoice){lineVoice.play(voice)}
    }
    func playOut(){lineVoice.play(.outside,test:true)}
    func playIn(){lineVoice.play(.inside,test:true)}
    func setInVoice(_ enabled: Bool){inVoice=enabled;UserDefaults.standard.set(enabled,forKey:"local.sound.in")}
    func edit(_ mode: Int,court: VisionCourt?=nil,surface:VisionSurface?=nil,home:Bool=false){
        guard ready,frame != nil,!testing,editing==0,!savingArea else{return};pause();frozen=frame;editing=mode
        let target=surface ?? ((mode==2 || court != nil && court != .custom) ? .floor:config.surface)
        let reference=currentArea.selectingSurface(target)
        editingCourt=court;editorConfig=reference
        homeSetup=home ? VisionHomeAreaSetup(settings:reference,surface:target):nil
        draft=mode==2 ? reference.floorLine:(mode==3 || home) ? reference.polygon:[]
        status=home ? editorHint:mode==2 ? "Toque nos dois extremos do limite parede/chão":mode==1 ? "Arraste para criar a área":draft.count==4 ? "Arraste os cantos para ajustar":"Toque nos quatro cantos da superfície"
    }
    func editHome(_ surface:VisionSurface){edit(1,court:.custom,surface:surface,home:true)}
    func changeSurface(_ surface:VisionSurface){guard editing==0,!savingArea else{return};confirmed=false;persist(currentArea.selectingSurface(surface))}
    func cancelEdit(){guard !savingArea else{return};editing=0;draft=[];frozen=nil;editingCourt=nil;editorConfig=nil;homeSetup=nil;status="Edição cancelada · modo \(config.surface == .wall ? "parede":config.surface == .floor ? "chão":"somente rastrear") mantido"}
    func redraw(){draft=[]}
    func useWholeImageFloor(){
        guard editing==2,!savingArea else{return}
        draft=VisionFloorBoundaryDraft.wholeImage;saveArea()
    }
    func confirmCurrentArea(){
        guard ready,editing==0,!savingArea,needsAreaCheck else{return}
        // Explicit visual confirmation, never an automatic acceptance after a
        // different view merely becomes still. Preserve history and settings.
        let resume=active,value=currentArea
        overlayStartID=session.events.map{$0.id}.max() ?? 0
        persist(value){[weak self] in
            guard let self else{return};self.confirmed=true
            self.status="Área conferida · impactos liberados nesta posição"
            if resume{self.begin(confirmed:true)}
        }
    }
    var canSaveArea: Bool{!savingArea && (editing==2 ? VisionGeometry.validLine(draft):VisionGeometry.valid(draft))}
    func saveArea(){
        guard canSaveArea else{return}
        var value=editorConfig ?? config
        if var home=homeSetup {
            guard home.accept(draft) else{return};homeSetup=home
            if home.stage == .floorBoundary{editing=2;draft=home.settings.floorLine;status=editorHint;return}
            guard let completed=home.completedSettings else{return};value=completed
        }else{
            value.court=editingCourt ?? value.court
            if editing==2{value.floorLine=draft;value.court = .custom;value.surface = .floor}else{value.polygon=draft}
        }
        savingArea=true
        let saved=value.rememberingArea()
        persist(saved){[weak self] in
            guard let self else{return};self.savingArea=false;self.editing=0;self.frozen=nil;self.draft=[];self.editingCourt=nil;self.editorConfig=nil;self.homeSetup=nil;self.confirmed=true
            self.status="\(saved.surface == .wall ? "Parede":"Chão") marcado para esta sessão; nova sessão ou fechar o app limpa a área"
        }
    }
    func persist(_ value: VisionSettings,done: (() -> Void)?=nil){
        let changesReference = !value.usesSameVisibleArea(as:config)
        settingsRevision+=1;let revision=settingsRevision,savedKey=key
        pause();config=value
        if changesReference{overlayStartID=session.events.map{$0.id}.max() ?? 0}
        camera.update(value)
        store.save(settings:value,key:key,session:nil,evidence:nil){[weak self] message in
            guard let self else{return}
            if let message{self.savingArea=false;self.error=message;self.savingError=true;return}
            // Disk completion from an earlier session/camera cannot restore its area.
            guard self.settingsRevision==revision,self.key==savedKey else{return}
            done?()
        }
    }
    func changeDetail(_ value: LocalDetail){guard editing==0 else{return};pause();detail=value;UserDefaults.standard.set(value.rawValue,forKey:"local.detail");prepare(force:true)}
    func changeLens(_ value: String){guard editing==0 else{return};pause();lensID=value;UserDefaults.standard.set(value,forKey:"local.lens");prepare(force:true)}
    func changeRate(_ value: Int){guard editing==0,[0,30,60,120,240].contains(value) else{return};pause();rate=value;UserDefaults.standard.set(value,forKey:"local.rate");prepare(force:true)}
    func newSession(){
        guard !savingArea,editing==0 else{return}
        settingsRevision+=1
        pause();store.clearAreas();config=config.withoutSessionArea;confirmed=false;confirmArea=false
        // Remove the previous result/area immediately; new frames use the cleared settings.
        frame=nil;frozen=nil;acceptedContacts.removeAll();contactClock.reset();camera.update(config)
        overlayStartID=0;session=LocalTrainingSession(key:key)
        store.save(settings:nil,key:key,session:session,evidence:nil){[weak self] message in self?.error=message}
        history=store.history();status="Nova sessão · marque o chão ou a parede; áreas anteriores descartadas"
    }
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
