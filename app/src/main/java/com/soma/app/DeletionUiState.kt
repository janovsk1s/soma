package com.soma.app

import java.time.Instant

/** Exact prior tombstones so one-tap Undo restores the state byte-for-byte. */
internal data class DeletionUndo(
    val entryId: String,
    val previousDeletedAt: Instant?,
    val previousAudioDeletedAt: Instant?,
    val previousImageDeletedAt: Instant?,
)
