package com.soma.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.soma.core.model.SupportedLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SomaSpeechLanguagePreferenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("soma_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("soma_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `all supported languages are enabled until the user narrows the set`() {
        assertEquals(SupportedLanguage.entries.toSet(), SomaPrefs.speechLanguages(context))

        val selected = setOf(SupportedLanguage.LATVIAN, SupportedLanguage.GERMAN)
        SomaPrefs.setSpeechLanguages(context, selected)

        assertEquals(selected, SomaPrefs.speechLanguages(context))
    }

    @Test
    fun `ElevenLabs is the initial cloud speech provider but remains selectable`() {
        assertEquals(CloudSpeechProvider.ELEVENLABS, SomaPrefs.cloudSpeechProvider(context))

        SomaPrefs.setCloudSpeechProvider(context, CloudSpeechProvider.GROQ)

        assertEquals(CloudSpeechProvider.GROQ, SomaPrefs.cloudSpeechProvider(context))
    }

    @Test
    fun `an empty selection safely restores every supported language`() {
        SomaPrefs.setSpeechLanguages(context, emptySet())

        assertEquals(SupportedLanguage.entries.toSet(), SomaPrefs.speechLanguages(context))
    }
}
