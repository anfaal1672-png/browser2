package com.anfaal.gamebrowser

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved login. Mirrors `Credential` in AutofillStore.swift
 * (id, domain, username, password).
 */
data class Credential(
    val id: String = UUID.randomUUID().toString(),
    var domain: String,
    var username: String,
    var password: String,
)

/**
 * The single saved payment card. Mirrors `PaymentCard` in AutofillStore.swift
 * (number, holder, expMonth, expYear).
 */
data class PaymentCard(
    var number: String = "",
    var holder: String = "",
    var expMonth: String = "",
    var expYear: String = "",
) {
    val isEmpty: Boolean get() = number.isEmpty()

    val maskedNumber: String
        get() {
            val digits = number.filter { it.isDigit() }
            if (digits.length < 4) return number
            return "•••• " + digits.takeLast(4)
        }
}

/**
 * Credentials and payment card, stored encrypted via
 * androidx.security.crypto.EncryptedSharedPreferences — the Android analogue
 * of the iOS Keychain-backed AutofillStore.swift. Requires the
 * `androidx.security:security-crypto:1.1.0-alpha06` Gradle dependency (not yet
 * added to app/build.gradle.kts; see integration notes).
 */
object AutofillStore {

    private const val PREFS_NAME = "com.anfaal.gamebrowser.autofill"
    private const val KEY_CREDENTIALS = "credentials"
    private const val KEY_CARD = "card"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        return prefs ?: synchronized(this) {
            prefs ?: buildPrefs(context.applicationContext).also { prefs = it }
        }
    }

    private fun buildPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // MARK: - Credentials

    fun loadCredentials(context: Context): List<Credential> {
        val json = prefs(context).getString(KEY_CREDENTIALS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Credential(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    domain = obj.optString("domain", ""),
                    username = obj.optString("username", ""),
                    password = obj.optString("password", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCredentials(context: Context, credentials: List<Credential>) {
        val array = JSONArray()
        for (c in credentials) {
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("domain", c.domain)
            obj.put("username", c.username)
            obj.put("password", c.password)
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_CREDENTIALS, array.toString()).apply()
    }

    // MARK: - Payment card

    fun loadCard(context: Context): PaymentCard {
        val json = prefs(context).getString(KEY_CARD, null) ?: return PaymentCard()
        return try {
            val obj = JSONObject(json)
            PaymentCard(
                number = obj.optString("number", ""),
                holder = obj.optString("holder", ""),
                expMonth = obj.optString("expMonth", ""),
                expYear = obj.optString("expYear", ""),
            )
        } catch (e: Exception) {
            PaymentCard()
        }
    }

    fun saveCard(context: Context, card: PaymentCard) {
        val obj = JSONObject()
        obj.put("number", card.number)
        obj.put("holder", card.holder)
        obj.put("expMonth", card.expMonth)
        obj.put("expYear", card.expYear)
        prefs(context).edit().putString(KEY_CARD, obj.toString()).apply()
    }

    /**
     * Match saved credentials to a host ("play.example.com" matches entries
     * saved for "example.com" and vice versa). Suffix matching is boundary-
     * checked on "." so "evilexample.com" can't match a saved "example.com"
     * entry just because it happens to share a substring.
     *
     * (Security fix vs. the original iOS matching, which was
     * `host.hasSuffix(domain) || domain.hasSuffix(host)` — ported here in its
     * fixed, boundary-checked form.)
     */
    fun credentials(host: String, list: List<Credential>): List<Credential> {
        return list.filter { c ->
            host == c.domain ||
                host.endsWith("." + c.domain) ||
                c.domain.endsWith("." + host)
        }
    }
}
