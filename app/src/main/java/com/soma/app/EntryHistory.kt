package com.soma.app

import com.soma.core.model.EntryRevision
import com.soma.core.model.NoteEntry
import java.time.Instant

/** One wording in the user-authored history of an entry, oldest first. */
internal data class EntryHistoryVersion(
    val number: Int,
    val text: String,
    /** When this wording became current, not when a later wording replaced it. */
    val becameCurrentAt: Instant,
    val isCurrent: Boolean,
) {
    val isOriginal: Boolean get() = number == 1
}

/**
 * Reconstructs the full wording history without changing the live entry.
 *
 * A stored revision contains the wording that was replaced and the instant it
 * was replaced. Therefore each revision after the first became current at the
 * preceding revision's edit instant; the live wording began at the final edit.
 */
internal fun buildEntryHistory(
    entry: NoteEntry,
    revisions: List<EntryRevision>,
): List<EntryHistoryVersion> {
    val ordered = revisions
        .filter { it.entryId == entry.id }
        .sortedBy(EntryRevision::revision)
    if (ordered.isEmpty()) {
        return listOf(
            EntryHistoryVersion(
                number = 1,
                text = entry.text,
                becameCurrentAt = entry.createdAt,
                isCurrent = true,
            ),
        )
    }

    val previous = ordered.mapIndexed { index, revision ->
        EntryHistoryVersion(
            number = index + 1,
            text = revision.text,
            becameCurrentAt = if (index == 0) entry.createdAt else ordered[index - 1].editedAt,
            isCurrent = false,
        )
    }
    return previous + EntryHistoryVersion(
        number = previous.size + 1,
        text = entry.text,
        becameCurrentAt = ordered.last().editedAt,
        isCurrent = true,
    )
}
