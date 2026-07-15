package com.soma.app

import android.app.Application
import com.soma.core.model.LogKind
import com.soma.core.model.TranscriptionFallbackReason
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudVisionModelFallbackTest {

    @Test
    fun `a retired vision model falls through to the next candidate`() {
        val opener = ScriptedOpener(404 to MODEL_GONE, 200 to proposal("item: piens | 1 | 1.09"))

        val result = runBlocking {
            CloudHttp.extractTrackingText("key", LogKind.RECEIPT, "čeks", JPEG, opener)
        }

        assertEquals("item: piens | 1 | 1.09", result)
        assertEquals(CLOUD_AI_VISION_MODELS, opener.requestedModels())
        assertTrue(opener.requestBodies()[0].has("reasoning_effort"))
        assertFalse(opener.requestBodies()[1].has("reasoning_effort"))
    }

    @Test
    fun `an account failure is never retried on another model`() {
        val opener = ScriptedOpener(401 to """{"error":{"code":"invalid_api_key"}}""")

        try {
            runBlocking { CloudHttp.extractTrackingText("key", LogKind.MEAL, "pusdienas", JPEG, opener) }
            fail("Expected the provider failure to surface")
        } catch (error: CloudProviderException) {
            assertEquals(TranscriptionFallbackReason.AUTHENTICATION_ERROR, error.fallbackReason)
            assertFalse(error.modelUnavailable)
        }
        assertEquals(1, opener.connections.size)
    }

    @Test
    fun `a rate limit does not burn the fallback model`() {
        val opener = ScriptedOpener(429 to """{"error":{"code":"rate_limit_exceeded"}}""")

        try {
            runBlocking { CloudHttp.extractTrackingText("key", LogKind.WORKOUT, "treniņš", JPEG, opener) }
            fail("Expected the provider failure to surface")
        } catch (error: CloudProviderException) {
            assertEquals(TranscriptionFallbackReason.RATE_LIMITED, error.fallbackReason)
            assertFalse(error.modelUnavailable)
        }
        assertEquals(1, opener.connections.size)
    }

    @Test
    fun `every candidate missing surfaces the last failure`() {
        val opener = ScriptedOpener(404 to MODEL_GONE, 404 to MODEL_GONE)

        try {
            runBlocking { CloudHttp.extractTrackingText("key", LogKind.RECEIPT, "čeks", JPEG, opener) }
            fail("Expected the provider failure to surface")
        } catch (error: CloudProviderException) {
            assertTrue(error.modelUnavailable)
        }
        assertEquals(CLOUD_AI_VISION_MODELS.size, opener.connections.size)
    }

    @Test
    fun `the text path has a single model and no chain`() {
        val opener = ScriptedOpener(404 to MODEL_GONE)

        try {
            runBlocking { CloudHttp.extractTrackingText("key", LogKind.RECIPE, "recepte", null, opener) }
            fail("Expected the provider failure to surface")
        } catch (error: CloudProviderException) {
            assertTrue(error.modelUnavailable)
        }
        assertEquals(listOf(CLOUD_AI_TODO_MODEL), opener.requestedModels())
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: String,
    ) : HttpURLConnection(url) {
        val written = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): OutputStream = written
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = body.byteInputStream()
        override fun getErrorStream(): InputStream = body.byteInputStream()
    }

    private class ScriptedOpener(vararg responses: Pair<Int, String>) : CloudConnectionOpener {
        private val script = responses.toMutableList()
        val connections = mutableListOf<FakeConnection>()

        override fun open(url: URL): HttpURLConnection {
            check(script.isNotEmpty()) { "No scripted response left for $url" }
            val (status, body) = script.removeAt(0)
            return FakeConnection(url, status, body).also(connections::add)
        }

        fun requestBodies(): List<JSONObject> =
            connections.map { JSONObject(it.written.toString(Charsets.UTF_8.name())) }

        fun requestedModels(): List<String> = requestBodies().map { it.getString("model") }
    }

    private fun proposal(vararg lines: String): String = JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put(
                        "content",
                        JSONObject().put("lines", JSONArray(lines.toList())).toString(),
                    ),
                ),
            ),
        )
        .toString()

    private companion object {
        val JPEG = byteArrayOf(1, 2, 3)
        const val MODEL_GONE = """{"error":{"code":"model_not_found","message":"the model has been retired"}}"""
    }
}
