package com.soma.storage.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.EntrySource
import com.soma.core.model.ImportantKind
import com.soma.core.model.ImageAttachment
import com.soma.core.model.ImageFormat
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.NoteEntry
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.Todo
import com.soma.core.model.TodoSuggestion
import com.soma.core.model.TodoSuggestionReason
import com.soma.core.model.TodoSuggestionState
import com.soma.core.model.TranscriptSegment
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionFailureCode
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionJob
import com.soma.core.model.TranscriptionJobState
import com.soma.core.model.TranscriptionProvenance
import com.soma.core.model.TranscriptionResult
import com.soma.storage.crypto.AesGcmCipher
import com.soma.storage.backup.BackupSnapshot
import com.soma.storage.db.SomaDatabase
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSomaRepositoryTest {
    private lateinit var database: SomaDatabase
    private lateinit var repository: RoomSomaRepository
    private val date = LocalDate.of(2026, 7, 12)
    private val start = Instant.parse("2026-07-12T08:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SomaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomSomaRepository(
            database,
            AesGcmCipher(ByteArray(AesGcmCipher.KEY_BYTES) { (it * 3).toByte() }),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `one note per date contains ordered decrypted entries`() = runBlocking {
        val first = repository.getOrCreate(date, start)
        val second = repository.getOrCreate(date, start.plusSeconds(500))
        assertEquals(first.createdAt, second.createdAt)

        val later = NoteEntry.text("entry-2", date, 1, "second thought", start.plusSeconds(2))
        val earlier = NoteEntry.text("entry-1", date, 0, "first thought", start.plusSeconds(1))
        assertTrue(repository.insertEntry(later))
        assertTrue(repository.insertEntry(earlier))
        assertFalse(repository.insertEntry(earlier.copy(id = "duplicate-position")))

        val observed = repository.observe(date).first()
        assertEquals(listOf("first thought", "second thought"), observed?.entries?.map { it.text })
        assertEquals(listOf(date), repository.listBeforeOrOn(date, 5).map { it.date })
    }

    @Test
    fun `user edits keep position and store encrypted revision timestamps`() = runBlocking {
        repository.getOrCreate(date, start)
        repository.insertEntry(NoteEntry.text("entry-1", date, 0, "first wording", start))
        val editedAt = start.plusSeconds(90)

        val mutation = repository.editEntryText("entry-1", "better wording", editedAt)

        assertEquals(0, mutation?.current?.position)
        assertEquals(start, mutation?.current?.createdAt)
        assertEquals(editedAt, mutation?.current?.lastUserEditedAt)
        assertEquals("first wording", repository.listEntryRevisions("entry-1").single().text)
        database.openHelper.writableDatabase
            .query("SELECT text_ciphertext FROM entry_revisions WHERE entry_id = 'entry-1'")
            .use { cursor ->
                cursor.moveToFirst()
                assertFalse(cursor.getBlob(0).containsSubsequence("first wording".encodeToByteArray()))
            }
    }

    @Test
    fun `soft delete and undo preserve text audio history and authored timestamps`() = runBlocking {
        repository.getOrCreate(date, start)
        val editedAt = start.plusSeconds(30)
        repository.insertEntry(NoteEntry.text("entry-1", date, 0, "original", start))
        repository.editEntryText("entry-1", "current", editedAt)
        val before = requireNotNull(repository.getEntry("entry-1"))
        val deletedAt = start.plusSeconds(60)

        val deleted = repository.mutateEntry("entry-1") { it.copy(deletedAt = deletedAt) }

        assertEquals(before.createdAt, deleted?.current?.createdAt)
        assertEquals(before.updatedAt, deleted?.current?.updatedAt)
        assertEquals(before.lastUserEditedAt, deleted?.current?.lastUserEditedAt)
        assertNull(repository.getEntry("entry-1"))
        assertTrue(repository.get(date)?.entries?.isEmpty() == true)
        assertEquals(1, repository.nextEntryPosition(date))
        assertEquals("entry-1", repository.observeDeleted().first().single().id)
        assertEquals("original", repository.listEntryRevisions("entry-1").single().text)

        repository.mutateEntry("entry-1") { it.copy(deletedAt = null) }
        val restored = requireNotNull(repository.getEntry("entry-1"))
        assertEquals(before, restored)
        assertTrue(repository.observeDeleted().first().isEmpty())
    }

    @Test
    fun `audio tombstone hides playback but retains encrypted attachment metadata`() = runBlocking {
        repository.getOrCreate(date, start)
        val voice = voiceEntry("voice-1").copy(text = "kept transcript")
        repository.insertEntry(voice)

        repository.mutateEntry("voice-1") { it.copy(audioDeletedAt = start.plusSeconds(20)) }

        val visible = requireNotNull(repository.getEntry("voice-1"))
        assertNull(visible.activeAudio)
        assertEquals("audio-voice-1", visible.audio?.fileId)
        assertEquals(start, visible.createdAt)
        assertEquals(start, visible.updatedAt)
        assertEquals("voice-1", repository.observeDeleted().first().single().id)
    }

    @Test
    fun `image attachment and tombstone round trip without changing authored time`() = runBlocking {
        repository.getOrCreate(date, start)
        val image = ImageAttachment("image-1", ImageFormat.JPEG, 1280, 960, 90, 4_096)
        repository.insertEntry(NoteEntry.image("photo-1", date, 0, image, start, "train window"))

        assertEquals(image, repository.getEntry("photo-1")?.activeImage)
        repository.mutateEntry("photo-1") { it.copy(imageDeletedAt = start.plusSeconds(20)) }

        val visible = requireNotNull(repository.getEntry("photo-1"))
        assertNull(visible.activeImage)
        assertEquals(image, visible.image)
        assertEquals(start, visible.createdAt)
        assertEquals("photo-1", repository.observeDeleted().first().single().id)
        assertEquals(setOf("image-1"), repository.referencedImageFileIds())
    }

    @Test
    fun `suggestion acceptance creates todo and resolves suggestion atomically`() = runBlocking {
        repository.getOrCreate(date, start)
        repository.insertEntry(NoteEntry.text("entry-1", date, 0, "Need to call Anna", start))
        val suggestion = TodoSuggestion(
            id = "suggestion-1",
            entryId = "entry-1",
            suggestedText = "call Anna",
            suggestedKind = ImportantKind.LIST,
            language = SupportedLanguage.ENGLISH,
            reason = TodoSuggestionReason.TRIGGER_PHRASE,
            matchedRule = "need to",
            state = TodoSuggestionState.PENDING,
            createdAt = start,
        )
        assertTrue(repository.insert(suggestion))
        val todo = Todo(
            id = "todo-1",
            text = "call Anna",
            createdAt = start.plusSeconds(10),
            updatedAt = start.plusSeconds(10),
            kind = ImportantKind.LIST,
            source = EntrySource(date, "entry-1"),
        )

        assertTrue(repository.accept(suggestion.id, todo, start.plusSeconds(11)))
        assertEquals("call Anna", repository.get(todo.id)?.text)
        assertEquals(ImportantKind.LIST, repository.get(todo.id)?.kind)
        assertTrue(repository.pendingForEntry("entry-1").isEmpty())
        assertEquals(
            TodoSuggestionState.ACCEPTED,
            repository.observeForEntry("entry-1").first().single().state,
        )
        assertEquals(
            ImportantKind.LIST,
            repository.observeForEntry("entry-1").first().single().suggestedKind,
        )
        assertFalse(repository.accept(suggestion.id, todo.copy(id = "todo-2"), start.plusSeconds(12)))
        assertNull(repository.get("todo-2"))
    }

    @Test
    fun `transcription completion updates job and editable encrypted transcript together`() =
        runBlocking {
            repository.getOrCreate(date, start)
            assertTrue(repository.insertEntry(voiceEntry("voice-1")))
            assertTrue(repository.enqueue(TranscriptionJob.queued("job-1", "voice-1", start)))
            val claimed = repository.claimNext("worker", start, Duration.ofMinutes(5))
            assertEquals(TranscriptionJobState.RUNNING, claimed?.state)

            val result = TranscriptionResult(
                segments = listOf(
                    TranscriptSegment(0, 800, "Jāatceras", SupportedLanguage.LATVIAN),
                    TranscriptSegment(900, 1_500, "call Anna", SupportedLanguage.ENGLISH),
                ),
                provenance = TranscriptionProvenance(
                    requestedEngine = TranscriptionEngine.ELEVENLABS_SCRIBE_V2,
                    usedEngine = TranscriptionEngine.ELEVENLABS_SCRIBE_V2,
                ),
            )
            assertTrue(repository.complete("job-1", "worker", result, start.plusSeconds(5)))

            val entry = repository.getEntry("voice-1")
            assertEquals("Jāatceras call Anna", entry?.text)
            assertEquals(EntryTranscriptionState.SUCCEEDED, entry?.transcription?.state)
            assertEquals(
                listOf(SupportedLanguage.LATVIAN, SupportedLanguage.ENGLISH),
                entry?.transcription?.detectedLanguages,
            )
            assertEquals(result.provenance, entry?.transcription?.provenance)
            assertEquals(TranscriptionJobState.SUCCEEDED, repository.getForEntry("voice-1")?.state)

            val raw = database.openHelper.writableDatabase
                .query("SELECT text_ciphertext FROM entries WHERE id = 'voice-1'")
            raw.use { cursor ->
                cursor.moveToFirst()
                assertFalse(
                    cursor.getBlob(0).containsSubsequence("Jāatceras call Anna".encodeToByteArray()),
                )
            }
        }

    @Test
    fun `spoken photo comment transcribes without losing the image or authored timestamp`() = runBlocking {
        repository.getOrCreate(date, start)
        val image = ImageAttachment("image-photo-1", ImageFormat.JPEG, 1280, 960, 0, 4_096)
        val audio = AudioAttachment("audio-photo-1", AudioFormat.WAV, 1_500, 48_044)
        val photo = NoteEntry.image("photo-1", date, 0, image, start).copy(
            audio = audio,
            transcription = com.soma.core.model.TranscriptionInfo(
                state = EntryTranscriptionState.QUEUED,
                updatedAt = start.plusSeconds(1),
            ),
        )
        assertTrue(repository.insertEntry(photo))
        assertTrue(repository.enqueue(TranscriptionJob.queued("photo-job", photo.id, start.plusSeconds(1))))
        repository.claimNext("worker", start.plusSeconds(1), Duration.ofMinutes(5))

        assertTrue(
            repository.complete(
                "photo-job",
                "worker",
                TranscriptionResult(
                    segments = listOf(
                        TranscriptSegment(0, 1_500, "Milchreis recipe", SupportedLanguage.GERMAN),
                    ),
                    provenance = TranscriptionProvenance.local(),
                ),
                start.plusSeconds(5),
            ),
        )

        val transcribed = requireNotNull(repository.getEntry(photo.id))
        assertEquals(com.soma.core.model.EntryKind.IMAGE, transcribed.kind)
        assertEquals(image, transcribed.activeImage)
        assertEquals(audio, transcribed.activeAudio)
        assertEquals("Milchreis recipe", transcribed.text)
        assertEquals(start, transcribed.createdAt)
        assertNull(transcribed.lastUserEditedAt)
    }

    @Test
    fun `retryable then terminal transcription failures preserve the recording`() = runBlocking {
        repository.getOrCreate(date, start)
        repository.insertEntry(voiceEntry("voice-1"))
        repository.enqueue(TranscriptionJob.queued("job-1", "voice-1", start))
        repository.claimNext("worker-1", start, Duration.ofMinutes(1))
        val retryAt = start.plusSeconds(120)
        val retryable = TranscriptionFailure(
            code = TranscriptionFailureCode.ENGINE_UNAVAILABLE,
            retryable = true,
            diagnostic = "engine warming up",
        )
        assertTrue(
            repository.recordFailure(
                "job-1",
                "worker-1",
                retryable,
                start.plusSeconds(10),
                retryAt,
            ),
        )
        assertEquals(TranscriptionJobState.QUEUED, repository.getForEntry("voice-1")?.state)
        assertNull(repository.claimNext("too-early", retryAt.minusMillis(1), Duration.ofMinutes(1)))

        repository.claimNext("worker-2", retryAt, Duration.ofMinutes(1))
        val terminal = retryable.copy(
            code = TranscriptionFailureCode.MODEL_ERROR,
            retryable = false,
            diagnostic = "model rejected audio",
        )
        assertTrue(
            repository.recordFailure(
                "job-1",
                "worker-2",
                terminal,
                retryAt.plusSeconds(5),
                retryAt = null,
            ),
        )

        val entry = repository.getEntry("voice-1")
        assertEquals("audio-voice-1", entry?.audio?.fileId)
        assertEquals(EntryTranscriptionState.FAILED, entry?.transcription?.state)
        assertEquals(terminal, entry?.transcription?.failure)
        assertEquals(terminal, repository.getForEntry("voice-1")?.lastFailure)
    }

    @Test
    fun `restart replaces a completed job while preserving audio and existing text`() = runBlocking {
        repository.getOrCreate(date, start)
        repository.insertEntry(voiceEntry("voice-1"))
        repository.enqueue(TranscriptionJob.queued("job-1", "voice-1", start))
        repository.claimNext("worker", start, Duration.ofMinutes(1))
        repository.complete(
            "job-1",
            "worker",
            TranscriptionResult(
                segments = listOf(TranscriptSegment(0, 500, "old transcript", SupportedLanguage.ENGLISH)),
                provenance = TranscriptionProvenance.local(),
            ),
            start.plusSeconds(1),
        )

        val restartedAt = start.plusSeconds(2)
        assertTrue(
            repository.restart(TranscriptionJob.queued("job-2", "voice-1", restartedAt)),
        )

        val job = repository.getForEntry("voice-1")
        val entry = repository.getEntry("voice-1")
        assertEquals("job-2", job?.id)
        assertEquals(TranscriptionJobState.QUEUED, job?.state)
        assertEquals("old transcript", entry?.text)
        assertEquals("audio-voice-1", entry?.audio?.fileId)
        assertEquals(EntryTranscriptionState.QUEUED, entry?.transcription?.state)
        assertNull(entry?.transcription?.provenance)
    }

    @Test
    fun `expired leases stop after the maximum attempt instead of retrying forever`() = runBlocking {
        repository.getOrCreate(date, start)
        repository.insertEntry(voiceEntry("voice-1"))
        repository.enqueue(TranscriptionJob.queued("job-1", "voice-1", start))

        var claimAt = start
        repeat(3) { attempt ->
            val claimed = repository.claimNext("worker-$attempt", claimAt, Duration.ofSeconds(1))
            assertEquals(attempt + 1, claimed?.attemptCount)
            claimAt = claimAt.plusSeconds(2)
            assertEquals(1, repository.releaseExpiredLeases(claimAt))
        }

        val job = repository.getForEntry("voice-1")
        assertEquals(TranscriptionJobState.FAILED, job?.state)
        assertEquals(TranscriptionFailureCode.CANCELLED, job?.lastFailure?.code)
        assertEquals(EntryTranscriptionState.FAILED, repository.getEntry("voice-1")?.transcription?.state)
        assertNull(repository.claimNext("worker-forever", claimAt, Duration.ofSeconds(1)))
    }

    @Test
    fun `still-open dismissal round trips by local date`() = runBlocking {
        val dismissal = StillOpenDismissal(date, start)
        repository.dismiss(dismissal)

        assertEquals(dismissal, repository.dismissal(date))
        assertEquals(dismissal, repository.observeDismissal(date).first())
        assertNull(repository.dismissal(date.plusDays(1)))
    }

    @Test
    fun `portable restore atomically replaces existing rows`() = runBlocking {
        repository.getOrCreate(date.minusDays(1), start)
        repository.insertEntry(
            NoteEntry.text("old-entry", date.minusDays(1), 0, "old plaintext", start),
        )
        val restoredEntry = NoteEntry.text("new-entry", date, 0, "restored thought", start.plusSeconds(1))
        val restoredTodo = Todo("new-todo", "restored todo", start, start)
        repository.replaceAll(
            BackupSnapshot(
                exportedAt = start.plusSeconds(2),
                notes = listOf(com.soma.core.model.DailyNote(date, start, listOf(restoredEntry))),
                todos = listOf(restoredTodo),
                suggestions = emptyList(),
            ),
        )

        assertNull(repository.get(date.minusDays(1)))
        assertEquals("restored thought", repository.get(date)?.entries?.single()?.text)
        assertEquals("restored todo", repository.get("new-todo")?.text)
    }

    private fun voiceEntry(id: String) = NoteEntry.voice(
        id = id,
        noteDate = date,
        position = 0,
        audio = AudioAttachment(
            fileId = "audio-$id",
            format = AudioFormat.WAV,
            durationMillis = 1_500,
            byteCount = 48_044,
        ),
        createdAt = start,
        transcriptionEnabled = true,
    )

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
    }
}
