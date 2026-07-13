package com.soma.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperTranscriptTest {
    @Test
    fun blankAudioMarkerIsNotShownToTheUser() {
        assertEquals(
            "Remember to buy milk tomorrow.",
            cleanWhisperTranscript(" [BLANK_AUDIO]  Remember to buy milk tomorrow. "),
        )
    }

    @Test
    fun ordinaryBracketedSpeechIsPreserved() {
        assertEquals(
            "Say [hello] when you arrive.",
            cleanWhisperTranscript("Say [hello] when you arrive."),
        )
    }
}
