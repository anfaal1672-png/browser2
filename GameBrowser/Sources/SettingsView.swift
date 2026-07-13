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
            Text(loc("設定", "Settings"))
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
        card(icon: "gamecontroller.fill", tint: .cyan, title: loc("ブラウザモード", "Browser mode")) {
            HStack(spacing: 10) {
                modeButton(label: loc("スマホ", "Phone"), icon: "hand.tap.fill", selected: !viewModel.pcMode) {
                    viewModel.pcMode = false
                }
                modeButton(label: "PC", icon: "cursorarrow", selected: viewModel.pcMode) {
                    viewModel.pcMode = true
                }
            }
            Text(viewModel.pcMode
                 ? loc("仮想マウス・キーボード付きのゲーミングブラウザ", "Gaming browser with virtual mouse & keyboard")
                 : loc("通常のタッチ操作ブラウザ(下部バー非表示)", "Regular touch browser (no bottom bar)"))
                .font(.system(size: 12))
                .foregroundStyle(.white.opacity(0.5))
        }
    }

    private var controlsCard: some View {
        card(icon: "cursorarrow.motionlines", tint: .blue, title: loc("操作", "Controls")) {
            labeled(loc("操作スキーム", "Control scheme")) {
                chips(BrowserViewModel.ControlScheme.allCases.map { ($0, $0.label) },
                      selection: $viewModel.controlScheme)
            }
            Text(viewModel.controlScheme == .quick
                 ? loc("タップ後すぐ押し込みでドラッグ / フリックで慣性 / 長押しで右クリック", "Tap-and-press to drag / flick for momentum / long-press to right-click")
                 : loc("長押しでドラッグ / 2本指タップで右クリック", "Long-press to drag / two-finger tap to right-click"))
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.45))

            divider
            sliderRow(title: loc("カーソル感度", "Cursor sensitivity"),
                      value: $viewModel.cursorSensitivity,
                      range: 0.5...3.0, step: 0.1,
                      display: String(format: "%.1fx", viewModel.cursorSensitivity))
            sliderRow(title: loc("スクロール速度", "Scroll speed"),
                      value: $viewModel.scrollSpeed,
                      range: 300...1500, step: 50,
                      display: "\(Int(viewModel.scrollSpeed))")

            divider
            toggleRow(loc("触覚フィードバック", "Haptic feedback"), isOn: $viewModel.hapticsEnabled)
            toggleRow(loc("ジョイスティック: 矢印キーを送信", "Joystick sends arrow keys"), isOn: $viewModel.joystickUsesArrows)
            buttonRow(loc("ジョイスティック位置をリセット", "Reset joystick position")) {
                viewModel.resetJoystickPosition()
            }
        }
    }

    private var searchCard: some View {
        card(icon: "magnifyingglass", tint: .orange, title: loc("検索とタブ", "Search & tabs")) {
            labeled(loc("検索エンジン", "Search engine")) {
                chips(BrowserViewModel.SearchEngine.allCases.map { ($0, $0.label) },
                      selection: $viewModel.searchEngine)
            }
            labeled(loc("新しいタブ", "New tab")) {
                chips(BrowserViewModel.NewTabPage.allCases.map { ($0, $0.label) },
                      selection: $viewModel.newTabPage)
            }
            buttonRow(loc("ホームに戻る", "Go home")) {
                viewModel.goHome()
                dismiss()
            }
        }
    }

    private var appearanceCard: some View {
        card(icon: "paintbrush.fill", tint: .purple, title: loc("外観", "Appearance")) {
            labeled(loc("テーマ", "Theme")) {
                chips([(0, loc("ダーク", "Dark")), (1, loc("ライト", "Light")), (2, loc("システム", "System"))],
                      selection: $appTheme)
            }
            labeled(loc("言語", "Language")) {
                chips([(0, loc("システム", "System")), (1, "日本語"), (2, "English")],
                      selection: $viewModel.appLanguage)
            }
            labeled(loc("ツールバーの位置", "Toolbar position")) {
                chips([(false, loc("上", "Top")), (true, loc("下", "Bottom"))],
                      selection: $viewModel.toolbarOnBottom)
            }
            divider
            toggleRow(loc("PC版サイトを表示", "Show desktop sites"), isOn: $viewModel.desktopMode)
            toggleRow(loc("スクロールボタンを表示", "Show scroll buttons"), isOn: $viewModel.showScrollButtons)
        }
    }

    private var autofillCard: some View {
        card(icon: "key.fill", tint: .yellow, title: loc("自動入力", "Autofill")) {
            toggleRow(loc("パスワード・カードの自動入力", "Autofill passwords & cards"), isOn: $viewModel.autofillEnabled)

            if !viewModel.credentials.isEmpty {
                divider
                ForEach(viewModel.credentials) { credential in
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(credential.username.isEmpty ? loc("(ユーザー名なし)", "(no username)") : credential.username)
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
                Text(loc("支払い方法", "Payment method"))
                    .font(.system(size: 14))
                    .foregroundStyle(.white)
                Spacer()
                Button {
                    showCardEditor = true
                } label: {
                    Text(viewModel.paymentCard.isEmpty ? loc("追加", "Add") : viewModel.paymentCard.maskedNumber)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(accent)
                }
            }
        }
    }

    private var securityCard: some View {
        card(icon: "shield.fill", tint: .green, title: loc("セキュリティ", "Security")) {
            toggleRow(loc("広告ブロック", "Ad blocking"), isOn: $viewModel.adBlockEnabled)
            if viewModel.adBlockEnabled {
                toggleRow(loc("強力な広告ブロック(EasyList)", "Strong ad blocking (EasyList)"), isOn: $viewModel.useFullAdList)
                    .padding(.leading, 12)
            }
            divider
            labeled(loc("トラッキング防止", "Tracking prevention")) {
                chips(TrackerBlocker.Level.allCases.map { ($0, $0.label) },
                      selection: $viewModel.trackingLevel)
            }
            if viewModel.trackingLevel == .strict {
                Text(loc("厳重: 一部サイトが動かなくなる場合があります", "Strict: some sites may break"))
                    .font(.system(size: 11))
                    .foregroundStyle(.orange.opacity(0.9))
            }
            divider
            toggleRow(loc("Face IDでアプリをロック", "Lock app with Face ID"), isOn: $viewModel.appLockEnabled)
            toggleRow(loc("詐欺Webサイトの警告", "Fraudulent site warning"), isOn: $viewModel.fraudWarning)
            toggleRow(loc("HTTPSを優先", "Prefer HTTPS"), isOn: $viewModel.httpsOnly)
            toggleRow(loc("ポップアップをブロック", "Block pop-ups"), isOn: $viewModel.blockPopups)
            toggleRow(loc("JavaScriptを有効にする", "Enable JavaScript"), isOn: $viewModel.javaScriptEnabled)
        }
    }

    private var permissionsCard: some View {
        card(icon: "lock.shield.fill", tint: .teal, title: loc("サイトの権限", "Site permissions")) {
            labeled(loc("カメラ・マイク", "Camera & microphone")) {
                chips(BrowserViewModel.CapturePolicy.allCases.map { ($0, $0.label) },
                      selection: $viewModel.capturePolicy)
            }
            toggleRow(loc("サイトからの通知を許可", "Allow site notifications"), isOn: $viewModel.webNotificationsEnabled)
            HStack(spacing: 10) {
                buttonRow(loc("位置情報を許可", "Allow location")) { viewModel.requestLocationPermission() }
                buttonRow(loc("通知を許可", "Allow notifications")) { viewModel.requestNotificationPermission() }
            }
        }
    }

    private var dataCard: some View {
        card(icon: "trash.fill", tint: .red, title: loc("閲覧データを削除", "Clear browsing data")) {
            toggleRow(loc("Cookie・サイトデータ", "Cookies & site data"), isOn: $clearCookies)
            toggleRow(loc("キャッシュ", "Cache"), isOn: $clearCache)
            toggleRow(loc("履歴", "History"), isOn: $clearHistoryToo)
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
                Text(dataCleared ? loc("削除しました ✓", "Cleared ✓") : loc("選択したデータを削除", "Clear selected data"))
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
        card(icon: "moon.zzz.fill", tint: .indigo, title: loc("バックグラウンド", "Background")) {
            toggleRow(loc("バックグラウンドで実行を継続", "Keep running in background"), isOn: $viewModel.keepAliveInBackground)
            Text(loc("無音のオーディオを再生し続けることで、アプリを閉じてもページが動き続けます。バッテリー消費が増えます。", "Plays silent audio so pages keep running when the app is closed. Uses more battery."))
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
                    Text(loc("支払い方法", "Payment method"))
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                    Spacer()
                    Button(loc("完了", "Done")) { showCardEditor = false }
                        .foregroundStyle(accent)
                }
                .padding(.top, 20)

                field(loc("カード番号", "Card number"), text: $viewModel.paymentCard.number, keyboard: .numberPad)
                field(loc("名義(ローマ字)", "Name on card"), text: $viewModel.paymentCard.holder)
                HStack(spacing: 10) {
                    field(loc("月(MM)", "Month (MM)"), text: $viewModel.paymentCard.expMonth, keyboard: .numberPad)
                    field(loc("年(YY)", "Year (YY)"), text: $viewModel.paymentCard.expYear, keyboard: .numberPad)
                }

                Text(loc("この端末のKeychainにのみ暗号化保存されます", "Stored encrypted only in this device Keychain"))
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.45))

                if !viewModel.paymentCard.isEmpty {
                    Button(loc("カード情報を削除", "Delete card"), role: .destructive) {
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
