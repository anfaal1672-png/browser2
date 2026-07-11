import SwiftUI

@main
struct GameBrowserApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .persistentSystemOverlays(.hidden)
                .statusBarHidden(false)
        }
    }
}
