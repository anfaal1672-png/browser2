package com.anfaal.gamebrowser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Incremental romaji -> hiragana conversion for the built-in IME. Returns the
 * converted kana plus the still-ambiguous romaji tail (e.g. "ky" waiting for
 * a vowel). Ported from RomajiConverter in RomajiIME.swift.
 */
object RomajiConverter {

    val vowels: Set<Char> = setOf('a', 'i', 'u', 'e', 'o')

    val table: Map<String, String> = mapOf(
        // Gojuon
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "sa" to "さ", "si" to "し", "shi" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "ta" to "た", "ti" to "ち", "chi" to "ち", "tu" to "つ", "tsu" to "つ", "te" to "て", "to" to "と",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "hu" to "ふ", "fu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を", "nn" to "ん",
        // Dakuon / handakuon
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "za" to "ざ", "zi" to "じ", "ji" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        // Youon
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",
        // Small kana / foreign sounds
        "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
        "ltu" to "っ", "xtu" to "っ", "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
        "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
        "wi" to "うぃ", "we" to "うぇ",
        "she" to "しぇ", "che" to "ちぇ", "je" to "じぇ",
        "thi" to "てぃ", "dhi" to "でぃ", "twu" to "とぅ", "dwu" to "どぅ",
        // Punctuation
        "-" to "ー", "," to "、", "." to "。", "?" to "?", "!" to "!",
        "1" to "1", "2" to "2", "3" to "3", "4" to "4", "5" to "5",
        "6" to "6", "7" to "7", "8" to "8", "9" to "9", "0" to "0",
    )

    /** Returns (kana, pending) — the converted kana and the still-ambiguous romaji tail. */
    fun convert(romaji: String): Pair<String, String> {
        val kana = StringBuilder()
        val chars = romaji.lowercase().toCharArray()
        var i = 0

        while (i < chars.size) {
            val c = chars[i]
            val remaining = chars.size - i

            // ん: "n" followed by a non-vowel, non-y consonant (or a digit —
            // e.g. "n1" must convert to "ん1", not stay as literal "n1").
            if (c == 'n' && remaining >= 2) {
                val next = chars[i + 1]
                if (next == 'n') {
                    kana.append('ん'); i += 2; continue
                }
                if (!vowels.contains(next) && next != 'y' && (next.isLetter() || next.isDigit())) {
                    kana.append('ん'); i += 1; continue
                }
            }

            // っ: doubled consonant (kk, tt, ss, pp...).
            if (remaining >= 2 && c == chars[i + 1] && c.isLetter() &&
                !vowels.contains(c) && c != 'n'
            ) {
                kana.append('っ'); i += 1; continue
            }

            // Longest match: 3 -> 2 -> 1 characters.
            var matched = false
            for (len in minOf(3, remaining) downTo 1) {
                val candidate = String(chars, i, len)
                val converted = table[candidate]
                if (converted != null) {
                    kana.append(converted)
                    i += len
                    matched = true
                    break
                }
            }
            if (matched) continue

            // Could this become a match with more input? If yes, stop here and
            // keep the tail as pending; otherwise pass the character through.
            val tail = String(chars, i, chars.size - i)
            if (tail.length <= 3 && table.keys.any { it.startsWith(tail) }) {
                return Pair(kana.toString(), tail)
            }
            kana.append(c)
            i += 1
        }
        return Pair(kana.toString(), "")
    }
}

/**
 * Fetches kanji conversion candidates from the Google transliterate API (same
 * backend as Google Japanese Input's cloud conversion). Ported from
 * KanjiConverter in RomajiIME.swift.
 */
object KanjiConverter {

    private const val ENDPOINT = "https://www.google.com/transliterate?langpair=ja-Hira%7Cja&text="

    /** One segment of an API response: its kana plus candidate readings. */
    private data class Segment(val kana: String, val candidates: List<String>)

    /** One API request: returns per-segment candidate lists (with segment kana). */
    private suspend fun query(kana: String): List<Segment> = withContext(Dispatchers.IO) {
        if (kana.isEmpty()) return@withContext emptyList()

        val encoded = try {
            URLEncoder.encode(kana, "UTF-8")
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        // Trailing comma is intentional/required by the transliterate API.
        val urlString = "$ENDPOINT$encoded,"

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext emptyList()

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONArray(body)
            val result = mutableListOf<Segment>()
            // Response: [[segmentKana, [candidate, ...]], ...]
            for (i in 0 until root.length()) {
                val seg = root.optJSONArray(i) ?: continue
                if (seg.length() < 2 || seg.isNull(0)) continue
                val rawSegKana = seg.optString(0, "")
                val candArray = seg.optJSONArray(1) ?: continue
                val cands = (0 until candArray.length()).map { candArray.optString(it, "") }
                // Segment kana comes back with a trailing comma marker sometimes.
                result.add(Segment(rawSegKana.replace(",", ""), cands))
            }
            result
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Returns conversion candidates for the given hiragana string. The API
     * caps each response at ~5 per segment with no paging, so each segment is
     * also re-queried individually in parallel — standalone queries return a
     * different (often richer) candidate set — and everything is merged.
     */
    suspend fun candidates(kana: String): List<String> = coroutineScope {
        val initial = query(kana)
        if (initial.isEmpty()) return@coroutineScope emptyList()

        val segments = initial.map { it.candidates }.toMutableList()

        // Per-segment re-query for extra candidates (only useful for phrases).
        if (initial.size > 1) {
            val deferred = initial.mapIndexed { i, seg ->
                async { i to query(seg.kana) }
            }
            for (task in deferred) {
                val (i, result) = task.await()
                // A standalone query may itself split; only merge when it
                // stays a single segment.
                val extraCandidates = if (result.size == 1) result[0].candidates else emptyList()
                val seen = segments[i].toMutableSet()
                val added = extraCandidates.filter { seen.add(it) }
                if (added.isNotEmpty()) {
                    segments[i] = segments[i] + added
                }
            }
        }

        val nonEmptySegments = segments.filter { it.isNotEmpty() }
        if (nonEmptySegments.isEmpty()) return@coroutineScope emptyList()

        val results = mutableListOf<String>()
        if (nonEmptySegments.size == 1) {
            results.addAll(nonEmptySegments[0])
        } else {
            // Expand combinations: every candidate of each segment, one
            // segment varied at a time against the first choices of the rest.
            val firsts = nonEmptySegments.map { it[0] }
            results.add(firsts.joinToString(""))
            for ((i, segment) in nonEmptySegments.withIndex()) {
                for (candidate in segment.drop(1)) {
                    val combo = firsts.toMutableList()
                    combo[i] = candidate
                    results.add(combo.joinToString(""))
                }
            }
        }

        // Supplement the API's ~5 per segment with local transliterations.
        val katakana = hiraganaToKatakana(kana)
        if (katakana != kana) {
            results.add(katakana)
        }

        // Deduplicate and drop the raw kana (shown separately).
        val seen = mutableSetOf<String>()
        results.filter { seen.add(it) && it != kana }
    }

    /**
     * Manual hiragana -> katakana transliteration (no ICU transform API on
     * Android/Kotlin like Swift's `.hiraganaToKatakana`): the Unicode
     * Hiragana block (U+3041-U+3096) and the corresponding part of the
     * Katakana block (U+30A1-U+30F6) are a fixed +0x60 offset apart.
     */
    private fun hiraganaToKatakana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            if (code in 0x3041..0x3096) {
                sb.append((code + 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
