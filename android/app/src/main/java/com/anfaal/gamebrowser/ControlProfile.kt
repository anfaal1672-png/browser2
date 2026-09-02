package com.anfaal.gamebrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Custom on-screen controls, the Kotlin twin of ControlProfile.swift: the user
 * places their own buttons over the game and binds each one to real
 * keyboard/mouse input, saves the layout as a profile, and (optionally) has it
 * applied automatically per site.
 *
 * This is what makes a keyboard-and-mouse game genuinely playable with two
 * thumbs: the fixed WASD keyboard can't reach a game's own bindings, and every
 * game binds something different.
 */

/** One button in a layout. */
data class PadButton(
    val id: String = UUID.randomUUID().toString(),
    /**
     * Keys sent together while held, e.g. ["Shift", "w"] for sprint. Empty
     * when this button is a mouse button instead.
     */
    val keys: List<String> = emptyList(),
    /** 0 = left, 2 = right. null for a keyboard button. */
    val mouseButton: Int? = null,
    /** What the button shows; empty means "derive it from the binding". */
    val label: String = "",
    /**
     * Centre position as a fraction of the web area, so a layout survives
     * rotation and different devices.
     */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Diameter in dp. */
    val size: Float = 62f,
    /** Tap to latch held (auto-run, aim-down-sights), tap again to release. */
    val sticky: Boolean = false,
    /** Fire repeatedly while held, for games that want mashing. */
    val turbo: Boolean = false,
    /** Index into [PadPalette.colors]. */
    val tint: Int = 0,
) {
    val displayLabel: String
        get() = when {
            label.isNotEmpty() -> label
            mouseButton != null -> if (mouseButton == 2) "R" else "L"
            else -> keys.joinToString("+") { KeyCatalog.shortLabel(it) }
        }

    /** A binding must do something; an empty one is dropped on save. */
    val isEmpty: Boolean get() = keys.isEmpty() && mouseButton == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("keys", JSONArray(keys))
        if (mouseButton != null) put("mouseButton", mouseButton)
        put("label", label)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("size", size.toDouble())
        put("sticky", sticky)
        put("turbo", turbo)
        put("tint", tint)
    }

    companion object {
        /**
         * Every field falls back to a default rather than failing: layouts
         * saved by an older build have none of the keys added since, and
         * throwing on one would take every saved layout down with it.
         */
        fun fromJson(json: JSONObject): PadButton {
            val keysArray = json.optJSONArray("keys")
            val keys = buildList {
                for (i in 0 until (keysArray?.length() ?: 0)) {
                    keysArray?.optString(i)?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
            return PadButton(
                id = json.optString("id").ifEmpty { UUID.randomUUID().toString() },
                keys = keys,
                mouseButton = if (json.has("mouseButton") && !json.isNull("mouseButton")) {
                    json.optInt("mouseButton")
                } else {
                    null
                },
                label = json.optString("label"),
                x = json.optDouble("x", 0.5).toFloat(),
                y = json.optDouble("y", 0.5).toFloat(),
                size = json.optDouble("size", 62.0).toFloat(),
                sticky = json.optBoolean("sticky", false),
                turbo = json.optBoolean("turbo", false),
                tint = json.optInt("tint", 0),
            )
        }
    }
}

/**
 * A named layout: the buttons, the joystick setup and the controller mapping
 * that go together for one game (or one genre).
 */
data class ControlProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val buttons: List<PadButton> = emptyList(),
    /** Controller slot ([GamepadSlot.name]) -> key name, or "" for unbound. */
    val gamepadMap: Map<String, String> = emptyMap(),
    /** Show the analog stick automatically when this profile is applied. */
    val showJoystick: Boolean = false,
    /** Stick sends arrows instead of WASD. */
    val joystickArrows: Boolean = false,
    /**
     * Cursor speed for this game only; null uses the global setting. An FPS
     * wants a flick to cross the screen, a strategy game wants precision.
     */
    val cursorSensitivity: Float? = null,
    /** Pads are drawn over the game, so they can be dimmed out of the way. */
    val padOpacity: Float = 0.9f,
    /**
     * Blow the game up to fill the screen as soon as this profile applies -
     * with a per-site assignment, opening the game is the whole setup.
     */
    val autoFocusGame: Boolean = false,
) {
    fun gamepadKey(slot: GamepadSlot): String = gamepadMap[slot.name] ?: slot.defaultKey

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("buttons", JSONArray().also { array -> buttons.forEach { array.put(it.toJson()) } })
        put("gamepadMap", JSONObject().also { map -> gamepadMap.forEach { map.put(it.key, it.value) } })
        put("showJoystick", showJoystick)
        put("joystickArrows", joystickArrows)
        if (cursorSensitivity != null) put("cursorSensitivity", cursorSensitivity.toDouble())
        put("padOpacity", padOpacity.toDouble())
        put("autoFocusGame", autoFocusGame)
    }

    companion object {
        fun fromJson(json: JSONObject): ControlProfile {
            val buttonsArray = json.optJSONArray("buttons")
            val buttons = buildList {
                for (i in 0 until (buttonsArray?.length() ?: 0)) {
                    buttonsArray?.optJSONObject(i)?.let { add(PadButton.fromJson(it)) }
                }
            }
            val mapObject = json.optJSONObject("gamepadMap")
            val map = buildMap {
                mapObject?.keys()?.forEach { key -> put(key, mapObject.optString(key)) }
            }
            return ControlProfile(
                id = json.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name = json.optString("name").ifEmpty { loc("プロファイル", "Profile") },
                buttons = buttons,
                gamepadMap = map,
                showJoystick = json.optBoolean("showJoystick", false),
                joystickArrows = json.optBoolean("joystickArrows", false),
                cursorSensitivity = if (json.has("cursorSensitivity") &&
                    !json.isNull("cursorSensitivity")
                ) {
                    json.optDouble("cursorSensitivity").toFloat()
                } else {
                    null
                },
                padOpacity = json.optDouble("padOpacity", 0.9).toFloat(),
                autoFocusGame = json.optBoolean("autoFocusGame", false),
            )
        }

        /**
         * Ready-made layouts for the genres this app is used for. Positions are
         * in fractions of the web area, tuned for a thumb reaching in from the
         * bottom-right while the left thumb drives the stick.
         */
        fun presets(): List<ControlProfile> =
            listOf(fpsPreset(), actionPreset(), mmoPreset(), racingPreset())

        fun fpsPreset() = ControlProfile(
            name = loc("FPS(WASD+マウス)", "FPS (WASD + mouse)"),
            buttons = listOf(
                PadButton(mouseButton = 0, label = loc("撃つ", "Fire"),
                    x = 0.88f, y = 0.62f, size = 74f, turbo = true, tint = 4),
                PadButton(mouseButton = 2, label = loc("覗く", "ADS"),
                    x = 0.72f, y = 0.52f, size = 58f, sticky = true, tint = 1),
                PadButton(keys = listOf("Space"), label = loc("ジャンプ", "Jump"),
                    x = 0.88f, y = 0.84f, size = 62f, tint = 0),
                PadButton(keys = listOf("Shift"), label = loc("ダッシュ", "Sprint"),
                    x = 0.72f, y = 0.80f, size = 54f, sticky = true, tint = 2),
                PadButton(keys = listOf("c"), label = loc("しゃがむ", "Crouch"),
                    x = 0.58f, y = 0.88f, size = 48f, sticky = true, tint = 5),
                PadButton(keys = listOf("r"), label = loc("リロード", "Reload"),
                    x = 0.60f, y = 0.66f, size = 48f, tint = 3),
                PadButton(keys = listOf("e"), label = loc("拾う", "Use"),
                    x = 0.60f, y = 0.38f, size = 48f, tint = 0),
                PadButton(keys = listOf("1"), x = 0.80f, y = 0.16f, size = 44f, tint = 5),
                PadButton(keys = listOf("2"), x = 0.88f, y = 0.16f, size = 44f, tint = 5),
                PadButton(keys = listOf("3"), x = 0.96f, y = 0.16f, size = 44f, tint = 5),
            ),
            showJoystick = true,
        )

        fun actionPreset() = ControlProfile(
            name = loc("2Dアクション", "2D action"),
            buttons = listOf(
                PadButton(keys = listOf("z"), label = "Z", x = 0.86f, y = 0.80f, size = 68f, tint = 0),
                PadButton(keys = listOf("x"), label = "X", x = 0.70f, y = 0.72f, size = 68f, tint = 1),
                PadButton(keys = listOf("c"), label = "C", x = 0.86f, y = 0.58f, size = 58f, tint = 2),
                PadButton(keys = listOf("Space"), label = "␣", x = 0.70f, y = 0.90f, size = 58f, tint = 3),
                PadButton(keys = listOf("Shift"), label = "⇧", x = 0.58f, y = 0.82f, size = 48f,
                    sticky = true, tint = 5),
                PadButton(keys = listOf("Enter"), label = "⏎", x = 0.94f, y = 0.20f, size = 44f, tint = 5),
                PadButton(keys = listOf("Escape"), label = "ESC", x = 0.82f, y = 0.20f, size = 44f, tint = 5),
            ),
            showJoystick = true,
            joystickArrows = true,
        )

        fun mmoPreset(): ControlProfile {
            val buttons = buildList {
                // Skill bar 1-8 across the bottom, above the control bar.
                listOf("1", "2", "3", "4", "5", "6", "7", "8").forEachIndexed { i, name ->
                    add(
                        PadButton(
                            keys = listOf(name),
                            x = 0.30f + i * 0.085f, y = 0.90f, size = 44f,
                            tint = if (i < 4) 0 else 3,
                        ),
                    )
                }
                add(PadButton(mouseButton = 2, label = loc("右", "R"),
                    x = 0.92f, y = 0.72f, size = 58f, tint = 1))
                add(PadButton(keys = listOf("Tab"), label = "TAB", x = 0.92f, y = 0.56f, size = 48f, tint = 5))
                add(PadButton(keys = listOf("Escape"), label = "ESC", x = 0.92f, y = 0.20f, size = 44f, tint = 5))
            }
            return ControlProfile(
                name = loc("MMO(スキルバー)", "MMO (skill bar)"),
                buttons = buttons,
                showJoystick = true,
            )
        }

        fun racingPreset() = ControlProfile(
            name = loc("レース", "Racing"),
            buttons = listOf(
                PadButton(keys = listOf("ArrowUp"), label = loc("アクセル", "Gas"),
                    x = 0.88f, y = 0.78f, size = 80f, tint = 2),
                PadButton(keys = listOf("ArrowDown"), label = loc("ブレーキ", "Brake"),
                    x = 0.70f, y = 0.88f, size = 62f, tint = 4),
                PadButton(keys = listOf("ArrowLeft"), label = "◀", x = 0.10f, y = 0.82f, size = 74f, tint = 0),
                PadButton(keys = listOf("ArrowRight"), label = "▶", x = 0.30f, y = 0.82f, size = 74f, tint = 0),
                PadButton(keys = listOf("Space"), label = loc("ドリフト", "Drift"),
                    x = 0.70f, y = 0.62f, size = 56f, tint = 1),
                PadButton(keys = listOf("Shift"), label = loc("ブースト", "Boost"),
                    x = 0.88f, y = 0.56f, size = 56f, tint = 3),
            ),
        )
    }
}

/** The buttons and sticks of a physical controller that can be re-bound. */
enum class GamepadSlot {
    BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y,
    LEFT_SHOULDER, RIGHT_SHOULDER, MENU,
    LEFT_TRIGGER, RIGHT_TRIGGER;

    /** The mapping the app has always shipped, still the default. */
    val defaultKey: String
        get() = when (this) {
            BUTTON_A -> "Space"
            BUTTON_B -> "Shift"
            BUTTON_X -> "e"
            BUTTON_Y -> "q"
            LEFT_SHOULDER -> "r"
            RIGHT_SHOULDER -> "f"
            MENU -> "Escape"
            LEFT_TRIGGER -> PadKeyName.RIGHT_CLICK
            RIGHT_TRIGGER -> PadKeyName.LEFT_CLICK_HOLD
        }

    val label: String
        get() = when (this) {
            BUTTON_A -> "A"
            BUTTON_B -> "B"
            BUTTON_X -> "X"
            BUTTON_Y -> "Y"
            LEFT_SHOULDER -> "L1"
            RIGHT_SHOULDER -> "R1"
            MENU -> loc("メニュー", "Menu")
            LEFT_TRIGGER -> "L2"
            RIGHT_TRIGGER -> "R2"
        }
}

/** Pseudo key names for the mouse, so one picker can bind both. */
object PadKeyName {
    const val LEFT_CLICK = "@mouseL"
    const val RIGHT_CLICK = "@mouseR"

    /** Hold the left button down while the control is held (aim, drag). */
    const val LEFT_CLICK_HOLD = "@mouseHold"
    const val NONE = ""

    val mouseNames = listOf(LEFT_CLICK, RIGHT_CLICK, LEFT_CLICK_HOLD)
}

/** One section of the key picker. */
data class KeyGroup(val title: String, val names: List<String>)

/** Every key a pad button or a controller slot can be bound to. */
object KeyCatalog {

    val groups: List<KeyGroup>
        get() = listOf(
            KeyGroup(
                loc("移動", "Movement"),
                listOf("w", "a", "s", "d", "ArrowUp", "ArrowLeft", "ArrowDown", "ArrowRight"),
            ),
            KeyGroup(
                loc("よく使う", "Common"),
                listOf("Space", "Shift", "Control", "Alt", "Enter", "Escape", "Tab", "Backspace"),
            ),
            KeyGroup(
                loc("数字", "Numbers"),
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            ),
            KeyGroup(loc("文字", "Letters"), ('a'..'z').map { it.toString() }),
            KeyGroup(loc("ファンクション", "Function"), (1..12).map { "F$it" }),
            KeyGroup(loc("記号", "Symbols"), listOf("-", "=", "[", "]", ";", "'", ",", ".", "/")),
            KeyGroup(loc("マウス", "Mouse"), PadKeyName.mouseNames),
        )

    private val specials: Map<String, GbKey> = mapOf(
        "Space" to GbKey.space,
        "Enter" to GbKey.enter,
        "Escape" to GbKey.escape,
        "Shift" to GbKey.shift,
        "Control" to GbKey.ctrl,
        "Alt" to GbKey("Alt", "AltLeft", 18, "ALT"),
        "Tab" to GbKey.tab,
        "Backspace" to GbKey.backspace,
        "ArrowUp" to GbKey.arrowUp,
        "ArrowDown" to GbKey.arrowDown,
        "ArrowLeft" to GbKey.arrowLeft,
        "ArrowRight" to GbKey.arrowRight,
    )

    private val symbols: Map<String, Pair<String, Int>> = mapOf(
        "-" to ("Minus" to 189), "=" to ("Equal" to 187),
        "[" to ("BracketLeft" to 219), "]" to ("BracketRight" to 221),
        ";" to ("Semicolon" to 186), "'" to ("Quote" to 222),
        "," to ("Comma" to 188), "." to ("Period" to 190), "/" to ("Slash" to 191),
    )

    /** The event a name sends, or null for mouse pseudo-keys and unknown names. */
    fun key(name: String): GbKey? {
        specials[name]?.let { return it }
        symbols[name]?.let { (code, keyCode) -> return GbKey(name, code, keyCode, name) }
        if (name.length == 1) {
            val ch = name[0]
            if (ch.isLetter()) return GbKey.letter(name.lowercase())
            if (ch.isDigit()) return GbKey.digit(name)
        }
        if (name.startsWith("F")) {
            val n = name.drop(1).toIntOrNull()
            if (n != null && n in 1..12) return GbKey(name, name, 111 + n, name)
        }
        return null
    }

    /** Short text for a key cap. */
    fun shortLabel(name: String): String = when (name) {
        PadKeyName.LEFT_CLICK -> "L"
        PadKeyName.RIGHT_CLICK -> "R"
        PadKeyName.LEFT_CLICK_HOLD -> loc("押下", "Hold")
        "Space" -> "␣"
        "Enter" -> "⏎"
        "Escape" -> "ESC"
        "Shift" -> "⇧"
        "Control" -> "CTRL"
        "Alt" -> "ALT"
        "Tab" -> "⇥"
        "Backspace" -> "⌫"
        "ArrowUp" -> "▲"
        "ArrowDown" -> "▼"
        "ArrowLeft" -> "◀"
        "ArrowRight" -> "▶"
        PadKeyName.NONE -> "—"
        else -> if (name.length == 1) name.uppercase() else name
    }

    /** Longer text for pickers and lists. */
    fun label(name: String): String = when (name) {
        PadKeyName.LEFT_CLICK -> loc("左クリック", "Left click")
        PadKeyName.RIGHT_CLICK -> loc("右クリック", "Right click")
        PadKeyName.LEFT_CLICK_HOLD -> loc("左ボタン長押し", "Hold left button")
        PadKeyName.NONE -> loc("なし", "None")
        else -> shortLabel(name)
    }
}

/** Button colours, kept as indexes so profiles stay serialisable. */
object PadPalette {
    /** (r, g, b) in 0..1 - resolved to a Color in the view layer. */
    val colors: List<Triple<Float, Float, Float>> = listOf(
        Triple(0.22f, 0.83f, 0.96f),   // cyan
        Triple(0.98f, 0.55f, 0.25f),   // orange
        Triple(0.55f, 0.85f, 0.35f),   // green
        Triple(0.85f, 0.40f, 0.90f),   // magenta
        Triple(0.95f, 0.35f, 0.40f),   // red
        Triple(0.60f, 0.65f, 0.75f),   // grey
    )

    fun clampIndex(index: Int): Int = if (index in colors.indices) index else 0
}

/**
 * Profiles, the per-site assignments and the active selection, in
 * SharedPreferences. Mirrors ControlProfileStore on iOS.
 */
object ControlProfileStore {
    private const val PROFILES_KEY = "controlProfiles"
    private const val ASSIGNMENTS_KEY = "controlProfileSites"
    private const val ACTIVE_KEY = "controlProfileActive"

    fun loadProfiles(prefs: SharedPreferences): List<ControlProfile> {
        val json = prefs.getString(PROFILES_KEY, null)
            ?: return ControlProfile.presets()   // first run: ship the presets
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(ControlProfile.fromJson(it)) }
                }
            }
        } catch (e: Exception) {
            ControlProfile.presets()
        }
    }

    fun saveProfiles(prefs: SharedPreferences, profiles: List<ControlProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        prefs.edit().putString(PROFILES_KEY, array.toString()).apply()
    }

    /** host -> profile id. */
    fun loadAssignments(prefs: SharedPreferences): Map<String, String> {
        val json = prefs.getString(ASSIGNMENTS_KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { key -> put(key, obj.optString(key)) } }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveAssignments(prefs: SharedPreferences, assignments: Map<String, String>) {
        val obj = JSONObject()
        assignments.forEach { obj.put(it.key, it.value) }
        prefs.edit().putString(ASSIGNMENTS_KEY, obj.toString()).apply()
    }

    fun loadActive(prefs: SharedPreferences): String? = prefs.getString(ACTIVE_KEY, null)

    fun saveActive(prefs: SharedPreferences, id: String?) {
        prefs.edit().putString(ACTIVE_KEY, id).apply()
    }
}
