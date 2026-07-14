package com.soma.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Release gate: every translatable string ships in all eight languages.
 *
 * The 1.0 contract requires each localization to cover every user-facing
 * production flow, and `MissingTranslation` lint is disabled, so this test is
 * the enforcement point. It also rejects stale locale keys and placeholder
 * drift, which lint would not catch once a key is renamed in the default file.
 */
class LocalizationCompletenessTest {
    private val locales = listOf("de", "et", "fi", "lt", "lv", "sk", "sv")

    private val resDir: File = sequenceOf("src/main/res", "app/src/main/res")
        .map { File(System.getProperty("user.dir"), it) }
        .firstOrNull(File::isDirectory)
        ?: error("Could not locate app/src/main/res from ${System.getProperty("user.dir")}")

    @Test
    fun `every translatable string exists in all eight languages`() {
        val required = stringValues(File(resDir, "values"), translatableOnly = true).keys
        val problems = buildList {
            locales.forEach { locale ->
                val missing = required - stringValues(File(resDir, "values-$locale")).keys
                if (missing.isNotEmpty()) add("values-$locale is missing: ${missing.sorted().joinToString()}")
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `no locale carries keys the default language no longer defines`() {
        val known = stringValues(File(resDir, "values")).keys
        val problems = buildList {
            locales.forEach { locale ->
                val stale = stringValues(File(resDir, "values-$locale")).keys - known
                if (stale.isNotEmpty()) add("values-$locale has stale keys: ${stale.sorted().joinToString()}")
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `translations keep the default language's format placeholders`() {
        val defaults = stringValues(File(resDir, "values"))
        val problems = buildList {
            locales.forEach { locale ->
                stringValues(File(resDir, "values-$locale")).forEach { (key, value) ->
                    val expected = placeholders(defaults[key] ?: return@forEach)
                    val actual = placeholders(value)
                    if (expected != actual) {
                        add("values-$locale/$key has placeholders $actual, default has $expected")
                    }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Positional placeholders such as %1$s or %2$d, order-independent. */
    private fun placeholders(value: String): Map<String, Int> =
        PLACEHOLDER.findAll(value).map(MatchResult::value).groupingBy { it }.eachCount()

    private fun stringValues(valuesDir: File, translatableOnly: Boolean = false): Map<String, String> {
        val files = valuesDir.listFiles { file -> file.extension == "xml" }.orEmpty()
        check(files.isNotEmpty()) { "No resource files under $valuesDir" }
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return buildMap {
            files.forEach { file ->
                val strings = builder.parse(file).documentElement.getElementsByTagName("string")
                for (index in 0 until strings.length) {
                    val element = strings.item(index) as Element
                    if (translatableOnly && element.getAttribute("translatable") == "false") continue
                    put(element.getAttribute("name"), element.textContent)
                }
            }
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("%[0-9]+\\$[sd]")
    }
}
