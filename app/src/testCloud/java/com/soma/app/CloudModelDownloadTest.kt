package com.soma.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.soma.whisper.LocalWhisperModel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
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
class CloudModelDownloadTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val store = LocalModelStore(context)
    private val bytes = ByteArray(150_000) { (it % 199).toByte() }
    private val spec = LocalModelSpec(
        fileName = "test-base.bin",
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        byteCount = bytes.size.toLong(),
    )

    @Test
    fun `a fresh download verifies and installs`() {
        val opener = ScriptedOpener(FakeConnection(200, bytes))
        val downloader = downloader(opener, onWifi = true)
        var lastProgress = 0L to 0L

        val installed = runBlocking {
            downloader.download(LocalWhisperModel.BASE) { downloaded, total ->
                lastProgress = downloaded to total
            }
        }

        assertTrue(installed.isFile)
        assertNotNull(store.installedFile(spec))
        assertEquals(spec.byteCount to spec.byteCount, lastProgress)
        assertNull(opener.connections.single().getRequestProperty("Range"))
    }

    @Test
    fun `an interrupted download resumes with a range request`() {
        val split = 60_000
        store.partialFile(spec).writeBytes(bytes.copyOfRange(0, split))
        val opener = ScriptedOpener(FakeConnection(206, bytes.copyOfRange(split, bytes.size)))
        val downloader = downloader(opener, onWifi = true)

        runBlocking { downloader.download(LocalWhisperModel.BASE) { _, _ -> } }

        assertEquals("bytes=$split-", opener.connections.single().getRequestProperty("Range"))
        assertNotNull(store.installedFile(spec))
    }

    @Test
    fun `a server that ignores the range restarts the stage from zero`() {
        store.partialFile(spec).writeBytes(bytes.copyOfRange(0, 10_000))
        val opener = ScriptedOpener(FakeConnection(200, bytes))
        val downloader = downloader(opener, onWifi = true)

        runBlocking { downloader.download(LocalWhisperModel.BASE) { _, _ -> } }

        assertNotNull(store.installedFile(spec))
    }

    @Test
    fun `off wifi the download refuses before opening a connection`() {
        val opener = ScriptedOpener()
        val downloader = downloader(opener, onWifi = false)

        try {
            runBlocking { downloader.download(LocalWhisperModel.BASE) { _, _ -> } }
            fail("Expected the Wi-Fi requirement to surface")
        } catch (_: ModelWifiRequiredException) {
        }
        assertTrue(opener.connections.isEmpty())
    }

    @Test
    fun `a rejected resume clears the stale stage`() {
        store.partialFile(spec).writeBytes(ByteArray(10_000))
        val opener = ScriptedOpener(FakeConnection(416, ByteArray(0)))
        val downloader = downloader(opener, onWifi = true)

        try {
            runBlocking { downloader.download(LocalWhisperModel.BASE) { _, _ -> } }
            fail("Expected the HTTP failure to surface")
        } catch (error: IOException) {
            assertFalse(error is ModelVerificationException)
        }
        assertFalse(store.partialFile(spec).exists())
    }

    @Test
    fun `bytes that do not match the pinned digest never install`() {
        val wrong = bytes.copyOf().also { it[5] = 99 }
        val opener = ScriptedOpener(FakeConnection(200, wrong))
        val downloader = downloader(opener, onWifi = true)

        try {
            runBlocking { downloader.download(LocalWhisperModel.BASE) { _, _ -> } }
            fail("Expected verification to fail")
        } catch (_: ModelVerificationException) {
        }
        assertNull(store.installedFile(spec))
        assertFalse(store.partialFile(spec).exists())
    }

    @Test
    fun `the cloud flavor exposes a downloader and the registry URL is pinned to base`() {
        assertNotNull(cloudFeatures(context).modelDownloader())
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            whisperModelUrl(LocalWhisperModel.BASE).toString(),
        )
    }

    private fun downloader(opener: ScriptedOpener, onWifi: Boolean) = CloudModelDownloader(
        store = store,
        networkStatus = { onWifi },
        connectionOpener = opener,
        urlForModel = { URL("https://example.test/model.bin") },
        specForModel = { spec },
    )

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray,
    ) : HttpURLConnection(URL("https://example.test/model.bin")) {
        private val headers = mutableMapOf<String, String>()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun getRequestProperty(key: String): String? = headers[key]
        override fun getOutputStream(): OutputStream = throw IllegalStateException("GET only")
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = body.inputStream()
        override fun getErrorStream(): InputStream = body.inputStream()
    }

    private class ScriptedOpener(vararg responses: FakeConnection) : CloudConnectionOpener {
        private val script = responses.toMutableList()
        val connections = mutableListOf<FakeConnection>()

        override fun open(url: URL): HttpURLConnection {
            check(script.isNotEmpty()) { "No scripted response left for $url" }
            return script.removeAt(0).also(connections::add)
        }
    }
}
