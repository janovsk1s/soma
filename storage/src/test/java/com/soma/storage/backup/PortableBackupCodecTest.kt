package com.soma.storage.backup

import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.DailyNote
import com.soma.core.model.EntrySource
import com.soma.core.model.EntryLink
import com.soma.core.model.EntryLinkKind
import com.soma.core.model.EntryMetadata
import com.soma.core.model.ImportantKind
import com.soma.core.model.ImageAttachment
import com.soma.core.model.ImageFormat
import com.soma.core.model.FoodItem
import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.LogRevision
import com.soma.core.model.MetadataSource
import com.soma.core.model.NoteEntry
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import com.soma.core.model.TodoSuggestion
import com.soma.core.model.TodoSuggestionReason
import com.soma.core.model.TodoSuggestionState
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionFailureCode
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionInfo
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.TranscriptionJob
import com.soma.core.model.TranscriptionJobState
import com.soma.core.model.TranscriptionProvenance
import java.time.Instant
import java.time.LocalDate
import java.util.Arrays
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PortableBackupCodecTest {
    @Test
    fun `complete snapshot round trips with encrypted audio bytes`() {
        val decoded = decode(FULL_ENCODED, PASSPHRASE)

        assertEquals(SNAPSHOT, decoded)
        assertArrayEquals(
            SNAPSHOT.audioContainers.single().portableWavBytes(),
            decoded.audioContainers.single().portableWavBytes(),
        )
        assertArrayEquals(
            SNAPSHOT.imageContainers.single().portableJpegBytes(),
            decoded.imageContainers.single().portableJpegBytes(),
        )
    }

    @Test
    fun `wrong passphrase is rejected`() {
        assertThrows(BackupAuthenticationException::class.java) {
            decode(FULL_ENCODED, "this password is wrong")
        }
    }

    @Test
    fun `authenticated header and ciphertext tampering are rejected`() {
        val headerTamper = FULL_ENCODED.copyOf().also { it[SALT_OFFSET] = (it[SALT_OFFSET] + 1).toByte() }
        assertThrows(BackupAuthenticationException::class.java) {
            decode(headerTamper, PASSPHRASE)
        }

        val ciphertextTamper = FULL_ENCODED.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
        }
        assertThrows(BackupAuthenticationException::class.java) {
            decode(ciphertextTamper, PASSPHRASE)
        }
    }

    @Test
    fun `truncated backups fail closed`() {
        assertThrows(BackupFormatException::class.java) {
            decode(FULL_ENCODED.copyOf(FULL_ENCODED.size - 1), PASSPHRASE)
        }
        assertThrows(BackupFormatException::class.java) {
            decode(FULL_ENCODED.copyOf(24), PASSPHRASE)
        }
    }

    @Test
    fun `text-only snapshot carries no audio containers`() {
        val textOnly = SNAPSHOT.copy(audioContainers = emptyList(), imageContainers = emptyList())
        val encoded = encode(textOnly, PASSPHRASE)
        val decoded = decode(encoded, PASSPHRASE)

        assertTrue(decoded.audioContainers.isEmpty())
        assertTrue(decoded.imageContainers.isEmpty())
        assertEquals(textOnly, decoded)
    }

    @Test
    fun `photo entry round trip can carry a spoken comment`() {
        val commentedPhoto = IMAGE_ENTRY.copy(
            text = "Milchreis recipe",
            audio = AUDIO.copy(fileId = "audio-image-comment"),
            transcription = TranscriptionInfo(
                state = EntryTranscriptionState.SUCCEEDED,
                detectedLanguages = listOf(SupportedLanguage.GERMAN),
                provenance = TranscriptionProvenance.local(),
                updatedAt = START.plusSeconds(8),
            ),
        )
        val snapshot = BackupSnapshot(
            exportedAt = START.plusSeconds(9),
            notes = listOf(DailyNote(DATE, START, listOf(commentedPhoto))),
            todos = emptyList(),
            suggestions = emptyList(),
        )

        val encoded = BackupPayloadCodec.encode(snapshot)
        try {
            val decoded = BackupPayloadCodec.decode(encoded, BackupSnapshot.CURRENT_PAYLOAD_VERSION)
            assertEquals(commentedPhoto, decoded.notes.single().entries.single())
        } finally {
            encoded.fill(0)
        }
    }

    @Test
    fun `portable payload keeps structured logs and their prior versions`() {
        val original = LogRecord(
            id = "recipe-log",
            kind = LogKind.RECIPE,
            title = "Milchreis",
            occurredAt = START,
            createdAt = START,
            updatedAt = START,
            source = EntrySource(DATE, TEXT_ENTRY.id),
            foods = listOf(FoodItem("Rice"), FoodItem("Milk")),
        )
        val revised = original.revise(title = "Rice pudding", at = START.plusSeconds(30))
        val snapshot = BackupSnapshot(
            exportedAt = START.plusSeconds(60),
            notes = listOf(DailyNote(DATE, START, listOf(TEXT_ENTRY))),
            trackingLogs = listOf(revised),
            trackingLogRevisions = listOf(
                LogRevision(original.id, original.revision, original, revised.updatedAt),
            ),
            todos = emptyList(),
            suggestions = emptyList(),
        )

        val encoded = BackupPayloadCodec.encode(snapshot)
        try {
            assertEquals(
                snapshot,
                BackupPayloadCodec.decode(encoded, BackupSnapshot.CURRENT_PAYLOAD_VERSION),
            )
        } finally {
            encoded.fill(0)
        }
    }

    @Test
    fun `new backup passphrase must contain twelve characters`() {
        val passphrase = "eleven-char".toCharArray()
        try {
            assertEquals(11, passphrase.size)
            assertThrows(BackupPassphraseException::class.java) {
                PortableBackupCodec().encode(SNAPSHOT, passphrase)
            }
        } finally {
            Arrays.fill(passphrase, '\u0000')
        }
    }

    @Test
    fun `plaintext payload serialization is deterministic`() {
        val first = BackupPayloadCodec.encode(SNAPSHOT)
        val second = BackupPayloadCodec.encode(SNAPSHOT)
        try {
            assertArrayEquals(first, second)
            assertFalse(first.isEmpty())
        } finally {
            Arrays.fill(first, 0)
            Arrays.fill(second, 0)
        }
    }

    companion object {
        private const val PASSPHRASE = "correct horse battery staple"
        // Header offset: magic + versions + KDF id + iterations + key bits + salt length.
        private const val SALT_OFFSET = 50
        private val DATE = LocalDate.of(2026, 7, 12)
        private val START = Instant.parse("2026-07-12T08:00:00.123456789Z")
        private val FAILURE = TranscriptionFailure(
            code = TranscriptionFailureCode.ENGINE_UNAVAILABLE,
            retryable = true,
            diagnostic = "engine temporarily unavailable",
        )
        private val AUDIO = AudioAttachment(
            fileId = "audio-voice-1",
            format = AudioFormat.WAV,
            durationMillis = 1_250,
            byteCount = 40_044,
            sampleRateHz = 16_000,
            channelCount = 1,
        )
        private val IMAGE = ImageAttachment(
            fileId = "image-1",
            format = ImageFormat.JPEG,
            width = 1280,
            height = 960,
            rotationDegrees = 90,
            byteCount = 4_096,
        )
        private val TEXT_ENTRY = NoteEntry.text(
            id = "entry-1",
            noteDate = DATE,
            position = 0,
            text = "Jāatceras piezvanīt Annai",
            createdAt = START,
        ).copy(
            returnLater = true,
            updatedAt = START.plusNanos(10),
            deletedAt = START.plusSeconds(40),
        )
        private val VOICE_ENTRY = NoteEntry.voice(
            id = "voice-1",
            noteDate = DATE,
            position = 1,
            audio = AUDIO,
            createdAt = START.plusSeconds(1),
            transcriptionEnabled = true,
        ).copy(
            text = "Remember to buy milk",
            updatedAt = START.plusSeconds(3),
            transcription = TranscriptionInfo(
                state = EntryTranscriptionState.SUCCEEDED,
                attemptCount = 2,
                detectedLanguages = listOf(SupportedLanguage.ENGLISH, SupportedLanguage.LATVIAN),
                provenance = TranscriptionProvenance(
                    requestedEngine = TranscriptionEngine.ELEVENLABS_SCRIBE_V2,
                    usedEngine = TranscriptionEngine.ELEVENLABS_SCRIBE_V2,
                ),
                updatedAt = START.plusSeconds(3),
            ),
            audioDeletedAt = START.plusSeconds(50),
        )
        private val IMAGE_ENTRY = NoteEntry.image(
            id = "image-entry-1",
            noteDate = DATE,
            position = 2,
            image = IMAGE,
            createdAt = START.plusSeconds(4),
            caption = "Train window",
        )
        private val SNAPSHOT = BackupSnapshot(
            exportedAt = START.plusSeconds(600),
            notes = listOf(DailyNote(DATE, START, listOf(TEXT_ENTRY, VOICE_ENTRY, IMAGE_ENTRY))),
            entryMetadata = listOf(
                EntryMetadata(
                    entryId = IMAGE_ENTRY.id,
                    tags = listOf("train", "recipe"),
                    links = listOf(EntryLink(EntryLinkKind.DATE, DATE.toString(), "captured-on")),
                    derivedAt = START.plusSeconds(10),
                    source = MetadataSource.AI,
                ),
            ),
            todos = listOf(
                Todo(
                    id = "todo-open",
                    text = "Call Anna",
                    createdAt = START.plusSeconds(5),
                    updatedAt = START.plusSeconds(5),
                    kind = ImportantKind.EXCERPT,
                    source = EntrySource(DATE, TEXT_ENTRY.id),
                    resurfaceOn = DATE.plusWeeks(1),
                ),
                Todo(
                    id = "todo-done",
                    text = "Buy milk",
                    createdAt = START.plusSeconds(6),
                    updatedAt = START.plusSeconds(20),
                    lastTouchedAt = START.plusSeconds(20),
                    state = TodoState.DONE,
                    source = EntrySource(DATE, VOICE_ENTRY.id),
                    closedAt = START.plusSeconds(20),
                    stalePromptShownAt = START.plusSeconds(15),
                ),
            ),
            suggestions = listOf(
                TodoSuggestion(
                    id = "suggestion-1",
                    entryId = TEXT_ENTRY.id,
                    suggestedText = "piezvanīt Annai",
                    suggestedKind = ImportantKind.LIST,
                    language = SupportedLanguage.LATVIAN,
                    reason = TodoSuggestionReason.TRIGGER_PHRASE,
                    matchedRule = "jāatceras",
                    state = TodoSuggestionState.ACCEPTED,
                    createdAt = START.plusSeconds(2),
                    resolvedAt = START.plusSeconds(5),
                ),
            ),
            stillOpenDismissals = listOf(StillOpenDismissal(DATE, START.plusSeconds(30))),
            transcriptionJobs = listOf(
                TranscriptionJob(
                    id = "job-1",
                    entryId = VOICE_ENTRY.id,
                    state = TranscriptionJobState.QUEUED,
                    attemptCount = 1,
                    availableAt = START.plusSeconds(60),
                    lastFailure = FAILURE,
                    updatedAt = START.plusSeconds(30),
                ),
            ),
            audioContainers = listOf(
                BackupAudioContainer(AUDIO.fileId, ByteArray(257) { (it * 17).toByte() }),
            ),
            imageContainers = listOf(
                BackupImageContainer(IMAGE.fileId, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())),
            ),
            transcriptionVocabulary = listOf("Milchreis", "Rīga"),
        )
        private lateinit var FULL_ENCODED: ByteArray

        @BeforeClass
        @JvmStatic
        fun createFixture() {
            FULL_ENCODED = encode(SNAPSHOT, PASSPHRASE)
        }

        private fun encode(snapshot: BackupSnapshot, password: String): ByteArray {
            val passphrase = password.toCharArray()
            return try {
                PortableBackupCodec().encode(snapshot, passphrase)
            } finally {
                Arrays.fill(passphrase, '\u0000')
            }
        }

        private fun decode(encoded: ByteArray, password: String): BackupSnapshot {
            val passphrase = password.toCharArray()
            return try {
                PortableBackupCodec().decode(encoded, passphrase)
            } finally {
                Arrays.fill(passphrase, '\u0000')
            }
        }
    }
}
