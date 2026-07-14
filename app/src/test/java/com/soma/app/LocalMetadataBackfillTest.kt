package com.soma.app

import com.soma.core.model.MetadataSource
import com.soma.core.model.NoteEntry
import com.soma.core.model.SupportedLanguage
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMetadataBackfillTest {
    @Test
    fun `backfill enriches an existing note using its historical date`() = runBlocking {
        val today = LocalDate.of(2026, 7, 14)
        val oldDate = LocalDate.of(2024, 3, 4)
        val now = Instant.parse("2026-07-14T09:00:00Z")
        val repository = InMemorySomaRepository.demo(today, now)
        repository.getOrCreate(oldDate, now)
        assertTrue(
            repository.insertEntry(
                NoteEntry.text(
                    id = "backfill-entry",
                    noteDate = oldDate,
                    position = 0,
                    text = "jāpiezvana tomorrow #Darbs",
                    createdAt = now,
                ),
            ),
        )
        val repositories = SomaRepositories(
            repository,
            repository,
            repository,
            repository,
            repository,
            repository,
            repository,
        )

        val batch = backfillLocalMetadataBatch(
            repositories = repositories,
            languages = setOf(SupportedLanguage.LATVIAN, SupportedLanguage.ENGLISH),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            beforeOrOn = oldDate,
        )

        assertTrue(batch.complete)
        val layer = repository.forEntry("backfill-entry").single { it.source == MetadataSource.LOCAL }
        assertEquals(listOf("darbs"), layer.tags)
        assertEquals("2024-03-05", layer.links.single().target)
    }
}
