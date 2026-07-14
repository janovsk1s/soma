package com.soma.app

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LatvianResourcesTest {
    @Test
    fun `meal editor and vocabulary remain Latvian`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val configuration = Configuration(application.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("lv"))
        }
        val context = application.createConfigurationContext(configuration)

        assertEquals("pievienot maltīti…", context.getString(R.string.add_meal))
        assertEquals("transkripcijas vārdnīca", context.getString(R.string.settings_transcription_vocabulary))
        assertEquals("pievienot vārdu vai frāzi…", context.getString(R.string.transcription_vocabulary_add))
        assertFalse(context.getString(R.string.log_meal_help).startsWith("One food"))
    }
}
