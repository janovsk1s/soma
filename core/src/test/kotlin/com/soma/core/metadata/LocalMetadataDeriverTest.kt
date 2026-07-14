package com.soma.core.metadata

import com.soma.core.model.EntryLinkKind
import com.soma.core.model.SupportedLanguage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMetadataDeriverTest {
    private val monday = LocalDate.of(2026, 7, 13)

    @Test
    fun `hashtags become normalized, de-duplicated tags`() {
        val result = LocalMetadataDeriver.derive(
            "Met with #Alice about #Project_X again #Project_X",
            SupportedLanguage.ENGLISH,
            monday,
        )
        assertEquals(listOf("alice", "project_x"), result.tags)
        assertTrue(result.links.isEmpty())
    }

    @Test
    fun `a recognised due date becomes a DATE link`() {
        val result = LocalMetadataDeriver.derive("ship it tomorrow #work", SupportedLanguage.ENGLISH, monday)
        assertEquals(listOf("work"), result.tags)
        assertEquals(1, result.links.size)
        assertEquals(EntryLinkKind.DATE, result.links[0].kind)
        assertEquals("2026-07-14", result.links[0].target)
    }

    @Test
    fun `plain text yields no tags or links`() {
        val result = LocalMetadataDeriver.derive("just thinking out loud", SupportedLanguage.ENGLISH, monday)
        assertTrue(result.tags.isEmpty())
        assertTrue(result.links.isEmpty())
    }
}
