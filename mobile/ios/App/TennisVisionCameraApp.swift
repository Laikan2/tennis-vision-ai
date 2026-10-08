import SwiftUI

@main struct TennisVisionCameraApp: App {
    @StateObject private var model=CameraViewModel()
    var body: some Scene {
        WindowGroup { ContentView(model:model).onOpenURL { model.applyPairing($0.absoluteString) } }
    }
}
