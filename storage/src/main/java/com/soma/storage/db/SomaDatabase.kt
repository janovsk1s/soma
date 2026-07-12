package com.soma.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DailyNoteEntity::class,
        EntryEntity::class,
        TodoEntity::class,
        TodoSuggestionEntity::class,
        StillOpenDismissalEntity::class,
        TranscriptionJobEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SomaDatabase : RoomDatabase() {
    abstract fun dailyNoteDao(): DailyNoteDao
    abstract fun entryDao(): EntryDao
    abstract fun todoDao(): TodoDao
    abstract fun todoSuggestionDao(): TodoSuggestionDao
    abstract fun stillOpenDismissalDao(): StillOpenDismissalDao
    abstract fun transcriptionJobDao(): TranscriptionJobDao

    companion object {
        const val DEFAULT_DATABASE_NAME = "soma.db"

        fun build(context: Context, name: String = DEFAULT_DATABASE_NAME): SomaDatabase =
            Room.databaseBuilder(context.applicationContext, SomaDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
