import SwiftUI

/// Custom-designed settings screen: dark surface cards with tinted section
/// icons, chip pickers and cyan accents — replaces the stock Form look.
struct SettingsView: View {
    @ObservedObject var viewModel: BrowserViewModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appTheme") private var appTheme = 0

    @State private var clearCookies = true
    @State private var clearCache = true
    @State private var clearHistoryToo = false
    @State private var showCardEditor = false
    @State private var dataCleared = false

    private let accent = Color.cyan

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.05, green: 0.07, blue: 0.10),
                                    Color(red: 0.02, green: 0.03, blue: 0.05)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 14) {
                    header
                    browserModeCard
                    controlsCard
                    searchCard
                    appearanceCard
                    autofillCard
                    securityCard
                    permissionsCard
                    dataCard
                    backgroundCard
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 30)
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showCardEditor) { cardEditor }
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            Text("設定")
                .font(.system(size: 28, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
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
        .padding(.bottom, 4)
    }

    // MARK: - Cards

    private var browserModeCard: some View {
        card(icon: "gamecontroller.fill", tint: .cyan, title: "ブラウザモード") {
            HStack(spacing: 10) {
                modeButton(label: "スマホ", icon: "hand.tap.fill", selected: !viewModel.pcMode) {
                    viewModel.pcMode = false
                }
                modeButton(label: "PC", icon: "cursorarrow", selected: viewModel.pcMode) {
                    viewModel.pcMode = true
                }
            }
            Text(viewModel.pcMode
                 ? "仮想マウス・キーボード付きのゲーミングブラウザ"
                 : "通常のタッチ操作ブラウザ(下部バー非表示)")
                .font(.system(size: 12))
                .foregroundStyle(.white.opacity(0.5))
        }
    }

    private var controlsCard: some View {
        card(icon: "cursorarrow.motionlines", tint: .blue, title: "操作") {
            labeled("操作スキーム") {
                chips(BrowserViewModel.ControlScheme.allCases.map { ($0, $0.label) },
                      selection: $viewModel.controlScheme)
            }
            Text(viewModel.controlScheme == .quick
                 ? "タップ後すぐ押し込みでドラッグ / フリックで慣性 / 長押しで右クリック"
                 : "長押しでドラッグ / 2本指タップで右クリック")
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.45))

            divider
            sliderRow(title: "カーソル感度",
                      value: $viewModel.cursorSensitivity,
                      range: 0.5...3.0, step: 0.1,
                      display: String(format: "%.1fx", viewModel.cursorSensitivity))
            sliderRow(title: "スクロール速度",
                      value: $viewModel.scrollSpeed,
                      range: 300...1500, step: 50,
                      display: "\(Int(viewModel.scrollSpeed))")

            divider
            toggleRow("触覚フィードバック", isOn: $viewModel.hapticsEnabled)
            toggleRow("ジョイスティック: 矢印キーを送信", isOn: $viewModel.joystickUsesArrows)
            buttonRow("ジョイスティック位置をリセット") {
                viewModel.resetJoystickPosition()
            }
        }
    }

    private var searchCard: some View {
        card(icon: "magnifyingglass", tint: .orange, title: "検索とタブ") {
            labeled("検索エンジン") {
                chips(BrowserViewModel.SearchEngine.allCases.map { ($0, $0.label) },
                      selection: $viewModel.searchEngine)
            }
            labeled("新しいタブ") {
                chips(BrowserViewModel.NewTabPage.allCases.map { ($0, $0.label) },
                      selection: $viewModel.newTabPage)
            }
            buttonRow("ホームに戻る") {
                viewModel.goHome()
                dismiss()
            }
        }
    }

    private var appearanceCard: some View {
        card(icon: "paintbrush.fill", tint: .purple, title: "外観") {
            chips([(0, "ダーク"), (1, "ライト"), (2, "システム")], selection: $appTheme)
        }
    }

    private var autofillCard: some View {
        card(icon: "key.fill", tint: .yellow, title: "自動入力") {
            toggleRow("パスワード・カードの自動入力", isOn: $viewModel.autofillEnabled)

            if !viewModel.credentials.isEmpty {
                divider
                ForEach(viewModel.credentials) { credential in
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(credential.username.isEmpty ? "(ユーザー名なし)" : credential.username)
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(.white)
                            Text(credential.domain)
                                .font(.system(size: 11))
                                .foregroundStyle(.white.opacity(0.5))
                        }
                        Spacer()
                        Button {
                            viewModel.credentials.removeAll { $0.id == credential.id }
                        } label: {
                            Image(systemName: "trash")
                                .font(.system(size: 13))
                                .foregroundStyle(.red.opacity(0.85))
                        }
                    }
                    .padding(.vertical, 3)
                }
            }

            divider
            HStack {
                Text("支払い方法")
                    .font(.system(size: 14))
                    .foregroundStyle(.white)
                Spacer()
                Button {
                    showCardEditor = true
                } label: {
                    Text(viewModel.paymentCard.isEmpty ? "追加" : viewModel.paymentCard.maskedNumber)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(accent)
                }
            }
        }
    }

    private var securityCard: some View {
        card(icon: "shield.fill", tint: .green, title: "セキュリティ") {
            toggleRow("広告ブロック", isOn: $viewModel.adBlockEnabled)
            if viewModel.adBlockEnabled {
                toggleRow("強力な広告ブロック(EasyList)", isOn: $viewModel.useFullAdList)
                    .padding(.leading, 12)
            }
            divider
            labeled("トラッキング防止") {
                chips(TrackerBlocker.Level.allCases.map { ($0, $0.label) },
                      selection: $viewModel.trackingLevel)
            }
            if viewModel.trackingLevel == .strict {
                Text("厳重: 一部サイトが動かなくなる場合があります")
                    .font(.system(size: 11))
                    .foregroundStyle(.orange.opacity(0.9))
            }
            divider
            toggleRow("Face IDでアプリをロック", isOn: $viewModel.appLockEnabled)
            toggleRow("詐欺Webサイトの警告", isOn: $viewModel.fraudWarning)
            toggleRow("HTTPSを優先", isOn: $viewModel.httpsOnly)
            toggleRow("ポップアップをブロック", isOn: $viewModel.blockPopups)
            toggleRow("JavaScriptを有効にする", isOn: $viewModel.javaScriptEnabled)
        }
    }

    private var permissionsCard: some View {
        card(icon: "lock.shield.fill", tint: .teal, title: "サイトの権限") {
            labeled("カメラ・マイク") {
                chips(BrowserViewModel.CapturePolicy.allCases.map { ($0, $0.label) },
                      selection: $viewModel.capturePolicy)
            }
            toggleRow("サイトからの通知を許可", isOn: $viewModel.webNotificationsEnabled)
            HStack(spacing: 10) {
                buttonRow("位置情報を許可") { viewModel.requestLocationPermission() }
                buttonRow("通知を許可") { viewModel.requestNotificationPermission() }
            }
        }
    }

    private var dataCard: some View {
        card(icon: "trash.fill", tint: .red, title: "閲覧データを削除") {
            toggleRow("Cookie・サイトデータ", isOn: $clearCookies)
            toggleRow("キャッシュ", isOn: $clearCache)
            toggleRow("履歴", isOn: $clearHistoryToo)
            Button {
                viewModel.clearData(cookies: clearCookies, cache: clearCache,
                                    history: clearHistoryToo)
                viewModel.hapticMedium()
                dataCleared = true
                Task {
                    try? await Task.sleep(for: .seconds(2))
                    dataCleared = false
                }
            } label: {
                Text(dataCleared ? "削除しました ✓" : "選択したデータを削除")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(dataCleared ? Color.green.opacity(0.7) : Color.red.opacity(0.8),
                                in: RoundedRectangle(cornerRadius: 10))
            }
            .disabled(!clearCookies && !clearCache && !clearHistoryToo)
        }
    }

    private var backgroundCard: some View {
        card(icon: "moon.zzz.fill", tint: .indigo, title: "バックグラウンド") {
            toggleRow("バックグラウンドで実行を継続", isOn: $viewModel.keepAliveInBackground)
            Text("無音のオーディオを再生し続けることで、アプリを閉じてもページが動き続けます。バッテリー消費が増えます。")
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.45))
        }
    }

    // MARK: - Card editor sheet

    private var cardEditor: some View {
        ZStack {
            Color(red: 0.05, green: 0.07, blue: 0.10).ignoresSafeArea()
            VStack(spacing: 14) {
                HStack {
                    Text("支払い方法")
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                    Spacer()
                    Button("完了") { showCardEditor = false }
                        .foregroundStyle(accent)
                }
                .padding(.top, 20)

                field("カード番号", text: $viewModel.paymentCard.number, keyboard: .numberPad)
                field("名義(ローマ字)", text: $viewModel.paymentCard.holder)
                HStack(spacing: 10) {
                    field("月(MM)", text: $viewModel.paymentCard.expMonth, keyboard: .numberPad)
                    field("年(YY)", text: $viewModel.paymentCard.expYear, keyboard: .numberPad)
                }

                Text("この端末のKeychainにのみ暗号化保存されます")
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.45))

                if !viewModel.paymentCard.isEmpty {
                    Button("カード情報を削除", role: .destructive) {
                        viewModel.paymentCard = PaymentCard()
                    }
                    .foregroundStyle(.red)
                }
                Spacer()
            }
            .padding(.horizontal, 16)
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium])
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
            RoundedRectangle(cornerRadius: 18)
                .stroke(Color.white.opacity(0.08), lineWidth: 1)
        )
    }

    private var divider: some View {
        Rectangle().fill(Color.white.opacity(0.08)).frame(height: 1)
    }

    private func toggleRow(_ title: String, isOn: Binding<Bool>) -> some View {
        Toggle(title, isOn: isOn)
            .font(.system(size: 14))
            .foregroundStyle(.white)
            .tint(accent)
    }

    private func buttonRow(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(accent)
                .frame(maxWidth: .infinity)
                .frame(height: 34)
                .background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 9))
        }
    }

    private func labeled<Content: View>(_ title: String,
                                        @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.white.opacity(0.55))
            content()
        }
    }

    /// Horizontal chip picker.
    private func chips<T: Hashable>(_ options: [(T, String)],
                                    selection: Binding<T>) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                ForEach(options, id: \.0) { value, label in
                    Button {
                        selection.wrappedValue = value
                        viewModel.hapticLight()
                    } label: {
                        Text(label)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(selection.wrappedValue == value ? .black : .white)
                            .padding(.horizontal, 13)
                            .frame(height: 31)
                            .background(
                                selection.wrappedValue == value
                                    ? accent : Color.white.opacity(0.1),
                                in: Capsule()
                            )
                    }
                }
            }
        }
    }

    private func modeButton(label: String, icon: String, selected: Bool,
                            action: @escaping () -> Void) -> some View {
        Button {
            action()
            viewModel.hapticLight()
        } label: {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 20, weight: .medium))
                Text(label)
                    .font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(selected ? .black : .white)
            .frame(maxWidth: .infinity)
            .frame(height: 66)
            .background(selected ? accent : Color.white.opacity(0.08),
                        in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private func field(_ placeholder: String, text: Binding<String>,
                       keyboard: UIKeyboardType = .default) -> some View {
        TextField(placeholder, text: text)
            .keyboardType(keyboard)
            .font(.system(size: 15))
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .frame(height: 44)
            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
    }
}

private func sliderRow(title: String, value: Binding<Double>,
                       range: ClosedRange<Double>, step: Double,
                       display: String) -> some View {
    VStack(alignment: .leading, spacing: 4) {
        HStack {
            Text(title)
                .font(.system(size: 14))
                .foregroundStyle(.white)
            Spacer()
            Text(display)
                .font(.system(size: 13, weight: .medium, design: .monospaced))
                .foregroundStyle(.cyan)
        }
        Slider(value: value, in: range, step: step)
            .tint(.cyan)
    }
}
