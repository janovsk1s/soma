package com.soma.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingUiStateTest {
    @Test
    fun `elapsed recording time uses stable minute and second formatting`() {
        assertEquals("0:00", formatRecordingElapsed(-1))
        assertEquals("0:05", formatRecordingElapsed(5))
        assertEquals("1:05", formatRecordingElapsed(65))
        assertEquals("60:00", formatRecordingElapsed(3_600))
    }
}
