package com.soma.storage.db

import androidx.room.Embedded
import androidx.room.Relation

data class DailyNoteWithEntries(
    @Embedded val note: DailyNoteEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "note_id",
    )
    val entries: List<EntryEntity>,
)
