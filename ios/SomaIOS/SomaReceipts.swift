import Foundation

/// Deterministic receipt structure from OCR text — no model, no network. Carries
/// the LightOS app's hard-won parsing rules: amounts are signed (a discount line
/// must never become a positive charge), a register-style trailing minus counts,
/// and the total line is found by keyword across Soma's languages before falling
/// back to the largest amount.
enum ReceiptParse {
    struct Receipt {
        var merchant: String
        var items: [(name: String, cents: Int)]
        var totalCents: Int

        var summary: String {
            "\(merchant) · \(ReceiptParse.decimalString(totalCents))"
        }

        var detail: String {
            var lines = items.map { item in
                "\(item.name)  \(ReceiptParse.decimalString(item.cents))"
            }
            lines.append("—")
            lines.append("\(String(localized: "Total"))  \(ReceiptParse.decimalString(totalCents))")
            return lines.joined(separator: "\n")
        }
    }

    // Name, optional leading minus, digits, decimal separator, two digits,
    // optional register-style trailing minus. The sign must hug the number so
    // ranges and phone numbers stay out.
    private static let amountLine = try? NSRegularExpression(
        pattern: #"^(.*?)[ \t]*(-)?([0-9]{1,5})[.,]([0-9]{2})(-)?$"#
    )

    private static let totalKeywords = [
        "total", "summe", "gesamtbetrag", "zu zahlen", "kopa", "summa",
        "is viso", "yhteensa", "totalt", "spolu", "kokku", "sum",
    ]

    static func parse(_ text: String) -> Receipt? {
        guard let amountLine else { return nil }
        let rawLines = text
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard rawLines.count >= 2 else { return nil }

        var amounts: [(name: String, cents: Int, isTotal: Bool)] = []
        var merchant: String?

        for line in rawLines {
            let range = NSRange(line.startIndex..., in: line)
            guard
                let match = amountLine.firstMatch(in: line, range: range),
                let nameRange = Range(match.range(at: 1), in: line),
                let unitsRange = Range(match.range(at: 3), in: line),
                let centsRange = Range(match.range(at: 4), in: line),
                let units = Int(line[unitsRange]),
                let fraction = Int(line[centsRange])
            else {
                if merchant == nil {
                    merchant = String(line.prefix(48))
                }
                continue
            }
            let name = line[nameRange].trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { continue }
            let negative = match.range(at: 2).location != NSNotFound
                || match.range(at: 5).location != NSNotFound
            let cents = (units * 100 + fraction) * (negative ? -1 : 1)
            let folded = fold(name)
            let isTotal = totalKeywords.contains { folded.contains($0) }
            amounts.append((name, cents, isTotal))
        }

        guard !amounts.isEmpty else { return nil }

        let total: (name: String, cents: Int, isTotal: Bool)
        if let keywordTotal = amounts.last(where: \.isTotal) {
            total = keywordTotal
        } else if amounts.count >= 2, let largest = amounts.max(by: { $0.cents < $1.cents }) {
            total = largest
        } else {
            return nil
        }
        guard total.cents > 0 else { return nil }

        let items = amounts
            .filter { $0.name != total.name || $0.cents != total.cents }
            .filter { !$0.isTotal }
            .map { (name: $0.name, cents: $0.cents) }

        return Receipt(
            merchant: merchant ?? String(rawLines[0].prefix(48)),
            items: items,
            totalCents: total.cents
        )
    }

    /// Signed cents to "8.50" / "-0.50" — naive `/100 . %100` formatting breaks
    /// on negatives ("0.-50"), the exact bug the Android app shipped once.
    static func decimalString(_ cents: Int) -> String {
        let sign = cents < 0 ? "-" : ""
        let magnitude = abs(cents)
        return String(format: "%@%d.%02d", sign, magnitude / 100, magnitude % 100)
    }

    private static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }
}
