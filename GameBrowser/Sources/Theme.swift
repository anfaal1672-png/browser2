import SwiftUI

/// The app's design system.
///
/// Before this, each screen invented its own look: Settings and the control
/// profiles were custom dark cards, while bookmarks, history, tabs and
/// downloads used stock list chrome on a system background — the app read as
/// several apps stitched together. Everything now draws from these tokens and
/// the components below, so a change here is a change everywhere.
enum GB {

    // MARK: - Colour

    /// Page background, at the bottom of the stack.
    static let bg = Color(red: 0.043, green: 0.059, blue: 0.078)
    static let bgDeep = Color(red: 0.016, green: 0.024, blue: 0.035)
    /// Raised surfaces (cards, bars, fields).
    static let surface = Color.white.opacity(0.06)
    static let surfaceHigh = Color.white.opacity(0.10)
    static let surfacePressed = Color.white.opacity(0.16)
    /// Hairlines.
    static let border = Color.white.opacity(0.09)
    static let borderStrong = Color.white.opacity(0.16)

    static let text = Color(red: 0.91, green: 0.93, blue: 0.95)
    static let textDim = Color.white.opacity(0.55)
    static let textFaint = Color.white.opacity(0.38)

    static let accent = Color(red: 0.224, green: 0.827, blue: 0.961)
    static let accentDeep = Color(red: 0.231, green: 0.510, blue: 0.965)
    /// Private browsing runs violet everywhere, so the mode is never in doubt.
    static let privateAccent = Color(red: 0.655, green: 0.545, blue: 0.980)
    static let danger = Color(red: 0.949, green: 0.329, blue: 0.357)
    static let success = Color(red: 0.404, green: 0.827, blue: 0.545)
    static let warning = Color(red: 0.976, green: 0.729, blue: 0.318)

    // MARK: - Metrics

    enum Radius {
        static let small: CGFloat = 10
        static let medium: CGFloat = 14
        static let large: CGFloat = 18
        static let pill: CGFloat = 999
    }

    enum Space {
        static let xs: CGFloat = 6
        static let s: CGFloat = 10
        static let m: CGFloat = 14
        static let l: CGFloat = 20
        static let xl: CGFloat = 28
    }

    // MARK: - Type

    enum Font_ {
        static let title = Font.system(size: 26, weight: .bold, design: .rounded)
        static let heading = Font.system(size: 16, weight: .semibold, design: .rounded)
        static let rowTitle = Font.system(size: 15, weight: .medium)
        static let body = Font.system(size: 14)
        static let label = Font.system(size: 13, weight: .medium)
        static let caption = Font.system(size: 11)
        static let mono = Font.system(size: 12, weight: .medium, design: .monospaced)
    }

    /// The screen-filling background every sheet and full-screen surface uses.
    static var background: some View {
        LinearGradient(colors: [bg, bgDeep], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }
}

// MARK: - Components

/// Section container: a rounded surface with a tinted icon, a title, and an
/// optional trailing control.
struct GBCard<Content: View, Trailing: View>: View {
    let icon: String
    let tint: Color
    let title: String
    @ViewBuilder var trailing: () -> Trailing
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: GB.Space.m) {
            HStack(spacing: GB.Space.s) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                    .background(tint.opacity(0.85), in: RoundedRectangle(cornerRadius: 8))
                Text(title)
                    .font(GB.Font_.heading)
                    .foregroundStyle(GB.text)
                Spacer(minLength: 0)
                trailing()
            }
            content()
        }
        .padding(GB.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GB.surface, in: RoundedRectangle(cornerRadius: GB.Radius.large))
        .overlay(
            RoundedRectangle(cornerRadius: GB.Radius.large).stroke(GB.border, lineWidth: 1)
        )
    }
}

extension GBCard where Trailing == EmptyView {
    init(icon: String, tint: Color, title: String, @ViewBuilder content: @escaping () -> Content) {
        self.init(icon: icon, tint: tint, title: title, trailing: { EmptyView() }, content: content)
    }
}

/// Sheet chrome: the app's background, a large title with a close button, and
/// a scrolling body. Replaces the stock NavigationStack look the list screens
/// used to inherit.
struct GBSheet<Content: View, Toolbar: View>: View {
    let title: String
    var subtitle: String? = nil
    var accent: Color = GB.accent
    let dismiss: () -> Void
    @ViewBuilder var toolbar: () -> Toolbar
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack {
            GB.background
            VStack(spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: GB.Space.s) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(GB.Font_.title)
                            .foregroundStyle(GB.text)
                        if let subtitle {
                            Text(subtitle)
                                .font(GB.Font_.caption)
                                .foregroundStyle(GB.textDim)
                        }
                    }
                    Spacer(minLength: 0)
                    toolbar()
                    Button(action: dismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(GB.text.opacity(0.8))
                            .frame(width: 32, height: 32)
                            .background(GB.surfaceHigh, in: Circle())
                    }
                }
                .padding(.horizontal, GB.Space.m)
                .padding(.top, GB.Space.l)
                .padding(.bottom, GB.Space.s)

                content()
            }
        }
        .tint(accent)
        .preferredColorScheme(.dark)
    }
}

extension GBSheet where Toolbar == EmptyView {
    init(title: String, subtitle: String? = nil, accent: Color = GB.accent,
         dismiss: @escaping () -> Void, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, subtitle: subtitle, accent: accent, dismiss: dismiss,
                  toolbar: { EmptyView() }, content: content)
    }
}

/// One line in a list screen: leading glyph, title, subtitle, trailing slot.
struct GBRow<Trailing: View>: View {
    let icon: String
    var iconTint: Color = GB.accent
    let title: String
    var subtitle: String? = nil
    var monoSubtitle: Bool = false
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: GB.Space.s) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(iconTint)
                .frame(width: 30, height: 30)
                .background(iconTint.opacity(0.14), in: RoundedRectangle(cornerRadius: 9))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(GB.Font_.rowTitle)
                    .foregroundStyle(GB.text)
                    .lineLimit(1)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(monoSubtitle ? GB.Font_.mono : GB.Font_.caption)
                        .foregroundStyle(GB.textDim)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            trailing()
        }
        .padding(.horizontal, GB.Space.m)
        .padding(.vertical, GB.Space.s)
        .contentShape(Rectangle())
    }
}

extension GBRow where Trailing == EmptyView {
    init(icon: String, iconTint: Color = GB.accent, title: String,
         subtitle: String? = nil, monoSubtitle: Bool = false) {
        self.init(icon: icon, iconTint: iconTint, title: title, subtitle: subtitle,
                  monoSubtitle: monoSubtitle, trailing: { EmptyView() })
    }
}

/// Full-width primary action.
struct GBPrimaryButton: View {
    let title: String
    var icon: String? = nil
    var tint: Color = GB.accent
    var destructive: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: GB.Space.xs) {
                if let icon { Image(systemName: icon) }
                Text(title)
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(destructive ? .white : GB.bgDeep)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(destructive ? GB.danger.opacity(0.85) : tint,
                        in: RoundedRectangle(cornerRadius: GB.Radius.small))
        }
    }
}

/// Quiet, tinted action used inside cards.
struct GBQuietButton: View {
    let title: String
    var icon: String? = nil
    var tint: Color = GB.accent
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: GB.Space.xs) {
                if let icon { Image(systemName: icon) }
                Text(title)
            }
            .font(GB.Font_.label)
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity)
            .frame(height: 36)
            .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 9))
        }
    }
}

/// Empty-state block for the list screens.
struct GBEmptyState: View {
    let icon: String
    let title: String
    var message: String? = nil

    var body: some View {
        VStack(spacing: GB.Space.s) {
            Image(systemName: icon)
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(GB.textFaint)
            Text(title)
                .font(GB.Font_.rowTitle)
                .foregroundStyle(GB.textDim)
            if let message {
                Text(message)
                    .font(GB.Font_.caption)
                    .foregroundStyle(GB.textFaint)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 36)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }
}

/// Hairline separator matching the card borders.
struct GBDivider: View {
    var body: some View { Rectangle().fill(GB.border).frame(height: 1) }
}
