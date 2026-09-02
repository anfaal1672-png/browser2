import SwiftUI

/// Manages control profiles: pick one, edit its layout, bind the physical
/// controller, and pin a profile to the site you're on so it comes back
/// automatically next time you open that game.
struct ControlProfilesView: View {
    @ObservedObject var viewModel: BrowserViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var renamingID: UUID?
    @State private var draftName = ""
    /// Short confirmation shown after copy/paste.
    @State private var note: String?

    private let accent = GB.accent

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.05, green: 0.07, blue: 0.10),
                                    Color(red: 0.02, green: 0.03, blue: 0.05)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 14) {
                    header
                    profilesCard
                    tuningCard
                    siteCard
                    gamepadCard
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 30)
            }
        }
        .preferredColorScheme(.dark)
        .alert(loc("プロファイル名", "Profile name"),
               isPresented: Binding(get: { renamingID != nil },
                                    set: { if !$0 { renamingID = nil } })) {
            TextField(loc("名前", "Name"), text: $draftName)
            Button(loc("保存", "Save")) {
                if let id = renamingID { viewModel.renameProfile(id, to: draftName) }
                renamingID = nil
            }
            Button(loc("キャンセル", "Cancel"), role: .cancel) { renamingID = nil }
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(loc("コントロール", "Controls"))
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text(loc("画面上のボタンを自由に配置できます",
                         "Place your own buttons anywhere on screen"))
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.5))
            }
            Spacer()
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white.opacity(0.8))
                    .padding(9)
                    .background(.white.opacity(0.1), in: Circle())
            }
        }
        .padding(.top, 20)
    }

    // MARK: - Profiles

    private var profilesCard: some View {
        card(icon: "square.stack.3d.up.fill", tint: GB.accent,
             title: loc("プロファイル", "Profiles")) {
            ForEach(viewModel.profiles) { profile in
                HStack(spacing: 10) {
                    Button {
                        viewModel.activateProfile(profile.id)
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: viewModel.activeProfileID == profile.id
                                  ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(viewModel.activeProfileID == profile.id
                                                 ? accent : Color.white.opacity(0.35))
                            VStack(alignment: .leading, spacing: 1) {
                                Text(profile.name)
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                                Text(loc("\(profile.buttons.count) ボタン",
                                         "\(profile.buttons.count) buttons"))
                                    .font(.system(size: 11))
                                    .foregroundStyle(.white.opacity(0.45))
                            }
                            Spacer()
                        }
                    }
                    Menu {
                        Button {
                            draftName = profile.name
                            renamingID = profile.id
                        } label: {
                            Label(loc("名前を変更", "Rename"), systemImage: "pencil")
                        }
                        Button {
                            viewModel.duplicateProfile(profile.id)
                        } label: {
                            Label(loc("複製", "Duplicate"), systemImage: "plus.square.on.square")
                        }
                        Button(role: .destructive) {
                            viewModel.deleteProfile(profile.id)
                        } label: {
                            Label(loc("削除", "Delete"), systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .foregroundStyle(.white.opacity(0.6))
                    }
                }
                .padding(.vertical, 4)
            }

            divider
            HStack(spacing: 10) {
                smallButton(loc("新規作成", "New"), icon: "plus") {
                    viewModel.createProfile()
                }
                smallButton(loc("プリセットを追加", "Add presets"), icon: "sparkles") {
                    viewModel.addPresetProfiles()
                }
            }
            Button {
                viewModel.padVisible = true
                viewModel.padEditing = true
                dismiss()
            } label: {
                Label(loc("レイアウトを編集", "Edit layout"), systemImage: "hand.draw.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.black)
                    .frame(maxWidth: .infinity)
                    .frame(height: 42)
                    .background(accent, in: RoundedRectangle(cornerRadius: 11))
            }
            .disabled(viewModel.activeProfile == nil)
            .opacity(viewModel.activeProfile == nil ? 0.4 : 1)
        }
    }

    // MARK: - Per-profile tuning

    private var tuningCard: some View {
        card(icon: "slider.horizontal.3", tint: .purple,
             title: loc("このプロファイル", "This profile")) {
            if let profile = viewModel.activeProfile {
                Toggle(isOn: Binding(
                    get: { profile.autoFocusGame },
                    set: { viewModel.setAutoFocusGame($0) }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(loc("開いたらゲームを全画面にする", "Fullscreen the game on open"))
                            .font(.system(size: 14))
                            .foregroundStyle(.white)
                        Text(loc("サイト割り当てと組み合わせると、開くだけで遊べる状態になります",
                                 "With a site assignment, opening the game is the whole setup"))
                            .font(.system(size: 11))
                            .foregroundStyle(.white.opacity(0.45))
                    }
                }
                .tint(accent)

                divider
                slider(loc("ボタンの濃さ", "Pad opacity"),
                       value: Binding(get: { profile.padOpacity },
                                      set: { viewModel.setPadOpacity($0) }),
                       range: 0.25...1, step: 0.05,
                       display: "\(Int(profile.padOpacity * 100))%")

                Toggle(isOn: Binding(
                    get: { profile.cursorSensitivity != nil },
                    set: { on in
                        viewModel.setProfileSensitivity(on ? viewModel.cursorSensitivity : nil)
                    }
                )) {
                    Text(loc("カーソル感度をこのゲーム用に上書き",
                             "Override cursor speed for this game"))
                        .font(.system(size: 14))
                        .foregroundStyle(.white)
                }
                .tint(accent)

                if let sensitivity = profile.cursorSensitivity {
                    slider(loc("カーソル感度", "Cursor speed"),
                           value: Binding(get: { sensitivity },
                                          set: { viewModel.setProfileSensitivity($0) }),
                           range: 0.5...4.0, step: 0.1,
                           display: String(format: "%.1fx", sensitivity))
                }

                divider
                smallButton(loc("この設定を初期値に戻す", "Reset these to defaults"),
                            icon: "arrow.counterclockwise") {
                    viewModel.resetProfileTuning()
                    transient(loc("初期値に戻しました", "Reset to defaults"))
                }
                HStack(spacing: 10) {
                    smallButton(loc("コピー", "Copy"), icon: "doc.on.doc") {
                        transient(viewModel.copyActiveProfile()
                                  ? loc("コピーしました", "Copied")
                                  : loc("コピーできませんでした", "Couldn't copy"))
                    }
                    smallButton(loc("貼り付けて追加", "Paste"), icon: "doc.on.clipboard") {
                        transient(viewModel.pasteProfile()
                                  ? loc("読み込みました", "Imported")
                                  : loc("クリップボードにプロファイルがありません",
                                        "No profile on the clipboard"))
                    }
                }
                if let note {
                    Text(note)
                        .font(.system(size: 11))
                        .foregroundStyle(accent)
                }
            } else {
                Text(loc("プロファイルを選ぶと編集できます", "Select a profile to tune it"))
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.45))
            }
        }
    }

    private func transient(_ message: String) {
        note = message
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2))
            if note == message { note = nil }
        }
    }

    private func slider(_ title: String, value: Binding<Double>,
                        range: ClosedRange<Double>, step: Double,
                        display: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                    .font(.system(size: 14))
                    .foregroundStyle(.white)
                Spacer()
                Text(display)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(accent)
            }
            Slider(value: value, in: range, step: step).tint(accent)
        }
    }

    // MARK: - Per-site

    private var siteCard: some View {
        card(icon: "globe", tint: .orange, title: loc("このサイト", "This site")) {
            if let host = viewModel.currentURL?.host {
                Text(host)
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.75))
                    .lineLimit(1)
                Toggle(isOn: Binding(
                    get: { viewModel.siteProfileID(for: host) == viewModel.activeProfileID
                           && viewModel.activeProfileID != nil },
                    set: { viewModel.assignCurrentProfileToSite($0) }
                )) {
                    Text(loc("このサイトを開いたら自動で適用",
                             "Apply automatically on this site"))
                        .font(.system(size: 14))
                        .foregroundStyle(.white)
                }
                .tint(accent)
                .disabled(viewModel.activeProfileID == nil)

                if let assigned = viewModel.siteProfileName(for: host) {
                    Text(loc("現在の割り当て: \(assigned)", "Currently assigned: \(assigned)"))
                        .font(.system(size: 11))
                        .foregroundStyle(.white.opacity(0.45))
                }
            } else {
                Text(loc("ページを開くとサイトごとの設定ができます",
                         "Open a page to pin a profile to it"))
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.45))
            }
        }
    }

    // MARK: - Physical controller

    private var gamepadCard: some View {
        card(icon: "gamecontroller.fill", tint: .green,
             title: loc("ゲームパッドの割り当て", "Controller mapping")) {
            if viewModel.activeProfile == nil {
                Text(loc("プロファイルを選ぶと編集できます", "Select a profile to edit its mapping"))
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.45))
            } else {
                ForEach(GamepadSlot.allCases, id: \.self) { slot in
                    HStack {
                        Text(slot.label)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(.white)
                            .frame(width: 78, alignment: .leading)
                        Spacer()
                        Menu {
                            Button(loc("なし", "None")) {
                                viewModel.setGamepadBinding(slot, to: PadKeyName.none)
                            }
                            ForEach(KeyCatalog.groups) { group in
                                Menu(group.title) {
                                    ForEach(group.names, id: \.self) { name in
                                        Button(KeyCatalog.label(name)) {
                                            viewModel.setGamepadBinding(slot, to: name)
                                        }
                                    }
                                }
                            }
                        } label: {
                            Text(KeyCatalog.label(viewModel.gamepadBinding(slot)))
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(accent)
                                .padding(.horizontal, 12)
                                .frame(height: 32)
                                .background(accent.opacity(0.14), in: Capsule())
                        }
                    }
                }
                divider
                smallButton(loc("初期設定に戻す", "Reset to defaults"), icon: "arrow.uturn.backward") {
                    viewModel.resetGamepadMapping()
                }
            }
        }
    }

    // MARK: - Building blocks

    private func card<Content: View>(icon: String, tint: Color, title: String,
                                     @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                    .background(tint.opacity(0.85), in: RoundedRectangle(cornerRadius: 8))
                Text(title)
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
            }
            content()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 18))
        .overlay(
            RoundedRectangle(cornerRadius: 18).stroke(Color.white.opacity(0.08), lineWidth: 1)
        )
    }

    private var divider: some View {
        Rectangle().fill(Color.white.opacity(0.08)).frame(height: 1)
    }

    private func smallButton(_ title: String, icon: String,
                             action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(accent)
                .frame(maxWidth: .infinity)
                .frame(height: 34)
                .background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 9))
        }
    }
}
