package com.soma.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.soma.core.model.SupportedLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `Groq Turbo is the initial cloud speech choice but providers and accuracy remain selectable`() {
        assertEquals(CloudSpeechProvider.GROQ, SomaPrefs.cloudSpeechProvider(context))
        assertEquals(GroqSpeechModel.TURBO, SomaPrefs.groqSpeechModel(context))

        SomaPrefs.setCloudSpeechProvider(context, CloudSpeechProvider.ELEVENLABS)
        SomaPrefs.setGroqSpeechModel(context, GroqSpeechModel.LARGE_V3)

        assertEquals(CloudSpeechProvider.ELEVENLABS, SomaPrefs.cloudSpeechProvider(context))
        assertEquals(GroqSpeechModel.LARGE_V3, SomaPrefs.groqSpeechModel(context))
    }

    @Test
    fun `cloud requests allow cellular by default and wifi only remains optional`() {
        assertFalse(SomaPrefs.cloudWifiOnly(context))

        SomaPrefs.setCloudWifiOnly(context, true)
        assertTrue(SomaPrefs.cloudWifiOnly(context))

        SomaPrefs.setCloudWifiOnly(context, false)
        assertFalse(SomaPrefs.cloudWifiOnly(context))
    }

    @Test
    fun `automatic cloud analysis is separately opt in`() {
        assertFalse(SomaPrefs.aiTodoSuggestions(context))
        assertFalse(SomaPrefs.aiAutoMetadata(context))
        assertFalse(SomaPrefs.aiTrackingSuggestions(context))

        SomaPrefs.setAiAutoMetadata(context, true)
        SomaPrefs.setAiTrackingSuggestions(context, true)

        assertTrue(SomaPrefs.aiAutoMetadata(context))
        assertTrue(SomaPrefs.aiTrackingSuggestions(context))
        assertFalse(SomaPrefs.aiTodoSuggestions(context))
    }

    @Test
    fun `an empty selection safely restores every supported language`() {
        SomaPrefs.setSpeechLanguages(context, emptySet())

        assertEquals(SupportedLanguage.entries.toSet(), SomaPrefs.speechLanguages(context))
    }
}
