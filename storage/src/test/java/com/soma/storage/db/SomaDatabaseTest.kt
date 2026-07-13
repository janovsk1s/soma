package com.soma.storage.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.soma.storage.crypto.AesGcmCipher
import com.soma.storage.crypto.StorageAad
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SomaDatabaseTest {
    private lateinit var database: SomaDatabase
    private val cipher = AesGcmCipher(ByteArray(32) { (it + 7).toByte() })

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SomaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `plaintext is absent from encrypted Room columns`() = runBlocking {
        val marker = "PLAINTEXT-MARKER-jāatceras"
        val note = note("note-42", 42)
        database.dailyNoteDao().insert(note)
        database.entryDao().insert(
            entry(
                id = "entry-1",
                noteId = note.id,
                position = 0,
                textCiphertext = cipher.encrypt(
                    marker,
                    StorageAad.forField("entry-1", "entry.text", 1),
                ),
            ),
        )
        database.todoDao().insert(
            TodoEntity(
                id = "todo-1",
                textCiphertext = cipher.encrypt(
                    marker,
                    StorageAad.forField("todo-1", "todo.text", 1),
                ),
                cryptoVersion = 1,
                createdEpochDay = 42,
                createdAtMillis = 1_000,
                updatedAtMillis = 1_000,
                lastTouchedAtMillis = 1_000,
                sourceNoteId = note.id,
                sourceEntryId = "entry-1",
                status = TodoStatusValue.OPEN,
                completedAtMillis = null,
                reviewPromptedAtMillis = null,
            ),
        )

        val sqlite = database.openHelper.writableDatabase
        sqlite.query("SELECT text_ciphertext FROM entries").use { cursor ->
            cursor.moveToFirst()
            assertFalse(cursor.getBlob(0).containsSubsequence(marker.encodeToByteArray()))
        }
        sqlite.query("SELECT text_ciphertext FROM todos").use { cursor ->
            cursor.moveToFirst()
            assertFalse(cursor.getBlob(0).containsSubsequence(marker.encodeToByteArray()))
        }
    }

    @Test
    fun `schema three migration preserves rows as action kinds`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "soma-migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE todos (id TEXT NOT NULL PRIMARY KEY)")
                            db.execSQL("CREATE TABLE todo_suggestions (id TEXT NOT NULL PRIMARY KEY)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val sqlite = helper.writableDatabase
            sqlite.execSQL("INSERT INTO todos (id) VALUES ('existing-todo')")
            sqlite.execSQL("INSERT INTO todo_suggestions (id) VALUES ('existing-suggestion')")

            SomaDatabase.MIGRATION_3_4.migrate(sqlite)

            sqlite.query("SELECT kind FROM todos WHERE id = 'existing-todo'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(ImportantKindValue.ACTION, cursor.getString(0))
            }
            sqlite.query(
                "SELECT suggested_kind FROM todo_suggestions WHERE id = 'existing-suggestion'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(ImportantKindValue.ACTION, cursor.getString(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `schema four migration adds an empty show again date`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "soma-migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE todos (id TEXT NOT NULL PRIMARY KEY)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val sqlite = helper.writableDatabase
            sqlite.execSQL("INSERT INTO todos (id) VALUES ('existing-todo')")

            SomaDatabase.MIGRATION_4_5.migrate(sqlite)

            sqlite.query("SELECT resurface_epoch_day FROM todos WHERE id = 'existing-todo'").use { cursor ->
                cursor.moveToFirst()
                assertTrue(cursor.isNull(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `only one transcription job may be leased at a time`() = runBlocking {
        val note = note("note-1", 1)
        database.dailyNoteDao().insert(note)
        database.entryDao().insert(entry("entry-1", note.id, 0, null))
        database.entryDao().insert(entry("entry-2", note.id, 1, null))
        val dao = database.transcriptionJobDao()
        dao.upsert(job("job-1", "entry-1", enqueuedAt = 10))
        dao.upsert(job("job-2", "entry-2", enqueuedAt = 20))

        val first = dao.claimNext("worker-a", nowMillis = 100, leaseExpiresAtMillis = 200, maxAttempts = 3)
        assertEquals("job-1", first?.id)
        assertEquals(1, first?.attemptCount)
        assertNull(dao.claimNext("worker-b", nowMillis = 101, leaseExpiresAtMillis = 201, maxAttempts = 3))

        val reclaimed = dao.claimNext("worker-b", nowMillis = 200, leaseExpiresAtMillis = 300, maxAttempts = 3)
        assertEquals("job-1", reclaimed?.id)
        assertEquals(2, reclaimed?.attemptCount)
    }

    @Test
    fun `retry delay leaves another queued job claimable`() = runBlocking {
        val note = note("note-1", 1)
        database.dailyNoteDao().insert(note)
        database.entryDao().insert(entry("entry-1", note.id, 0, null))
        database.entryDao().insert(entry("entry-2", note.id, 1, null))
        val dao = database.transcriptionJobDao()
        dao.upsert(job("job-1", "entry-1", enqueuedAt = 10))
        dao.upsert(job("job-2", "entry-2", enqueuedAt = 20))
        assertNotNull(dao.claimNext("worker-a", 100, 200, 3))

        val failure = cipher.encrypt(
            "model unavailable",
            StorageAad.forField("job-1", "transcription.lastFailure", 1),
        )
        assertEquals(
            1,
            dao.fail(
                jobId = "job-1",
                workerId = "worker-a",
                nextState = TranscriptionStateValue.QUEUED,
                notBeforeMillis = 1_000,
                lastErrorCiphertext = failure,
                lastErrorCode = "ENGINE_UNAVAILABLE",
                lastErrorRetryable = true,
                cryptoVersion = 1,
                nowMillis = 110,
            ),
        )

        assertEquals("job-2", dao.claimNext("worker-b", 111, 211, 3)?.id)
    }

    private fun note(id: String, epochDay: Long) = DailyNoteEntity(
        id = id,
        epochDay = epochDay,
        createdAtMillis = 1_000,
        updatedAtMillis = 1_000,
    )

    private fun entry(
        id: String,
        noteId: String,
        position: Int,
        textCiphertext: ByteArray?,
    ) = EntryEntity(
        id = id,
        noteId = noteId,
        position = position,
        type = EntryTypeValue.VOICE,
        textCiphertext = textCiphertext,
        cryptoVersion = 1,
        audioFileId = "audio-$id",
        audioFormat = "WAV",
        audioDurationMillis = 500,
        audioByteCount = 16_000,
        audioSampleRateHz = 16_000,
        audioChannelCount = 1,
        transcriptionState = TranscriptionStateValue.QUEUED,
        transcriptionAttemptCount = 0,
        detectedLanguages = null,
        transcriptionRequestedEngine = null,
        transcriptionUsedEngine = null,
        transcriptionFallbackReason = null,
        transcriptionUpdatedAtMillis = 1_000,
        transcriptionFailureCode = null,
        transcriptionFailureRetryable = null,
        transcriptionFailureCiphertext = null,
        returnLater = false,
        createdAtMillis = 1_000,
        updatedAtMillis = 1_000,
        lastUserEditedAtMillis = null,
        revision = 0,
    )

    private fun job(id: String, entryId: String, enqueuedAt: Long) = TranscriptionJobEntity(
        id = id,
        entryId = entryId,
        state = TranscriptionStateValue.QUEUED,
        enqueuedAtMillis = enqueuedAt,
        updatedAtMillis = enqueuedAt,
        attemptCount = 0,
        notBeforeMillis = enqueuedAt,
        leaseOwner = null,
        leaseExpiresAtMillis = null,
        lastErrorCiphertext = null,
        lastErrorCode = null,
        lastErrorRetryable = null,
        cryptoVersion = 1,
    )

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
    }
}
