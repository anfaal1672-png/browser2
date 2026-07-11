import SwiftUI

/// The virtual mouse cursor drawn above the web view. Like Windows/macOS,
/// its shape follows the CSS `cursor` of the element under the pointer.
struct CursorView: View {
    let position: CGPoint
    let pressed: Bool
    var style: String = "auto"

    private enum Shape {
        case arrow                    // default / auto
        case pointerHand              // hotspot at the fingertip
        case symbol(String, rotation: Double)   // centered SF Symbol
    }

    // Enum cases can't take default arguments, hence the helper.
    private static func sym(_ name: String, _ rotation: Double = 0) -> Shape {
        .symbol(name, rotation: rotation)
    }

    private var shape: Shape {
        switch style {
        case "pointer":                 return .pointerHand
        case "text", "vertical-text":   return Self.sym("text.cursor")
        case "wait", "progress":        return Self.sym("hourglass")
        case "crosshair", "cell":       return Self.sym("plus")
        case "move", "all-scroll":      return Self.sym("arrow.up.and.down.and.arrow.left.and.right")
        case "grab":                    return Self.sym("hand.raised")
        case "grabbing":                return Self.sym("hand.raised.fill")
        case "not-allowed", "no-drop":  return Self.sym("circle.slash")
        case "help":                    return Self.sym("questionmark.circle")
        case "zoom-in":                 return Self.sym("plus.magnifyingglass")
        case "zoom-out":                return Self.sym("minus.magnifyingglass")
        case "ns-resize", "n-resize", "s-resize", "row-resize":
            return Self.sym("arrow.up.and.down")
        case "ew-resize", "e-resize", "w-resize", "col-resize":
            return Self.sym("arrow.left.and.right")
        case "nwse-resize", "nw-resize", "se-resize":
            return Self.sym("arrow.up.and.down", 45)
        case "nesw-resize", "ne-resize", "sw-resize":
            return Self.sym("arrow.up.and.down", -45)
        case "copy":                    return Self.sym("plus.square.on.square")
        case "alias":                   return Self.sym("arrow.up.right.square")
        case "context-menu":            return Self.sym("list.bullet.rectangle")
        default:                        return .arrow
        }
    }

    var body: some View {
        Group {
            switch shape {
            case .arrow:
                CursorShape()
                    .fill(.white)
                    .overlay(CursorShape().stroke(.black, lineWidth: 1.2))
                    .frame(width: 22, height: 22)
                    .scaleEffect(pressed ? 0.82 : 1.0, anchor: .topLeading)
                    .shadow(color: .black.opacity(0.45), radius: 2, x: 1, y: 1)
                    // The arrow's hotspot is its top-left tip.
                    .position(x: position.x + 11, y: position.y + 11)

            case .pointerHand:
                // The hand's hotspot is the tip of the extended finger
                // (upper-left of the glyph), like the Windows link cursor.
                Image(systemName: "hand.point.up.left.fill")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(.white)
                    .scaleEffect(pressed ? 0.85 : 1.0, anchor: .topLeading)
                    .shadow(color: .black.opacity(0.8), radius: 1.5)
                    .shadow(color: .black.opacity(0.5), radius: 3)
                    .position(x: position.x + 7, y: position.y + 10)

            case .symbol(let name, let rotation):
                Image(systemName: name)
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(.white)
                    .rotationEffect(.degrees(rotation))
                    .scaleEffect(pressed ? 0.85 : 1.0)
                    .shadow(color: .black.opacity(0.8), radius: 1.5)
                    .shadow(color: .black.opacity(0.5), radius: 3)
                    // Symbol cursors (I-beam, crosshair, resize...) are
                    // hotspot-centered, like the real OS cursors.
                    .position(position)
            }
        }
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
