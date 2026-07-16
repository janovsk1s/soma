import Foundation

/// Local-first Important detection. Deterministic rules run before any AI so the
/// suggestion path never depends on a model, a key, or a network; AI only sees the
/// note when these rules find nothing. Matched sentences are kept whole — an
/// enumeration is never shortened into just its first item.
enum SomaRules {
    static let maximumCandidates = 3

    static func importantCandidates(in text: String) -> [String] {
        var results: [String] = []
        var seen = Set<String>()
        for sentence in sentences(in: text) {
            guard results.count < maximumCandidates else { break }
            guard sentence.count <= 240 else { continue }
            guard
                isObligation(sentence) ||
                containsReference(sentence) ||
                mentionsUpcomingDate(sentence)
            else {
                continue
            }
            let fingerprint = fold(sentence)
            guard seen.insert(fingerprint).inserted else { continue }
            results.append(sentence)
        }
        return results
    }

    // MARK: - Sentences

    private static func sentences(in text: String) -> [String] {
        var sentences: [String] = []
        for line in text.split(whereSeparator: \.isNewline) {
            let line = String(line)
            line.enumerateSubstrings(
                in: line.startIndex...,
                options: [.bySentences, .localized]
            ) { substring, _, _, _ in
                let cleaned = substring?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if !cleaned.isEmpty {
                    sentences.append(cleaned)
                }
            }
        }
        return sentences
    }

    // MARK: - Obligation words

    /// Obligation markers for the eight Soma languages, stored diacritic-folded and
    /// matched on word boundaries. Latvian is the exception: its obligation is the
    /// bound debitive prefix "jā-", which has no separate word.
    private static let obligationPhrases: [[String]] = [
        // English
        ["must"], ["have", "to"], ["has", "to"], ["need", "to"], ["needs", "to"],
        ["dont", "forget"], ["remember", "to"],
        // Latvian (besides the debitive prefix rule below)
        ["vajag"], ["vajadzetu"], ["neaizmirsti"], ["neaizmirstiet"], ["obligati"],
        // Estonian
        ["pean"], ["pead"], ["peab"], ["peame"], ["peate"], ["peavad"],
        ["vaja"], ["ara", "unusta"],
        // Lithuanian
        ["reikia"], ["reikes"], ["privalau"], ["privalai"], ["privalo"],
        ["butina"], ["nepamirsk"], ["nepamirskite"],
        // Finnish
        ["taytyy"], ["pitaisi"], ["ala", "unohda"], ["muista"],
        // Swedish
        ["maste"], ["kom", "ihag"], ["glom", "inte"], ["behover"],
        // German
        ["muss"], ["musst"], ["mussen"], ["nicht", "vergessen"], ["denk", "daran"],
        ["denke", "daran"],
        // Slovak
        ["musim"], ["musis"], ["musi"], ["musime"], ["musite"], ["treba"],
        ["nezabudni"], ["nezabudnite"], ["potrebujem"],
    ]

    /// Latvian debitive lookalikes that start with "jā" but carry no obligation.
    private static let debitiveLookalikes: Set<String> = ["jāja", "jājam", "jājat"]
    private static let minimumDebitiveLength = 5

    private static func isObligation(_ sentence: String) -> Bool {
        let foldedWords = words(in: fold(sentence))
        guard !foldedWords.isEmpty else { return false }
        for phrase in obligationPhrases where containsPhrase(foldedWords, phrase) {
            return true
        }
        // The debitive check needs the original diacritics: folded "jā-" collapses
        // into "ja-", which collides with ordinary words like "janvāris".
        for word in words(in: sentence.lowercased()) {
            if
                word.hasPrefix("jā"),
                word.count >= minimumDebitiveLength,
                !debitiveLookalikes.contains(word)
            {
                return true
            }
        }
        return false
    }

    private static func containsPhrase(_ words: [String], _ phrase: [String]) -> Bool {
        guard words.count >= phrase.count else { return false }
        for start in 0...(words.count - phrase.count) {
            if Array(words[start..<(start + phrase.count)]) == phrase {
                return true
            }
        }
        return false
    }

    // MARK: - References

    private static let referencePatterns: [NSRegularExpression] = {
        let patterns = [
            // Booking/order codes: 5–9 uppercase alphanumerics mixing letters and digits.
            #"\b(?=[A-Z0-9]{5,9}\b)(?=[A-Z0-9]*[0-9])(?=[A-Z0-9]*[A-Z])[A-Z0-9]+\b"#,
            // Long digit runs: order and parcel numbers.
            #"\b[0-9]{6,}\b"#,
        ]
        return patterns.compactMap { try? NSRegularExpression(pattern: $0) }
    }()

    // Apple's detector handles phone-number shapes across locales far better
    // than a regex; codes and digit runs stay regex because no detector has them.
    private static let phoneDetector = try? NSDataDetector(
        types: NSTextCheckingResult.CheckingType.phoneNumber.rawValue
    )

    private static let dateDetector = try? NSDataDetector(
        types: NSTextCheckingResult.CheckingType.date.rawValue
    )

    private static func containsReference(_ sentence: String) -> Bool {
        let range = NSRange(sentence.startIndex..., in: sentence)
        if phoneDetector?.firstMatch(in: sentence, range: range) != nil {
            return true
        }
        return referencePatterns.contains { $0.firstMatch(in: sentence, range: range) != nil }
    }

    /// A sentence naming a future moment ("dentist on Thursday at 3") is worth
    /// keeping even without an obligation word. Past and immediate dates ("this
    /// morning") stay out — notes describing the day are not todos.
    private static func mentionsUpcomingDate(_ sentence: String) -> Bool {
        guard let dateDetector else { return false }
        let range = NSRange(sentence.startIndex..., in: sentence)
        let horizon = Date().addingTimeInterval(2 * 60 * 60)
        var upcoming = false
        dateDetector.enumerateMatches(in: sentence, range: range) { match, _, stop in
            if let date = match?.date, date > horizon {
                upcoming = true
                stop.pointee = true
            }
        }
        return upcoming
    }

    // MARK: - Folding

    private static func fold(_ text: String) -> String {
        text
            .replacingOccurrences(of: "’", with: "'")
            .replacingOccurrences(of: "'", with: "")
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
    }

    private static func words(in text: String) -> [String] {
        text.split { !$0.isLetter && !$0.isNumber }.map(String.init)
    }
}
