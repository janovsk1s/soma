package com.soma.app

import android.system.Os
import android.system.OsConstants
import com.soma.core.model.DailyNote
import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS
import com.soma.core.model.EntryKind
import com.soma.core.model.ImageAttachment
import com.soma.core.model.ImageFormat
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.NoteEntry
import com.soma.core.model.TodoState
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionFailureCode
import com.soma.core.model.TranscriptionInfo
import com.soma.core.model.TranscriptionJobState
import com.soma.storage.backup.BackupAudioContainer
import com.soma.storage.backup.BackupSnapshot
import com.soma.storage.backup.BackupImageContainer
import com.soma.storage.backup.MarkdownVaultExporter
import com.soma.storage.backup.PortableBackupCodec
import com.soma.storage.backup.ReadableArchiveExporter
import com.soma.storage.repository.RoomSomaRepository
import com.soma.voice.EncryptedAudioReader
import com.soma.voice.EncryptedAudioWriter
import com.soma.media.EncryptedImageContainer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupCoordinator(
    private val app: SomaApplication,
    private val codec: PortableBackupCodec = PortableBackupCodec(),
    private val readableExporter: ReadableArchiveExporter = ReadableArchiveExporter(),
    private val markdownExporter: MarkdownVaultExporter = MarkdownVaultExporter(ZoneId.systemDefault()),
) {
    suspend fun export(passphrase: CharArray, includeAudio: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val snapshot = createSnapshot(includeAudio)
        try {
            codec.encode(snapshot, passphrase)
        } finally {
            snapshot.audioContainers.forEach { it.clearPortableBytes() }
            snapshot.imageContainers.forEach { it.clearPortableBytes() }
        }
    }

    suspend fun exportReadable(includeAudio: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val snapshot = createSnapshot(includeAudio)
        try {
            readableExporter.encode(snapshot)
        } finally {
            snapshot.audioContainers.forEach { it.clearPortableBytes() }
            snapshot.imageContainers.forEach { it.clearPortableBytes() }
        }
    }

    suspend fun exportMarkdown(includeAudio: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val snapshot = createSnapshot(includeAudio)
        try {
            markdownExporter.encode(snapshot)
        } finally {
            snapshot.audioContainers.forEach { it.clearPortableBytes() }
            snapshot.imageContainers.forEach { it.clearPortableBytes() }
        }
    }

    suspend fun decode(bytes: ByteArray, passphrase: CharArray): BackupSnapshot = withContext(Dispatchers.IO) {
        codec.decode(bytes, passphrase)
    }

    suspend fun restore(snapshot: BackupSnapshot) = withContext(Dispatchers.IO) {
        check(!SomaPrefs.demoMode(app)) { "Backups are unavailable in demo mode" }
        check(app.audioDirectory.listFiles { file -> file.name.endsWith(".partial") }.isNullOrEmpty()) {
            "Finish or recover the active recording before restoring a backup"
        }
        val repositories = app.repositories()
        val room = repositories.notes as? RoomSomaRepository
            ?: error("Portable restore requires the Room repository")
        val restoreId = UUID.randomUUID().toString()
        val activeDir = app.audioDirectory
        val activeImageDir = app.imageDirectory
        val stagedFiles = mutableListOf<File>()
        var committed = false
        val vocabularyStore = TranscriptionVocabularyStore(app)
        val previousVocabulary = vocabularyStore.read()
        var vocabularyChanged = false

        try {
            val staged = stagePortableAudio(snapshot, activeDir, restoreId, stagedFiles)
            val stagedImages = stagePortableImages(snapshot, stagedFiles)
            syncDirectory(activeDir)
            syncDirectory(activeImageDir)
            val restored = normalizeForDestination(snapshot, staged, stagedImages)
            // Imported audio already has fresh, collision-free final names. A crash
            // before this transaction commits leaves the old data intact plus only
            // harmless orphans; a crash after commit leaves a complete new generation.
            vocabularyStore.writeTerms(restored.transcriptionVocabulary)
            vocabularyChanged = true
            room.replaceAll(restored)
            committed = true
            val referenced = restored.notes.flatMap(DailyNote::entries)
                .mapNotNull { it.audio?.fileId }
                .toSet()
            activeDir.listFiles().orEmpty().forEach { file ->
                val id = file.name.removeSuffix(".sma")
                if (file.name.endsWith(".sma") && id !in referenced) runCatching { file.delete() }
                if (file.name.endsWith(".importing")) runCatching { file.delete() }
            }
            val referencedImages = restored.notes.flatMap(DailyNote::entries)
                .mapNotNull { it.image?.fileId }
                .toSet()
            activeImageDir.listFiles().orEmpty().forEach { file ->
                val id = file.name.removeSuffix(".smi")
                if (file.name.endsWith(".smi") && id !in referencedImages) runCatching { file.delete() }
                if (file.name.endsWith(".importing") || file.name.endsWith(".partial")) {
                    runCatching { file.delete() }
                }
            }
            runCatching { syncDirectory(activeDir) }
            runCatching { syncDirectory(activeImageDir) }
        } catch (error: Throwable) {
            if (!committed) {
                stagedFiles.forEach { runCatching { it.delete() } }
                if (vocabularyChanged) runCatching { vocabularyStore.writeTerms(previousVocabulary) }
            }
            throw error
        }
        // Scheduling is recoverable (application start also drains queued jobs),
        // so a scheduler failure must not report a successfully committed restore as failed.
        runCatching { TranscriptionScheduler.enqueue(app) }
    }

    suspend fun createSnapshot(includeAudio: Boolean): BackupSnapshot = withContext(Dispatchers.IO) {
        val repositories = app.repositories()
        val room = repositories.notes as? RoomSomaRepository
            ?: error("Portable export requires the Room repository")
        check(app.audioDirectory.listFiles { file -> file.name.endsWith(".partial") }.isNullOrEmpty()) {
            "Finish or recover the active recording before exporting a backup"
        }
        val base = room.createBackupSnapshot(Instant.now(), MAX_BACKUP_ROWS)
        val entries = base.notes.flatMap(DailyNote::entries)
        val audio = if (includeAudio) {
            var totalPortableBytes = 0L
            entries.mapNotNull(NoteEntry::audio).map { attachment ->
                val file = app.encryptedAudioFile(attachment.fileId)
                check(file.isFile) { "A referenced recording is missing" }
                val wav = EncryptedAudioReader.open(file, app.audioKeyProvider, attachment.fileId).use { reader ->
                    totalPortableBytes = Math.addExact(totalPortableBytes, reader.metadata.pcmBytes + WAV_HEADER_BYTES)
                    check(totalPortableBytes <= MAX_PORTABLE_AUDIO_BYTES) {
                        "Recordings are too large for one in-memory v1 backup"
                    }
                    reader.wavStream().readBytes()
                }
                BackupAudioContainer(attachment.fileId, wav).also { wav.fill(0) }
            }
        } else {
            emptyList()
        }
        val images = if (includeAudio) {
            var totalPortableBytes = 0L
            entries.mapNotNull(NoteEntry::image).map { attachment ->
                val file = app.encryptedImageFile(attachment.fileId)
                check(file.isFile) { "A referenced image is missing" }
                val (metadata, jpeg) = EncryptedImageContainer.read(
                    file,
                    attachment.fileId,
                    app.imageKeyProvider,
                )
                check(
                    metadata.width == attachment.width &&
                        metadata.height == attachment.height &&
                        metadata.rotationDegrees == attachment.rotationDegrees
                ) { "Image metadata does not match its attachment" }
                totalPortableBytes = Math.addExact(totalPortableBytes, jpeg.size.toLong())
                check(totalPortableBytes <= MAX_PORTABLE_IMAGE_BYTES) {
                    "Images are too large for one in-memory backup"
                }
                BackupImageContainer(attachment.fileId, jpeg).also { jpeg.fill(0) }
            }
        } else {
            emptyList()
        }
        base.copy(
            audioContainers = audio,
            imageContainers = images,
            transcriptionVocabulary = TranscriptionVocabularyStore(app).read(),
        )
    }

    private fun stagePortableImages(
        snapshot: BackupSnapshot,
        stagedFiles: MutableList<File>,
    ): Map<String, StagedImage> {
        val attachments = snapshot.notes.flatMap(DailyNote::entries)
            .mapNotNull(NoteEntry::image)
            .associateBy(ImageAttachment::fileId)
        val staged = mutableMapOf<String, StagedImage>()
        snapshot.imageContainers.forEach { portable ->
            val source = requireNotNull(attachments[portable.fileId]) { "Portable image has no attachment" }
            val jpeg = portable.portableJpegBytes()
            val destinationId = UUID.randomUUID().toString()
            val final = app.encryptedImageFile(destinationId)
            stagedFiles += final
            try {
                EncryptedImageContainer.write(
                    finalFile = final,
                    imageId = destinationId,
                    jpegBytes = jpeg,
                    width = source.width,
                    height = source.height,
                    rotationDegrees = source.rotationDegrees,
                    keyProvider = app.imageKeyProvider,
                )
                staged[portable.fileId] = StagedImage(
                    destinationId = destinationId,
                    width = source.width,
                    height = source.height,
                    rotationDegrees = source.rotationDegrees,
                    encryptedByteCount = final.length(),
                )
            } finally {
                jpeg.fill(0)
            }
        }
        return staged
    }

    private fun stagePortableAudio(
        snapshot: BackupSnapshot,
        directory: File,
        restoreId: String,
        stagedFiles: MutableList<File>,
    ): Map<String, StagedAudio> {
        val staged = mutableMapOf<String, StagedAudio>()
        snapshot.audioContainers.forEach { portable ->
            val wav = portable.portableWavBytes()
            val pcm = parseWavPcm(wav)
            val destinationId = UUID.randomUUID().toString()
            val partial = File(directory, "$destinationId-$restoreId.importing")
            val final = app.encryptedAudioFile(destinationId)
            stagedFiles += partial
            stagedFiles += final
            val writer = EncryptedAudioWriter(partial, destinationId, app.audioKeyProvider)
            try {
                var offset = 0
                while (offset < pcm.size) {
                    val count = minOf(AUDIO_IMPORT_CHUNK, pcm.size - offset)
                    val chunk = pcm.copyOfRange(offset, offset + count)
                    writer.writePcm(chunk)
                    chunk.fill(0)
                    offset += count
                }
                val metadata = writer.finish(final)
                staged[portable.fileId] = StagedAudio(
                    destinationId = destinationId,
                    durationMillis = metadata.durationMillis,
                    encryptedByteCount = final.length(),
                )
            } finally {
                runCatching { writer.close() }
                pcm.fill(0)
                wav.fill(0)
            }
        }
        return staged
    }

    private fun normalizeForDestination(
        snapshot: BackupSnapshot,
        staged: Map<String, StagedAudio>,
        stagedImages: Map<String, StagedImage>,
    ): BackupSnapshot {
        val now = Instant.now()
        val normalizedJobs = snapshot.transcriptionJobs.map { job ->
            when {
                job.attemptCount >= DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS &&
                    job.state != TranscriptionJobState.SUCCEEDED -> job.copy(
                    state = TranscriptionJobState.FAILED,
                    leaseOwner = null,
                    leaseExpiresAt = null,
                    lastFailure = TranscriptionFailure(TranscriptionFailureCode.CANCELLED, retryable = false),
                    updatedAt = now,
                )
                job.state == TranscriptionJobState.RUNNING -> job.copy(
                    state = TranscriptionJobState.QUEUED,
                    availableAt = now,
                    leaseOwner = null,
                    leaseExpiresAt = null,
                    updatedAt = now,
                )
                else -> job
            }
        }
        val jobsByEntry = normalizedJobs.associateBy { it.entryId }
        val notes = snapshot.notes.map { note ->
            note.copy(entries = note.entries.map { entry ->
                val audio = entry.audio
                val withAudio = if (audio == null) {
                    entry
                } else if (audio.fileId in staged) {
                    val imported = staged.getValue(audio.fileId)
                    val job = jobsByEntry[entry.id]
                    entry.copy(
                        audio = AudioAttachment(
                            fileId = imported.destinationId,
                            format = AudioFormat.WAV,
                            durationMillis = imported.durationMillis,
                            byteCount = imported.encryptedByteCount,
                        ),
                        transcription = when (job?.state) {
                            TranscriptionJobState.QUEUED -> TranscriptionInfo(
                                state = EntryTranscriptionState.QUEUED,
                                attemptCount = job.attemptCount,
                                updatedAt = job.updatedAt,
                            )
                            TranscriptionJobState.FAILED -> TranscriptionInfo(
                                state = EntryTranscriptionState.FAILED,
                                attemptCount = job.attemptCount,
                                updatedAt = job.updatedAt,
                                failure = job.lastFailure,
                            )
                            else -> entry.transcription
                        },
                    )
                } else {
                    // A text-only backup retains the editable transcript but not a dead device-bound reference.
                    entry.copy(
                        kind = if (entry.image != null) EntryKind.IMAGE else EntryKind.TEXT,
                        text = entry.text.ifBlank { app.getString(R.string.voice_audio_not_in_backup) },
                        audio = null,
                        transcription = null,
                        audioDeletedAt = null,
                    )
                }
                val image = withAudio.image
                if (image == null) {
                    withAudio
                } else if (image.fileId in stagedImages) {
                    val imported = stagedImages.getValue(image.fileId)
                    withAudio.copy(
                        image = ImageAttachment(
                            fileId = imported.destinationId,
                            format = ImageFormat.JPEG,
                            width = imported.width,
                            height = imported.height,
                            rotationDegrees = imported.rotationDegrees,
                            byteCount = imported.encryptedByteCount,
                        ),
                    )
                } else {
                    withAudio.copy(
                        kind = if (withAudio.audio != null) EntryKind.VOICE else EntryKind.TEXT,
                        text = withAudio.text.ifBlank { app.getString(R.string.photo_not_in_backup) },
                        image = null,
                        imageDeletedAt = null,
                    )
                }
            })
        }
        val audioEntryIds = notes.flatMap(DailyNote::entries)
            .filter { it.audio != null }
            .mapTo(hashSetOf(), NoteEntry::id)
        val jobs = normalizedJobs.filter { it.entryId in audioEntryIds }
        return snapshot.copy(
            notes = notes,
            transcriptionJobs = jobs,
            audioContainers = emptyList(),
            imageContainers = emptyList(),
        )
    }

    private fun parseWavPcm(wav: ByteArray): ByteArray {
        require(wav.size >= WAV_HEADER_BYTES) { "Portable WAV is truncated" }
        require(wav.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF") { "Portable audio is not RIFF" }
        require(wav.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") { "Portable audio is not WAV" }
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getShort(20).toInt() == 1) { "Portable audio must be PCM" }
        require(header.getShort(22).toInt() == 1) { "Portable audio must be mono" }
        require(header.getInt(24) == 16_000) { "Portable audio must be 16 kHz" }
        require(header.getShort(34).toInt() == 16) { "Portable audio must be 16-bit" }
        val length = header.getInt(40)
        require(length >= 0 && WAV_HEADER_BYTES + length == wav.size) { "Portable WAV length is invalid" }
        return wav.copyOfRange(WAV_HEADER_BYTES, wav.size)
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private companion object {
        const val MAX_BACKUP_ROWS = 100_000
        const val MAX_PORTABLE_AUDIO_BYTES = 120L * 1024 * 1024
        const val MAX_PORTABLE_IMAGE_BYTES = 120L * 1024 * 1024
        const val WAV_HEADER_BYTES = 44
        const val AUDIO_IMPORT_CHUNK = 64 * 1024
    }

    private data class StagedAudio(
        val destinationId: String,
        val durationMillis: Long,
        val encryptedByteCount: Long,
    )

    private data class StagedImage(
        val destinationId: String,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val encryptedByteCount: Long,
    )
}
