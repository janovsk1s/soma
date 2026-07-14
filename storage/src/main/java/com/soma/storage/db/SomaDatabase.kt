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
        EntryMetadataEntity::class,
        TrackingLogEntity::class,
        TrackingLogRevisionEntity::class,
        TodoEntity::class,
        TodoSuggestionEntity::class,
        StillOpenDismissalEntity::class,
        TranscriptionJobEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class SomaDatabase : RoomDatabase() {
    abstract fun dailyNoteDao(): DailyNoteDao
    abstract fun entryDao(): EntryDao
    abstract fun entryRevisionDao(): EntryRevisionDao
    abstract fun entryMetadataDao(): EntryMetadataDao
    abstract fun trackingLogDao(): TrackingLogDao
    abstract fun trackingLogRevisionDao(): TrackingLogRevisionDao
    abstract fun todoDao(): TodoDao
    abstract fun todoSuggestionDao(): TodoSuggestionDao
    abstract fun stillOpenDismissalDao(): StillOpenDismissalDao
    abstract fun transcriptionJobDao(): TranscriptionJobDao

    companion object {
        const val DEFAULT_DATABASE_NAME = "soma.db"

        fun build(context: Context, name: String = DEFAULT_DATABASE_NAME): SomaDatabase =
            Room.databaseBuilder(context.applicationContext, SomaDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                )
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN transcription_requested_engine TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN transcription_used_engine TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN transcription_fallback_reason TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todos ADD COLUMN kind TEXT NOT NULL DEFAULT 'ACTION'")
                db.execSQL(
                    "ALTER TABLE todo_suggestions ADD COLUMN suggested_kind TEXT NOT NULL DEFAULT 'ACTION'",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todos ADD COLUMN resurface_epoch_day INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN deleted_at_millis INTEGER")
                db.execSQL("ALTER TABLE entries ADD COLUMN audio_deleted_at_millis INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN image_file_id TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN image_format TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN image_width INTEGER")
                db.execSQL("ALTER TABLE entries ADD COLUMN image_height INTEGER")
                db.execSQL("ALTER TABLE entries ADD COLUMN image_rotation_degrees INTEGER")
                db.execSQL("ALTER TABLE entries ADD COLUMN image_byte_count INTEGER")
                db.execSQL("ALTER TABLE entries ADD COLUMN image_deleted_at_millis INTEGER")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_entries_image_file_id ON entries(image_file_id)",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS entry_metadata (
                        entry_id TEXT NOT NULL,
                        source TEXT NOT NULL,
                        tags_ciphertext BLOB NOT NULL,
                        links_ciphertext BLOB NOT NULL,
                        crypto_version INTEGER NOT NULL,
                        derived_at_millis INTEGER NOT NULL,
                        PRIMARY KEY(entry_id, source),
                        FOREIGN KEY(entry_id) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_entry_metadata_entry_id ON entry_metadata(entry_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_entry_metadata_source_derived_at_millis " +
                        "ON entry_metadata(source, derived_at_millis)",
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tracking_logs (
                        id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        payload_ciphertext BLOB NOT NULL,
                        crypto_version INTEGER NOT NULL,
                        occurred_at_millis INTEGER NOT NULL,
                        created_at_millis INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL,
                        source_note_epoch_day INTEGER,
                        source_entry_id TEXT,
                        revision INTEGER NOT NULL,
                        archived_at_millis INTEGER,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tracking_logs_kind_occurred_at_millis " +
                        "ON tracking_logs(kind, occurred_at_millis)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tracking_logs_source_entry_id " +
                        "ON tracking_logs(source_entry_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tracking_logs_archived_at_millis " +
                        "ON tracking_logs(archived_at_millis)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tracking_log_revisions (
                        log_id TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        payload_ciphertext BLOB NOT NULL,
                        crypto_version INTEGER NOT NULL,
                        edited_at_millis INTEGER NOT NULL,
                        PRIMARY KEY(log_id, revision),
                        FOREIGN KEY(log_id) REFERENCES tracking_logs(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tracking_log_revisions_log_id " +
                        "ON tracking_log_revisions(log_id)",
                )
            }
        }
    }
}
