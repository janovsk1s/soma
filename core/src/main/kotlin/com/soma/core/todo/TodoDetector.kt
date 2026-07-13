package com.soma.core.todo

import com.soma.core.model.ImportantKind
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.TodoSuggestionReason
import java.util.Locale

data class TodoCandidate(
    /** The original sentence, kept editable and suitable as the initial todo text. */
    val suggestedText: String,
    val kind: ImportantKind = ImportantKind.ACTION,
    val language: SupportedLanguage,
    val reason: TodoSuggestionReason,
    /** The human-readable trigger or imperative form that matched. */
    val matchedRule: String,
)

data class TodoLanguageRules(
    val triggerPhrases: Set<String>,
    /** Conservative, common imperative words or phrases that may start a sentence. */
    val imperativeStarts: Set<String>,
    /** Explicit headings that make a group of lines or comma-separated values a list. */
    val listHeadings: Set<String> = emptySet(),
)

fun interface TodoDetector {
    fun detect(text: String, language: SupportedLanguage): List<TodoCandidate>
}

/**
 * Predictable detector used after typed text is saved or a transcript lands.
 * It intentionally does not infer language, intent, priority, or a due date.
 */
class RuleBasedTodoDetector(
    private val rules: Map<SupportedLanguage, TodoLanguageRules> = DefaultTodoRules.all,
) : TodoDetector {
    override fun detect(text: String, language: SupportedLanguage): List<TodoCandidate> {
        val languageRules = rules[language] ?: return emptyList()
        detectList(text, language, languageRules)?.let { return listOf(it) }
        return sentences(text).asSequence().mapNotNull { sentence ->
            detectSentence(sentence, language, languageRules)
        }.take(MAX_CANDIDATES).toList()
    }

    /**
     * Useful for code-switched transcripts. Each sentence produces at most one candidate;
     * languages are evaluated in the stable Paka language order.
     */
    fun detect(
        text: String,
        expectedLanguages: Set<SupportedLanguage>,
    ): List<TodoCandidate> {
        if (expectedLanguages.isEmpty()) return emptyList()
        val orderedLanguages = SupportedLanguage.entries.filter(expectedLanguages::contains)
        orderedLanguages.firstNotNullOfOrNull { language ->
            rules[language]?.let { detectList(text, language, it) }
        }?.let { return listOf(it) }
        return sentences(text).asSequence().mapNotNull { sentence ->
            orderedLanguages.firstNotNullOfOrNull { language ->
                rules[language]?.let { detectSentence(sentence, language, it) }
            }
        }.take(MAX_CANDIDATES).toList()
    }

    private fun detectList(
        text: String,
        language: SupportedLanguage,
        languageRules: TodoLanguageRules,
    ): TodoCandidate? {
        val bounded = text.take(MAX_INPUT_CHARS).trim()
        if (bounded.isBlank()) return null
        val lines = bounded.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val bulletItems = lines.mapNotNull { line ->
            LEADING_LIST_MARKER.find(line)?.let { marker -> line.removeRange(marker.range).trim() }
        }.filter(String::isNotBlank)
        if (bulletItems.size >= MIN_LIST_ITEMS) {
            return listCandidate(bulletItems, language, "bullet-list")
        }

        val heading = languageRules.listHeadings
            .asSequence()
            .map(::normalizeForMatching)
            .sortedByDescending(String::length)
            .firstNotNullOfOrNull { candidate ->
                val match = Regex(
                    "(?i)(?:^|\\R)\\s*${Regex.escape(candidate)}\\s*[:\\-]\\s*",
                ).find(bounded)
                match?.let { candidate to bounded.substring(it.range.last + 1) }
            } ?: return null
        val items = heading.second
            .split(LIST_ITEM_SEPARATOR)
            .map { it.trim().replace(LEADING_LIST_MARKER, "") }
            .filter(String::isNotBlank)
        if (items.size < MIN_LIST_ITEMS) return null
        return listCandidate(items, language, heading.first)
    }

    private fun listCandidate(
        items: List<String>,
        language: SupportedLanguage,
        matchedRule: String,
    ) = TodoCandidate(
        suggestedText = items.take(MAX_LIST_ITEMS).joinToString("\n"),
        kind = ImportantKind.LIST,
        language = language,
        reason = TodoSuggestionReason.LIST_PATTERN,
        matchedRule = matchedRule,
    )

    private fun detectSentence(
        sentence: String,
        language: SupportedLanguage,
        languageRules: TodoLanguageRules,
    ): TodoCandidate? {
        val normalized = normalizeForMatching(sentence)
        if (normalized.isBlank() || normalized.endsWith('?')) return null

        val trigger = languageRules.triggerPhrases
            .asSequence()
            .map(::normalizeForMatching)
            .sortedByDescending(String::length)
            .firstOrNull { phrase -> containsWholePhrase(normalized, phrase) }
        if (trigger != null) {
            return TodoCandidate(
                suggestedText = sentence.trim(),
                language = language,
                reason = TodoSuggestionReason.TRIGGER_PHRASE,
                matchedRule = trigger,
            )
        }

        val imperative = languageRules.imperativeStarts
            .asSequence()
            .map(::normalizeForMatching)
            .sortedByDescending(String::length)
            .firstOrNull { phrase ->
                normalized.startsWith("$phrase ") && normalized.length > phrase.length + 1
            }
        return imperative?.let {
            TodoCandidate(
                suggestedText = sentence.trim(),
                language = language,
                reason = TodoSuggestionReason.IMPERATIVE,
                matchedRule = it,
            )
        }
    }

    private fun sentences(text: String): List<String> = text.take(MAX_INPUT_CHARS)
        .split(SENTENCE_BOUNDARY)
        .map(String::trim)
        .filter(String::isNotEmpty)

    private fun normalizeForMatching(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .trim()
        .replace(LEADING_LIST_MARKER, "")
        .replace(WHITESPACE, " ")

    private fun containsWholePhrase(sentence: String, phrase: String): Boolean {
        if (phrase.isBlank()) return false
        val boundaryPattern = Regex(
            pattern = "(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])",
        )
        return boundaryPattern.containsMatchIn(sentence)
    }

    private companion object {
        val SENTENCE_BOUNDARY = Regex("(?<=[.!?])(?:\\s+|$)|\\R+")
        val LEADING_LIST_MARKER = Regex("^(?:[-*•]|\\d+[.)])\\s+")
        val LIST_ITEM_SEPARATOR = Regex("\\R+|[,;]")
        val WHITESPACE = Regex("\\s+")
        const val MIN_LIST_ITEMS = 2
        const val MAX_LIST_ITEMS = 50
        const val MAX_CANDIDATES = 20
        const val MAX_INPUT_CHARS = 50_000
    }
}

/**
 * Small, reviewable word lists rather than opaque language classification. Additions should
 * include a positive and a near-miss case in the detector tests.
 */
object DefaultTodoRules {
    val all: Map<SupportedLanguage, TodoLanguageRules> = mapOf(
        SupportedLanguage.ENGLISH to TodoLanguageRules(
            triggerPhrases = setOf(
                "need to",
                "have to",
                "remember to",
                "todo",
                "to-do",
                "don't forget",
                "do not forget",
            ),
            imperativeStarts = setOf(
                "ask",
                "book",
                "bring",
                "buy",
                "call",
                "cancel",
                "check",
                "email",
                "fix",
                "make",
                "pay",
                "pick up",
                "remember",
                "renew",
                "return",
                "schedule",
                "send",
                "take",
                "write",
            ),
            listHeadings = setOf("grocery list", "shopping list", "groceries"),
        ),
        SupportedLanguage.LATVIAN to TodoLanguageRules(
            triggerPhrases = setOf(
                "jāizdara",
                "jāatceras",
                "vajag",
                "nepieciešams",
                "nedrīkstu aizmirst",
                "neaizmirst",
            ),
            imperativeStarts = setOf(
                "aiznes",
                "atceries",
                "izdari",
                "nopērc",
                "nosūti",
                "paņem",
                "pajautā",
                "pārbaudi",
                "piezvani",
                "rezervē",
                "samaksā",
                "uzraksti",
            ),
            listHeadings = setOf("iepirkumu saraksts", "pirkumu saraksts", "pirkumi"),
        ),
        SupportedLanguage.ESTONIAN to TodoLanguageRules(
            triggerPhrases = setOf(
                "vaja teha",
                "vaja",
                "pean",
                "tuleb",
                "ära unusta",
                "meeles pidada",
                "ülesanne",
            ),
            imperativeStarts = setOf(
                "broneeri",
                "helista",
                "kirjuta",
                "kontrolli",
                "küsi",
                "maksa",
                "osta",
                "pea meeles",
                "saada",
                "tee",
                "vii",
                "võta",
            ),
            listHeadings = setOf("ostunimekiri", "poenimekiri", "ostud"),
        ),
        SupportedLanguage.LITHUANIAN to TodoLanguageRules(
            triggerPhrases = setOf(
                "reikia padaryti",
                "reikia",
                "turiu",
                "būtina",
                "nepamiršti",
                "nepamiršk",
                "užduotis",
            ),
            imperativeStarts = setOf(
                "išsiųsk",
                "nunešk",
                "nupirk",
                "padaryk",
                "paimk",
                "paklausk",
                "paskambink",
                "patikrink",
                "parašyk",
                "prisimink",
                "rezervuok",
                "sumokėk",
            ),
            listHeadings = setOf("pirkinių sąrašas", "pirkiniai"),
        ),
        SupportedLanguage.FINNISH to TodoLanguageRules(
            triggerPhrases = setOf(
                "pitää",
                "täytyy",
                "muista",
                "älä unohda",
                "tehtävä",
                "minun on",
            ),
            imperativeStarts = setOf(
                "kysy",
                "kirjoita",
                "lähetä",
                "maksa",
                "muista",
                "osta",
                "ota",
                "soita",
                "tarkista",
                "tee",
                "varaa",
                "vie",
            ),
            listHeadings = setOf("ostoslista", "kauppalista", "ostokset"),
        ),
        SupportedLanguage.SWEDISH to TodoLanguageRules(
            triggerPhrases = setOf(
                "behöver göra",
                "behöver",
                "måste",
                "kom ihåg att",
                "glöm inte",
                "att göra",
            ),
            imperativeStarts = setOf(
                "betala",
                "boka",
                "fråga",
                "glöm inte",
                "gör",
                "hämta",
                "kom ihåg",
                "köp",
                "ring",
                "skicka",
                "skriv",
                "ta",
            ),
            listHeadings = setOf("inköpslista", "handlingslista", "inköp"),
        ),
        SupportedLanguage.GERMAN to TodoLanguageRules(
            triggerPhrases = setOf(
                "ich muss",
                "muss noch",
                "muss",
                "müssen",
                "ich sollte",
                "nicht vergessen",
                "daran denken",
                "zu erledigen",
                "brauche",
            ),
            imperativeStarts = setOf(
                "bezahl",
                "buche",
                "bring",
                "denk",
                "frag",
                "hole",
                "kauf",
                "kündige",
                "mach",
                "nimm",
                "prüfe",
                "ruf",
                "rufe",
                "schick",
                "schreibe",
            ),
            listHeadings = setOf("einkaufsliste", "einkäufe"),
        ),
        SupportedLanguage.SLOVAK to TodoLanguageRules(
            triggerPhrases = setOf(
                "treba urobiť",
                "musím",
                "treba",
                "potrebujem",
                "nezabudnúť",
                "nezabudni",
                "mám urobiť",
                "úloha",
            ),
            imperativeStarts = setOf(
                "kúp",
                "napíš",
                "nezabudni",
                "opýtaj",
                "pošli",
                "prines",
                "rezervuj",
                "skontroluj",
                "urob",
                "vezmi",
                "zanes",
                "zaplať",
                "zavolaj",
            ),
            listHeadings = setOf("nákupný zoznam", "nákupy"),
        ),
    )
}
