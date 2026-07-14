package com.soma.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.soma.core.model.TranscriptionFallbackReason
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LastCloudErrorPreferenceTest {
    @Test
    fun `last cloud error round trips and clears`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertNull(SomaPrefs.lastCloudError(context))

        val at = Instant.parse("2026-07-14T18:30:00Z")
        SomaPrefs.setLastCloudError(context, TranscriptionFallbackReason.AUTHENTICATION_ERROR, at)
        assertEquals(
            LastCloudError(TranscriptionFallbackReason.AUTHENTICATION_ERROR, at),
            SomaPrefs.lastCloudError(context),
        )

        SomaPrefs.setLastCloudError(context, TranscriptionFallbackReason.RATE_LIMITED, at.plusSeconds(60))
        assertEquals(
            LastCloudError(TranscriptionFallbackReason.RATE_LIMITED, at.plusSeconds(60)),
            SomaPrefs.lastCloudError(context),
        )

        SomaPrefs.clearLastCloudError(context)
        assertNull(SomaPrefs.lastCloudError(context))
    }

    @Test
    fun `an unknown stored reason reads as no error`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("soma_preferences", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("last_cloud_error_reason", "NOT_A_REAL_REASON")
            .putLong("last_cloud_error_at", 1_000L)
            .apply()
        assertNull(SomaPrefs.lastCloudError(context))
    }
}
