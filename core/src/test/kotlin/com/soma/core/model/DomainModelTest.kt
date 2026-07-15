package com.soma.core.model

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DomainModelTest {
    private val date = LocalDate.of(2026, 7, 12)
    private val now = Instant.parse("2026-07-12T10:00:00Z")

    @Test
    fun `voice factory keeps audio immediately and queues optional transcription`() {
        val entry = NoteEntry.voice(
            id = "voice-1",
            noteDate = date,
            position = 0,
            audio = audio(),
            createdAt = now,
            transcriptionEnabled = true,
        )

        assertEquals(EntryKind.VOICE, entry.kind)
        assertEquals("audio-1", entry.audio!!.fileId)
        assertEquals(EntryTranscriptionState.QUEUED, entry.transcription!!.state)
        assertTrue(entry.text.isEmpty())
        assertFalse(entry.hasTranscript)
    }

    @Test
    fun `voice factory can preserve recording with transcription disabled`() {
        val entry = NoteEntry.voice(
            id = "voice-1",
            noteDate = date,
            position = 0,
            audio = audio(),
            createdAt = now,
            transcriptionEnabled = false,
        )

        assertEquals(EntryTranscriptionState.DISABLED, entry.transcription!!.state)
        assertEquals(audio(), entry.audio)
    }

    @Test
    fun `audio and entry tombstones keep the recoverable attachment`() {
        val audioDeletedAt = now.plusSeconds(10)
        val deletedAt = now.plusSeconds(20)
        val entry = NoteEntry.voice(
            id = "voice-1",
            noteDate = date,
            position = 0,
            audio = audio(),
            createdAt = now,
            transcriptionEnabled = false,
        ).copy(audioDeletedAt = audioDeletedAt)

        assertNull(entry.activeAudio)
        assertEquals(audio(), entry.audio)
        assertFalse(entry.isDeleted)

        val deleted = entry.copy(deletedAt = deletedAt)
        assertTrue(deleted.isDeleted)
        assertEquals(now, deleted.createdAt)
        assertEquals(audio(), deleted.audio)
    }

    @Test
    fun `image factory keeps encrypted original metadata through a tombstone`() {
        val image = ImageAttachment("image-1", ImageFormat.JPEG, 1280, 960, 90, 12_000)
        val entry = NoteEntry.image("image-entry", date, 0, image, now, "train window")

        assertEquals(EntryKind.IMAGE, entry.kind)
        assertEquals(image, entry.activeImage)
        val deleted = entry.copy(imageDeletedAt = now.plusSeconds(5))
        assertNull(deleted.activeImage)
        assertEquals(image, deleted.image)
        assertEquals(now, deleted.createdAt)
    }

    @Test
    fun `image can keep an encrypted spoken comment and transcript`() {
        val image = ImageAttachment("image-1", ImageFormat.JPEG, 1280, 960, 0, 12_000)
        val spokenComment = NoteEntry.image("image-entry", date, 0, image, now).copy(
            audio = audio(),
            text = "milk rice on the train",
            transcription = TranscriptionInfo(
                state = EntryTranscriptionState.SUCCEEDED,
                updatedAt = now.plusSeconds(4),
            ),
        )

        assertEquals(EntryKind.IMAGE, spokenComment.kind)
        assertEquals(image, spokenComment.activeImage)
        assertEquals(audio(), spokenComment.activeAudio)
        assertTrue(spokenComment.hasTranscript)
    }

    @Test
    fun `metadata normalizes portable tags and validates links`() {
        assertEquals("milk-rice", normalizeMetadataTag(" #Milk Rice "))
        assertNull(normalizeMetadataTag("!!!"))
        val metadata = EntryMetadata(
            entryId = "entry-1",
            tags = listOf("recipe", "milk-rice"),
            links = listOf(
                EntryLink(EntryLinkKind.DATE, "2026-07-14", "mentioned-on"),
                EntryLink(EntryLinkKind.TAG, "groceries"),
            ),
            derivedAt = now,
            source = MetadataSource.AI,
        )
        assertEquals(2, metadata.tags.size)
        assertThrows(IllegalArgumentException::class.java) {
            EntryLink(EntryLinkKind.DATE, "14.07.2026")
        }
    }

    @Test
    fun `daily note accepts only ordered entries from its date`() {
        val first = NoteEntry.text("one", date, 1, "One", now)
        val second = NoteEntry.text("two", date, 2, "Two", now)

        assertEquals(listOf(first, second), DailyNote(date, now, listOf(first, second)).entries)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `daily note rejects duplicate positions`() {
        DailyNote(
            date,
            now,
            listOf(
                NoteEntry.text("one", date, 1, "One", now),
                NoteEntry.text("two", date, 1, "Two", now),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `daily note rejects entry from another date`() {
        DailyNote(
            date,
            now,
            listOf(NoteEntry.text("one", date.minusDays(1), 0, "One", now)),
        )
    }

    @Test
    fun `language tags accept regional variants and reject unsupported languages`() {
        assertEquals(SupportedLanguage.GERMAN, SupportedLanguage.fromLanguageTag("de-AT"))
        assertEquals(SupportedLanguage.LATVIAN, SupportedLanguage.fromLanguageTag("LV-lv"))
        assertNull(SupportedLanguage.fromLanguageTag("fr-FR"))
    }

    @Test
    fun `transcription provenance distinguishes cloud success from local fallback`() {
        val cloud = TranscriptionProvenance(
            requestedEngine = TranscriptionEngine.ELEVENLABS_SCRIBE_V2,
            usedEngine = TranscriptionEngine.ELEVENLABS_SCRIBE_V2,
        )
        val fallback = TranscriptionProvenance(
            requestedEngine = TranscriptionEngine.ELEVENLABS_SCRIBE_V2,
            usedEngine = TranscriptionEngine.LOCAL_WHISPER_TINY,
            fallbackReason = TranscriptionFallbackReason.PROVIDER_ERROR,
        )

        assertEquals(TranscriptionEngine.ELEVENLABS_SCRIBE_V2, cloud.usedEngine)
        assertEquals(TranscriptionEngine.LOCAL_WHISPER_TINY, fallback.usedEngine)
        assertEquals(TranscriptionFallbackReason.PROVIDER_ERROR, fallback.fallbackReason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `transcription provenance rejects unexplained engine changes`() {
        TranscriptionProvenance(
            requestedEngine = TranscriptionEngine.GROQ_WHISPER_LARGE_V3,
            usedEngine = TranscriptionEngine.LOCAL_WHISPER_TINY,
        )
    }

    @Test
    fun `local provenance covers both on-device models and defaults to tiny`() {
        assertEquals(TranscriptionEngine.LOCAL_WHISPER_TINY, TranscriptionProvenance.local().usedEngine)
        val base = TranscriptionProvenance.local(TranscriptionEngine.LOCAL_WHISPER_BASE)
        assertEquals(TranscriptionEngine.LOCAL_WHISPER_BASE, base.requestedEngine)
        assertEquals(TranscriptionEngine.LOCAL_WHISPER_BASE, base.usedEngine)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `local provenance rejects cloud engines`() {
        TranscriptionProvenance.local(TranscriptionEngine.GROQ_WHISPER_LARGE_V3)
    }

    @Test
    fun `cloud fallback may finish on the downloaded base model`() {
        val fallback = TranscriptionProvenance(
            requestedEngine = TranscriptionEngine.GROQ_WHISPER_LARGE_V3_TURBO,
            usedEngine = TranscriptionEngine.LOCAL_WHISPER_BASE,
            fallbackReason = TranscriptionFallbackReason.NETWORK_ERROR,
        )
        assertEquals(TranscriptionEngine.LOCAL_WHISPER_BASE, fallback.usedEngine)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a local engine can never be the source of a cloud fallback`() {
        TranscriptionProvenance(
            requestedEngine = TranscriptionEngine.LOCAL_WHISPER_BASE,
            usedEngine = TranscriptionEngine.LOCAL_WHISPER_TINY,
            fallbackReason = TranscriptionFallbackReason.PROVIDER_ERROR,
        )
    }

    private fun audio(): AudioAttachment = AudioAttachment(
        fileId = "audio-1",
        format = AudioFormat.WAV,
        durationMillis = 1_000,
        byteCount = 32_044,
    )
}
