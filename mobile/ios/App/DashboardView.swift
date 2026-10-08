import SwiftUI
import WebKit

@MainActor final class PanelState: NSObject, ObservableObject, WKNavigationDelegate {
    let webView: WKWebView
    @Published var loading=true
    @Published var error: String?
    private var origin: URL?
    private var identity=""
    override init() {
        let config=WKWebViewConfiguration()
        config.allowsInlineMediaPlayback=true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.userContentController.addUserScript(WKUserScript(source:"window.tennisPhonePanel = true;",injectionTime:.atDocumentStart,forMainFrameOnly:true))
        webView=WKWebView(frame:.zero,configuration:config)
        super.init()
        webView.navigationDelegate=self
        webView.isOpaque=false
        webView.backgroundColor=UIColor(red:0.035,green:0.055,blue:0.065,alpha:1)
        webView.scrollView.contentInsetAdjustmentBehavior = .never
    }
    func connect(_ configuration: PairingConfiguration, revision: Int) {
        guard let url=configuration.panelURL, let token=configuration.panelToken else { return }
        let key="\(url.absoluteString)|\(token)|\(revision)"
        guard key != identity else { return }; identity=key; origin=url
        loading=true; error=nil
        var request=URLRequest(url:url.appendingPathComponent("mobile/pair"))
        request.httpMethod="POST"; request.setValue("Bearer "+token,forHTTPHeaderField:"Authorization")
        request.timeoutInterval=12
        webView.load(request)
    }
    func webView(_ view: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy)->Void) {
        guard let url=action.request.url, let origin,
              url.scheme == origin.scheme, url.host == origin.host, url.port == origin.port else {
            decisionHandler(.cancel); return
        }
        decisionHandler(.allow)
    }
    func webView(_ view: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy)->Void) {
        if let response=response.response as? HTTPURLResponse, response.statusCode >= 400 {
            loading=false
            error=response.statusCode == 401 ? "O pareamento mudou. Leia novamente o QR do Windows." : "O PC ainda não está pronto. Aguarde e toque em Reconectar."
            decisionHandler(.cancel); return
        }
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loading=false; error=nil }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { failed(error) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { failed(error) }
    private func failed(_ failure: Error) {
        if (failure as NSError).code == NSURLErrorCancelled { return }
        loading=false; error="Não foi possível abrir o painel. Mantenha o Studio aberto, use a mesma rede e, no Windows, toque em Preparar rede do PC."
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        loading=false; error="O iOS liberou a memória do painel. Toque em Reconectar. A câmera continua independente."
    }
}
private struct PanelWebView: UIViewRepresentable {
    let state: PanelState
    let configuration: PairingConfiguration
    let revision: Int
    func makeUIView(context: Context) -> WKWebView { state.webView }
    func updateUIView(_ view: WKWebView, context: Context) { state.connect(configuration,revision:revision) }
    static func dismantleUIView(_ view: WKWebView, coordinator: ()) {
        view.stopLoading(); view.loadHTMLString("",baseURL:nil)
    }
}
struct DashboardView: View {
    @ObservedObject var model: CameraViewModel
    @StateObject private var state=PanelState()
    var body: some View {
        VStack(spacing:0) {
            HStack(spacing:12) {
                VStack(alignment:.leading,spacing:4) {
                    Text("Seu treino, ao vivo.").font(.headline)
                    Text(model.snapshot.transmitting ? "Câmera transmitindo · análise no PC" : "Painel do Windows · mesma sessão")
                        .font(.caption).foregroundStyle(Palette.muted)
                }
                Spacer()
                Button { model.panelReload+=1 } label: { Image(systemName:"arrow.clockwise").padding(12) }
                    .accessibilityLabel("Reconectar painel")
            }.padding(16).background(Palette.panel)
            if let config=model.panelConfiguration {
                ZStack {
                    PanelWebView(state:state,configuration:config,revision:model.panelReload)
                    if state.loading {
                        VStack(spacing:12) { ProgressView(); Text("Conectando ao seu estúdio…").font(.subheadline) }
                            .padding(24).background(Palette.panel,in:RoundedRectangle(cornerRadius:18))
                    }
                    if let error=state.error {
                        VStack(spacing:16) {
                            Image(systemName:"wifi.exclamationmark").font(.largeTitle)
                            Text(error).multilineTextAlignment(.center)
                            Button("Reconectar") { model.panelReload+=1 }.buttonStyle(.borderedProminent).foregroundStyle(Palette.background)
                        }.padding(28).background(Palette.panel,in:RoundedRectangle(cornerRadius:22)).padding(24)
                    }
                }
            } else {
                VStack(spacing:20) {
                    Image(systemName:"rectangle.connected.to.line.below").font(.system(size:48)).foregroundStyle(Palette.lime)
                    Text("O estúdio cabe aqui.").font(.title2.weight(.semibold))
                    Text("Abra Conectar celular → iPhone no Windows atualizado e leia o QR. Você poderá desenhar a área, ver bola, impactos e estatísticas e ativar o som OUT.")
                        .foregroundStyle(Palette.muted).multilineTextAlignment(.center)
                    Button("Conectar ao PC") { model.page=2 }.buttonStyle(.borderedProminent).foregroundStyle(Palette.background)
                    Text("A câmera continua transmitindo enquanto você usa o painel. Mantenha o aplicativo aberto.")
                        .font(.footnote).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
                }.padding(28).frame(maxWidth:540,maxHeight:.infinity)
            }
        }.background(Palette.background)
    }
}
