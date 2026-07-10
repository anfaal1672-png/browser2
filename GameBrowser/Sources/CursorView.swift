import SwiftUI

/// The virtual mouse cursor drawn above the web view.
struct CursorView: View {
    let position: CGPoint
    let pressed: Bool

    var body: some View {
        CursorShape()
            .fill(.white)
            .overlay(CursorShape().stroke(.black, lineWidth: 1.2))
            .frame(width: 22, height: 22)
            .scaleEffect(pressed ? 0.82 : 1.0, anchor: .topLeading)
            .shadow(color: .black.opacity(0.45), radius: 2, x: 1, y: 1)
            .position(x: position.x + 11, y: position.y + 11)
            .animation(.easeOut(duration: 0.08), value: pressed)
            .allowsHitTesting(false)
    }
}

/// Classic arrow pointer, tip at the top-left of its frame.
struct CursorShape: Shape {
    func path(in rect: CGRect) -> Path {
        let w = rect.width, h = rect.height
        var p = Path()
        p.move(to: CGPoint(x: 0, y: 0))
        p.addLine(to: CGPoint(x: 0, y: h * 0.82))
        p.addLine(to: CGPoint(x: w * 0.22, y: h * 0.62))
        p.addLine(to: CGPoint(x: w * 0.38, y: h * 0.98))
        p.addLine(to: CGPoint(x: w * 0.52, y: h * 0.92))
        p.addLine(to: CGPoint(x: w * 0.36, y: h * 0.56))
        p.addLine(to: CGPoint(x: w * 0.64, y: h * 0.56))
        p.closeSubpath()
        return p
    }
}
