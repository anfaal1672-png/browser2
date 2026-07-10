import SwiftUI
import WebKit

struct WebViewContainer: UIViewRepresentable {
    @ObservedObject var viewModel: BrowserViewModel

    func makeUIView(context: Context) -> WKWebView {
        viewModel.webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        viewModel.webViewSize = uiView.bounds.size
        // In cursor mode all touches are handled by the trackpad overlay.
        uiView.isUserInteractionEnabled = !viewModel.cursorMode
    }
}
