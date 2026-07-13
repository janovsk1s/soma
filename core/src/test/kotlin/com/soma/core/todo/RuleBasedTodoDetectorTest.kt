package com.soma.core.todo

import com.soma.core.model.SupportedLanguage
import com.soma.core.model.ImportantKind
import com.soma.core.model.TodoSuggestionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedTodoDetectorTest {
    private val detector = RuleBasedTodoDetector()

    @Test
    fun `trigger phrases produce a suggestion in every supported language`() {
        val examples = mapOf(
            SupportedLanguage.ENGLISH to "I need to buy milk.",
            SupportedLanguage.LATVIAN to "Man jāizdara mājasdarbs.",
            SupportedLanguage.ESTONIAN to "Mul on vaja emale helistada.",
            SupportedLanguage.LITHUANIAN to "Man reikia paskambinti mamai.",
            SupportedLanguage.FINNISH to "Minun pitää soittaa äidille.",
            SupportedLanguage.SWEDISH to "Jag måste ringa mamma.",
            SupportedLanguage.GERMAN to "Ich muss Mama anrufen.",
            SupportedLanguage.SLOVAK to "Musím zavolať mame.",
        )

        examples.forEach { (language, text) ->
            val candidates = detector.detect(text, language)
            assertEquals("Expected one trigger candidate for $language", 1, candidates.size)
            assertEquals(TodoSuggestionReason.TRIGGER_PHRASE, candidates.single().reason)
            assertEquals(language, candidates.single().language)
            assertEquals(text, candidates.single().suggestedText)
        }
    }

    @Test
    fun `imperative starts produce a suggestion in every supported language`() {
        val examples = mapOf(
            SupportedLanguage.ENGLISH to "Buy milk.",
            SupportedLanguage.LATVIAN to "Nopērc pienu.",
            SupportedLanguage.ESTONIAN to "Helista emale.",
            SupportedLanguage.LITHUANIAN to "Paskambink mamai.",
            SupportedLanguage.FINNISH to "Soita äidille.",
            SupportedLanguage.SWEDISH to "Ring mamma.",
            SupportedLanguage.GERMAN to "Ruf Mama an.",
            SupportedLanguage.SLOVAK to "Zavolaj mame.",
        )

        examples.forEach { (language, text) ->
            val candidate = detector.detect(text, language).singleOrNull()
            assertTrue("Expected imperative candidate for $language", candidate != null)
            assertEquals(TodoSuggestionReason.IMPERATIVE, candidate!!.reason)
            assertEquals(language, candidate.language)
        }
    }

    @Test
    fun `ordinary statements stay quiet in every supported language`() {
        val examples = mapOf(
            SupportedLanguage.ENGLISH to "The walk through the park was calm.",
            SupportedLanguage.LATVIAN to "Šodien parkā bija mierīgi.",
            SupportedLanguage.ESTONIAN to "Täna oli pargis vaikne.",
            SupportedLanguage.LITHUANIAN to "Šiandien parke buvo ramu.",
            SupportedLanguage.FINNISH to "Puistossa oli tänään rauhallista.",
            SupportedLanguage.SWEDISH to "Det var lugnt i parken i dag.",
            SupportedLanguage.GERMAN to "Heute war es ruhig im Park.",
            SupportedLanguage.SLOVAK to "Dnes bolo v parku pokojne.",
        )

        examples.forEach { (language, text) ->
            assertTrue("Unexpected suggestion for $language", detector.detect(text, language).isEmpty())
        }
    }

    @Test
    fun `trigger-looking substrings do not match whole phrases`() {
        val nearMisses = mapOf(
            SupportedLanguage.ENGLISH to "Todoist is installed.",
            SupportedLanguage.LATVIAN to "Vajadzības mainās.",
            SupportedLanguage.ESTONIAN to "Ülesannetest rääkisime eile.",
            SupportedLanguage.LITHUANIAN to "Būtinas poilsis.",
            SupportedLanguage.FINNISH to "Tehtävästä keskusteltiin.",
            SupportedLanguage.SWEDISH to "Måsterverket hänger där.",
            SupportedLanguage.GERMAN to "Der Musselin ist weich.",
            SupportedLanguage.SLOVAK to "Potreba oddychu je prirodzená.",
        )

        nearMisses.forEach { (language, text) ->
            assertTrue("Substring matched for $language", detector.detect(text, language).isEmpty())
        }
    }

    @Test
    fun `questions are not treated as commitments`() {
        val questions = mapOf(
            SupportedLanguage.ENGLISH to "Do I need to leave now?",
            SupportedLanguage.LATVIAN to "Vai man vajag doties prom?",
            SupportedLanguage.ESTONIAN to "Kas mul on vaja lahkuda?",
            SupportedLanguage.LITHUANIAN to "Ar man reikia išeiti?",
            SupportedLanguage.FINNISH to "Pitääkö minun lähteä?",
            SupportedLanguage.SWEDISH to "Måste jag gå?",
            SupportedLanguage.GERMAN to "Muss ich gehen?",
            SupportedLanguage.SLOVAK to "Musím už ísť?",
        )

        questions.forEach { (language, text) ->
            assertTrue("Question matched for $language", detector.detect(text, language).isEmpty())
        }
    }

    @Test
    fun `multiple sentences yield only matching candidates`() {
        val result = detector.detect(
            "The morning was calm. I need to call Ada! The evening was calm too.",
            SupportedLanguage.ENGLISH,
        )

        assertEquals(1, result.size)
        assertEquals("I need to call Ada!", result.single().suggestedText)
    }

    @Test
    fun `code switched detection chooses the matching language per sentence`() {
        val result = detector.detect(
            text = "Nopērc pienu. Send the letter.",
            expectedLanguages = setOf(SupportedLanguage.ENGLISH, SupportedLanguage.LATVIAN),
        )

        assertEquals(listOf(SupportedLanguage.LATVIAN, SupportedLanguage.ENGLISH), result.map { it.language })
    }

    @Test
    fun `list markers and curly apostrophes are normalized`() {
        val result = detector.detect(
            "- Don’t forget the keys",
            SupportedLanguage.ENGLISH,
        )

        assertEquals(1, result.size)
        assertEquals("don't forget", result.single().matchedRule)
    }

    @Test
    fun `empty expected language set remains quiet`() {
        assertTrue(detector.detect("Buy milk.", emptySet()).isEmpty())
    }

    @Test
    fun `explicit grocery headings produce one list suggestion in every language`() {
        val examples = mapOf(
            SupportedLanguage.ENGLISH to "Shopping list: milk, rice, bananas",
            SupportedLanguage.LATVIAN to "Iepirkumu saraksts: piens, rīsi, banāni",
            SupportedLanguage.ESTONIAN to "Ostunimekiri: piim, riis, banaanid",
            SupportedLanguage.LITHUANIAN to "Pirkinių sąrašas: pienas, ryžiai, bananai",
            SupportedLanguage.FINNISH to "Ostoslista: maito, riisi, banaanit",
            SupportedLanguage.SWEDISH to "Inköpslista: mjölk, ris, bananer",
            SupportedLanguage.GERMAN to "Einkaufsliste: Milch, Reis, Bananen",
            SupportedLanguage.SLOVAK to "Nákupný zoznam: mlieko, ryža, banány",
        )

        examples.forEach { (language, text) ->
            val candidate = detector.detect(text, language).single()
            assertEquals(ImportantKind.LIST, candidate.kind)
            assertEquals(TodoSuggestionReason.LIST_PATTERN, candidate.reason)
            assertEquals(3, candidate.suggestedText.lines().size)
        }
    }

    @Test
    fun `two bullet lines become one bounded list`() {
        val candidate = detector.detect("- milk\n- rice\n- bananas", SupportedLanguage.ENGLISH).single()

        assertEquals(ImportantKind.LIST, candidate.kind)
        assertEquals("milk\nrice\nbananas", candidate.suggestedText)
    }

    @Test
    fun `detector caps action candidates for oversized input`() {
        val input = List(100) { "Buy item $it." }.joinToString(" ")

        assertEquals(20, detector.detect(input, SupportedLanguage.ENGLISH).size)
    }
}
