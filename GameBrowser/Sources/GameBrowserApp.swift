import SwiftUI

@main
struct GameBrowserApp: App {
    // Theme must be applied at the window root: set inside ContentView it
    // isn't picked up when the window is first created (light flash on
    // first launch).
    @AppStorage("appTheme") private var appTheme = 0   // 0 dark, 1 light, 2 system

    var body: some Scene {
        WindowGroup {
            ContentView()
                .persistentSystemOverlays(.hidden)
                .statusBarHidden(false)
                .preferredColorScheme(appTheme == 0 ? .dark : appTheme == 1 ? .light : nil)
        }
    }
}
