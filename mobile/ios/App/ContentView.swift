import SwiftUI
import UIKit

enum Palette {
    static let background=Color(red:0.035,green:0.055,blue:0.065)
    static let panel=Color(red:0.075,green:0.105,blue:0.12)
    static let line=Color.white.opacity(0.13)
    static let lime=Color(red:0.78,green:0.96,blue:0.31)
    static let muted=Color(red:0.62,green:0.69,blue:0.72)
}
struct ContentView: View {
    @ObservedObject var model: CameraViewModel
    @State private var tab=0
    @Environment(\.scenePhase) private var phase
    var body: some View {
        Group {
            if model.page == 1 { DashboardView(model:model) }
            else {
                GeometryReader { geometry in
                    ScrollView {
                        VStack(alignment:.leading,spacing:24) {
                            header
                            if model.page == 0 {
                                VStack(alignment:.leading,spacing:8) {
                                    Text("MOVIMENTO EM FOCO").font(.system(size:11,weight:.semibold)).tracking(2).foregroundStyle(Palette.lime)
                                    Text("Cada bola.\nCada detalhe.").font(.system(size:36,weight:.semibold,design:.rounded)).fixedSize(horizontal:false,vertical:true)
                                    Text("Capture no iPhone. Acompanhe o treino aqui e no Windows.")
                                        .font(.subheadline).foregroundStyle(Palette.muted)
                                }
                                preview
                                HStack(spacing:14) {
                                    Image(systemName:"bolt.shield.fill").font(.title2).foregroundStyle(Palette.lime)
                                    VStack(alignment:.leading,spacing:5) {
                                        Text(model.automatic ? "Melhor perfil automÃ¡tico" : "Perfil escolhido por vocÃª").font(.subheadline.weight(.semibold))
                                        Text(model.deviceLabel + " Â· " + (model.snapshot.activeProfile?.label ?? "Verificando cÃ¢mera"))
                                            .font(.footnote).foregroundStyle(Palette.muted)
                                    }
                                    Spacer()
                                    Button("Ajustar") { tab=1; model.page=2 }.font(.subheadline.weight(.semibold))
                                }.padding(18).background(Palette.panel,in:RoundedRectangle(cornerRadius:18))
                                if let adaptation=model.snapshot.adaptation { notice(adaptation,icon:"arrow.triangle.2.circlepath") }
                                if let error=model.snapshot.error { notice(error,icon:"exclamationmark.triangle") }
                                if let message=model.message { notice(message,icon:"info.circle") }
                                Button { model.page = model.panelConfiguration == nil ? 2 : 1 } label: {
                                    HStack { Label(model.panelConfiguration == nil ? "Parear com seu computador" : "Abrir Ã¡reas, impactos e IN/OUT",systemImage:model.panelConfiguration == nil ? "qrcode" : "scope"); Spacer(); Image(systemName:"arrow.up.right") }
                                        .font(.subheadline.weight(.medium)).padding(18).frame(maxWidth:.infinity)
                                }.buttonStyle(.plain).background(Palette.panel,in:RoundedRectangle(cornerRadius:18))
                            } else if model.page == 2 { controls }
                            else { updates }
                        }.padding(geometry.size.width > 850 ? 28 : 20).frame(maxWidth:900).frame(maxWidth:.infinity)
                    }.scrollDismissesKeyboard(.interactively)
                }
            }
        }.background(Palette.background).preferredColorScheme(.dark).tint(Palette.lime)
        .safeAreaInset(edge:.bottom,spacing:0) {
            VStack(spacing:0) {
                if model.page == 0 { primaryAction.padding(.horizontal,20).padding(.top,12).padding(.bottom,10) }
                HStack(spacing:0) {
                    navigation("CÃ¢mera","camera.fill",0)
                    navigation("Treino","scope",1)
                    navigation("Ajustes","slider.horizontal.3",2)
                    navigation("AtualizaÃ§Ãµes","arrow.down.circle",3)
                }.padding(.vertical,10)
            }.background(Palette.background.opacity(0.98)).overlay(alignment:.top) { Rectangle().fill(Palette.line).frame(height:1) }
        }
        .task { model.prepare(); await model.checkUpdates() }
        .onChange(of:phase) { _, value in
            if value == .active { model.prepare() } else if value == .background { model.suspend() }
        }
        .fullScreenCover(isPresented:$model.scannerPresented) { QRScanner { model.scannerFinished($0) }.ignoresSafeArea() }
        .fullScreenCover(isPresented:$model.fullscreen) { fullscreen }
    }
    private func navigation(_ label: String,_ icon: String,_ index: Int) -> some View {
        Button { model.page=index } label: {
            VStack(spacing:6) {
                Image(systemName:icon).font(.system(size:20,weight:.medium))
                Text(label).font(.system(size:10,weight:.semibold))
            }.frame(maxWidth:.infinity).padding(.vertical,5)
                .foregroundStyle(model.page == index ? Palette.lime : Palette.muted)
        }.buttonStyle(.plain).accessibilityAddTraits(model.page == index ? .isSelected : [])
    }
    private func notice(_ text: String,icon: String) -> some View {
        Label(text,systemImage:icon).font(.footnote).foregroundStyle(Palette.muted)
            .padding(16).frame(maxWidth:.infinity,alignment:.leading).background(Palette.panel,in:RoundedRectangle(cornerRadius:14))
    }
    private var updates: some View {
        VStack(alignment:.leading,spacing:22) {
            Image(systemName:"arrow.down.circle.fill").font(.system(size:44)).foregroundStyle(Palette.lime)
            Text("Seu app, evoluindo.").font(.largeTitle.weight(.semibold))
            Text("Novas versÃµes no mesmo lugar, sem procurar arquivos para instalar.")
                .font(.subheadline).foregroundStyle(Palette.muted)
            VStack(alignment:.leading,spacing:16) {
                networkRow("Instalado","iOS " + model.appVersion)
                Text(model.updateMessage).font(.subheadline).accessibilityLabel(model.updateMessage)
                Button { model.openUpdates() } label: {
                    Label(model.newVersion == nil ? "Adicionar fonte ao AltStore" : "Atualizar pelo AltStore",systemImage:"arrow.up.right.square")
                        .frame(maxWidth:.infinity).padding(12)
                }.buttonStyle(.borderedProminent).foregroundStyle(Palette.background)
                Button { Task { await model.checkUpdates() } } label: {
                    Label(model.checkingUpdate ? "Verificandoâ€¦" : "Verificar atualizaÃ§Ã£o",systemImage:"arrow.clockwise")
                }.disabled(model.checkingUpdate)
            }.padding(22).background(Palette.panel,in:RoundedRectangle(cornerRadius:22))
            Text("Adicione a fonte uma vez. O AltStore avisa quando houver uma nova versÃ£o; toque em Update para instalar. A assinatura e a renovaÃ§Ã£o continuam pelo AltStore e AltServer.")
                .font(.footnote).foregroundStyle(Palette.muted)
            Text("Suas cÃ¢meras e Ã¡reas continuam no PC. O pareamento fica salvo no iPhone ao atualizar.")
                .font(.footnote).foregroundStyle(Palette.muted)
            Button { UIPasteboard.general.string=UpdateSource.url.absoluteString; model.updateMessage="URL copiada. No AltStore, abra Sources â†’ + e cole." } label: {
                Label("Copiar URL da fonte",systemImage:"doc.on.doc")
            }
        }
    }
    private var header: some View {
        HStack(spacing:12) {
            Image(systemName:"tennisball.fill").font(.system(size:30)).foregroundStyle(Palette.lime)
                .frame(width:52,height:52).background(Palette.panel,in:RoundedRectangle(cornerRadius:16))
            VStack(alignment:.leading,spacing:3) {
                Text("TENNIS VISION").font(.headline).tracking(1.3)
                Text("SEU ESTÃšDIO EM MOVIMENTO").font(.subheadline).foregroundStyle(Palette.muted)
            }
            Spacer()
            Text(Bundle.main.object(forInfoDictionaryKey:"CFBundleShortVersionString") as? String ?? "0.2.0")
                .font(.caption.monospaced()).foregroundStyle(Palette.muted)
        }
    }
    private var preview: some View {
        VStack(alignment:.leading,spacing:14) {
            HStack {
                Label(model.snapshot.state,systemImage:model.snapshot.transmitting ? "dot.radiowaves.left.and.right" : "camera")
                    .font(.subheadline.weight(.medium)).foregroundStyle(model.snapshot.transmitting ? Palette.lime : Palette.muted)
                Spacer()
                Button { model.fullscreen=true } label: { Image(systemName:"arrow.up.left.and.arrow.down.right").padding(10) }
                    .accessibilityLabel("Abrir cÃ¢mera em tela cheia")
            }
            ZStack {
                CameraPreview(session:model.controller.session) { model.controller.focus(at:$0,lockFocus:false) }
                if model.grid { grid }
                if !model.snapshot.previewReady {
                    VStack(spacing:12) {
                        Image(systemName:model.denied ? "camera.fill.badge.ellipsis" : "camera").font(.largeTitle)
                        Text(model.denied ? "Permita o acesso Ã  cÃ¢mera" : model.snapshot.error != nil ? "CÃ¢mera indisponÃ­vel" : "Preparando sua cÃ¢mera").font(.headline)
                        if model.denied {
                            Button("Abrir Ajustes") { if let url=URL(string:UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                        } else if model.snapshot.error != nil {
                            Button("Tentar novamente") { model.prepare() }
                        }
                    }.foregroundStyle(.white).padding(24).background(Palette.background.opacity(0.9),in:RoundedRectangle(cornerRadius:18))
                }
            }.aspectRatio(16/9,contentMode:.fit).background(.black).clipShape(RoundedRectangle(cornerRadius:20))
                .overlay(RoundedRectangle(cornerRadius:20).stroke(Palette.line))
            HStack {
                Text(model.snapshot.activeProfile?.label ?? "Perfil indisponÃ­vel").font(.caption.monospaced())
                Spacer()
                Text("Toque na imagem para focar").font(.caption)
            }.foregroundStyle(Palette.muted)
            metrics
        }
    }
    private var metrics: some View {
        HStack(spacing:8) {
            metric("CAPTURA",model.snapshot.captureFPS,unit:"fps")
            metric("H.264",model.snapshot.encodedFPS,unit:"fps")
            metric("ENVIO",model.snapshot.sentFPS,unit:"fps")
        }
    }
    private func metric(_ name: String,_ value: Double,unit: String) -> some View {
        VStack(alignment:.leading,spacing:8) {
            Text(name).font(.system(size:10,weight:.semibold)).tracking(1).foregroundStyle(Palette.muted)
            Text(model.snapshot.transmitting ? String(format:"%.0f",value) : "â€”").font(.title2.monospacedDigit().weight(.medium))
                + Text(" \(unit)").font(.caption).foregroundColor(Palette.muted)
        }.frame(maxWidth:.infinity,alignment:.leading).padding(14).background(Palette.panel,in:RoundedRectangle(cornerRadius:14))
    }
    private var controls: some View {
        VStack(alignment:.leading,spacing:20) {
            Picker("ConfiguraÃ§Ã£o",selection:$tab) {
                Text("ConexÃ£o").tag(0); Text("Captura").tag(1); Text("Rede").tag(2)
            }.pickerStyle(.segmented)
            switch tab {
            case 0: connection
            case 1: capture
            default: network
            }
            if let error=model.snapshot.error {
                Label(error,systemImage:"exclamationmark.triangle.fill").font(.subheadline).foregroundStyle(Color(red:1,green:0.55,blue:0.5))
                    .padding(14).background(Color.red.opacity(0.09),in:RoundedRectangle(cornerRadius:12))
            }
            if let message=model.message {
                Text(message).font(.subheadline).foregroundStyle(Palette.muted)
            }
            Text("O envio mede os quadros entregues Ã  rede local. A recepÃ§Ã£o e a anÃ¡lise sÃ£o medidas no PC.")
                .font(.caption).foregroundStyle(Palette.muted)
        }.padding(22).background(Palette.panel,in:RoundedRectangle(cornerRadius:24)).overlay(RoundedRectangle(cornerRadius:24).stroke(Palette.line))
    }
    private var primaryAction: some View {
        Button {
            if model.snapshot.transmitting { model.controller.stop() } else { model.transmit() }
        } label: {
            HStack(spacing:10) {
                Image(systemName:model.snapshot.transmitting ? "stop.fill" : "play.fill")
                Text(model.snapshot.transmitting ? "Parar transmissÃ£o" : "Transmitir ao Windows")
            }.font(.headline).frame(maxWidth:.infinity).padding(.vertical,18)
        }.buttonStyle(.plain).foregroundStyle(Palette.background)
            .background(model.snapshot.transmitting ? Color(red:1,green:0.43,blue:0.4) : Palette.lime,in:RoundedRectangle(cornerRadius:16))
            .opacity(!model.snapshot.transmitting && (!model.selectedSupported || model.denied || !model.snapshot.previewReady || model.starting) ? 0.45 : 1)
            .disabled(!model.snapshot.transmitting && (!model.selectedSupported || model.denied || !model.snapshot.previewReady || model.starting))
    }
    private var connection: some View {
        VStack(alignment:.leading,spacing:16) {
            Text("Seu PC, conectado.").font(.title2.weight(.semibold))
            Text("No Windows, abra Conectar celular e escolha iPhone / iOS. Leia o QR ou preencha o endereÃ§o.")
                .font(.subheadline).foregroundStyle(Palette.muted)
            Button { model.openScanner() } label: {
                Label("Ler QR do Windows",systemImage:"qrcode.viewfinder").frame(maxWidth:.infinity).padding(15)
            }.buttonStyle(.plain).background(Palette.background,in:RoundedRectangle(cornerRadius:12))
                .overlay(RoundedRectangle(cornerRadius:12).stroke(Palette.line)).disabled(model.snapshot.transmitting)
            field("IP ou nome do PC",placeholder:"Ex.: 192.168.15.2",value:$model.host)
            HStack(alignment:.top,spacing:12) {
                field("Porta RTSP",placeholder:"8554",value:$model.port,number:true)
                field("Caminho",placeholder:"iphone",value:$model.path)
            }
            Button("Salvar endereÃ§o") { model.save() }.disabled(model.snapshot.transmitting)
            Label("Mesma rede Wi-Fi. No PC, use Preparar rede do PC para liberar a conexÃ£o local.",systemImage:"wifi")
                .font(.footnote).foregroundStyle(Palette.muted)
        }
    }
    private func field(_ label: String,placeholder: String,value: Binding<String>,number: Bool=false) -> some View {
        VStack(alignment:.leading,spacing:8) {
            Text(label).font(.caption.weight(.semibold)).foregroundStyle(Palette.muted)
            TextField(placeholder,text:value).textInputAutocapitalization(.never).autocorrectionDisabled()
                .keyboardType(number ? .numberPad : .asciiCapable).padding(14).background(Palette.background,in:RoundedRectangle(cornerRadius:11))
                .overlay(RoundedRectangle(cornerRadius:11).stroke(Palette.line)).disabled(model.snapshot.transmitting)
                .accessibilityLabel(label)
        }
    }
    private var capture: some View {
        VStack(alignment:.leading,spacing:16) {
            Text("Nitidez em movimento.").font(.title2.weight(.semibold))
            Toggle("Melhor perfil automÃ¡tico",isOn:Binding(get:{model.automatic},set:{model.chooseAutomatic($0)}))
                .disabled(model.snapshot.transmitting)
            Text("Usa a maior taxa real disponÃ­vel na cÃ¢mera traseira, preferindo 1080p quando possÃ­vel. Se a captura ou o H.264 nÃ£o sustentarem o perfil, o app avisa e ajusta. O modo manual mantÃ©m sua escolha.")
                .font(.footnote).foregroundStyle(Palette.muted)
            Label(model.deviceLabel + " Â· " + model.snapshot.cameraName,systemImage:"iphone").font(.footnote)
            if let best=CameraPolicy.best(model.profiles) {
                Text("Capacidade detectada: " + best.label).font(.footnote).foregroundStyle(Palette.lime)
            }
            Picker("Perfil de cÃ¢mera",selection:Binding(get:{model.profile.id},set:{ id in
                if let profile=model.profiles.first(where:{$0.id == id}) { model.chooseProfile(profile) }
            })) {
                if !model.selectedSupported { Text("\(model.profile.label) Â· indisponÃ­vel").tag(model.profile.id) }
                ForEach(model.profiles) { Text($0.label).tag($0.id) }
            }.disabled(model.snapshot.transmitting)
            Text("SÃ³ aparecem taxas informadas pela cÃ¢mera. O H.264 e o desempenho sustentado tambÃ©m precisam suportar o perfil.")
                .font(.footnote).foregroundStyle(Palette.muted)
            Picker("ExposiÃ§Ã£o",selection:Binding(get:{model.exposure},set:{model.changeExposure($0)})) {
                Text("AutomÃ¡tica").tag(0); Text("1/500 s").tag(500); Text("1/1000 s").tag(1000); Text("1/2000 s").tag(2000)
            }
            Text("Mais luz e uma exposiÃ§Ã£o curta ajudam a reduzir o borrÃ£o da bola. ExposiÃ§Ã£o curta pode escurecer a imagem.")
                .font(.footnote).foregroundStyle(Palette.muted)
            Toggle("Linhas de enquadramento",isOn:$model.grid)
            Toggle("Travar foco atual",isOn:Binding(get:{model.snapshot.focusLocked},set:{
                model.controller.focus(at:CGPoint(x:0.5,y:0.5),lockFocus:$0)
            }))
            if model.snapshot.torchAvailable {
                Toggle("Lanterna",isOn:Binding(get:{model.snapshot.torchOn},set:{model.controller.setTorch($0)}))
            }
            Label("Temperatura Â· \(model.snapshot.thermal)",systemImage:"thermometer.medium")
                .font(.footnote).foregroundStyle(Palette.muted)
        }
    }
    private var network: some View {
        VStack(alignment:.leading,spacing:16) {
            Text("VÃ­deo direto ao PC.").font(.title2.weight(.semibold))
            Text(model.destination).font(.footnote.monospaced()).textSelection(.enabled).foregroundStyle(Palette.lime)
            networkRow("Transporte","RTSP / TCP Â· H.264")
            networkRow("VÃ­deo enviado",String(format:"%.1f Mbps",model.snapshot.mbps))
            networkRow("Descartes de captura / H.264",String(model.snapshot.encoderDrops))
            networkRow("Descartes de rede",String(model.snapshot.networkDrops))
            Text("A fila de envio descarta vÃ­deo antigo e pede um novo quadro completo. Assim, uma rede lenta nÃ£o acumula segundos de atraso.")
                .font(.footnote).foregroundStyle(Palette.muted)
            Text("Mantenha este aplicativo aberto durante a transmissÃ£o. Ao ir para o segundo plano, a cÃ¢mera Ã© parada.")
                .font(.footnote).foregroundStyle(Palette.muted)
        }
    }
    private func networkRow(_ label: String,_ value: String) -> some View {
        HStack(alignment:.top) { Text(label).foregroundStyle(Palette.muted); Spacer(); Text(value).monospacedDigit() }
            .font(.footnote).padding(.vertical,8).overlay(alignment:.bottom){Rectangle().fill(Palette.line).frame(height:1)}
    }
    private var grid: some View {
        GeometryReader { geo in
            Path { path in
                for fraction in [1.0/3,2.0/3] {
                    path.move(to:CGPoint(x:geo.size.width*fraction,y:0)); path.addLine(to:CGPoint(x:geo.size.width*fraction,y:geo.size.height))
                    path.move(to:CGPoint(x:0,y:geo.size.height*fraction)); path.addLine(to:CGPoint(x:geo.size.width,y:geo.size.height*fraction))
                }
            }.stroke(Color.white.opacity(0.18),lineWidth:1)
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
    private var fullscreen: some View {
        ZStack(alignment:.topTrailing) {
            Color.black.ignoresSafeArea()
            CameraPreview(session:model.controller.session) { model.controller.focus(at:$0,lockFocus:false) }.ignoresSafeArea()
            Button { model.fullscreen=false } label: {
                Label("Voltar",systemImage:"xmark").padding(14).background(Palette.panel,in:Capsule())
            }.buttonStyle(.plain).foregroundStyle(.white).padding(20)
        }.preferredColorScheme(.dark)
    }
}
