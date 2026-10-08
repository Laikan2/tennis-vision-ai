import SwiftUI
import UIKit

private enum Palette {
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
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment:.leading,spacing:24) {
                    header
                    if geometry.size.width > 850 {
                        HStack(alignment:.top,spacing:24) { preview.frame(maxWidth:.infinity); controls.frame(width:380) }
                    } else { preview; controls }
                    Text("A câmera transmite ao seu PC. Bola, impactos, IN/OUT e estatísticas aparecem no Tennis Vision Studio.")
                        .font(.footnote).foregroundStyle(Palette.muted)
                }.padding(geometry.size.width > 850 ? 28 : 20).frame(maxWidth:1250).frame(maxWidth:.infinity)
            }.background(Palette.background).preferredColorScheme(.dark)
        }
        .tint(Palette.lime)
        .safeAreaInset(edge:.bottom) {
            primaryAction.padding(.horizontal,20).padding(.vertical,12)
                .frame(maxWidth:850).frame(maxWidth:.infinity)
                .background(Palette.background.opacity(0.98))
        }
        .task { model.prepare() }
        .onChange(of:phase) { _, value in
            if value == .active { model.prepare() } else if value == .background { model.suspend() }
        }
        .fullScreenCover(isPresented:$model.scannerPresented) { QRScanner { model.scannerFinished($0) }.ignoresSafeArea() }
        .fullScreenCover(isPresented:$model.fullscreen) { fullscreen }
    }
    private var header: some View {
        HStack(spacing:12) {
            Image(systemName:"tennisball.fill").font(.system(size:30)).foregroundStyle(Palette.lime)
                .frame(width:52,height:52).background(Palette.panel,in:RoundedRectangle(cornerRadius:16))
            VStack(alignment:.leading,spacing:3) {
                Text("TENNIS VISION").font(.headline).tracking(1.3)
                Text("Câmera para iPhone").font(.subheadline).foregroundStyle(Palette.muted)
            }
            Spacer()
            Text(Bundle.main.object(forInfoDictionaryKey:"CFBundleShortVersionString") as? String ?? "0.1.0")
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
                    .accessibilityLabel("Abrir câmera em tela cheia")
            }
            ZStack {
                CameraPreview(session:model.controller.session) { model.controller.focus(at:$0,lockFocus:false) }
                if model.grid { grid }
                if !model.snapshot.previewReady {
                    VStack(spacing:12) {
                        Image(systemName:model.denied ? "camera.fill.badge.ellipsis" : "camera").font(.largeTitle)
                        Text(model.denied ? "Permita o acesso à câmera" : model.snapshot.error != nil ? "Câmera indisponível" : "Preparando sua câmera").font(.headline)
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
                Text(model.snapshot.activeProfile?.label ?? "Perfil indisponível").font(.caption.monospaced())
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
            Text(model.snapshot.transmitting ? String(format:"%.0f",value) : "—").font(.title2.monospacedDigit().weight(.medium))
                + Text(" \(unit)").font(.caption).foregroundColor(Palette.muted)
        }.frame(maxWidth:.infinity,alignment:.leading).padding(14).background(Palette.panel,in:RoundedRectangle(cornerRadius:14))
    }
    private var controls: some View {
        VStack(alignment:.leading,spacing:20) {
            Picker("Configuração",selection:$tab) {
                Text("Conexão").tag(0); Text("Captura").tag(1); Text("Rede").tag(2)
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
            Text("O envio mede os quadros entregues à rede local. A recepção e a análise são medidas no PC.")
                .font(.caption).foregroundStyle(Palette.muted)
        }.padding(22).background(Palette.panel,in:RoundedRectangle(cornerRadius:24)).overlay(RoundedRectangle(cornerRadius:24).stroke(Palette.line))
    }
    private var primaryAction: some View {
        Button {
            if model.snapshot.transmitting { model.controller.stop() } else { model.transmit() }
        } label: {
            HStack(spacing:10) {
                Image(systemName:model.snapshot.transmitting ? "stop.fill" : "play.fill")
                Text(model.snapshot.transmitting ? "Parar transmissão" : "Transmitir ao Windows")
            }.font(.headline).frame(maxWidth:.infinity).padding(.vertical,18)
        }.buttonStyle(.plain).foregroundStyle(Palette.background)
            .background(model.snapshot.transmitting ? Color(red:1,green:0.43,blue:0.4) : Palette.lime,in:RoundedRectangle(cornerRadius:16))
            .opacity(!model.snapshot.transmitting && (!model.selectedSupported || model.denied || !model.snapshot.previewReady) ? 0.45 : 1)
            .disabled(!model.snapshot.transmitting && (!model.selectedSupported || model.denied || !model.snapshot.previewReady))
    }
    private var connection: some View {
        VStack(alignment:.leading,spacing:16) {
            Text("Seu PC, conectado.").font(.title2.weight(.semibold))
            Text("No Windows, abra Conectar celular e escolha iPhone / iOS. Leia o QR ou preencha o endereço.")
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
            Button("Salvar endereço") { model.save() }.disabled(model.snapshot.transmitting)
            Label("Mesma rede Wi-Fi. No PC, use Preparar rede do PC para liberar a conexão local.",systemImage:"wifi")
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
            Picker("Perfil de câmera",selection:Binding(get:{model.profile.id},set:{ id in
                if let profile=model.profiles.first(where:{$0.id == id}) { model.chooseProfile(profile) }
            })) {
                if !model.selectedSupported { Text("\(model.profile.label) · indisponível").tag(model.profile.id) }
                ForEach(model.profiles) { Text($0.label).tag($0.id) }
            }.disabled(model.snapshot.transmitting)
            Text("Só aparecem taxas informadas pela câmera. O H.264 e o desempenho sustentado também precisam suportar o perfil.")
                .font(.footnote).foregroundStyle(Palette.muted)
            Picker("Exposição",selection:Binding(get:{model.exposure},set:{model.changeExposure($0)})) {
                Text("Automática").tag(0); Text("1/500 s").tag(500); Text("1/1000 s").tag(1000); Text("1/2000 s").tag(2000)
            }
            Text("Mais luz e uma exposição curta ajudam a reduzir o borrão da bola. Exposição curta pode escurecer a imagem.")
                .font(.footnote).foregroundStyle(Palette.muted)
            Toggle("Linhas de enquadramento",isOn:$model.grid)
            Toggle("Travar foco atual",isOn:Binding(get:{model.snapshot.focusLocked},set:{
                model.controller.focus(at:CGPoint(x:0.5,y:0.5),lockFocus:$0)
            }))
            if model.snapshot.torchAvailable {
                Toggle("Lanterna",isOn:Binding(get:{model.snapshot.torchOn},set:{model.controller.setTorch($0)}))
            }
            Label("Temperatura · \(model.snapshot.thermal)",systemImage:"thermometer.medium")
                .font(.footnote).foregroundStyle(Palette.muted)
        }
    }
    private var network: some View {
        VStack(alignment:.leading,spacing:16) {
            Text("Vídeo direto ao PC.").font(.title2.weight(.semibold))
            Text(model.destination).font(.footnote.monospaced()).textSelection(.enabled).foregroundStyle(Palette.lime)
            networkRow("Transporte","RTSP / TCP · H.264")
            networkRow("Vídeo enviado",String(format:"%.1f Mbps",model.snapshot.mbps))
            networkRow("Descartes de captura / H.264",String(model.snapshot.encoderDrops))
            networkRow("Descartes de rede",String(model.snapshot.networkDrops))
            Text("A fila de envio descarta vídeo antigo e pede um novo quadro completo. Assim, uma rede lenta não acumula segundos de atraso.")
                .font(.footnote).foregroundStyle(Palette.muted)
            Text("Mantenha este aplicativo aberto durante a transmissão. Ao ir para o segundo plano, a câmera é parada.")
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
