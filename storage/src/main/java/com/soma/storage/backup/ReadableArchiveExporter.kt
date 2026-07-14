package com.soma.storage.backup

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryMetadata
import com.soma.core.model.FoodItem
import com.soma.core.model.LogRecord
import com.soma.core.model.NoteEntry
import com.soma.core.model.NutritionEstimate
import com.soma.core.model.ReceiptDetails
import com.soma.core.model.ReceiptMoney
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionFallbackReason
import com.soma.core.model.TranscriptionProvenance
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a deliberately boring, dependency-free ZIP that remains useful without Soma.
 *
 * Markdown and CSV are for people; JSON/JSONL preserve exact timestamps, ids, and edit
 * history for a future importer. All timestamps are ISO-8601 UTC instants so exports do
 * not silently change meaning when opened in another timezone.
 */
class ReadableArchiveExporter {
    fun encode(snapshot: BackupSnapshot): ByteArray {
        val readable = snapshot.withoutDeletedContent()
        val metadata = readable.entryMetadata.groupBy(EntryMetadata::entryId)
        val includedImages = readable.imageContainers.mapTo(hashSetOf(), BackupImageContainer::fileId)
        val imagePaths = readable.notes.asSequence().flatMap { it.entries.asSequence() }
            .mapNotNull { entry ->
                entry.activeImage?.fileId?.takeIf { it in includedImages }
                    ?.let { it to "images/${entry.noteDate}-$it.jpg" }
            }.toMap()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            zip.putText("README.txt", readme(readable))
            zip.putText("manifest.json", manifest(readable))
            readable.notes.sortedBy { it.date }.forEach { note ->
                zip.putText("notes/${note.date}.md", noteMarkdown(note, imagePaths, metadata))
            }
            zip.putText("todos.csv", todosCsv(readable))
            zip.putText("logs.csv", trackingLogsCsv(readable))
            zip.putText("data/notes.json", notesJson(readable))
            zip.putText("data/history.jsonl", historyJsonl(readable))
            zip.putText("data/metadata.json", metadataJson(readable))
            zip.putText("data/logs.json", trackingLogsJson(readable))
            zip.putText("data/log-history.jsonl", trackingLogHistoryJsonl(readable))
            zip.putText(
                "settings/transcription-vocabulary.txt",
                readable.transcriptionVocabulary.joinToString(separator = "\n", postfix = "\n"),
            )
            readable.audioContainers.sortedBy { it.fileId }.forEach { audio ->
                val date = readable.notes.asSequence()
                    .flatMap { note -> note.entries.asSequence() }
                    .firstOrNull { it.activeAudio?.fileId == audio.fileId }
                    ?.noteDate
                    ?.toString()
                    ?: "unknown-date"
                val bytes = audio.portableWavBytes()
                try {
                    zip.putNextEntry(ZipEntry("audio/$date-${audio.fileId}.wav"))
                    zip.write(bytes)
                    zip.closeEntry()
                } finally {
                    bytes.fill(0)
                }
            }
            readable.imageContainers.sortedBy(BackupImageContainer::fileId).forEach { image ->
                val path = imagePaths[image.fileId] ?: return@forEach
                val bytes = image.portableJpegBytes()
                try {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                } finally {
                    bytes.fill(0)
                }
            }
        }
        return output.toByteArray()
    }

    private fun readme(snapshot: BackupSnapshot): String = buildString {
        appendLine("Soma readable archive")
        appendLine("=====================")
        appendLine()
        appendLine("Created: ${snapshot.exportedAt}")
        appendLine("Timestamps: ISO-8601 UTC")
        appendLine()
        appendLine("notes/ contains one human-readable Markdown file per day.")
        appendLine("todos.csv contains every Important item and opens in spreadsheet applications.")
        appendLine("logs.csv contains every meal, recipe, and workout in a spreadsheet-friendly form.")
        appendLine("data/notes.json preserves complete structured note data.")
        appendLine("data/history.jsonl preserves previous text after user edits.")
        appendLine("data/metadata.json preserves additive manual and AI tags and links.")
        appendLine("data/logs.json preserves complete structured food, nutrition, and workout data.")
        appendLine("data/log-history.jsonl preserves every earlier version of a structured log.")
        appendLine("settings/transcription-vocabulary.txt preserves user-provided speech spellings.")
        appendLine("audio/ contains standard 16 kHz mono WAV files when included.")
        appendLine("images/ contains standard JPEG originals when included.")
        appendLine()
        appendLine("This ZIP is intentionally not encrypted. Store it somewhere you trust.")
        appendLine("It is designed to remain readable even if Soma no longer exists.")
    }

    private fun manifest(snapshot: BackupSnapshot): String = buildString {
        append('{')
        jsonField("format", "soma-readable-archive")
        append(',')
        jsonNumberField("version", READABLE_FORMAT_VERSION)
        append(',')
        jsonField("exportedAt", snapshot.exportedAt.toString())
        append(',')
        jsonNumberField("noteCount", snapshot.notes.size)
        append(',')
        jsonNumberField("entryCount", snapshot.notes.sumOf { it.entries.size })
        append(',')
        jsonNumberField("todoCount", snapshot.todos.size)
        append(',')
        jsonNumberField("logCount", snapshot.trackingLogs.size)
        append(',')
        jsonNumberField("logRevisionCount", snapshot.trackingLogRevisions.size)
        append(',')
        jsonNumberField("revisionCount", snapshot.entryRevisions.size)
        append(',')
        jsonNumberField("audioCount", snapshot.audioContainers.size)
        append(',')
        jsonNumberField("imageCount", snapshot.imageContainers.size)
        append(',')
        jsonNumberField("metadataLayerCount", snapshot.entryMetadata.size)
        append(',')
        jsonNumberField("transcriptionVocabularyCount", snapshot.transcriptionVocabulary.size)
        append('}')
        appendLine()
    }

    private fun noteMarkdown(
        note: DailyNote,
        imagePaths: Map<String, String>,
        metadataByEntry: Map<String, List<EntryMetadata>>,
    ): String = buildString {
        appendLine("# ${note.date}")
        appendLine()
        if (note.entries.isEmpty()) appendLine("_No entries._")
        note.entries.sortedBy(NoteEntry::position).forEach { entry ->
            val kind = entry.kind.name.lowercase()
            append("- **${TIME.format(entry.createdAt)}**")
            if (kind == "voice") append(" · voice")
            if (kind == "image") append(" · photo")
            if (entry.returnLater) append(" · return later")
            appendLine()
            appendLine(
                "  ${markdownText(entry.text.ifBlank {
                    if (entry.kind == EntryKind.IMAGE) "_(photo)_" else "_(no transcript)_"
                })}",
            )
            entry.lastUserEditedAt?.let { appendLine("  _edited ${TIME.format(it)}_") }
            val layers = metadataByEntry[entry.id].orEmpty()
            val tags = layers.flatMap(EntryMetadata::tags).distinct()
            if (tags.isNotEmpty()) appendLine("  _tags: ${tags.joinToString(" ") { "#$it" }}_")
            val links = layers.flatMap(EntryMetadata::links).distinct()
            if (links.isNotEmpty()) {
                appendLine(
                    "  _links: ${links.joinToString(", ") { link ->
                        "${link.kind.name.lowercase()}:${link.target}" +
                            link.relation?.let { " ($it)" }.orEmpty()
                    }}_",
                )
            }
            entry.transcription?.provenance?.let {
                appendLine("  _transcription: ${transcriptionLabel(it)}_")
            }
            entry.activeAudio?.let { appendLine("  _audio id: `${it.fileId}`_") }
            entry.activeImage?.fileId?.let { imageId ->
                imagePaths[imageId]?.let { appendLine("  ![photo](../$it)") }
            }
            appendLine()
        }
    }

    private fun todosCsv(snapshot: BackupSnapshot): String = buildString {
        appendLine("id,text,kind,state,created_at,updated_at,closed_at,show_again_on,source_date,source_entry_id")
        snapshot.todos.sortedBy { it.createdAt }.forEach { todo ->
            appendLine(
                listOf(
                    todo.id,
                    todo.text,
                    todo.kind.name.lowercase(),
                    todo.state.name.lowercase(),
                    todo.createdAt.toString(),
                    todo.updatedAt.toString(),
                    todo.closedAt?.toString().orEmpty(),
                    todo.resurfaceOn?.toString().orEmpty(),
                    todo.source?.noteDate?.toString().orEmpty(),
                    todo.source?.entryId.orEmpty(),
                ).joinToString(",", transform = ::csv),
            )
        }
    }

    private fun trackingLogsCsv(snapshot: BackupSnapshot): String = buildString {
        appendLine("id,type,title,note,occurred_at,created_at,updated_at,revision,archived_at,source_date,source_entry_id,foods,exercises,receipt_merchant,receipt_currency,receipt_total,receipt_items")
        snapshot.trackingLogs.sortedWith(compareBy(LogRecord::occurredAt, LogRecord::id)).forEach { log ->
            val foods = log.foods.joinToString(" | ") { food ->
                buildString {
                    append(food.name)
                    food.quantity?.let { append(" ${plainNumber(it)} ${food.unit?.name?.lowercase()}") }
                    food.nutrition?.let { nutrition ->
                        append(" [${nutrition.basis.name.lowercase()}/${nutrition.source.name.lowercase()}")
                        nutrition.energyKcal?.let { append("; ${plainNumber(it)} kcal") }
                        nutrition.energyKcalMin?.let { minimum ->
                            append("; ${plainNumber(minimum)}-${plainNumber(nutrition.energyKcalMax ?: minimum)} kcal")
                        }
                        append(']')
                    }
                }
            }
            val exercises = log.exercises.joinToString(" | ") { exercise ->
                val sets = exercise.sets.joinToString("; ") { set ->
                    listOfNotNull(
                        set.repetitions?.let { "$it reps" },
                        set.weightKilograms?.let { "${plainNumber(it)} kg" },
                        set.durationSeconds?.let { "$it sec" },
                    ).joinToString(" ")
                }
                "${exercise.name}${exercise.machine?.let { " ($it)" }.orEmpty()}${sets.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
            }
            val receiptItems = log.receipt?.items.orEmpty().joinToString(" | ") { item ->
                buildString {
                    append(item.name)
                    item.quantity?.let { append(" × ${plainNumber(it)}") }
                    item.lineTotal?.let { append(" · ${plainMoney(it)}") }
                    item.category?.let { append(" [$it]") }
                }
            }
            appendLine(
                listOf(
                    log.id,
                    log.kind.name.lowercase(),
                    log.title,
                    log.note,
                    log.occurredAt.toString(),
                    log.createdAt.toString(),
                    log.updatedAt.toString(),
                    log.revision.toString(),
                    log.archivedAt?.toString().orEmpty(),
                    log.source?.noteDate?.toString().orEmpty(),
                    log.source?.entryId.orEmpty(),
                    foods,
                    exercises,
                    log.receipt?.merchant.orEmpty(),
                    log.receipt?.currencyCode.orEmpty(),
                    log.receipt?.total?.let(::plainMoney).orEmpty(),
                    receiptItems,
                ).joinToString(",", transform = ::csv),
            )
        }
    }

    private fun trackingLogsJson(snapshot: BackupSnapshot): String = buildString {
        append("{\"format\":\"soma-structured-logs\",\"version\":2,\"exportedAt\":")
        appendJson(snapshot.exportedAt.toString())
        append(",\"logs\":[")
        snapshot.trackingLogs.sortedWith(compareBy(LogRecord::occurredAt, LogRecord::id))
            .forEachIndexed { index, log ->
                if (index > 0) append(',')
                appendTrackingLogJson(log)
            }
        append("]}\n")
    }

    private fun trackingLogHistoryJsonl(snapshot: BackupSnapshot): String = buildString {
        snapshot.trackingLogRevisions.sortedWith(compareBy({ it.logId }, { it.revision })).forEach { revision ->
            append("{\"logId\":")
            appendJson(revision.logId)
            append(",\"revision\":${revision.revision},\"editedAt\":")
            appendJson(revision.editedAt.toString())
            append(",\"snapshot\":")
            appendTrackingLogJson(revision.snapshot)
            appendLine('}')
        }
    }

    private fun StringBuilder.appendTrackingLogJson(log: LogRecord) {
        append('{')
        jsonField("id", log.id)
        append(','); jsonField("type", log.kind.name.lowercase())
        append(','); jsonField("title", log.title)
        append(','); jsonField("note", log.note)
        append(','); jsonField("occurredAt", log.occurredAt.toString())
        append(','); jsonField("createdAt", log.createdAt.toString())
        append(','); jsonField("updatedAt", log.updatedAt.toString())
        append(','); jsonNumberField("revision", log.revision)
        append(",\"archivedAt\":"); appendNullableJson(log.archivedAt?.toString())
        append(",\"source\":")
        log.source?.let { source ->
            append('{'); jsonField("date", source.noteDate.toString()); append(',')
            jsonField("entryId", source.entryId); append('}')
        } ?: append("null")
        append(",\"foods\":[")
        log.foods.forEachIndexed { index, food ->
            if (index > 0) append(',')
            appendFoodJson(food)
        }
        append("],\"exercises\":[")
        log.exercises.forEachIndexed { index, exercise ->
            if (index > 0) append(',')
            append('{'); jsonField("name", exercise.name)
            append(",\"machine\":"); appendNullableJson(exercise.machine)
            append(",\"sets\":[")
            exercise.sets.forEachIndexed { setIndex, set ->
                if (setIndex > 0) append(',')
                append("{\"repetitions\":"); appendNullableNumber(set.repetitions)
                append(",\"weightKilograms\":"); appendNullableNumber(set.weightKilograms)
                append(",\"durationSeconds\":"); appendNullableNumber(set.durationSeconds)
                append('}')
            }
            append("]}")
        }
        append("],\"receipt\":")
        log.receipt?.let { appendReceiptJson(it) } ?: append("null")
        append('}')
    }

    private fun StringBuilder.appendReceiptJson(receipt: ReceiptDetails) {
        append('{')
        append("\"merchant\":"); appendNullableJson(receipt.merchant)
        append(','); jsonField("currency", receipt.currencyCode)
        append(",\"subtotalMinorUnits\":"); appendNullableNumber(receipt.subtotal?.minorUnits)
        append(",\"taxMinorUnits\":"); appendNullableNumber(receipt.tax?.minorUnits)
        append(",\"totalMinorUnits\":"); appendNullableNumber(receipt.total?.minorUnits)
        append(",\"items\":[")
        receipt.items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append('{'); jsonField("name", item.name)
            append(",\"quantity\":"); appendNullableNumber(item.quantity)
            append(",\"unitPriceMinorUnits\":"); appendNullableNumber(item.unitPrice?.minorUnits)
            append(",\"lineTotalMinorUnits\":"); appendNullableNumber(item.lineTotal?.minorUnits)
            append(",\"category\":"); appendNullableJson(item.category)
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendFoodJson(food: FoodItem) {
        append('{'); jsonField("name", food.name)
        append(",\"quantity\":"); appendNullableNumber(food.quantity)
        append(",\"unit\":"); appendNullableJson(food.unit?.name?.lowercase())
        append(",\"gramWeight\":"); appendNullableNumber(food.gramWeight)
        append(",\"nutrition\":")
        food.nutrition?.let { appendNutritionJson(it) } ?: append("null")
        append('}')
    }

    private fun StringBuilder.appendNutritionJson(nutrition: NutritionEstimate) {
        append('{'); jsonField("basis", nutrition.basis.name.lowercase())
        append(','); jsonField("source", nutrition.source.name.lowercase())
        append(",\"energyKcal\":"); appendNullableNumber(nutrition.energyKcal)
        append(",\"energyKcalMin\":"); appendNullableNumber(nutrition.energyKcalMin)
        append(",\"energyKcalMax\":"); appendNullableNumber(nutrition.energyKcalMax)
        append(",\"proteinGrams\":"); appendNullableNumber(nutrition.proteinGrams)
        append(",\"carbohydrateGrams\":"); appendNullableNumber(nutrition.carbohydrateGrams)
        append(",\"fatGrams\":"); appendNullableNumber(nutrition.fatGrams)
        append(",\"reference\":"); appendNullableJson(nutrition.reference)
        append('}')
    }

    private fun notesJson(snapshot: BackupSnapshot): String = buildString {
        append("{\"format\":\"soma-notes\",\"version\":$READABLE_FORMAT_VERSION,\"exportedAt\":")
        appendJson(snapshot.exportedAt.toString())
        append(",\"notes\":[")
        snapshot.notes.sortedBy { it.date }.forEachIndexed { noteIndex, note ->
            if (noteIndex > 0) append(',')
            append("{\"date\":")
            appendJson(note.date.toString())
            append(",\"createdAt\":")
            appendJson(note.createdAt.toString())
            append(",\"entries\":[")
            note.entries.sortedBy(NoteEntry::position).forEachIndexed { entryIndex, entry ->
                if (entryIndex > 0) append(',')
                entryJson(entry)
            }
            append("]}")
        }
        append("]}\n")
    }

    private fun StringBuilder.entryJson(entry: NoteEntry) {
        append('{')
        jsonField("id", entry.id)
        append(',')
        jsonNumberField("position", entry.position)
        append(',')
        jsonField("kind", entry.kind.name.lowercase())
        append(',')
        jsonField("text", entry.text)
        append(',')
        jsonField("createdAt", entry.createdAt.toString())
        append(',')
        jsonField("updatedAt", entry.updatedAt.toString())
        append(",\"lastUserEditedAt\":")
        appendNullableJson(entry.lastUserEditedAt?.toString())
        append(",\"returnLater\":${entry.returnLater}")
        append(",\"audio\":")
        val audio = entry.activeAudio
        if (audio == null) {
            append("null")
        } else {
            append('{')
            jsonField("fileId", audio.fileId)
            append(',')
            jsonField("format", audio.format.name.lowercase())
            append(',')
            jsonNumberField("durationMillis", audio.durationMillis)
            append(',')
            jsonNumberField("byteCount", audio.byteCount)
            append(',')
            jsonNumberField("sampleRateHz", audio.sampleRateHz)
            append(',')
            jsonNumberField("channelCount", audio.channelCount)
            append('}')
        }
        append(",\"transcription\":")
        val transcription = entry.transcription
        if (transcription == null) {
            append("null")
        } else {
            append('{')
            jsonField("state", transcription.state.name.lowercase())
            append(',')
            jsonNumberField("attemptCount", transcription.attemptCount)
            append(",\"detectedLanguages\":[")
            transcription.detectedLanguages.forEachIndexed { index, language ->
                if (index > 0) append(',')
                appendJson(language.languageTag)
            }
            append(']')
            append(',')
            jsonField("updatedAt", transcription.updatedAt.toString())
            append(",\"provenance\":")
            val provenance = transcription.provenance
            if (provenance == null) {
                append("null")
            } else {
                append('{')
                jsonField("requestedEngine", provenance.requestedEngine.name.lowercase())
                append(',')
                jsonField("usedEngine", provenance.usedEngine.name.lowercase())
                append(",\"fallbackReason\":")
                appendNullableJson(provenance.fallbackReason?.name?.lowercase())
                append('}')
            }
            append('}')
        }
        append(",\"image\":")
        val image = entry.activeImage
        if (image == null) {
            append("null")
        } else {
            append('{')
            jsonField("fileId", image.fileId)
            append(',')
            jsonField("format", image.format.name.lowercase())
            append(',')
            jsonNumberField("width", image.width)
            append(',')
            jsonNumberField("height", image.height)
            append(',')
            jsonNumberField("rotationDegrees", image.rotationDegrees)
            append(',')
            jsonNumberField("byteCount", image.byteCount)
            append('}')
        }
        append('}')
    }

    private fun transcriptionLabel(provenance: TranscriptionProvenance): String {
        val requested = engineLabel(provenance.requestedEngine)
        val used = engineLabel(provenance.usedEngine)
        return when (provenance.fallbackReason) {
            null -> used
            TranscriptionFallbackReason.WIFI_REQUIRED -> "$used (requested $requested; skipped without Wi-Fi)"
            TranscriptionFallbackReason.API_KEY_MISSING -> "$used (requested $requested; API key missing)"
            TranscriptionFallbackReason.PROVIDER_ERROR -> "$used (requested $requested; provider failed)"
            TranscriptionFallbackReason.AUTHENTICATION_ERROR -> "$used (requested $requested; API key rejected)"
            TranscriptionFallbackReason.PERMISSION_ERROR -> "$used (requested $requested; speech-to-text permission missing)"
            TranscriptionFallbackReason.PAYMENT_REQUIRED -> "$used (requested $requested; credits required)"
            TranscriptionFallbackReason.RATE_LIMITED -> "$used (requested $requested; rate limited)"
            TranscriptionFallbackReason.INVALID_REQUEST -> "$used (requested $requested; audio request rejected)"
            TranscriptionFallbackReason.NETWORK_ERROR -> "$used (requested $requested; network error)"
        }
    }

    private fun engineLabel(engine: TranscriptionEngine): String = when (engine) {
        TranscriptionEngine.LOCAL_WHISPER_TINY -> "local Whisper tiny"
        TranscriptionEngine.ELEVENLABS_SCRIBE_V2 -> "ElevenLabs Scribe v2"
        TranscriptionEngine.GROQ_WHISPER_LARGE_V3_TURBO -> "Groq Whisper Large v3 Turbo"
        TranscriptionEngine.GROQ_WHISPER_LARGE_V3 -> "Groq Whisper Large v3"
    }

    private fun plainMoney(money: ReceiptMoney): String =
        "${money.currencyCode} ${money.minorUnits / 100}.${(money.minorUnits % 100).toString().padStart(2, '0')}"

    private fun historyJsonl(snapshot: BackupSnapshot): String = buildString {
        snapshot.entryRevisions.sortedWith(compareBy({ it.entryId }, { it.revision })).forEach { revision ->
            append('{')
            jsonField("entryId", revision.entryId)
            append(',')
            jsonNumberField("revision", revision.revision)
            append(',')
            jsonField("text", revision.text)
            append(',')
            jsonField("editedAt", revision.editedAt.toString())
            appendLine('}')
        }
    }

    private fun metadataJson(snapshot: BackupSnapshot): String = buildString {
        append("{\"format\":\"soma-entry-metadata\",\"version\":1,\"layers\":[")
        snapshot.entryMetadata.sortedWith(compareBy(EntryMetadata::entryId, EntryMetadata::source))
            .forEachIndexed { index, metadata ->
                if (index > 0) append(',')
                append('{')
                jsonField("entryId", metadata.entryId)
                append(',')
                jsonField("source", metadata.source.name.lowercase())
                append(',')
                jsonField("derivedAt", metadata.derivedAt.toString())
                append(",\"tags\":[")
                metadata.tags.forEachIndexed { tagIndex, tag ->
                    if (tagIndex > 0) append(',')
                    appendJson(tag)
                }
                append("],\"links\":[")
                metadata.links.forEachIndexed { linkIndex, link ->
                    if (linkIndex > 0) append(',')
                    append('{')
                    jsonField("kind", link.kind.name.lowercase())
                    append(',')
                    jsonField("target", link.target)
                    append(",\"relation\":")
                    appendNullableJson(link.relation)
                    append('}')
                }
                append("]}")
            }
        append("]}\n")
    }

    private fun markdownText(value: String): String = value.replace("\n", "\n  ")

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun StringBuilder.jsonField(name: String, value: String) {
        appendJson(name)
        append(':')
        appendJson(value)
    }

    private fun StringBuilder.jsonNumberField(name: String, value: Number) {
        appendJson(name)
        append(':')
        append(value)
    }

    private fun StringBuilder.appendNullableJson(value: String?) {
        if (value == null) append("null") else appendJson(value)
    }

    private fun StringBuilder.appendNullableNumber(value: Number?) {
        if (value == null) append("null") else append(value)
    }

    private fun plainNumber(value: Double): String = if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        value.toString()
    }

    private fun StringBuilder.appendJson(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun ZipOutputStream.putText(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private companion object {
        const val READABLE_FORMAT_VERSION = 10
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
    }
}
