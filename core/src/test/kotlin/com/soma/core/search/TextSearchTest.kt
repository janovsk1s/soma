package com.soma.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    @Test
    fun `queries without diacritics find accented originals`() {
        val match = TextSearch.match("Šodien satiku Jāni pie ezera.", TextSearch.fold("jani"))
        assertNotNull(match)
        assertEquals("Jāni", match!!.highlighted())
    }

    @Test
    fun `accented queries find accented originals`() {
        val match = TextSearch.match("Jānopērk olas, piens, zeķes", TextSearch.fold("zeķes"))
        assertEquals("zeķes", match?.highlighted())
    }

    @Test
    fun `matching is case-insensitive across languages`() {
        assertNotNull(TextSearch.match("KÕNNIME hommikul", TextSearch.fold("kõnnime")))
        assertNotNull(TextSearch.match("übermorgen im Wald", TextSearch.fold("ÜBERMORGEN")))
        assertNotNull(TextSearch.match("žąsys skrenda", TextSearch.fold("Zasys")))
    }

    @Test
    fun `unrelated text does not match`() {
        assertNull(TextSearch.match("Šodien lija lietus.", TextSearch.fold("saule")))
        assertNull(TextSearch.match("", TextSearch.fold("kas")))
        assertNull(TextSearch.match("teksts", TextSearch.fold("")))
    }

    @Test
    fun `long text produces a bounded snippet with truncation flags`() {
        val text = "sākums ".repeat(30) + "meklētais vārds" + " beigas".repeat(30)
        val match = TextSearch.match(text, TextSearch.fold("meklētais"))!!
        assertTrue(match.leadingTruncated)
        assertTrue(match.trailingTruncated)
        assertTrue(match.snippet.length < text.length)
        assertEquals("meklētais", match.highlighted())
    }

    @Test
    fun `short text keeps the full line without truncation`() {
        val match = TextSearch.match("piens un maize", TextSearch.fold("maize"))!!
        assertFalse(match.leadingTruncated)
        assertFalse(match.trailingTruncated)
        assertEquals("piens un maize", match.snippet)
    }

    @Test
    fun `newlines inside the snippet flatten to spaces`() {
        val match = TextSearch.match("pirmā rinda\notrā rinda", TextSearch.fold("otrā"))!!
        assertFalse(match.snippet.contains('\n'))
        assertEquals("otrā", match.highlighted())
    }

    @Test
    fun `folding maps positions through removed marks`() {
        val folded = FoldedText.of("Āboliņš zied")
        val at = folded.indexOf("abolins")
        assertEquals(0, at)
        assertEquals(0..6, folded.originalRange(at, "abolins".length))
    }

    private fun SearchMatch.highlighted(): String =
        snippet.substring(highlightStart, highlightEndExclusive)

    private fun TextSearch.fold(query: String): String = FoldedText.foldQuery(query)
}
