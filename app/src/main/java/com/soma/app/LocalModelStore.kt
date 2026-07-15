package com.soma.app

import android.content.Context
import com.soma.whisper.LocalWhisperModel
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** The bytes on disk do not match the registry's pinned digest or size. */
class ModelVerificationException : IOException("Model file failed SHA-256 verification")

/** The in-app model download only runs on Wi-Fi; cellular is never used for weights. */
class ModelWifiRequiredException : IOException("Wi-Fi is required to download a model")

/** What the store verifies against; the registry provides it, tests can shrink it. */
internal data class LocalModelSpec(
    val fileName: String,
    val sha256: String,
    val byteCount: Long,
)

internal val LocalWhisperModel.spec: LocalModelSpec
    get() = LocalModelSpec(fileName, sha256, byteCount)

/**
 * App-private storage for downloadable Whisper models.
 *
 * Weights are public, reviewed artifacts, so files live plaintext in the
 * no-backup `models/` directory (documented in docs/THREAT_MODEL.md); what is
 * enforced instead is integrity: nothing becomes loadable until the complete
 * file matches the registry's pinned SHA-256 and exact size, whether it arrived
 * over the in-app download or through the system file picker. Until then the
 * bytes stay in a `.part` file the download can resume into.
 */
class LocalModelStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    /** The verified, loadable weights, or null while the model is not installed. */
    fun installedFile(model: LocalWhisperModel): File? = installedFile(model.spec)

    /** Staging file for an in-progress download; survives interruption for resume. */
    fun partialFile(model: LocalWhisperModel): File = partialFile(model.spec)

    /** Copies a user-picked stream into staging and promotes it if it verifies. */
    @Throws(IOException::class)
    fun importFrom(model: LocalWhisperModel, input: InputStream): File = importFrom(model.spec, input)

    /** Verifies staged bytes against the pinned digest and moves them into place. */
    @Throws(IOException::class)
    fun promotePartial(model: LocalWhisperModel): File = promotePartial(model.spec)

    /** Removes installed and partial bytes. Selection falls back to tiny elsewhere. */
    fun delete(model: LocalWhisperModel): Boolean = delete(model.spec)

    internal fun installedFile(spec: LocalModelSpec): File? =
        File(directory, spec.fileName).takeIf { it.isFile && it.length() == spec.byteCount }

    internal fun partialFile(spec: LocalModelSpec): File =
        File(directory, spec.fileName + PARTIAL_SUFFIX)

    /**
     * The stream is bounded by the registry size so a wrong pick cannot fill
     * the phone; any mismatch leaves no partial state behind.
     */
    internal fun importFrom(spec: LocalModelSpec, input: InputStream): File {
        val partial = partialFile(spec)
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > spec.byteCount) throw ModelVerificationException()
                    output.write(buffer, 0, read)
                }
            }
            return promotePartial(spec)
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    /**
     * A failed verification deletes the stage so a corrupt or wrong file is
     * never retried into acceptance.
     */
    internal fun promotePartial(spec: LocalModelSpec): File {
        val partial = partialFile(spec)
        if (!partial.isFile || partial.length() != spec.byteCount || sha256(partial) != spec.sha256) {
            partial.delete()
            throw ModelVerificationException()
        }
        val destination = File(directory, spec.fileName)
        destination.delete()
        if (!partial.renameTo(destination)) {
            partial.delete()
            throw IOException("Could not move the verified model into place")
        }
        return destination
    }

    internal fun delete(spec: LocalModelSpec): Boolean {
        val partial = partialFile(spec).delete()
        val installed = File(directory, spec.fileName).delete()
        return installed || partial
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val DIRECTORY = "models"
        const val PARTIAL_SUFFIX = ".part"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * The active local engine: the user's selection when its weights are actually
 * on disk and verified-size, otherwise the bundled tiny model. Deleting a
 * model therefore degrades transcription instead of breaking it.
 */
internal fun resolveLocalWhisperModel(context: Context, store: LocalModelStore): LocalWhisperModel {
    val selected = SomaPrefs.localWhisperModel(context)
    return if (selected.bundled || store.installedFile(selected) != null) selected else LocalWhisperModel.TINY
}

/** Flavor boundary for fetching model weights; null where the flavor never opens the network. */
interface LocalModelDownloader {
    /**
     * Downloads (or resumes) the model into the store and returns the verified
     * file. Progress reports downloaded bytes against the registry size.
     * Throws [ModelWifiRequiredException] off Wi-Fi and
     * [ModelVerificationException] when the completed bytes do not match.
     */
    suspend fun download(
        model: LocalWhisperModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File
}
