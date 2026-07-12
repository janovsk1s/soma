package com.soma.app

import com.soma.core.model.TranscriptionFailureCode
import java.io.FileNotFoundException
import java.io.IOException
import com.soma.whisper.WhisperModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionFailureClassifierTest {
    @Test
    fun missingAudioIsTerminal() {
        val failure = classifyTranscriptionFailure(FileNotFoundException())
        assertEquals(TranscriptionFailureCode.AUDIO_UNAVAILABLE, failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun transientIoRetriesWithoutContentsInDiagnostic() {
        val failure = classifyTranscriptionFailure(IOException("private transcript text"))
        assertTrue(failure.retryable)
        assertEquals("IOException", failure.diagnostic)
    }

    @Test
    fun outOfMemoryIsTerminal() {
        val failure = classifyTranscriptionFailure(OutOfMemoryError())
        assertEquals(TranscriptionFailureCode.OUT_OF_MEMORY, failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun bundledModelFailureIsTerminal() {
        val failure = classifyTranscriptionFailure(WhisperModelException())
        assertEquals(TranscriptionFailureCode.MODEL_ERROR, failure.code)
        assertFalse(failure.retryable)
    }
}
