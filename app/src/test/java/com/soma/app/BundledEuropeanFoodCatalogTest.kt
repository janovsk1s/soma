package com.soma.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.soma.core.model.NutritionSource
import com.soma.core.model.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BundledEuropeanFoodCatalogTest {
    @Test
    fun `bundled Fineli data keeps stable localized names and nutrition`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val bundled = BundledEuropeanFoodCatalog(context)

        val english = bundled.catalog(SupportedLanguage.ENGLISH).search("sugar").first()
        val swedish = bundled.catalog(SupportedLanguage.SWEDISH).search("socker").first()

        assertEquals(NutritionSource.FINELI, english.source)
        assertEquals("SUGAR", english.displayName)
        assertEquals("SOCKER", swedish.displayName)
        assertTrue(checkNotNull(english.energyKcalPer100Grams) > 400.0)
    }
}
