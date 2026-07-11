import Foundation

/// Incremental romaji → hiragana conversion for the built-in IME.
/// Returns the converted kana plus the still-ambiguous romaji tail
/// (e.g. "ky" waiting for a vowel).
enum RomajiConverter {

    static let vowels: Set<Character> = ["a", "i", "u", "e", "o"]

    static let table: [String: String] = [
        // Gojuon
        "a": "あ", "i": "い", "u": "う", "e": "え", "o": "お",
        "ka": "か", "ki": "き", "ku": "く", "ke": "け", "ko": "こ",
        "sa": "さ", "si": "し", "shi": "し", "su": "す", "se": "せ", "so": "そ",
        "ta": "た", "ti": "ち", "chi": "ち", "tu": "つ", "tsu": "つ", "te": "て", "to": "と",
        "na": "な", "ni": "に", "nu": "ぬ", "ne": "ね", "no": "の",
        "ha": "は", "hi": "ひ", "hu": "ふ", "fu": "ふ", "he": "へ", "ho": "ほ",
        "ma": "ま", "mi": "み", "mu": "む", "me": "め", "mo": "も",
        "ya": "や", "yu": "ゆ", "yo": "よ",
        "ra": "ら", "ri": "り", "ru": "る", "re": "れ", "ro": "ろ",
        "wa": "わ", "wo": "を", "nn": "ん",
        // Dakuon / handakuon
        "ga": "が", "gi": "ぎ", "gu": "ぐ", "ge": "げ", "go": "ご",
        "za": "ざ", "zi": "じ", "ji": "じ", "zu": "ず", "ze": "ぜ", "zo": "ぞ",
        "da": "だ", "di": "ぢ", "du": "づ", "de": "で", "do": "ど",
        "ba": "ば", "bi": "び", "bu": "ぶ", "be": "べ", "bo": "ぼ",
        "pa": "ぱ", "pi": "ぴ", "pu": "ぷ", "pe": "ぺ", "po": "ぽ",
        // Youon
        "kya": "きゃ", "kyu": "きゅ", "kyo": "きょ",
        "sya": "しゃ", "syu": "しゅ", "syo": "しょ",
        "sha": "しゃ", "shu": "しゅ", "sho": "しょ",
        "tya": "ちゃ", "tyu": "ちゅ", "tyo": "ちょ",
        "cha": "ちゃ", "chu": "ちゅ", "cho": "ちょ",
        "nya": "にゃ", "nyu": "にゅ", "nyo": "にょ",
        "hya": "ひゃ", "hyu": "ひゅ", "hyo": "ひょ",
        "mya": "みゃ", "myu": "みゅ", "myo": "みょ",
        "rya": "りゃ", "ryu": "りゅ", "ryo": "りょ",
        "gya": "ぎゃ", "gyu": "ぎゅ", "gyo": "ぎょ",
        "zya": "じゃ", "zyu": "じゅ", "zyo": "じょ",
        "ja": "じゃ", "ju": "じゅ", "jo": "じょ",
        "jya": "じゃ", "jyu": "じゅ", "jyo": "じょ",
        "bya": "びゃ", "byu": "びゅ", "byo": "びょ",
        "pya": "ぴゃ", "pyu": "ぴゅ", "pyo": "ぴょ",
        "dya": "ぢゃ", "dyu": "ぢゅ", "dyo": "ぢょ",
        // Small kana / foreign sounds
        "la": "ぁ", "li": "ぃ", "lu": "ぅ", "le": "ぇ", "lo": "ぉ",
        "xa": "ぁ", "xi": "ぃ", "xu": "ぅ", "xe": "ぇ", "xo": "ぉ",
        "ltu": "っ", "xtu": "っ", "lya": "ゃ", "lyu": "ゅ", "lyo": "ょ",
        "xya": "ゃ", "xyu": "ゅ", "xyo": "ょ",
        "fa": "ふぁ", "fi": "ふぃ", "fe": "ふぇ", "fo": "ふぉ",
        "va": "ゔぁ", "vi": "ゔぃ", "vu": "ゔ", "ve": "ゔぇ", "vo": "ゔぉ",
        "wi": "うぃ", "we": "うぇ",
        "she": "しぇ", "che": "ちぇ", "je": "じぇ",
        "thi": "てぃ", "dhi": "でぃ", "twu": "とぅ", "dwu": "どぅ",
        // Punctuation
        "-": "ー", ",": "、", ".": "。", "?": "?", "!": "!",
        "1": "1", "2": "2", "3": "3", "4": "4", "5": "5",
        "6": "6", "7": "7", "8": "8", "9": "9", "0": "0",
    ]

    static func convert(_ romaji: String) -> (kana: String, pending: String) {
        var kana = ""
        let chars = Array(romaji.lowercased())
        var i = 0

        while i < chars.count {
            let c = chars[i]
            let remaining = chars.count - i

            // ん: "n" followed by a non-vowel, non-y consonant.
            if c == "n", remaining >= 2 {
                let next = chars[i + 1]
                if next == "n" {
                    kana += "ん"; i += 2; continue
                }
                if !vowels.contains(next), next != "y", next.isLetter {
                    kana += "ん"; i += 1; continue
                }
            }

            // っ: doubled consonant (kk, tt, ss, pp...).
            if remaining >= 2, c == chars[i + 1], c.isLetter,
               !vowels.contains(c), c != "n" {
                kana += "っ"; i += 1; continue
            }

            // Longest match: 3 → 2 → 1 characters.
            var matched = false
            for len in stride(from: min(3, remaining), through: 1, by: -1) {
                let candidate = String(chars[i..<(i + len)])
                if let converted = table[candidate] {
                    kana += converted
                    i += len
                    matched = true
                    break
                }
            }
            if matched { continue }

            // Could this become a match with more input? If yes, stop here and
            // keep the tail as pending; otherwise pass the character through.
            let tail = String(chars[i...])
            if tail.count <= 3, table.keys.contains(where: { $0.hasPrefix(tail) }) {
                return (kana, tail)
            }
            kana += String(c)
            i += 1
        }
        return (kana, "")
    }
}

/// Fetches kanji conversion candidates from the Google transliterate API
/// (same backend as Google Japanese Input's cloud conversion).
enum KanjiConverter {

    /// Returns conversion candidates for the given hiragana string.
    static func candidates(for kana: String) async -> [String] {
        guard !kana.isEmpty,
              let encoded = kana.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string:
                "https://www.google.com/transliterate?langpair=ja-Hira%7Cja&text=\(encoded),")
        else { return [] }

        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [[Any]]
        else { return [] }

        // Response: [[segmentKana, [candidate, ...]], ...]
        let segments: [[String]] = json.compactMap { seg in
            guard seg.count >= 2, let cands = seg[1] as? [String] else { return nil }
            return cands
        }
        guard !segments.isEmpty else { return [] }

        var results: [String] = []
        if segments.count == 1 {
            results = segments[0]
        } else {
            // Best guess: first choice of every segment joined, then
            // variations of the first segment.
            let rest = segments.dropFirst().map { $0[0] }.joined()
            results = segments[0].prefix(6).map { $0 + rest }
        }
        // Deduplicate, drop the raw kana (shown separately), cap the list.
        var seen = Set<String>()
        return results.filter { seen.insert($0).inserted && $0 != kana }.prefix(9).map { $0 }
    }
}
