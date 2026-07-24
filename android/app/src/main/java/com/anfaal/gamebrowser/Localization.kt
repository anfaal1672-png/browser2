package com.anfaal.gamebrowser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * Minimal in-app localization (Japanese / English), ported from
 * GameBrowser/Sources/Localization.swift's global `loc(ja, en)` + `enum L`.
 *
 * Swift reads/writes the language setting straight out of UserDefaults
 * (`UserDefaults.standard.integer(forKey: "appLanguage")`), so it's readable
 * without any setup. Android has no such ambient global store — SharedPreferences
 * needs a [Context] first — so [init] must be called once at process startup
 * (e.g. from a custom `Application.onCreate()`, or as the very first line of
 * `MainActivity.onCreate()`, before `setContent {}`) so the persisted value can
 * be loaded. See this port's "INTEGRATION STEPS" notes for exactly where to
 * call it — this file is not allowed to add that call itself, since MainActivity.kt
 * is an existing file this change must not edit.
 *
 * Until [init] runs, [languageSetting] simply defaults to 0 ("system") and any
 * writes are kept in memory only for that run (never silently thrown away, but
 * not persisted either) — the same graceful-degradation shape as reading a
 * UserDefaults key that was never set.
 */
object Localization {

    /** SharedPreferences file every plain (non-encrypted) app setting should share — see INTEGRATION STEPS. */
    private const val PREFS_NAME = "gamebrowser_settings"

    /** Same key name Swift's `UserDefaults.standard...forKey: "appLanguage"` uses. */
    private const val KEY_APP_LANGUAGE = "appLanguage"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 0 = follow system language, 1 = force Japanese, 2 = force English —
     * identical encoding to Swift's `L.setting` / `BrowserViewModel.appLanguage`.
     *
     * Backed by Compose's snapshot-state system (like the `var x by
     * mutableStateOf(...)` properties on BrowserViewModel), so any composable
     * that calls [loc] while composing automatically recomposes the instant
     * the user changes the language in Settings — no restart, matching the
     * Swift version's "strings are picked at call time" behavior.
     */
    var languageSetting: Int
        get() = languageSettingState.value
        set(value) {
            languageSettingState.value = value
            prefs?.edit()?.putInt(KEY_APP_LANGUAGE, value)?.apply()
        }

    private val languageSettingState = mutableStateOf(0)

    /**
     * Call once at process startup, before any composable calls [loc] or reads
     * [languageSetting]. Safe to call more than once (subsequent calls no-op).
     */
    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        languageSettingState.value = sp.getInt(KEY_APP_LANGUAGE, 0)
    }

    /** True if the current app language resolves to Japanese. */
    val isJapanese: Boolean
        get() = when (languageSetting) {
            1 -> true
            2 -> false
            else -> Locale.getDefault().language.startsWith("ja")
        }

    /** Target language code for the page-translation feature (mirrors `L.translationTarget`). */
    val translationTarget: String
        get() = if (isJapanese) "ja" else "en"
}

/** Pick the string matching the current app language. Direct port of Swift's top-level `loc(_:_:)`. */
fun loc(ja: String, en: String): String = if (Localization.isJapanese) ja else en
