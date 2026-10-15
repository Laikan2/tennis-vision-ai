import SwiftUI
import UIKit

extension VisionCall {
    var label: String{switch self{case .inside:return "IN";case .outside:return "OUT";case .review:return "REVISAR";case .unmarked:return "SEM ÁREA";case .rejected:return "DESCARTADO"}}
    var color: UIColor{switch self{case .inside:return UIColor(red:0.78,green:0.96,blue:0.31,alpha:1);case .outside:return .systemRed;default:return .systemOrange}}
}
final class LocalCourtSurface: UIView,UIGestureRecognizerDelegate {
    var frameValue: LocalCameraFrame?,result: VisionResult?,config=VisionSettings(),events=[VisionImpact]()
    var confirmed=false,editing=0,draft=[VisionPoint](),onDraft: (([VisionPoint])->Void)?,onImpact: ((Int)->Void)?
    private var movingCorner: Int?,anchor: VisionPoint?,touchPoint: VisionPoint?
    private lazy var pan=UIPanGestureRecognizer(target:self,action:#selector(handlePan(_:)))
    private lazy var tap=UITapGestureRecognizer(target:self,action:#selector(handleTap(_:)))
    private var imageRect: CGRect{
        guard let frame=frameValue else{return bounds}
        let scale=min(bounds.width/CGFloat(frame.width),bounds.height/CGFloat(frame.height))
        let size=CGSize(width:CGFloat(frame.width)*scale,height:CGFloat(frame.height)*scale)
        return CGRect(x:(bounds.width-size.width)/2,y:(bounds.height-size.height)/2,width:size.width,height:size.height)
    }
    override init(frame: CGRect){super.init(frame:frame);backgroundColor = .black;isMultipleTouchEnabled=false;accessibilityLabel="Imagem do treino; selecione e ajuste os cantos no modo de área";pan.delegate=self;addGestureRecognizer(pan);addGestureRecognizer(tap);tap.require(toFail:pan)}
    required init?(coder: NSCoder){fatalError("init(coder:) has not been implemented")}
    override func didMoveToSuperview(){super.didMoveToSuperview();attachScrollGesture()}
    override func didMoveToWindow(){super.didMoveToWindow();attachScrollGesture()}
    private func attachScrollGesture(){var parent=superview;while let p=parent{if let scroll=p as? UIScrollView{scroll.panGestureRecognizer.require(toFail:pan);break};parent=p.superview}}
    override func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer)->Bool{
        if recognizer === pan{let p=pan.location(in:self),d=pan.translation(in:self);return editing>0 && point(CGPoint(x:p.x-d.x,y:p.y-d.y)) != nil};return true
    }
    func refreshGestures(){pan.isEnabled=editing>0;if editing==0{touchPoint=nil;movingCorner=nil;anchor=nil}}
    private func screen(_ p: VisionPoint) -> CGPoint{let r=imageRect;return CGPoint(x:r.minX+CGFloat(p.x)*r.width,y:r.minY+CGFloat(p.y)*r.height)}
    private func point(_ p: CGPoint) -> VisionPoint?{let r=imageRect;guard r.width>0,r.height>0,r.contains(p) else{return nil};return VisionPoint(Double((p.x-r.minX)/r.width),Double((p.y-r.minY)/r.height))}
    private func line(_ points: [VisionPoint],closed: Bool,color: UIColor,width: CGFloat=2){
        guard let first=points.first else{return};let path=UIBezierPath();path.move(to:screen(first));points.dropFirst().forEach{path.addLine(to:screen($0))};if closed{path.close()};color.setStroke();path.lineWidth=width;path.stroke()
    }
    private func label(_ text: String,at p: CGPoint,color: UIColor){
        let attributes: [NSAttributedString.Key:Any]=[.font:UIFont.monospacedSystemFont(ofSize:11,weight:.semibold),.foregroundColor:color]
        let size=(text as NSString).size(withAttributes:attributes),r=imageRect
        let x=max(r.minX+4,min(p.x,r.maxX-size.width-8)),y=max(r.minY+4,min(p.y,r.maxY-size.height-8))
        let rect=CGRect(x:x-3,y:y-2,width:size.width+6,height:size.height+4)
        UIColor(white:0.04,alpha:0.9).setFill();UIBezierPath(roundedRect:rect,cornerRadius:4).fill()
        (text as NSString).draw(at:CGPoint(x:x,y:y),withAttributes:attributes)
    }
    override func draw(_ rect: CGRect){
        guard let f=frameValue else{return};UIImage(cgImage:f.image).draw(in:imageRect)
        let cyan=UIColor.systemCyan,lime=VisionCall.inside.color
        let area=result?.area ?? f.result.area
        line(area.polygon,closed:true,color:cyan)
        for guide in VisionGeometry.guides(area.polygon,court:area.court){line(guide,closed:false,color:cyan.withAlphaComponent(0.6),width:1)}
        line(area.floorLine,closed:false,color:.systemOrange)
        if editing>0 {
            line(draft,closed:editing != 2 && draft.count==4,color:lime,width:3)
            for (i,p) in draft.enumerated(){let c=screen(p);lime.setFill();UIBezierPath(ovalIn:CGRect(x:c.x-7,y:c.y-7,width:14,height:14)).fill();label("\(i+1)",at:CGPoint(x:c.x+11,y:c.y-18),color:lime)}
            if let p=touchPoint{magnifier(p,image:f.image)}
            return
        }
        if confirmed && config.marks{for event in events.suffix(24) where event.decision != .rejected{
            let c=screen(VisionGeometry.impactPoint(event,offset:result?.cameraOffset ?? f.result.cameraOffset)),color=event.decision.color
            color.setStroke();let path=UIBezierPath(ovalIn:CGRect(x:c.x-7,y:c.y-7,width:14,height:14));path.lineWidth=2;path.stroke()
            label(event.decision.label+(event.speed.map{" · ≈\(Int($0)) km/h"} ?? ""),at:CGPoint(x:c.x+11,y:c.y-16),color:color)
        }}
        guard let result,ProcessInfo.processInfo.systemUptime-f.startedAt<0.5 else{return}
        line(result.trail.map{VisionPoint($0.x/Double(f.width),$0.y/Double(f.height))},closed:false,color:lime)
        if let ball=result.observed {
            let p=screen(VisionPoint(ball.x/Double(f.width),ball.y/Double(f.height))),radius=max(7,CGFloat(ball.radius/Double(f.width))*imageRect.width),color=ball.resolved ? lime:.systemOrange
            color.setStroke();let box=UIBezierPath(roundedRect:CGRect(x:p.x-radius,y:p.y-radius,width:radius*2,height:radius*2),cornerRadius:4);box.lineWidth=2;box.stroke()
            let text=ball.occluded ? "BOLA PARCIAL":!ball.resolved ? "SINAL INCERTO":result.speed.map{"≈\(Int($0)) km/h lateral"} ?? "BOLA"
            label(text,at:CGPoint(x:p.x+radius+5,y:p.y),color:color)
        }
        if let p=result.predicted{label("PREVISÃO",at:screen(VisionPoint(p.x/Double(f.width),p.y/Double(f.height))),color:.systemOrange)}
    }
    private func magnifier(_ p: VisionPoint,image: CGImage){
        guard let context=UIGraphicsGetCurrentContext() else{return}
        let size:CGFloat=108,r=imageRect
        let lens=CGRect(x:r.maxX-size-10,y:r.minY+10,width:size,height:size)
        context.saveGState();UIBezierPath(ovalIn:lens).addClip();UIColor.black.setFill();UIBezierPath(rect:lens).fill()
        let zoom:CGFloat=3,scale=r.width/CGFloat(image.width)*zoom
        UIImage(cgImage:image).draw(in:CGRect(x:lens.midX-CGFloat(p.x)*CGFloat(image.width)*scale,y:lens.midY-CGFloat(p.y)*CGFloat(image.height)*scale,width:CGFloat(image.width)*scale,height:CGFloat(image.height)*scale))
        UIColor.systemCyan.setStroke();let cross=UIBezierPath();cross.move(to:CGPoint(x:lens.midX-12,y:lens.midY));cross.addLine(to:CGPoint(x:lens.midX+12,y:lens.midY));cross.move(to:CGPoint(x:lens.midX,y:lens.midY-12));cross.addLine(to:CGPoint(x:lens.midX,y:lens.midY+12));cross.lineWidth=1.5;cross.stroke();context.restoreGState()
        VisionCall.inside.color.setStroke();let outline=UIBezierPath(ovalIn:lens);outline.lineWidth=2;outline.stroke()
    }
    @objc private func handleTap(_ gesture: UITapGestureRecognizer){
        let location=gesture.location(in:self);guard let p=point(location) else{return}
        if editing==0 {
            guard confirmed,config.marks,let f=frameValue else{return}
            let candidates=events.filter{$0.decision != .rejected}
            if let event=candidates.min(by:{a,b in
                let x=screen(VisionGeometry.impactPoint(a,offset:f.result.cameraOffset)),y=screen(VisionGeometry.impactPoint(b,offset:f.result.cameraOffset))
                return hypot(x.x-location.x,x.y-location.y)<hypot(y.x-location.x,y.y-location.y)
            }){let q=screen(VisionGeometry.impactPoint(event,offset:f.result.cameraOffset));if hypot(q.x-location.x,q.y-location.y)<=32{onImpact?(event.id)}}
        }else if editing != 1 && draft.count<(editing==2 ? 2:4){draft.append(p);onDraft?(draft);setNeedsDisplay()}
    }
    private func beginTouch(_ location: CGPoint){
        guard editing>0,let p=point(location) else{return};touchPoint=p
        movingCorner=draft.indices.min{hypot(screen(draft[$0]).x-location.x,screen(draft[$0]).y-location.y)<hypot(screen(draft[$1]).x-location.x,screen(draft[$1]).y-location.y)}
        if let i=movingCorner,hypot(screen(draft[i]).x-location.x,screen(draft[i]).y-location.y)>30{movingCorner=nil}
        if movingCorner==nil {
            if editing==1{anchor=p;draft=[p,p,p,p]}
            else if draft.count<(editing==2 ? 2:4){draft.append(p);movingCorner=draft.count-1}
        }
        onDraft?(draft);setNeedsDisplay()
    }
    @objc private func handlePan(_ gesture: UIPanGestureRecognizer){
        let location=gesture.location(in:self)
        if gesture.state == .began{let d=gesture.translation(in:self);beginTouch(CGPoint(x:location.x-d.x,y:location.y-d.y))}
        if [.began,.changed,.ended].contains(gesture.state){updateTouch(location)}
        if [.ended,.cancelled,.failed].contains(gesture.state){anchor=nil;movingCorner=nil;touchPoint=nil;setNeedsDisplay()}
    }
    private func updateTouch(_ location: CGPoint){
        let r=imageRect
        guard editing>0,r.width>0,r.height>0,anchor != nil || movingCorner != nil else{return}
        let p=VisionPoint(max(0,min(1,Double((location.x-r.minX)/r.width))),max(0,min(1,Double((location.y-r.minY)/r.height))))
        touchPoint=p
        if let a=anchor{draft=[VisionPoint(min(a.x,p.x),min(a.y,p.y)),VisionPoint(max(a.x,p.x),min(a.y,p.y)),VisionPoint(max(a.x,p.x),max(a.y,p.y)),VisionPoint(min(a.x,p.x),max(a.y,p.y))]}
        else if let i=movingCorner,draft.indices.contains(i){draft[i]=p}
        onDraft?(draft);setNeedsDisplay()
    }
}
struct LocalCourtCanvas: UIViewRepresentable {
    @ObservedObject var model: LocalTrainingModel
    func makeUIView(context: Context) -> LocalCourtSurface{LocalCourtSurface()}
    func updateUIView(_ view: LocalCourtSurface,context: Context){
        view.frameValue=model.visibleFrame;view.result=model.visibleResult;view.config=model.config;view.events=model.session.events
        view.confirmed=model.confirmed;view.editing=model.editing;view.draft=model.draft;view.onDraft={model.draft=$0}
        view.onImpact={id in if let hit=model.session.events.first(where:{$0.id==id}){model.review(hit)}}
        view.refreshGestures();view.setNeedsDisplay()
    }
}

struct LocalTrainingView: View {
    @ObservedObject var model: LocalTrainingModel
    var openStudio: ()->Void
    @Environment(\.scenePhase) private var phase
    @State private var settings=false
    @State private var history=false
    @State private var areaMenu=false
    @State private var framing=false
    @State private var showCaptureAfterFraming=false
    @State private var updates=false
    @State private var fullScreen=false
    private var ratio: CGFloat{guard let f=model.visibleFrame else{return 16/9};return CGFloat(f.width)/CGFloat(f.height)}
    var body: some View {
        ScrollView{
            VStack(alignment:.leading,spacing:20){
                HStack{
                    Image(systemName:"tennisball.fill").font(.system(size:32)).foregroundStyle(Palette.lime)
                    VStack(alignment:.leading,spacing:3){Text("TENNIS VISION").font(.headline).tracking(1.2);Text("TREINO NO IPHONE · \(model.appVersion)").font(.caption).foregroundStyle(Palette.muted)}
                    Spacer();Button{model.pause();settings=true}label:{Image(systemName:"slider.horizontal.3").font(.title3).padding(12)}.disabled(model.editing>0).accessibilityIdentifier("local.settings")
                }
                if let version=model.newVersion{Button{updates=true}label:{Label("Atualização \(version) disponível",systemImage:"arrow.down.circle.fill").font(.subheadline.weight(.semibold)).frame(maxWidth:.infinity,alignment:.leading).padding(16)}.buttonStyle(.plain).background(Palette.panel,in:RoundedRectangle(cornerRadius:16)).accessibilityIdentifier("local.update.available")}
                VStack(alignment:.leading,spacing:8){
                    Text("Cada bola.\nNo seu campo.").font(.system(size:34,weight:.semibold,design:.rounded))
                    Text("Bola verde, impactos e IN/OUT no próprio iPhone.").font(.subheadline).foregroundStyle(Palette.muted)
                }
                VStack(alignment:.leading,spacing:12){
                    HStack{Label(model.active ? "TREINO ATIVO":model.editing>0 ? "EDITANDO ÁREA":"CÂMERA LOCAL",systemImage:"camera.fill").font(.caption.weight(.semibold));Spacer();Button{fullScreen=true}label:{Image(systemName:"arrow.up.left.and.arrow.down.right")}.accessibilityLabel("Ampliar imagem e editor")}
                    ZStack{
                        LocalCourtCanvas(model:model).aspectRatio(max(ratio,0.85),contentMode:.fit).clipShape(RoundedRectangle(cornerRadius:16))
                        if model.visibleFrame==nil{VStack(spacing:12){Image(systemName:"camera").font(.largeTitle);Text(model.denied ? "Permita a câmera":model.cameraUnavailable ? "Câmera indisponível":"Preparando sua câmera");if model.denied{Button("Abrir Ajustes"){UIApplication.shared.open(URL(string:UIApplication.openSettingsURLString)!)}}}.padding(20)}
                    }.background(.black,in:RoundedRectangle(cornerRadius:16))
                    Text(model.status).font(.subheadline.weight(.medium)).foregroundStyle(model.active ? Palette.lime:Palette.muted).accessibilityIdentifier("local.status")
                    Text(model.areaSummary).font(.footnote).foregroundStyle(Palette.muted)
                    if model.editing>0 {
                        Text(model.editing==1 ? "Arraste para expandir; depois ajuste os cantos.":"\(model.draft.count) / \(model.editing==2 ? 2:4) pontos · arraste para ajustar").font(.footnote).foregroundStyle(Palette.muted)
                        HStack{Button("Salvar área"){model.saveArea()}.buttonStyle(.borderedProminent).foregroundStyle(Palette.background).disabled(!model.canSaveArea).accessibilityIdentifier("local.area.save");Button("Recomeçar"){model.redraw()}.buttonStyle(.bordered);Button("Cancelar"){model.cancelEdit()}.buttonStyle(.bordered)}
                    }else{
                        HStack{Button{model.pause();areaMenu=true}label:{Label("Marcar área",systemImage:"viewfinder")}.buttonStyle(.bordered).disabled(!model.ready).accessibilityIdentifier("local.area");Button{history=true;model.history=model.store.history()}label:{Label("Histórico",systemImage:"clock")}.buttonStyle(.bordered);Spacer()}
                        HStack{Button("Enquadramento"){model.pause();framing=true}.buttonStyle(.bordered);Button("Tela cheia"){fullScreen=true}.buttonStyle(.bordered);Button{updates=true}label:{Image(systemName:"arrow.down.circle")}.buttonStyle(.bordered).accessibilityLabel("Atualizações")}
                    }
                    Text(model.metrics).font(.system(size:11,design:.monospaced)).foregroundStyle(Palette.muted).textSelection(.enabled)
                }.padding(16).background(Palette.panel,in:RoundedRectangle(cornerRadius:22))
                HStack(spacing:10){stat("IN",model.insideCount,Palette.lime);stat("OUT",model.outsideCount,.red);stat("REVISAR",model.reviewCount,.orange)}
                HStack{Text("Acerto nas decisões válidas").foregroundStyle(Palette.muted);Spacer();Text(model.accuracy).font(.title3.monospacedDigit().weight(.semibold))}.font(.footnote)
                Text("\(model.totalContacts) contatos estimados · toque numa marca para revisar").font(.footnote).foregroundStyle(Palette.muted)
                if let last=model.session.events.last{Button{model.review(last)}label:{HStack{VStack(alignment:.leading,spacing:5){Text("Último contato · \(last.decision.label)").font(.headline);Text(last.speed.map{"≈\(Int($0)) km/h lateral"} ?? "Velocidade indisponível").font(.footnote).foregroundStyle(Palette.muted)};Spacer();Image(systemName:"arrow.up.right")}.padding(18)}.buttonStyle(.plain).background(Palette.panel,in:RoundedRectangle(cornerRadius:18)).accessibilityIdentifier("local.review")}
                if !VisionGeometry.valid(model.config.polygon){Text("A busca cobre a imagem inteira. Marque quatro cantos para classificar a área IN/OUT. A área sozinha não comprova contato com o chão.").font(.footnote).foregroundStyle(Palette.muted)}
                Text("Cor fraca, bola minúscula, oclusão e contato próximo à linha exigem revisão. Velocidade é lateral e estimada; não é radar.").font(.footnote).foregroundStyle(Palette.muted)
            }.padding(20).frame(maxWidth:900).frame(maxWidth:.infinity)
        }.background(Palette.background).preferredColorScheme(.dark).tint(Palette.lime)
        .safeAreaInset(edge:.bottom){
            Button{model.ready ? model.begin():model.prepare()}label:{Label(model.active ? "Pausar treino":model.ready ? "Iniciar treino":"Reabrir câmera",systemImage:model.active ? "pause.fill":"play.fill").font(.headline).frame(maxWidth:.infinity).padding(.vertical,17)}
                .buttonStyle(.borderedProminent).tint(Palette.lime).foregroundStyle(Palette.background).disabled(model.denied || model.editing>0 || model.testing).padding(16).background(Palette.background).accessibilityIdentifier("local.start")
        }
        .task{model.prepare();await model.checkUpdate()}
        .onChange(of:phase){_,phase in if phase == .active{model.prepare()}else if phase == .background{model.suspend()}}
        .confirmationDialog("Área da superfície",isPresented:$areaMenu,titleVisibility:.visible){
            Button("Quadra inteira · 4 cantos"){model.edit(3,court:.full)}
            Button("Meia quadra · 4 cantos"){model.edit(3,court:.half)}
            Button("Área livre · arrastar"){model.edit(1,court:.custom)}
            Button("Ajustar área salva"){model.edit(3)}
            Button("Quarto · limite do piso"){model.edit(2)}
        }message:{Text("Quadra: marque os cantos ao redor do piso. Meia quadra começa na rede. O limite do quarto é uma reta separada, usada só no quarto.")}
        .alert("Confira a área salva",isPresented:Binding(get:{!fullScreen && model.confirmArea},set:{model.confirmArea=$0})){Button("Coincide · iniciar"){model.begin(confirmed:true)};Button("Ajustar cantos"){model.edit(3)};Button("Cancelar",role:.cancel){}}message:{Text("Os quatro cantos ainda coincidem com o chão? Mover ou girar o iPhone exige ajustar a área.")}
        .alert("Atenção",isPresented:Binding(get:{!fullScreen && model.error != nil},set:{if !$0{model.error=nil}})){Button("Entendi"){model.error=nil}}message:{Text(model.error ?? "")}
        .sheet(isPresented:$settings){settingsView}
        .sheet(isPresented:$history){historyView}
        .sheet(isPresented:$framing,onDismiss:{if showCaptureAfterFraming{showCaptureAfterFraming=false;settings=true}}){framingView}
        .sheet(isPresented:$updates){updatesView}
        .fullScreenCover(isPresented:$fullScreen){fullScreenView}
        .sheet(item:Binding(get:{fullScreen ? nil:model.reviewing},set:{model.reviewing=$0})){event in reviewView(event)}
        .sheet(isPresented:Binding(get:{model.diagnosticReport != nil},set:{if !$0{model.diagnosticReport=nil;model.prepare()}})){diagnosticView}
    }
    private func stat(_ name: String,_ value: Int,_ color: Color) -> some View{VStack(alignment:.leading,spacing:8){Text(name).font(.caption.weight(.semibold)).foregroundStyle(color);Text("\(value)").font(.system(size:30,weight:.medium,design:.rounded)).monospacedDigit()}.frame(maxWidth:.infinity,alignment:.leading).padding(16).background(Palette.panel,in:RoundedRectangle(cornerRadius:16))}
    private var settingsView: some View{
        NavigationStack{Form{
            Section("Captura e distância"){
                Picker("Detalhe",selection:Binding(get:{model.detail},set:{model.changeDetail($0)})){ForEach(LocalDetail.allCases,id:\.self){Text($0.title).tag($0)}}
                Picker("Lente traseira",selection:Binding(get:{model.lensID},set:{model.changeLens($0)})){Text("Principal automática").tag("");ForEach(LocalCamera.lenses()){Text($0.name).tag($0.id)}}
                Picker("Taxa preferida",selection:Binding(get:{model.detail == .economy ? 30:model.rate},set:{model.changeRate($0)})){
                    Text("Até 60 FPS · estável").tag(60);Text("Maior taxa compatível").tag(0)
                    ForEach(LocalCamera.rates(detail:model.detail,lensID:model.lensID).filter{$0 != 60},id:\.self){Text("\($0) FPS").tag($0)}
                    if [120,240].contains(model.rate) && !LocalCamera.rates(detail:model.detail,lensID:model.lensID).contains(model.rate){Text("\(model.rate) FPS · indisponível nesta lente").tag(model.rate)}
                }.disabled(model.detail == .economy)
                Text("120/240 só aparecem se anunciados pela lente/resolução. Taxa escolhida não garante análise nessa taxa: confira FPS recebidos e tempos. Se um modo falhar, escolha outra taxa; a escolha manual não é trocada silenciosamente. Para longe, teste 1080p, boa luz e meia quadra. Teleobjetiva amplia a bola e reduz o campo.").font(.footnote)
            }
            Section("Impactos"){
                Picker("Superfície",selection:Binding(get:{model.config.surface},set:{value in var s=model.config;s.surface=value;if value == .wall{s.court = .custom};model.persist(s)})){Text("Chão · quicadas").tag(VisionSurface.floor);Text("Parede · revisão").tag(VisionSurface.wall);Text("Somente rastrear").tag(VisionSurface.track)}
                Toggle("Mostrar marcas de impacto",isOn:Binding(get:{model.config.marks},set:{var s=model.config;s.marks=$0;model.persist(s)}))
                Toggle("Som OUT",isOn:Binding(get:{model.config.sound},set:{var s=model.config;s.sound=$0;model.persist(s)}))
                Toggle("Som IN · opcional",isOn:Binding(get:{model.inVoice},set:{model.setInVoice($0)}))
                Button("Testar som OUT"){model.playOut()}.accessibilityIdentifier("local.sound.test")
                Button("Testar som IN"){model.playIn()}.accessibilityIdentifier("local.sound.in.test")
                Text(model.audioStatus).font(.footnote).accessibilityIdentifier("local.sound.status")
                Text("Voz Daniel · ElevenLabs. Chamadas automáticas só ocorrem com uma decisão IN/OUT; REVISAR permanece silencioso. OUT tem prioridade. Se não ouvir, confira o volume de mídia e a saída Bluetooth. O teste não altera as contagens.").font(.footnote)
            }
            Section("Sessão e testes"){
                Button("Começar nova sessão"){model.newSession()}
                Button("Preparar exportação JSON"){model.exportSession()}
                if let url=model.exportURL{ShareLink("Compartilhar sessão",item:url)}
                Button(model.testing ? "Executando testes…":"Testes do detector · imagens simuladas"){settings=false;model.diagnostics()}.disabled(model.testing).accessibilityIdentifier("local.diagnostics")
                Text("Os testes usam imagens com posições conhecidas. São separados do treino e não comprovam precisão em partidas reais.").font(.footnote)
            }
            Section("Aplicativo"){
                Text("iOS \(model.appVersion) · recursos mobile \(model.appVersion)")
                Text(model.updateMessage).font(.footnote)
                Button("Buscar atualização"){Task{await model.checkUpdate()}}.disabled(model.checkingUpdate)
                if model.newVersion != nil{Button("Instalar atualização"){model.installUpdate()}}
                Button("Abrir fonte no AltStore"){UIApplication.shared.open(UpdateSource.addURL)}
                Button("Câmera e painel do Windows · opcional"){settings=false;model.suspend(completion:openStudio)}
            }
        }.navigationTitle("Seu treino").toolbar{ToolbarItem(placement:.confirmationAction){Button("Concluir"){settings=false}}}.preferredColorScheme(.dark).tint(Palette.lime)}
    }
    private var historyView: some View{
        NavigationStack{List{
            if model.history.isEmpty{Text("As sessões salvas aparecerão aqui.")}
            ForEach(model.history){session in VStack(alignment:.leading,spacing:6){Text(session.started,style:.date).font(.headline);Text("\(session.events.filter{$0.decision == .inside}.count) IN · \(session.events.filter{$0.decision == .outside}.count) OUT · \(session.events.count) registros").font(.subheadline);Text(session.id).font(.caption.monospaced()).foregroundStyle(.secondary)}}
        }.navigationTitle("Histórico no iPhone").toolbar{Button("Fechar"){history=false}}.preferredColorScheme(.dark)}
    }
    private var framingView: some View{
        NavigationStack{ScrollView{VStack(alignment:.leading,spacing:20){
            Text("A quadra no seu campo de visão").font(.title2.weight(.semibold))
            Text("Fixe o celular na horizontal, recuado e elevado fora da área de jogo. Inclua os quatro cantos, o chão e margem além das linhas para bolas OUT.")
            Text("Se não couberem os dois lados, mude a posição ou experimente a lente Ampla. Ela mostra mais campo e deixa a bola menor. Meia quadra costuma preservar mais detalhe.").foregroundStyle(Palette.muted)
            Label(model.frame?.lens ?? "Lente aguardando câmera",systemImage:"camera.aperture").font(.headline)
            Button("Escolher lente e taxa"){showCaptureAfterFraming=true;framing=false}.buttonStyle(.bordered)
            Text("Melhorar bola distante").font(.headline)
            Text("Use 1080p, boa luz e meia quadra. Confira o tamanho em pixels e os FPS recebidos. A lente de Detalhe amplia a bola, mas precisa manter chão e margem para OUT. Aumentar o contorno não recupera detalhe perdido.").foregroundStyle(Palette.muted)
            Button("Usar 1080p"){model.changeDetail(.detail);framing=false}.buttonStyle(.borderedProminent).foregroundStyle(Palette.background)
            Text("Após trocar lente, resolução ou posição, confira a área correspondente. Uma câmera não garante cobertura dos dois lados nem precisão em saques a 200 km/h.").font(.footnote).foregroundStyle(Palette.muted)
        }.padding(24)}.background(Palette.background).navigationTitle("Enquadramento").toolbar{Button("Fechar"){framing=false}}.preferredColorScheme(.dark).tint(Palette.lime)}
    }
    private var updatesView: some View{
        NavigationStack{Form{
            Section("Versão de funcionalidades"){
                Text("Tennis Vision mobile \(model.appVersion)").font(.headline)
                Text("Esta entrega acompanha os recursos do Android \(model.appVersion): treino local, bola verde, áreas, revisão, som e histórico.").font(.footnote)
            }
            Section("Atualização pelo GitHub"){
                Text(model.updateMessage.isEmpty ? "Consultando fonte oficial…":model.updateMessage)
                Button(model.checkingUpdate ? "Consultando…":"Buscar atualização"){Task{await model.checkUpdate()}}.disabled(model.checkingUpdate)
                Button("Instalar atualização pelo AltStore"){model.installUpdate()}.disabled(model.newVersion==nil)
                Button("Abrir fonte no AltStore"){UIApplication.shared.open(UpdateSource.addURL)}
                if let url=model.updateDownloadURL{Link("Baixar IPA oficial pelo GitHub",destination:url)}
                Button("Copiar endereço da fonte"){UIPasteboard.general.string=UpdateSource.url.absoluteString}
                Text("No AltStore, use My Apps → Update. Se o app antigo aparece como Sideloaded, abra Tennis Vision na fonte ou selecione o IPA pelo botão +. Instale sobre o aplicativo existente.").font(.footnote)
            }
            Section("Como funciona no iPhone"){
                Text("O app verifica a fonte oficial. A instalação e a renovação da assinatura são feitas pelo AltStore e exigem sua confirmação; não há substituição silenciosa do aplicativo. O AltServer pode ser necessário para assinar. O treino local continua sem PC ou internet.").font(.footnote)
            }
        }.navigationTitle("Atualizações").toolbar{Button("Fechar"){updates=false}}.task{await model.checkUpdate()}.preferredColorScheme(.dark).tint(Palette.lime)}
    }
    private var fullScreenView: some View{
        VStack(spacing:12){
            HStack{Text(model.editing>0 ? "Ajustar área":"Tennis Vision").font(.headline);Spacer();Button("Fechar"){fullScreen=false}}.padding(.horizontal,20)
            LocalCourtCanvas(model:model).frame(maxWidth:.infinity,maxHeight:.infinity).background(.black)
            Text(model.status).font(.footnote).foregroundStyle(Palette.muted).padding(.horizontal,16)
            if model.editing>0{
                HStack{Button("Salvar área"){model.saveArea()}.buttonStyle(.borderedProminent).foregroundStyle(Palette.background).disabled(!model.canSaveArea);Button("Recomeçar"){model.redraw()}.buttonStyle(.bordered);Button("Cancelar"){model.cancelEdit()}.buttonStyle(.bordered)}
            }else{
                HStack{
                    Menu("Marcar área"){
                        Button("Quadra inteira"){model.edit(3,court:.full)};Button("Meia quadra"){model.edit(3,court:.half)};Button("Área livre"){model.edit(1,court:.custom)};Button("Limite do piso"){model.edit(2)}
                    }.buttonStyle(.bordered).disabled(!model.ready)
                    Button(model.active ? "Pausar treino":"Iniciar treino"){model.begin()}.buttonStyle(.borderedProminent).foregroundStyle(Palette.background).disabled(!model.ready)
                }
            }
        }.padding(.vertical,12).background(Palette.background).tint(Palette.lime).preferredColorScheme(.dark)
        .sheet(item:$model.reviewing){event in reviewView(event)}
        .alert("Confira a área salva",isPresented:$model.confirmArea){Button("Coincide · iniciar"){model.begin(confirmed:true)};Button("Ajustar cantos"){model.edit(3)};Button("Cancelar",role:.cancel){}}message:{Text("Os cantos ainda coincidem com o piso? Ajuste se moveu o iPhone.")}
        .alert("Atenção",isPresented:Binding(get:{model.error != nil},set:{if !$0{model.error=nil}})){Button("Entendi"){model.error=nil}}message:{Text(model.error ?? "")}
    }
    private func reviewView(_ event: VisionImpact) -> some View{
        NavigationStack{ScrollView{VStack(alignment:.leading,spacing:18){
            Text(event.decision.label).font(.largeTitle.weight(.bold)).foregroundStyle(Color(event.decision.color));Text(event.reason)
            if let image=model.reviewImage{Image(uiImage:image).resizable().scaledToFit().overlay{
                GeometryReader{g in let p=VisionGeometry.impactPoint(event,offset:event.evidenceOffset)
                    Circle().stroke(Color(event.decision.color),lineWidth:3).frame(width:26,height:26).position(x:CGFloat(p.x)*g.size.width,y:CGFloat(p.y)*g.size.height)
                }
            }.clipShape(RoundedRectangle(cornerRadius:16))}else{Text("Evidência do quadro indisponível").foregroundStyle(.secondary)}
            Text(event.speed.map{"≈\(Int($0)) km/h lateral"} ?? "Velocidade indisponível").font(.headline)
            Text("A imagem é o quadro observado mais próximo do contato estimado. A decisão original é preservada no registro.").font(.footnote).foregroundStyle(.secondary)
            Button("Confirmar IN"){model.revise(.inside)}.buttonStyle(.borderedProminent)
            Button("Confirmar OUT"){model.revise(.outside)}.buttonStyle(.bordered)
            Button("Não foi impacto"){model.revise(.rejected)}.buttonStyle(.bordered)
        }.padding(20)}.navigationTitle("Contato #\(event.id)").toolbar{Button("Fechar"){model.reviewing=nil;model.reviewImage=nil}}.preferredColorScheme(.dark).tint(Palette.lime)}
    }
    private var diagnosticView: some View{
        NavigationStack{List{
            Text("IMAGENS SIMULADAS · NÃO É CÂMERA").font(.headline).foregroundStyle(.orange)
            if let report=model.diagnosticReport{
                Text(report.passed ? "Critérios sintéticos atendidos":"Há falhas para investigar").font(.title3)
                ForEach(report.results,id:\.name){r in VStack(alignment:.leading,spacing:5){Text(r.name).font(.headline);Text("\(r.located)/\(r.frames) localizações · \(r.wrong) posições erradas");Text("\(r.contacts.map{$0.label}.joined(separator:", ")) · análise total \(Int(r.elapsedMs)) ms").font(.footnote)}}
            }
            Text("O relatório foi salvo em vision-diagnostics.json. Esses resultados não comprovam arbitragem nem detecção de saques reais a 200 km/h.").font(.footnote)
        }.navigationTitle("Validação do detector").toolbar{Button("Voltar à câmera"){model.diagnosticReport=nil;model.prepare()}}.preferredColorScheme(.dark)}
    }
}
