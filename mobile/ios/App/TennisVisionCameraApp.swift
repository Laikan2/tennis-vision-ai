import SwiftUI

@main struct TennisVisionCameraApp: App {
    @StateObject private var model=CameraViewModel()
    @StateObject private var local=LocalTrainingModel()
    @State private var localMode=true
    var body: some Scene {
        WindowGroup {
            Group {
                if localMode{LocalTrainingView(model:local,openStudio:{localMode=false})}
                else{ContentView(model:model,onLocal:{model.controller.pause{localMode=true}})}
            }.onOpenURL{url in
                if url.host=="diagnostics"{local.diagnostics()}
                else{local.suspend{model.applyPairing(url.absoluteString);localMode=false}}
            }
        }
    }
}
