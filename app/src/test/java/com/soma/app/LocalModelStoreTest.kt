package com.soma.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.soma.whisper.LocalWhisperModel
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalModelStoreTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val store = LocalModelStore(context)

    @Test
    fun `import accepts a stream matching the pinned digest`() {
        val bytes = ByteArray(4_096) { (it % 251).toByte() }
        val spec = specFor(bytes)

        val installed = store.importFrom(spec, bytes.inputStream())

        assertTrue(installed.isFile)
        assertEquals(spec.byteCount, installed.length())
        assertNotNull(store.installedFile(spec))
        assertFalse(store.partialFile(spec).exists())
    }

    @Test
    fun `import rejects a digest mismatch and leaves nothing behind`() {
        val bytes = ByteArray(4_096) { (it % 251).toByte() }
        val wrong = bytes.copyOf().also { it[100] = 42 }

        try {
            store.importFrom(specFor(bytes), wrong.inputStream())
            fail("Expected the digest mismatch to surface")
        } catch (_: ModelVerificationException) {
        }
        assertNull(store.installedFile(specFor(bytes)))
        assertFalse(store.partialFile(specFor(bytes)).exists())
    }

    @Test
    fun `import stops reading an oversized stream at the registry size`() {
        val bytes = ByteArray(2_048) { 7 }
        val spec = specFor(bytes)
        val oversized = ByteArray(bytes.size * 3) { 7 }

        try {
            store.importFrom(spec, oversized.inputStream())
            fail("Expected the oversized stream to be rejected")
        } catch (_: ModelVerificationException) {
        }
        assertNull(store.installedFile(spec))
        assertFalse(store.partialFile(spec).exists())
    }

    @Test
    fun `a complete staged file promotes into place`() {
        val bytes = ByteArray(1_024) { (it * 3).toByte() }
        val spec = specFor(bytes)
        store.partialFile(spec).writeBytes(bytes)

        val installed = store.promotePartial(spec)

        assertTrue(installed.isFile)
        assertNotNull(store.installedFile(spec))
        assertFalse(store.partialFile(spec).exists())
    }

    @Test
    fun `a corrupt staged file is discarded rather than promoted`() {
        val bytes = ByteArray(1_024) { (it * 3).toByte() }
        val spec = specFor(bytes)
        store.partialFile(spec).writeBytes(bytes.copyOf().also { it[0] = -1 })

        try {
            store.promotePartial(spec)
            fail("Expected the digest mismatch to surface")
        } catch (_: ModelVerificationException) {
        }
        assertFalse(store.partialFile(spec).exists())
        assertNull(store.installedFile(spec))
    }

    @Test
    fun `delete removes the installed weights`() {
        val bytes = ByteArray(512) { 1 }
        val spec = specFor(bytes)
        store.importFrom(spec, bytes.inputStream())

        assertTrue(store.delete(spec))

        assertNull(store.installedFile(spec))
    }

    @Test
    fun `a base selection without weights on disk resolves to tiny`() {
        SomaPrefs.setLocalWhisperModel(context, LocalWhisperModel.BASE)

        assertEquals(LocalWhisperModel.BASE, SomaPrefs.localWhisperModel(context))
        assertEquals(LocalWhisperModel.TINY, resolveLocalWhisperModel(context, store))
    }

    @Test
    fun `an unparseable stored model name falls back to tiny`() {
        context.getSharedPreferences("soma_preferences", android.content.Context.MODE_PRIVATE)
            .edit().putString("local_whisper_model", "LARGE_V4").commit()

        assertEquals(LocalWhisperModel.TINY, SomaPrefs.localWhisperModel(context))
    }

    private fun specFor(bytes: ByteArray) = LocalModelSpec(
        fileName = "test-model.bin",
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        byteCount = bytes.size.toLong(),
    )
}
