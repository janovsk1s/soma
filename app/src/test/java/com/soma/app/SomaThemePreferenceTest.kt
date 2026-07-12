package com.soma.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
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
class SomaThemePreferenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("soma_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        SomaPalette.lightMode = false
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("soma_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        SomaPalette.lightMode = false
    }

    @Test
    fun `dark mode is the default and light mode persists only when enabled`() {
        assertFalse(SomaPrefs.lightMode(context))

        SomaPrefs.setLightMode(context, true)
        assertTrue(SomaPrefs.lightMode(context))

        SomaPrefs.setLightMode(context, false)
        assertFalse(SomaPrefs.lightMode(context))
    }

    @Test
    fun `palette exactly matches Paka in both modes`() {
        SomaPalette.lightMode = false
        assertEquals(Color(0xFF000000), Paper)
        assertEquals(Color(0xFFFFFFFF), Ink)
        assertEquals(Color(0xFF888888), DimInk)

        SomaPalette.lightMode = true
        assertEquals(Color(0xFFFFFFFF), Paper)
        assertEquals(Color(0xFF000000), Ink)
        assertEquals(Color(0xFF555555), DimInk)
    }
}
