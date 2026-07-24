package com.anfaal.gamebrowser

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.reflect.KProperty

/**
 * Small SharedPreferences-backed Compose-state property delegates, so
 * BrowserViewModel's settings can read `by PersistedBoolean(prefs, "key", default)`
 * the same way its other state uses `by mutableStateOf(...)`, except every
 * write also persists — mirroring each setting's `didSet { UserDefaults... }`
 * in BrowserViewModel.swift.
 */
class PersistedBoolean(private val prefs: SharedPreferences, private val key: String, default: Boolean) {
    private var state by mutableStateOf(prefs.getBoolean(key, default))
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = state
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        state = value
        prefs.edit().putBoolean(key, value).apply()
    }
}

class PersistedInt(private val prefs: SharedPreferences, private val key: String, default: Int) {
    private var state by mutableStateOf(prefs.getInt(key, default))
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = state
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        state = value
        prefs.edit().putInt(key, value).apply()
    }
}

class PersistedFloat(private val prefs: SharedPreferences, private val key: String, default: Float) {
    private var state by mutableStateOf(prefs.getFloat(key, default))
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Float = state
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
        state = value
        prefs.edit().putFloat(key, value).apply()
    }
}

/** Persists an enum by ordinal, matching the Int-rawValue encoding the Swift settings use. */
class PersistedEnum<T : Enum<T>>(
    prefs: SharedPreferences,
    key: String,
    private val values: Array<T>,
    private val default: T,
) {
    private val backing = PersistedInt(prefs, key, default.ordinal)
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T =
        values.getOrElse(backing.getValue(thisRef, property)) { default }
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        backing.setValue(thisRef, property, value.ordinal)
    }
}
