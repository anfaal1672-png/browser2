import Foundation

/// Custom on-screen controls: the user places their own buttons over the game
/// and binds each one to real keyboard/mouse input, saves the layout as a
/// profile, and (optionally) has it applied automatically per site.
///
/// This is what makes a keyboard-and-mouse game genuinely playable with two
/// thumbs: the fixed WASD keyboard can't reach a game's own bindings, and
/// every game binds something different.

/// One button in a layout.
struct PadButton: Codable, Identifiable, Equatable {
    var id = UUID()
    /// Keys sent together while held, e.g. ["Shift", "w"] for sprint.
    /// Empty when this button is a mouse button instead.
    var keys: [String] = []
    /// 0 = left, 2 = right. `nil` for a keyboard button.
    var mouseButton: Int? = nil
    /// What the button shows; empty means "derive it from the binding".
    var label: String = ""
    /// Centre position as a fraction of the web area, so a layout survives
    /// rotation and different devices.
    var x: Double = 0.5
    var y: Double = 0.5
    /// Diameter in points.
    var size: Double = 62
    /// Tap to latch held (auto-run, aim-down-sights), tap again to release.
    var sticky: Bool = false
    /// Fire repeatedly while held, for games that want mashing.
    var turbo: Bool = false
    /// Index into `PadPalette.colors`.
    var tint: Int = 0

    var displayLabel: String {
        if !label.isEmpty { return label }
        if let mouseButton { return mouseButton == 2 ? "R" : "L" }
        return keys.map(KeyCatalog.shortLabel).joined(separator: "+")
    }

    /// A binding must do something; an empty one is dropped on save.
    var isEmpty: Bool { keys.isEmpty && mouseButton == nil }
}

/// A named layout: the buttons, the joystick setup and the controller mapping
/// that go together for one game (or one genre).
struct ControlProfile: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    var buttons: [PadButton] = []
    /// Controller slot (`GamepadSlot.rawValue`) → key name, or `""` for unbound.
    var gamepadMap: [String: String] = [:]
    /// Show the analog stick automatically when this profile is applied.
    var showJoystick: Bool = false
    /// Stick sends arrows instead of WASD.
    var joystickArrows: Bool = false

    func gamepadKey(_ slot: GamepadSlot) -> String {
        gamepadMap[slot.rawValue] ?? slot.defaultKey
    }
}

/// The buttons and sticks of a physical controller that can be re-bound.
enum GamepadSlot: String, CaseIterable, Codable {
    case buttonA, buttonB, buttonX, buttonY
    case leftShoulder, rightShoulder, menu
    case leftTrigger, rightTrigger

    /// The mapping the app has always shipped, still the default.
    var defaultKey: String {
        switch self {
        case .buttonA: return "Space"
        case .buttonB: return "Shift"
        case .buttonX: return "e"
        case .buttonY: return "q"
        case .leftShoulder: return "r"
        case .rightShoulder: return "f"
        case .menu: return "Escape"
        case .leftTrigger: return PadKeyName.rightClick
        case .rightTrigger: return PadKeyName.leftClickHold
        }
    }

    var label: String {
        switch self {
        case .buttonA: return "A"
        case .buttonB: return "B"
        case .buttonX: return "X"
        case .buttonY: return "Y"
        case .leftShoulder: return "L1"
        case .rightShoulder: return "R1"
        case .menu: return loc("メニュー", "Menu")
        case .leftTrigger: return "L2"
        case .rightTrigger: return "R2"
        }
    }
}

/// Pseudo key names for the mouse, so one picker can bind both.
enum PadKeyName {
    static let leftClick = "@mouseL"
    static let rightClick = "@mouseR"
    /// Hold the left button down while the control is held (aim, drag).
    static let leftClickHold = "@mouseHold"
    static let none = ""

    static let mouseNames = [leftClick, rightClick, leftClickHold]
}

/// One section of the key picker.
struct KeyGroup: Identifiable {
    var id: String { title }
    let title: String
    let names: [String]
}

/// Every key a pad button or a controller slot can be bound to.
enum KeyCatalog {

    static var groups: [KeyGroup] {
        [
            KeyGroup(title: loc("移動", "Movement"),
                     names: ["w", "a", "s", "d",
                             "ArrowUp", "ArrowLeft", "ArrowDown", "ArrowRight"]),
            KeyGroup(title: loc("よく使う", "Common"),
                     names: ["Space", "Shift", "Control", "Alt",
                             "Enter", "Escape", "Tab", "Backspace"]),
            KeyGroup(title: loc("数字", "Numbers"),
                     names: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"]),
            KeyGroup(title: loc("文字", "Letters"),
                     names: "abcdefghijklmnopqrstuvwxyz".map(String.init)),
            KeyGroup(title: loc("ファンクション", "Function"),
                     names: (1...12).map { "F\($0)" }),
            KeyGroup(title: loc("記号", "Symbols"),
                     names: ["-", "=", "[", "]", ";", "'", ",", ".", "/"]),
            KeyGroup(title: loc("マウス", "Mouse"), names: PadKeyName.mouseNames),
        ]
    }

    private static let specials: [String: InputBridge.Key] = [
        "Space": InputBridge.space,
        "Enter": InputBridge.enter,
        "Escape": InputBridge.escape,
        "Shift": InputBridge.shift,
        "Control": InputBridge.ctrl,
        "Alt": InputBridge.alt,
        "Tab": InputBridge.tab,
        "Backspace": InputBridge.backspace,
        "ArrowUp": InputBridge.arrowUp,
        "ArrowDown": InputBridge.arrowDown,
        "ArrowLeft": InputBridge.arrowLeft,
        "ArrowRight": InputBridge.arrowRight,
    ]

    private static let symbols: [String: (code: String, keyCode: Int)] = [
        "-": ("Minus", 189), "=": ("Equal", 187),
        "[": ("BracketLeft", 219), "]": ("BracketRight", 221),
        ";": ("Semicolon", 186), "'": ("Quote", 222),
        ",": ("Comma", 188), ".": ("Period", 190), "/": ("Slash", 191),
    ]

    /// The event a name sends, or nil for mouse pseudo-keys and unknown names.
    static func key(_ name: String) -> InputBridge.Key? {
        if let special = specials[name] { return special }
        if let symbol = symbols[name] {
            return InputBridge.Key(name, symbol.code, symbol.keyCode, label: name)
        }
        if name.count == 1, let ch = name.first {
            if ch.isLetter { return InputBridge.letter(name.lowercased()) }
            if ch.isNumber { return InputBridge.digit(name) }
        }
        if name.hasPrefix("F"), let n = Int(name.dropFirst()), (1...12).contains(n) {
            return InputBridge.Key(name, name, 111 + n, label: name)
        }
        return nil
    }

    /// Short text for a key cap.
    static func shortLabel(_ name: String) -> String {
        switch name {
        case PadKeyName.leftClick: return "L"
        case PadKeyName.rightClick: return "R"
        case PadKeyName.leftClickHold: return loc("押下", "Hold")
        case "Space": return "␣"
        case "Enter": return "⏎"
        case "Escape": return "ESC"
        case "Shift": return "⇧"
        case "Control": return "CTRL"
        case "Alt": return "ALT"
        case "Tab": return "⇥"
        case "Backspace": return "⌫"
        case "ArrowUp": return "▲"
        case "ArrowDown": return "▼"
        case "ArrowLeft": return "◀"
        case "ArrowRight": return "▶"
        case PadKeyName.none: return "—"
        default: return name.count == 1 ? name.uppercased() : name
        }
    }

    /// Longer text for pickers and lists.
    static func label(_ name: String) -> String {
        switch name {
        case PadKeyName.leftClick: return loc("左クリック", "Left click")
        case PadKeyName.rightClick: return loc("右クリック", "Right click")
        case PadKeyName.leftClickHold: return loc("左ボタン長押し", "Hold left button")
        case PadKeyName.none: return loc("なし", "None")
        default: return shortLabel(name)
        }
    }
}

/// Button colours, kept as indexes so profiles stay Codable.
enum PadPalette {
    /// (r, g, b) in 0...1 — resolved to Color in the view layer.
    static let colors: [(Double, Double, Double)] = [
        (0.22, 0.83, 0.96),   // cyan
        (0.98, 0.55, 0.25),   // orange
        (0.55, 0.85, 0.35),   // green
        (0.85, 0.40, 0.90),   // magenta
        (0.95, 0.35, 0.40),   // red
        (0.60, 0.65, 0.75),   // grey
    ]

    static func clampIndex(_ index: Int) -> Int {
        colors.indices.contains(index) ? index : 0
    }
}

// MARK: - Presets

extension ControlProfile {

    /// Ready-made layouts for the genres this app is used for. Positions are
    /// in fractions of the web area, tuned for a thumb reaching in from the
    /// bottom-right while the left thumb drives the stick.
    static func presets() -> [ControlProfile] {
        [fpsPreset(), actionPreset(), mmoPreset(), racingPreset()]
    }

    static func fpsPreset() -> ControlProfile {
        ControlProfile(
            name: loc("FPS(WASD+マウス)", "FPS (WASD + mouse)"),
            buttons: [
                PadButton(keys: [], mouseButton: 0, label: loc("撃つ", "Fire"),
                          x: 0.88, y: 0.62, size: 74, turbo: true, tint: 4),
                PadButton(keys: [], mouseButton: 2, label: loc("覗く", "ADS"),
                          x: 0.72, y: 0.52, size: 58, sticky: true, tint: 1),
                PadButton(keys: ["Space"], label: loc("ジャンプ", "Jump"),
                          x: 0.88, y: 0.84, size: 62, tint: 0),
                PadButton(keys: ["Shift"], label: loc("ダッシュ", "Sprint"),
                          x: 0.72, y: 0.80, size: 54, sticky: true, tint: 2),
                PadButton(keys: ["c"], label: loc("しゃがむ", "Crouch"),
                          x: 0.58, y: 0.88, size: 48, sticky: true, tint: 5),
                PadButton(keys: ["r"], label: loc("リロード", "Reload"),
                          x: 0.60, y: 0.66, size: 48, tint: 3),
                PadButton(keys: ["e"], label: loc("拾う", "Use"),
                          x: 0.60, y: 0.38, size: 48, tint: 0),
                PadButton(keys: ["1"], x: 0.80, y: 0.16, size: 44, tint: 5),
                PadButton(keys: ["2"], x: 0.88, y: 0.16, size: 44, tint: 5),
                PadButton(keys: ["3"], x: 0.96, y: 0.16, size: 44, tint: 5),
            ],
            showJoystick: true
        )
    }

    static func actionPreset() -> ControlProfile {
        ControlProfile(
            name: loc("2Dアクション", "2D action"),
            buttons: [
                PadButton(keys: ["z"], label: "Z", x: 0.86, y: 0.80, size: 68, tint: 0),
                PadButton(keys: ["x"], label: "X", x: 0.70, y: 0.72, size: 68, tint: 1),
                PadButton(keys: ["c"], label: "C", x: 0.86, y: 0.58, size: 58, tint: 2),
                PadButton(keys: ["Space"], label: "␣", x: 0.70, y: 0.90, size: 58, tint: 3),
                PadButton(keys: ["Shift"], label: "⇧", x: 0.58, y: 0.82, size: 48,
                          sticky: true, tint: 5),
                PadButton(keys: ["Enter"], label: "⏎", x: 0.94, y: 0.20, size: 44, tint: 5),
                PadButton(keys: ["Escape"], label: "ESC", x: 0.82, y: 0.20, size: 44, tint: 5),
            ],
            showJoystick: true,
            joystickArrows: true
        )
    }

    static func mmoPreset() -> ControlProfile {
        var buttons: [PadButton] = []
        // Skill bar 1-8 across the bottom, above the control bar.
        for (i, name) in ["1", "2", "3", "4", "5", "6", "7", "8"].enumerated() {
            buttons.append(PadButton(
                keys: [name],
                x: 0.30 + Double(i) * 0.085, y: 0.90, size: 44,
                tint: i < 4 ? 0 : 3
            ))
        }
        buttons.append(PadButton(keys: [], mouseButton: 2, label: loc("右", "R"),
                                 x: 0.92, y: 0.72, size: 58, tint: 1))
        buttons.append(PadButton(keys: ["Tab"], label: "TAB", x: 0.92, y: 0.56, size: 48, tint: 5))
        buttons.append(PadButton(keys: ["Escape"], label: "ESC", x: 0.92, y: 0.20, size: 44, tint: 5))
        return ControlProfile(name: loc("MMO(スキルバー)", "MMO (skill bar)"),
                              buttons: buttons, showJoystick: true)
    }

    static func racingPreset() -> ControlProfile {
        ControlProfile(
            name: loc("レース", "Racing"),
            buttons: [
                PadButton(keys: ["ArrowUp"], label: loc("アクセル", "Gas"),
                          x: 0.88, y: 0.78, size: 80, sticky: false, tint: 2),
                PadButton(keys: ["ArrowDown"], label: loc("ブレーキ", "Brake"),
                          x: 0.70, y: 0.88, size: 62, tint: 4),
                PadButton(keys: ["ArrowLeft"], label: "◀", x: 0.10, y: 0.82, size: 74, tint: 0),
                PadButton(keys: ["ArrowRight"], label: "▶", x: 0.30, y: 0.82, size: 74, tint: 0),
                PadButton(keys: ["Space"], label: loc("ドリフト", "Drift"),
                          x: 0.70, y: 0.62, size: 56, tint: 1),
                PadButton(keys: ["Shift"], label: loc("ブースト", "Boost"),
                          x: 0.88, y: 0.56, size: 56, tint: 3),
            ]
        )
    }
}

// MARK: - Persistence

/// Profiles, the per-site assignments and the active selection, in UserDefaults.
enum ControlProfileStore {
    private static let profilesKey = "controlProfiles"
    private static let assignmentsKey = "controlProfileSites"
    private static let activeKey = "controlProfileActive"

    static func loadProfiles() -> [ControlProfile] {
        guard let data = UserDefaults.standard.data(forKey: profilesKey),
              let profiles = try? JSONDecoder().decode([ControlProfile].self, from: data)
        else { return ControlProfile.presets() }   // first run: ship the presets
        return profiles
    }

    static func saveProfiles(_ profiles: [ControlProfile]) {
        if let data = try? JSONEncoder().encode(profiles) {
            UserDefaults.standard.set(data, forKey: profilesKey)
        }
    }

    /// host → profile id.
    static func loadAssignments() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: assignmentsKey) as? [String: String] ?? [:]
    }

    static func saveAssignments(_ assignments: [String: String]) {
        UserDefaults.standard.set(assignments, forKey: assignmentsKey)
    }

    static func loadActive() -> UUID? {
        guard let string = UserDefaults.standard.string(forKey: activeKey) else { return nil }
        return UUID(uuidString: string)
    }

    static func saveActive(_ id: UUID?) {
        UserDefaults.standard.set(id?.uuidString, forKey: activeKey)
    }
}
