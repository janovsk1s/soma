package com.soma.storage.backup

import com.soma.core.model.DailyNote
import com.soma.core.model.LogRevision
import com.soma.core.model.NoteEntry

/**
 * Removes recoverable tombstones from plaintext exports without mutating the backup source.
 * Encrypted portable backups deliberately keep these records so they can still be restored.
 */
internal fun BackupSnapshot.withoutDeletedContent(): BackupSnapshot {
    val visibleNotes = notes.map { note ->
        note.copy(entries = note.entries.filterNot(NoteEntry::isDeleted))
    }
    val entries = visibleNotes.flatMap(DailyNote::entries)
    val entryIds = entries.mapTo(hashSetOf(), NoteEntry::id)
    val audioIds = entries.mapNotNull { it.activeAudio?.fileId }.toSet()
    val audioEntryIds = entries.filter { it.activeAudio != null }.mapTo(hashSetOf(), NoteEntry::id)
    val imageIds = entries.mapNotNull { it.activeImage?.fileId }.toSet()
    val visibleLogs = trackingLogs.map { log ->
        if (log.source?.entryId in entryIds) log else log.copy(source = null)
    }
    val visibleLogRevisions = trackingLogRevisions.map { revision ->
        val snapshot = revision.snapshot
        if (snapshot.source?.entryId in entryIds) {
            revision
        } else {
            LogRevision(
                logId = revision.logId,
                revision = revision.revision,
                snapshot = snapshot.copy(source = null),
                editedAt = revision.editedAt,
            )
        }
    }
    return copy(
        notes = visibleNotes,
        entryRevisions = entryRevisions.filter { it.entryId in entryIds },
        entryMetadata = entryMetadata.filter { it.entryId in entryIds },
        trackingLogs = visibleLogs,
        trackingLogRevisions = visibleLogRevisions,
        todos = todos.map { todo ->
            if (todo.source?.entryId in entryIds) todo else todo.copy(source = null)
        },
        suggestions = suggestions.filter { it.entryId in entryIds },
        transcriptionJobs = transcriptionJobs.filter { it.entryId in audioEntryIds },
        audioContainers = audioContainers.filter { it.fileId in audioIds },
        imageContainers = imageContainers.filter { it.fileId in imageIds },
    )
}
