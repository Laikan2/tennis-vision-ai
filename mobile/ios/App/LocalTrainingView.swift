import SwiftUI
import UIKit

extension VisionCall {
    var label: String{switch self{case .inside:return "IN";case .outside:return "OUT";case .review:return "REVISAR";case .unmarked:return "SEM ÁREA";case .rejected:return "DESCARTADO"}}
    var color: UIColor{switch self{case .inside:return UIColor(red:0.78,green:0.96,blue:0.31,alpha:1);case .outside:return .systemRed;default:return .systemOrange}}
}
final class LocalCourtSurface: UIView {
    var frameValue: LocalCameraFrame?,result: VisionResult?,config=VisionSettings(),events=[VisionImpact]()
    var confirmed=false,editing=0,draft=[VisionPoint](),onDraft: (([VisionPoint])->Void)?
    private var movingCorner: Int?,anchor: VisionPoint?
    private var imageRect: CGRect{
        guard let frame=frameValue else{return bounds}
        let scale=min(bounds.width/CGFloat(frame.width),bounds.height/CGFloat(frame.height))
        let size=CGSize(width:CGFloat(frame.width)*scale,height:CGFloat(frame.height)*scale)
        return CGRect(x:(bounds.width-size.width)/2,y:(bounds.height-size.height)/2,width:size.width,height:size.height)
    }
    override init(frame: CGRect){super.init(frame:frame);backgroundColor = .black;isMultipleTouchEnabled=false;accessibilityLabel="Imagem do treino; selecione e ajuste os cantos no modo de área"}
    required init?(coder: NSCoder){fatalError("init(coder:) has not been implemented")}
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
        let area=result?.area ?? config
        line(area.polygon,closed:true,color:cyan)
        for guide in VisionGeometry.guides(area.polygon,court:area.court){line(guide,closed:false,color:cyan.withAlphaComponent(0.6),width:1)}
        line(area.floorLine,closed:false,color:.systemOrange)
        if editing>0 {
            line(draft,closed:editing != 2 && draft.count==4,color:lime,width:3)
            for (i,p) in draft.enumerated(){let c=screen(p);lime.setFill();UIBezierPath(ovalIn:CGRect(x:c.x-7,y:c.y-7,width:14,height:14)).fill();label("\(i+1)",at:CGPoint(x:c.x+11,y:c.y-18),color:lime)}
            return
        }
        if confirmed && config.marks{for event in events.suffix(24) where event.decision != .rejected{
            let c=screen(VisionGeometry.impactPoint(event,offset:result?.cameraOffset ?? VisionPoint(0,0))),color=event.decision.color
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
    override func touchesBegan(_ touches: Set<UITouch>,with event: UIEvent?){
        guard editing>0,let touch=touches.first,let p=point(touch.location(in:self)) else{return}
        let location=touch.location(in:self)
        movingCorner=draft.indices.min{hypot(screen(draft[$0]).x-location.x,screen(draft[$0]).y-location.y)<hypot(screen(draft[$1]).x-location.x,screen(draft[$1]).y-location.y)}
        if let i=movingCorner,hypot(screen(draft[i]).x-location.x,screen(draft[i]).y-location.y)>30{movingCorner=nil}
        if movingCorner==nil {
            if editing==1{anchor=p;draft=[p,p,p,p]}
            else if draft.count<(editing==2 ? 2:4){draft.append(p);movingCorner=draft.count-1}
        }
        onDraft?(draft);setNeedsDisplay()
    }
    override func touchesMoved(_ touches: Set<UITouch>,with event: UIEvent?){updateTouch(touches)}
    override func touchesEnded(_ touches: Set<UITouch>,with event: UIEvent?){updateTouch(touches);anchor=nil;movingCorner=nil}
    override func touchesCancelled(_ touches: Set<UITouch>,with event: UIEvent?){anchor=nil;movingCorner=nil}
    private func updateTouch(_ touches: Set<UITouch>){
        guard editing>0,let touch=touches.first,let p=point(touch.location(in:self)) else{return}
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
        view.confirmed=model.confirmed;view.editing=model.editing;view.draft=model.draft;view.onDraft={model.draft=$0};view.setNeedsDisplay()
    }
}

struct LocalTrainingView: View {
    @ObservedObject var model: LocalTrainingModel
    var openStudio: ()->Void
    @Environment(\.scenePhase) private var phase
    @State private var settings=false
    @State private var history=false
    @State private var areaMenu=false
    private var ratio: CGFloat{guard let f=model.visibleFrame else{return 16/9};return CGFloat(f.width)/CGFloat(f.height)}
    var body: some View {
        ScrollView{
            VStack(alignment:.leading,spacing:20){
                HStack{
                    Image(systemName:"tennisball.fill").font(.system(size:32)).foregroundStyle(Palette.lime)
                    VStack(alignment:.leading,spacing:3){Text("TENNIS VISION").font(.headline).tracking(1.2);Text("TREINO NO IPHONE · \(model.appVersion)").font(.caption).foregroundStyle(Palette.muted)}
                    Spacer();Button{model.pause();settings=true}label:{Image(systemName:"slider.horizontal.3").font(.title3).padding(12)}.accessibilityIdentifier("local.settings")
                }
                VStack(alignment:.leading,spacing:8){
                    Text("Cada bola.\nNo seu campo.").font(.system(size:34,weight:.semibold,design:.rounded))
                    Text("Bola verde, impactos e IN/OUT no próprio iPhone.").font(.subheadline).foregroundStyle(Palette.muted)
                }
                VStack(alignment:.leading,spacing:12){
                    HStack{Label(model.active ? "TREINO ATIVO":model.editing>0 ? "EDITANDO ÁREA":"CÂMERA LOCAL",systemImage:"camera.fill").font(.caption.weight(.semibold));Spacer();Text("SEM COMPUTADOR").font(.system(size:10,weight:.semibold)).foregroundStyle(Palette.lime)}
                    ZStack{
                        LocalCourtCanvas(model:model).aspectRatio(ratio,contentMode:.fit).clipShape(RoundedRectangle(cornerRadius:16))
                        if model.visibleFrame==nil{VStack(spacing:12){Image(systemName:"camera").font(.largeTitle);Text(model.denied ? "Permita a câmera":"Preparando sua câmera");if model.denied{Button("Abrir Ajustes"){UIApplication.shared.open(URL(string:UIApplication.openSettingsURLString)!)}}}.padding(20)}
                    }.background(.black,in:RoundedRectangle(cornerRadius:16))
                    Text(model.status).font(.subheadline.weight(.medium)).foregroundStyle(model.active ? Palette.lime:Palette.muted).accessibilityIdentifier("local.status")
                    if model.editing>0 {
                        Text(model.editing==1 ? "Arraste para expandir; depois ajuste os cantos.":"\(model.draft.count) / \(model.editing==2 ? 2:4) pontos · arraste para ajustar").font(.footnote).foregroundStyle(Palette.muted)
                        HStack{Button("Salvar área"){model.saveArea()}.buttonStyle(.borderedProminent).foregroundStyle(Palette.background).disabled(!model.canSaveArea).accessibilityIdentifier("local.area.save");Button("Recomeçar"){model.redraw()}.buttonStyle(.bordered);Button("Cancelar"){model.cancelEdit()}.buttonStyle(.bordered)}
                    }else{
                        HStack{Button{model.pause();areaMenu=true}label:{Label("Marcar área",systemImage:"viewfinder")}.buttonStyle(.bordered).disabled(!model.ready).accessibilityIdentifier("local.area");Button{history=true;model.history=model.store.history()}label:{Label("Histórico",systemImage:"clock")}.buttonStyle(.bordered);Spacer()}
                    }
                    Text(model.metrics).font(.system(size:11,design:.monospaced)).foregroundStyle(Palette.muted).textSelection(.enabled)
                }.padding(16).background(Palette.panel,in:RoundedRectangle(cornerRadius:22))
                HStack(spacing:10){stat("IN",model.insideCount,Palette.lime);stat("OUT",model.outsideCount,.red);stat("REVISAR",model.reviewCount,.orange)}
                HStack{Text("Acerto nas decisões válidas").foregroundStyle(Palette.muted);Spacer();Text(model.accuracy).font(.title3.monospacedDigit().weight(.semibold))}.font(.footnote)
                if let last=model.session.events.last{Button{model.review(last)}label:{HStack{VStack(alignment:.leading,spacing:5){Text("Último contato · \(last.decision.label)").font(.headline);Text(last.speed.map{"≈\(Int($0)) km/h lateral"} ?? "Velocidade indisponível").font(.footnote).foregroundStyle(Palette.muted)};Spacer();Image(systemName:"arrow.up.right")}.padding(18)}.buttonStyle(.plain).background(Palette.panel,in:RoundedRectangle(cornerRadius:18)).accessibilityIdentifier("local.review")}
                if !VisionGeometry.valid(model.config.polygon){Text("A busca cobre a imagem inteira. Marque quatro cantos para classificar a área IN/OUT. A área sozinha não comprova contato com o chão.").font(.footnote).foregroundStyle(Palette.muted)}
                Text("Cor fraca, bola minúscula, oclusão e contato próximo à linha exigem revisão. Velocidade é lateral e estimada; não é radar.").font(.footnote).foregroundStyle(Palette.muted)
            }.padding(20).frame(maxWidth:900).frame(maxWidth:.infinity)
        }.background(Palette.background).preferredColorScheme(.dark).tint(Palette.lime)
        .safeAreaInset(edge:.bottom){
            Button{model.ready ? model.begin():model.prepare()}label:{Label(model.active ? "Pausar treino":model.ready ? "Iniciar treino":"Reabrir câmera",systemImage:model.active ? "pause.fill":"play.fill").font(.headline).frame(maxWidth:.infinity).padding(.vertical,17)}
                .buttonStyle(.borderedProminent).foregroundStyle(Palette.background).disabled(model.denied || model.editing>0 || model.testing).padding(16).background(Palette.background).accessibilityIdentifier("local.start")
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
        .alert("Confira a área salva",isPresented:$model.confirmArea){Button("Coincide · iniciar"){model.begin(confirmed:true)};Button("Ajustar cantos"){model.edit(3)};Button("Cancelar",role:.cancel){}}message:{Text("Os quatro cantos ainda coincidem com o chão? Mover ou girar o iPhone exige ajustar a área.")}
        .alert("Atenção",isPresented:Binding(get:{model.error != nil},set:{if !$0{model.error=nil}})){Button("Entendi"){model.error=nil}}message:{Text(model.error ?? "")}
        .sheet(isPresented:$settings){settingsView}
        .sheet(isPresented:$history){historyView}
        .sheet(item:$model.reviewing){event in reviewView(event)}
        .sheet(isPresented:Binding(get:{model.diagnosticReport != nil},set:{if !$0{model.diagnosticReport=nil;model.prepare()}})){diagnosticView}
    }
    private func stat(_ name: String,_ value: Int,_ color: Color) -> some View{VStack(alignment:.leading,spacing:8){Text(name).font(.caption.weight(.semibold)).foregroundStyle(color);Text("\(value)").font(.system(size:30,weight:.medium,design:.rounded)).monospacedDigit()}.frame(maxWidth:.infinity,alignment:.leading).padding(16).background(Palette.panel,in:RoundedRectangle(cornerRadius:16))}
    private var settingsView: some View{
        NavigationStack{Form{
            Section("Captura e distância"){
                Picker("Detalhe",selection:Binding(get:{model.detail},set:{model.changeDetail($0)})){ForEach(LocalDetail.allCases,id:\.self){Text($0.title).tag($0)}}
                Picker("Lente traseira",selection:Binding(get:{model.lensID},set:{model.changeLens($0)})){Text("Principal automática").tag("");ForEach(LocalCamera.lenses()){Text($0.name).tag($0.id)}}
                Text("Para longe, teste 1080p, boa luz e meia quadra. Teleobjetiva amplia a bola, mas reduz o campo. Mantenha o chão e margem além das linhas na imagem. Confira os FPS recebidos. A análise local usa perfis regulares de até 60 FPS; a transmissão opcional mantém os formatos 120/240 anunciados pela câmera.").font(.footnote)
            }
            Section("Impactos"){
                Picker("Superfície",selection:Binding(get:{model.config.surface},set:{value in var s=model.config;s.surface=value;if value == .wall{s.court = .custom};model.persist(s)})){Text("Chão · quicadas").tag(VisionSurface.floor);Text("Parede · revisão").tag(VisionSurface.wall);Text("Somente rastrear").tag(VisionSurface.track)}
                Toggle("Mostrar marcas de impacto",isOn:Binding(get:{model.config.marks},set:{var s=model.config;s.marks=$0;model.persist(s)}))
                Toggle("Som OUT",isOn:Binding(get:{model.config.sound},set:{var s=model.config;s.sound=$0;model.persist(s)}))
                Button("Testar som OUT"){model.playOut()}.accessibilityIdentifier("local.sound.test")
                Text("OUT automático só ocorre quando há evidência suficiente. Casos incertos ficam em REVISAR e não disparam o som.").font(.footnote)
            }
            Section("Sessão e testes"){
                Button("Começar nova sessão"){model.newSession()}
                Button("Preparar exportação JSON"){model.exportSession()}
                if let url=model.exportURL{ShareLink("Compartilhar sessão",item:url)}
                Button(model.testing ? "Executando testes…":"Testes do detector · imagens simuladas"){settings=false;model.diagnostics()}.disabled(model.testing).accessibilityIdentifier("local.diagnostics")
                Text("Os testes usam imagens com posições conhecidas. São separados do treino e não comprovam precisão em partidas reais.").font(.footnote)
            }
            Section("Aplicativo"){
                Text("iOS \(model.appVersion) · treino local sem Windows ou internet")
                Text(model.updateMessage).font(.footnote)
                Button("Buscar atualização"){Task{await model.checkUpdate()}}.disabled(model.checkingUpdate)
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
    private func reviewView(_ event: VisionImpact) -> some View{
        NavigationStack{ScrollView{VStack(alignment:.leading,spacing:18){
            Text(event.decision.label).font(.largeTitle.weight(.bold)).foregroundStyle(Color(event.decision.color));Text(event.reason)
            if let image=model.reviewImage{Image(uiImage:image).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius:16))}else{Text("Evidência do quadro indisponível").foregroundStyle(.secondary)}
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
