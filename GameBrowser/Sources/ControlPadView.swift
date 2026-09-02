import SwiftUI

/// The user's own buttons, drawn over the game. Outside edit mode this layer
/// is transparent to everything except the buttons themselves, so the
/// trackpad underneath still gets every other touch.
struct ControlPadOverlay: View {
    @ObservedObject var viewModel: BrowserViewModel

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                ForEach(viewModel.activeProfile?.buttons ?? []) { button in
                    PadButtonView(viewModel: viewModel, button: button, area: geo.size)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            // Dimmed to taste while playing (they sit on top of the game),
            // always fully visible while arranging them.
            .opacity(viewModel.padEditing ? 1 : (viewModel.activeProfile?.padOpacity ?? 0.9))
        }
    }
}

/// One placed button: press/release in play mode, drag to move in edit mode.
struct PadButtonView: View {
    @ObservedObject var viewModel: BrowserViewModel
    let button: PadButton
    let area: CGSize

    @State private var pressed = false
    @State private var dragOrigin: CGPoint?

    private var editing: Bool { viewModel.padEditing }
    private var latched: Bool { viewModel.padLatched.contains(button.id) }
    private var selected: Bool { viewModel.selectedPadButton == button.id }
    private var active: Bool { pressed || latched }

    private var color: Color {
        let rgb = PadPalette.colors[PadPalette.clampIndex(button.tint)]
        return Color(red: rgb.0, green: rgb.1, blue: rgb.2)
    }

    var body: some View {
        cap
            .frame(width: button.size, height: button.size)
            .position(x: button.x * area.width, y: button.y * area.height)
            .gesture(editing ? moveGesture : pressGesture)
    }

    private var cap: some View {
        ZStack {
            Circle()
                .fill(active ? color.opacity(0.85) : Color.black.opacity(0.34))
                .overlay(
                    Circle().stroke(active ? Color.white.opacity(0.9) : color.opacity(0.75),
                                    lineWidth: latched ? 2.5 : 1.5)
                )
            Text(button.displayLabel)
                .font(.system(size: labelSize, weight: .bold, design: .rounded))
                .foregroundStyle(active ? Color.black : Color.white.opacity(0.95))
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .padding(.horizontal, 5)

            if button.turbo {
                Text("T")
                    .font(.system(size: 9, weight: .black, design: .rounded))
                    .foregroundStyle(.black)
                    .padding(3)
                    .background(color, in: Circle())
                    .offset(x: button.size * 0.32, y: -button.size * 0.32)
            }
            if editing {
                Circle()
                    .strokeBorder(selected ? Color.white : Color.white.opacity(0.35),
                                  style: StrokeStyle(lineWidth: selected ? 2 : 1, dash: [4, 3]))
                    .padding(-4)
            }
        }
        .scaleEffect(active ? 0.92 : 1)
        .animation(.easeOut(duration: 0.08), value: active)
        .shadow(color: .black.opacity(0.35), radius: 3, y: 1)
        .contentShape(Circle())
    }

    private var labelSize: CGFloat {
        let base = button.size * 0.34
        return min(max(base, 11), button.displayLabel.count > 3 ? 13 : 20)
    }

    // MARK: - Gestures

    /// Play mode: press sends the binding, release ends it. Both gestures are
    /// type-erased so the two can be swapped by the same `.gesture` modifier.
    private var pressGesture: AnyGesture<DragGesture.Value> {
        AnyGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard !pressed else { return }
                    pressed = true
                    viewModel.padPress(button)
                }
                .onEnded { _ in
                    pressed = false
                    viewModel.padRelease(button)
                }
        )
    }

    /// Edit mode: drag to place, tap to open the inspector.
    private var moveGesture: AnyGesture<DragGesture.Value> {
        AnyGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    if dragOrigin == nil {
                        dragOrigin = CGPoint(x: button.x, y: button.y)
                        viewModel.selectedPadButton = button.id
                    }
                    guard let origin = dragOrigin, area.width > 0, area.height > 0 else { return }
                    viewModel.movePadButton(
                        button.id,
                        to: CGPoint(x: origin.x + value.translation.width / area.width,
                                    y: origin.y + value.translation.height / area.height)
                    )
                }
                .onEnded { value in
                    dragOrigin = nil
                    // A tap (not a drag) opens the inspector for this button.
                    if abs(value.translation.width) < 6 && abs(value.translation.height) < 6 {
                        viewModel.selectedPadButton = button.id
                        viewModel.showPadInspector = true
                    }
                }
        )
    }
}

/// Floating bar shown while arranging the layout.
struct PadEditBar: View {
    @ObservedObject var viewModel: BrowserViewModel

    var body: some View {
        HStack(spacing: 10) {
            Button {
                viewModel.addPadButton()
            } label: {
                Label(loc("ボタン追加", "Add button"), systemImage: "plus.circle.fill")
                    .font(.system(size: 13, weight: .semibold))
            }

            Divider().frame(height: 18)

            Button {
                viewModel.showProfiles = true
            } label: {
                Label(viewModel.activeProfile?.name ?? loc("プロファイル", "Profiles"),
                      systemImage: "square.stack.3d.up.fill")
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
            }

            Spacer(minLength: 0)

            Button {
                viewModel.padEditing = false
                viewModel.selectedPadButton = nil
                viewModel.hapticMedium()
            } label: {
                Text(loc("完了", "Done"))
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 14)
                    .frame(height: 30)
                    .background(GB.accent, in: Capsule())
            }
        }
        .tint(GB.accent)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
        .padding(.horizontal, 10)
    }
}

/// Property sheet for the selected button.
struct PadButtonInspector: View {
    @ObservedObject var viewModel: BrowserViewModel
    @Environment(\.dismiss) private var dismiss

    private var button: PadButton? {
        guard let id = viewModel.selectedPadButton else { return nil }
        return viewModel.activeProfile?.buttons.first { $0.id == id }
    }

    var body: some View {
        ZStack {
            Color(red: 0.05, green: 0.07, blue: 0.10).ignoresSafeArea()
            if let button {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        header(button)
                        bindingSection(button)
                        appearanceSection(button)
                        behaviourSection(button)
                        deleteButton(button)
                    }
                    .padding(16)
                }
            } else {
                Text(loc("ボタンが選択されていません", "No button selected"))
                    .foregroundStyle(.secondary)
            }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
    }

    private func header(_ button: PadButton) -> some View {
        HStack {
            Text(loc("ボタンの設定", "Button settings"))
                .font(.system(size: 22, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
            Spacer()
            Button(loc("完了", "Done")) { dismiss() }
                .foregroundStyle(GB.accent)
        }
    }

    private func bindingSection(_ button: PadButton) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle(loc("送信するキー", "Sends"))
            // Current binding, as removable chips: several keys held together
            // make a combo (Shift+W = sprint).
            HStack(spacing: 6) {
                ForEach(currentNames(button), id: \.self) { name in
                    Button {
                        viewModel.removeBinding(name, from: button.id)
                    } label: {
                        HStack(spacing: 4) {
                            Text(KeyCatalog.label(name))
                            Image(systemName: "xmark.circle.fill").font(.system(size: 11))
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.black)
                        .padding(.horizontal, 10)
                        .frame(height: 30)
                        .background(GB.accent, in: Capsule())
                    }
                }
                if currentNames(button).isEmpty {
                    Text(loc("未設定 — 下から選んでください", "Nothing bound — pick one below"))
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.5))
                }
            }

            ForEach(KeyCatalog.groups) { group in
                Text(group.title)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.45))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(group.names, id: \.self) { name in
                            Button {
                                viewModel.addBinding(name, to: button.id)
                            } label: {
                                Text(KeyCatalog.shortLabel(name))
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 11)
                                    .frame(height: 30)
                                    .background(Color.white.opacity(0.10), in: Capsule())
                            }
                        }
                    }
                }
            }
        }
    }

    private func currentNames(_ button: PadButton) -> [String] {
        if let mouse = button.mouseButton {
            return [mouse == 2 ? PadKeyName.rightClick : PadKeyName.leftClick]
        }
        return button.keys
    }

    private func appearanceSection(_ button: PadButton) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(loc("見た目", "Appearance"))
            HStack {
                Text(loc("ラベル", "Label"))
                    .font(.system(size: 14))
                    .foregroundStyle(.white)
                TextField(button.displayLabel, text: Binding(
                    get: { button.label },
                    set: { new in viewModel.updatePadButton(button.id) { $0.label = new } }
                ))
                .font(.system(size: 14))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .frame(height: 36)
                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 9))
            }

            HStack {
                Text(loc("大きさ", "Size"))
                    .font(.system(size: 14))
                    .foregroundStyle(.white)
                Slider(
                    value: Binding(
                        get: { button.size },
                        set: { new in viewModel.updatePadButton(button.id) { $0.size = new } }
                    ),
                    in: 36...110, step: 2
                )
                .tint(GB.accent)
                Text("\(Int(button.size))")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(GB.accent)
            }

            HStack(spacing: 8) {
                ForEach(PadPalette.colors.indices, id: \.self) { index in
                    let rgb = PadPalette.colors[index]
                    Button {
                        viewModel.updatePadButton(button.id) { $0.tint = index }
                    } label: {
                        Circle()
                            .fill(Color(red: rgb.0, green: rgb.1, blue: rgb.2))
                            .frame(width: 30, height: 30)
                            .overlay(
                                Circle().stroke(.white,
                                                lineWidth: button.tint == index ? 2.5 : 0)
                            )
                    }
                }
            }
        }
    }

    private func behaviourSection(_ button: PadButton) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle(loc("動作", "Behaviour"))
            Toggle(loc("押しっぱなしで固定(タップで解除)", "Latch when tapped"), isOn: Binding(
                get: { button.sticky },
                set: { new in viewModel.updatePadButton(button.id) { $0.sticky = new } }
            ))
            .font(.system(size: 14))
            .foregroundStyle(.white)
            .tint(GB.accent)

            Toggle(loc("連打(ターボ)", "Turbo (auto-repeat)"), isOn: Binding(
                get: { button.turbo },
                set: { new in viewModel.updatePadButton(button.id) { $0.turbo = new } }
            ))
            .font(.system(size: 14))
            .foregroundStyle(.white)
            .tint(GB.accent)
        }
    }

    private func deleteButton(_ button: PadButton) -> some View {
        Button(role: .destructive) {
            viewModel.deletePadButton(button.id)
            dismiss()
        } label: {
            Text(loc("このボタンを削除", "Delete this button"))
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 42)
                .background(Color.red.opacity(0.8), in: RoundedRectangle(cornerRadius: 11))
        }
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(.white.opacity(0.6))
    }
}
