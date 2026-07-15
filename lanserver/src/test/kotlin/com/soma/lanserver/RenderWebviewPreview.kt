package com.soma.lanserver

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import org.junit.Test

/**
 * Not a behavioural test: renders every Browser-view page to the build/webview
 * directory with representative data so the HTML/CSS can be previewed and iterated on
 * without the app or a device. The forest background and audio/image endpoints
 * are rewritten to inline data URIs / placeholders so the files open standalone.
 */
class RenderWebviewPreview {
    private val today = LocalDate.parse("2026-07-15")

    private fun standalone(html: String): String {
        val forest = RenderWebviewPreview::class.java
            .getResourceAsStream("/com/soma/lanserver/forest/lv.webp")!!
            .use { it.readBytes() }
        val dataUri = "data:image/webp;base64," + Base64.getEncoder().encodeToString(forest)
        return html
            .replace("url('/assets/forest.webp')", "url('$dataUri')")
            .replace(Regex("<audio[^>]*>.*?</audio>", RegexOption.DOT_MATCHES_ALL), "<audio controls></audio>")
            .replace(Regex("src=\"/image/[^\"]*\""), "src=''")
    }

    private fun write(name: String, html: String) {
        val dir = File("build/webview").apply { mkdirs() }
        File(dir, "$name.html").writeText(standalone(html))
    }

    @Test
    fun render() {
        val lang = "lv"

        write("01-login", HtmlRenderer.login(lightMode = false, languageTag = lang))

        val days = PagedResult(
            items = listOf(
                BrowserDay(today, 4, "Wakey wakey! Šodien iešu peldēt. Tomas arī nāks ciemos."),
                BrowserDay(today.minusDays(1), 2, "Pieraksts pie zobārsta. Jānopērk olas, piens, zeķes."),
                BrowserDay(today.minusDays(3), 1, "Doma par māsas dārzu un to, kā gaisma krita pār galdu."),
                BrowserDay(today.minusDays(6), 7, "Gara diena. Daudz domu, maz miega."),
            ),
            totalCount = 18,
        )
        write("02-days", HtmlRenderer.days(1, days, languageTag = lang))
        write("02-days-editing", HtmlRenderer.days(1, days, languageTag = lang, edit = EditContext("preview-csrf"), today = today))

        val entries = PagedResult(
            items = listOf(
                BrowserEntry(
                    id = "e1",
                    text = "Wakey wakey! Mm, jā, man liekas, ir laiks celties. Šodien iešu peldēt. Droši vien. Tomas arī nāks ciemos.",
                    kind = BrowserEntryKind.VOICE,
                    audioId = "a1",
                    history = listOf(
                        BrowserEntryVersion(1, "Wakey wakey! Šodien iešu peldēt.", Instant.parse("2026-07-15T06:04:00Z"), false),
                        BrowserEntryVersion(2, "Wakey wakey! Mm, jā, man liekas, ir laiks celties. Šodien iešu peldēt. Droši vien. Tomas arī nāks ciemos.", Instant.parse("2026-07-15T06:12:00Z"), true),
                    ),
                ),
                BrowserEntry("e2", "Jānopērk olas, piens, zeķes.", BrowserEntryKind.TEXT, markedForReturn = true),
                BrowserEntry("e3", "Bilde no rīta pastaigas.", BrowserEntryKind.IMAGE, imageId = "img1"),
                BrowserEntry("e4", "Transkripcija vēl top…", BrowserEntryKind.VOICE, audioId = "a2", transcriptionPending = true),
            ),
            totalCount = 4,
        )
        write("03-day", HtmlRenderer.day(today, 1, entries, languageTag = lang))
        write("03-day-editing", HtmlRenderer.day(today, 1, entries, languageTag = lang, edit = EditContext("preview-csrf-token")))

        val todos = PagedResult(
            items = listOf(
                BrowserTodo("t1", "Būs jāsakrāmē istaba šodien", today, BrowserTodoState.OPEN),
                BrowserTodo("t2", "Pierakstīties pie zobārsta", today.minusDays(1), BrowserTodoState.OPEN, sourceDate = today.minusDays(1)),
                BrowserTodo("t3", "Mēģināt iterēt uz Soma programmas", today.minusDays(2), BrowserTodoState.OPEN, sourceDate = today.minusDays(2)),
                BrowserTodo("t4", "Jānopērk olas, piens, zeķes", today, BrowserTodoState.OPEN),
            ),
            totalCount = 6,
        )
        write("04-todos", HtmlRenderer.todos(BrowserTodoFilter.OPEN, 1, todos, languageTag = lang))

        val logs = PagedResult(
            items = listOf(
                BrowserLog(
                    id = "l1", kind = BrowserLogKind.RECEIPT,
                    title = "Psychotherapeutische Ambulanz Wien", note = "",
                    occurredAt = Instant.parse("2026-07-14T16:51:00Z"), occurredLabel = "14.07.26 18:51",
                    receipt = BrowserReceipt(
                        merchant = "Psychotherapeutische Ambulanz Wien", total = "EUR 59.00",
                        items = listOf(BrowserReceiptItem("Psychotherapie 45min", "1", "EUR 59.00", "veselība")),
                    ),
                    revisionCount = 1,
                ),
                BrowserLog(
                    id = "l2", kind = BrowserLogKind.MEAL, title = "NÖM Kefir Heidelbeere",
                    note = "", occurredAt = Instant.parse("2026-07-14T15:22:00Z"), occurredLabel = "14.07.26 17:22",
                    foods = listOf(BrowserFoodItem("NÖM Kefir Heidelbeere", "1 pudele", "~120 kcal", "package label")),
                ),
                BrowserLog(
                    id = "l3", kind = BrowserLogKind.WORKOUT, title = "Kāju prese",
                    note = "", occurredAt = Instant.parse("2026-07-13T17:44:00Z"), occurredLabel = "13.07.26 17:44",
                    exercises = listOf(BrowserWorkoutExercise("Kāju prese", "Prese", listOf("3×10 80 kg"))),
                ),
            ),
            totalCount = 8,
        )
        write("05-logs", HtmlRenderer.logs(BrowserLogFilter.MEALS, 1, logs, languageTag = lang))

        val insights = BrowserInsights(
            annotatedEntryCount = 42, manualLayerCount = 8, aiLayerCount = 30, localLayerCount = 14,
            tagOccurrenceCount = 61, linkCount = 12,
            connections = PagedResult(
                items = listOf(
                    BrowserInsight(BrowserInsightKind.TAG, "peldēšana", 9),
                    BrowserInsight(BrowserInsightKind.TAG, "zobārsts", 3),
                    BrowserInsight(BrowserInsightKind.DATE, "2026-07-20", 4),
                    BrowserInsight(BrowserInsightKind.TAG, "dārzs", 6),
                    BrowserInsight(BrowserInsightKind.ENTRY, "māsas dārzs", 2),
                ),
                totalCount = 24,
            ),
        )
        write("06-insights", HtmlRenderer.insights(1, insights, exportEnabled = true, languageTag = lang))

        val graph = PagedResult(
            items = listOf(
                BrowserGraphEdge("Rīta pieraksts", today, "peldēšana", BrowserGraphNodeKind.TAG, metadataSource = BrowserMetadataSource.AI),
                BrowserGraphEdge("Zobārsts", today.minusDays(1), "2026-07-20", BrowserGraphNodeKind.DATE, targetDate = LocalDate.parse("2026-07-20"), relation = "appointment", metadataSource = BrowserMetadataSource.LOCAL),
                BrowserGraphEdge("Māsas dārzs", today.minusDays(3), "dārzs", BrowserGraphNodeKind.TAG, metadataSource = BrowserMetadataSource.MANUAL),
            ),
            totalCount = 3,
        )
        write("07-graph", HtmlRenderer.graph(1, graph, languageTag = lang))

        write("08-export", HtmlRenderer.export(languageTag = lang))
        write("09-error", HtmlRenderer.error(404, lightMode = false, languageTag = lang))

        // Light-mode variants of the two busiest pages.
        write("10-day-light", HtmlRenderer.day(today, 1, entries, lightMode = true, languageTag = lang))
        write("11-logs-light", HtmlRenderer.logs(BrowserLogFilter.MEALS, 1, logs, lightMode = true, languageTag = lang))

        println("WEBVIEW_HTML_DIR=" + File("build/webview").absolutePath)
    }
}
