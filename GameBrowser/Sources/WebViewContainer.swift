import SwiftUI
import WebKit

/// Hosts the active tab's WKWebView, swapping it in when the tab changes.
struct WebViewContainer: UIViewRepresentable {
    @ObservedObject var viewModel: BrowserViewModel

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        return container
    }

    func updateUIView(_ container: UIView, context: Context) {
        let webView = viewModel.webView
        if webView.superview !== container {
            container.subviews.forEach { $0.removeFromSuperview() }
            webView.frame = container.bounds
            webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            container.addSubview(webView)
        }
        viewModel.webViewSize = container.bounds.size
        // In cursor mode all touches are handled by the trackpad overlay.
        webView.isUserInteractionEnabled = !viewModel.cursorMode
    }
}
