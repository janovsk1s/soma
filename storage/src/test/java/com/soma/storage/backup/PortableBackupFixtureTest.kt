package com.soma.storage.backup

import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.DailyNote
import com.soma.core.model.EntryLink
import com.soma.core.model.EntryLinkKind
import com.soma.core.model.EntryMetadata
import com.soma.core.model.EntrySource
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.FoodItem
import com.soma.core.model.ImportantKind
import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.LogRevision
import com.soma.core.model.MetadataSource
import com.soma.core.model.NoteEntry
import com.soma.core.model.ReceiptDetails
import com.soma.core.model.ReceiptItem
import com.soma.core.model.ReceiptMoney
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.Todo
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionInfo
import com.soma.core.model.TranscriptionProvenance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Arrays
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Release gate: a committed backup file from an earlier build must keep
 * decoding on every future build.
 *
 * Round-trip tests only prove the current code agrees with itself; these golden
 * fixtures prove the on-disk container and payload stay readable across code
 * changes. When the payload version advances, the previous fixture stays —
 * SUPPORTED_PAYLOAD_VERSIONS promises old backups still restore — and a new
 * fixture pins the new version.
 *
 * If the current-version fixture file is ever missing, running the test
 * regenerates it and fails once so the new file gets committed deliberately.
 * Old-version fixtures cannot be regenerated (the encoder only writes the
 * current version); if one goes missing, restore it from git history.
 */
class PortableBackupFixtureTest {
    @Test
    fun `committed v11 backup fixture decodes with the documented passphrase`() {
        val file = fixtureFile(V11_RESOURCE)
        assertTrue(
            "The v11 fixture is missing at $file — restore it from git history; " +
                "old payload versions cannot be re-encoded.",
            file.exists(),
        )

        val decoded = decode(file.readBytes())

        assertEquals(V11_SNAPSHOT, decoded)
        assertEquals(11, decoded.payloadVersion)
        assertEquals("Jāatceras piezvanīt Annai", decoded.notes.single().entries.first().text)
        assertEquals("Rimi", decoded.trackingLogs.single { it.kind == LogKind.RECEIPT }.receipt?.merchant)
        assertArrayEquals(AUDIO_BYTES, decoded.audioContainers.single().portableWavBytes())
    }

    @Test
    fun `committed v12 backup fixture decodes with the documented passphrase`() {
        val file = fixtureFile(V12_RESOURCE)
        assertTrue(
            "The v12 fixture is missing at $file — restore it from git history; " +
                "old payload versions cannot be re-encoded.",
            file.exists(),
        )

        val decoded = decode(file.readBytes())

        assertEquals(V12_SNAPSHOT, decoded)
        assertEquals(12, decoded.payloadVersion)
        assertEquals(
            TranscriptionEngine.LOCAL_WHISPER_BASE,
            decoded.notes.single().entries
                .single { it.id == "fixture-voice-1" }.transcription?.provenance?.usedEngine,
        )
        assertArrayEquals(AUDIO_BYTES, decoded.audioContainers.single().portableWavBytes())
    }

    @Test
    fun `committed v13 backup fixture decodes with the documented passphrase`() {
        val file = fixtureFile(V13_RESOURCE)
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeBytes(encodeFixture())
            fail("Backup fixture was missing and has been regenerated at $file — commit it.")
        }

        val decoded = decode(file.readBytes())

        assertEquals(V13_SNAPSHOT, decoded)
        assertEquals(13, decoded.payloadVersion)
        val receipt = decoded.trackingLogs.single { it.kind == LogKind.RECEIPT }.receipt
        assertEquals(-50L, receipt?.items?.single { it.name == "Atlaide" }?.lineTotal?.minorUnits)
        assertEquals(331L, receipt?.total?.minorUnits)
        assertArrayEquals(AUDIO_BYTES, decoded.audioContainers.single().portableWavBytes())
    }

    private fun fixtureFile(resource: String): File = sequenceOf(".", "storage")
        .map { File(System.getProperty("user.dir"), it) }
        .firstOrNull { File(it, "src/test").isDirectory }
        ?.let { module -> File(module, "src/test/resources/$resource") }
        ?: error("Could not locate storage/src/test from ${System.getProperty("user.dir")}")

    private fun encodeFixture(): ByteArray {
        val passphrase = PASSPHRASE.toCharArray()
        return try {
            PortableBackupCodec().encode(V13_SNAPSHOT, passphrase)
        } finally {
            Arrays.fill(passphrase, ' ')
        }
    }

    private fun decode(encoded: ByteArray): BackupSnapshot {
        val passphrase = PASSPHRASE.toCharArray()
        return try {
            PortableBackupCodec().decode(encoded, passphrase)
        } finally {
            Arrays.fill(passphrase, ' ')
        }
    }

    private companion object {
        const val V11_RESOURCE = "fixtures/portable-backup-v11.somabackup"
        const val V12_RESOURCE = "fixtures/portable-backup-v12.somabackup"
        const val V13_RESOURCE = "fixtures/portable-backup-v13.somabackup"

        /** Fixture-only passphrase; it protects no real data. */
        const val PASSPHRASE = "soma fixture passphrase 2026"

        val DATE: LocalDate = LocalDate.of(2026, 7, 14)
        val START: Instant = Instant.parse("2026-07-14T08:00:00Z")
        val AUDIO_BYTES = ByteArray(257) { (it * 17).toByte() }

        val TEXT_ENTRY = NoteEntry.text(
            id = "fixture-entry-1",
            noteDate = DATE,
            position = 0,
            text = "Jāatceras piezvanīt Annai",
            createdAt = START,
        )
        val VOICE_ENTRY = NoteEntry.voice(
            id = "fixture-voice-1",
            noteDate = DATE,
            position = 1,
            audio = AudioAttachment(
                fileId = "fixture-audio-1",
                format = AudioFormat.WAV,
                durationMillis = 1_250,
                byteCount = 40_044,
                sampleRateHz = 16_000,
                channelCount = 1,
            ),
            createdAt = START.plusSeconds(1),
            transcriptionEnabled = true,
        )

        /** The v12 voice entry finished locally on the downloaded base model. */
        val TRANSCRIBED_VOICE_ENTRY = VOICE_ENTRY.copy(
            text = "Sveiki no fiksētā ieraksta",
            transcription = TranscriptionInfo(
                state = EntryTranscriptionState.SUCCEEDED,
                attemptCount = 1,
                detectedLanguages = listOf(SupportedLanguage.LATVIAN),
                provenance = TranscriptionProvenance(
                    requestedEngine = TranscriptionEngine.LOCAL_WHISPER_BASE,
                    usedEngine = TranscriptionEngine.LOCAL_WHISPER_BASE,
                ),
                updatedAt = START.plusSeconds(90),
            ),
        )

        val FIXTURE_METADATA = EntryMetadata(
            entryId = TEXT_ENTRY.id,
            tags = listOf("anna", "zvani"),
            links = listOf(EntryLink(EntryLinkKind.DATE, DATE.toString(), "written-on")),
            derivedAt = START.plusSeconds(10),
            source = MetadataSource.LOCAL,
        )

        val FIXTURE_TRACKING_LOGS = listOf(
            LogRecord(
                id = "fixture-meal-1",
                kind = LogKind.MEAL,
                title = "Milchreis",
                occurredAt = START,
                createdAt = START,
                updatedAt = START.plusSeconds(30),
                source = EntrySource(DATE, TEXT_ENTRY.id),
                foods = listOf(FoodItem("Rice"), FoodItem("Milk")),
                revision = 1,
            ),
            LogRecord(
                id = "fixture-receipt-1",
                kind = LogKind.RECEIPT,
                title = "Rimi",
                occurredAt = START.plusSeconds(40),
                createdAt = START.plusSeconds(40),
                updatedAt = START.plusSeconds(40),
                receipt = ReceiptDetails(
                    merchant = "Rimi",
                    currencyCode = "EUR",
                    subtotal = ReceiptMoney(315, "EUR"),
                    tax = ReceiptMoney(66, "EUR"),
                    total = ReceiptMoney(381, "EUR"),
                    items = listOf(
                        ReceiptItem(
                            name = "Piens 2%",
                            quantity = 2.0,
                            lineTotal = ReceiptMoney(218, "EUR"),
                            category = "groceries",
                        ),
                        ReceiptItem(name = "Rīsi", lineTotal = ReceiptMoney(97, "EUR")),
                    ),
                ),
            ),
        )

        val FIXTURE_TRACKING_REVISIONS = listOf(
            LogRevision(
                logId = "fixture-meal-1",
                revision = 0,
                snapshot = LogRecord(
                    id = "fixture-meal-1",
                    kind = LogKind.MEAL,
                    title = "Rice pudding",
                    occurredAt = START,
                    createdAt = START,
                    updatedAt = START,
                    source = EntrySource(DATE, TEXT_ENTRY.id),
                    foods = listOf(FoodItem("Rice")),
                ),
                editedAt = START.plusSeconds(30),
            ),
        )

        val FIXTURE_TODOS = listOf(
            Todo(
                id = "fixture-todo-1",
                text = "Call Anna",
                createdAt = START.plusSeconds(5),
                updatedAt = START.plusSeconds(5),
                kind = ImportantKind.EXCERPT,
                source = EntrySource(DATE, TEXT_ENTRY.id),
                resurfaceOn = DATE.plusWeeks(1),
            ),
        )

        val V11_SNAPSHOT = BackupSnapshot(
            payloadVersion = 11,
            exportedAt = START.plusSeconds(600),
            notes = listOf(DailyNote(DATE, START, listOf(TEXT_ENTRY, VOICE_ENTRY))),
            entryMetadata = listOf(FIXTURE_METADATA),
            trackingLogs = FIXTURE_TRACKING_LOGS,
            trackingLogRevisions = FIXTURE_TRACKING_REVISIONS,
            todos = FIXTURE_TODOS,
            suggestions = emptyList(),
            audioContainers = listOf(BackupAudioContainer("fixture-audio-1", AUDIO_BYTES.copyOf())),
            transcriptionVocabulary = listOf("Milchreis", "Rīga"),
        )

        val V12_SNAPSHOT = BackupSnapshot(
            payloadVersion = 12,
            exportedAt = START.plusSeconds(600),
            notes = listOf(DailyNote(DATE, START, listOf(TEXT_ENTRY, TRANSCRIBED_VOICE_ENTRY))),
            entryMetadata = listOf(FIXTURE_METADATA),
            trackingLogs = FIXTURE_TRACKING_LOGS,
            trackingLogRevisions = FIXTURE_TRACKING_REVISIONS,
            todos = FIXTURE_TODOS,
            suggestions = emptyList(),
            audioContainers = listOf(BackupAudioContainer("fixture-audio-1", AUDIO_BYTES.copyOf())),
            transcriptionVocabulary = listOf("Milchreis", "Rīga"),
        )

        /** The v13 receipt gains a printed deduction line; 315 + 66 − 50 = 331. */
        val V13_TRACKING_LOGS = FIXTURE_TRACKING_LOGS.map { log ->
            val receipt = log.receipt ?: return@map log
            log.copy(
                receipt = receipt.copy(
                    total = ReceiptMoney(331, "EUR"),
                    items = receipt.items + ReceiptItem(
                        name = "Atlaide",
                        lineTotal = ReceiptMoney(-50, "EUR"),
                    ),
                ),
            )
        }

        val V13_SNAPSHOT = BackupSnapshot(
            payloadVersion = 13,
            exportedAt = START.plusSeconds(600),
            notes = listOf(DailyNote(DATE, START, listOf(TEXT_ENTRY, TRANSCRIBED_VOICE_ENTRY))),
            entryMetadata = listOf(FIXTURE_METADATA),
            trackingLogs = V13_TRACKING_LOGS,
            trackingLogRevisions = FIXTURE_TRACKING_REVISIONS,
            todos = FIXTURE_TODOS,
            suggestions = emptyList(),
            audioContainers = listOf(BackupAudioContainer("fixture-audio-1", AUDIO_BYTES.copyOf())),
            transcriptionVocabulary = listOf("Milchreis", "Rīga"),
        )
    }
}
