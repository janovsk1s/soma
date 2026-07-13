package com.soma.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DailyNoteEntity::class,
        EntryEntity::class,
        EntryRevisionEntity::class,
        TodoEntity::class,
        TodoSuggestionEntity::class,
        StillOpenDismissalEntity::class,
        TranscriptionJobEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SomaDatabase : RoomDatabase() {
    abstract fun dailyNoteDao(): DailyNoteDao
    abstract fun entryDao(): EntryDao
    abstract fun entryRevisionDao(): EntryRevisionDao
    abstract fun todoDao(): TodoDao
    abstract fun todoSuggestionDao(): TodoSuggestionDao
    abstract fun stillOpenDismissalDao(): StillOpenDismissalDao
    abstract fun transcriptionJobDao(): TranscriptionJobDao

    companion object {
        const val DEFAULT_DATABASE_NAME = "soma.db"

        fun build(context: Context, name: String = DEFAULT_DATABASE_NAME): SomaDatabase =
            Room.databaseBuilder(context.applicationContext, SomaDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN last_user_edited_at_millis INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS entry_revisions (
                        entry_id TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        text_ciphertext BLOB NOT NULL,
                        crypto_version INTEGER NOT NULL,
                        edited_at_millis INTEGER NOT NULL,
                        PRIMARY KEY(entry_id, revision),
                        FOREIGN KEY(entry_id) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_entry_revisions_entry_id ON entry_revisions(entry_id)",
                )
            }
        }
    }
}
