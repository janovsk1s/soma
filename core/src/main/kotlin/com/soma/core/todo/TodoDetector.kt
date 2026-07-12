package com.soma.core.todo

import com.soma.core.model.SupportedLanguage
import com.soma.core.model.TodoSuggestionReason
import java.util.Locale

data class TodoCandidate(
    /** The original sentence, kept editable and suitable as the initial todo text. */
    val suggestedText: String,
    val language: SupportedLanguage,
    val reason: TodoSuggestionReason,
    /** The human-readable trigger or imperative form that matched. */
    val matchedRule: String,
)

data class TodoLanguageRules(
    val triggerPhrases: Set<String>,
    /** Conservative, common imperative words or phrases that may start a sentence. */
    val imperativeStarts: Set<String>,
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
        return sentences(text).mapNotNull { sentence ->
            detectSentence(sentence, language, languageRules)
        }
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
        return sentences(text).mapNotNull { sentence ->
            orderedLanguages.firstNotNullOfOrNull { language ->
                rules[language]?.let { detectSentence(sentence, language, it) }
            }
        }
    }

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

    private fun sentences(text: String): List<String> = text
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
        val WHITESPACE = Regex("\\s+")
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
        ),
    )
}
