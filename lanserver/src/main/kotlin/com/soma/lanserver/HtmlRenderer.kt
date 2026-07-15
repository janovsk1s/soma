package com.soma.lanserver

import java.time.LocalDate
import kotlin.math.max

internal object HtmlRenderer {
    fun login(
        error: String? = null,
        lightMode: Boolean = false,
        languageTag: String = "en",
    ): String = page(
        title = "Browser view",
        navigation = false,
        lightMode = lightMode,
        languageTag = languageTag,
        body = buildString {
            val copy = BrowserWebCopy.forLanguage(languageTag)
            append("<main><header><h1>Soma</h1><p>")
            append(Html.escape(copy.browserView))
            append("</p></header>")
            if (error != null) {
                append("<p class=\"message\" role=\"alert\">")
                append(Html.escape(error))
                append("</p>")
            }
            append(
                """
                <form method="post" action="/auth">
                  <label for="code">${Html.escape(copy.accessCode)}</label>
                  <input id="code" name="code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required autofocus>
                  <button type="submit">${Html.escape(copy.continueLabel)}</button>
                </form>
                <p class="quiet">${Html.escape(copy.useCode)}</p></main>
                """.trimIndent(),
            )
        },
    )

    fun days(
        pageNumber: Int,
        result: PagedResult<BrowserDay>,
        lightMode: Boolean = false,
        languageTag: String = "en",
    ): String = page(
        title = "Days",
        lightMode = lightMode,
        languageTag = languageTag,
        active = "days",
        body = buildString {
            val copy = BrowserWebCopy.forLanguage(languageTag)
            append("<main><header><h1>").append(Html.escape(copy.days)).append("</h1><p>")
                .append(Html.escape(copy.dailyNotes)).append("</p></header>")
            val days = result.items.take(PAGE_SIZE)
            if (days.isEmpty()) {
                append("<p class=\"empty\">").append(Html.escape(copy.noNotes)).append("</p>")
            } else {
                append("<ol class=\"list\">")
                days.forEach { day ->
                    val dayPath = "/day/${Html.pathSegment(day.date.toString())}"
                    append("<li><a class=\"row\" href=\"")
                    append(dayPath)
                    append("\"><span><strong>")
                    append(Html.escape(day.date.toString()))
                    append("</strong>")
                    if (day.preview.isNotBlank()) {
                        append("<small>")
                        append(Html.escape(day.preview))
                        append("</small>")
                    }
                    append("</span><span class=\"count\">")
                    append(day.entryCount.coerceAtLeast(0))
                    append("</span></a></li>")
                }
                append("</ol>")
            }
            append(pager("/days", pageNumber, result.totalCount, languageTag = languageTag))
            append("</main>")
        },
    )

    fun day(
        date: LocalDate,
        pageNumber: Int,
        result: PagedResult<BrowserEntry>,
        lightMode: Boolean = false,
        languageTag: String = "en",
        edit: EditContext? = null,
    ): String = page(
        title = date.toString(),
        lightMode = lightMode,
        active = "days",
        languageTag = languageTag,
        body = buildString {
            val copy = BrowserWebCopy.forLanguage(languageTag)
            val dayPath = "/day/${Html.pathSegment(date.toString())}"
            append("<main><header><a class=\"back\" href=\"/days\">← ").append(Html.escape(copy.days)).append("</a><h1>")
            append(Html.escape(date.toString()))
            append("</h1><p>").append(Html.escape(copy.dailyNote)).append("</p></header>")
            val entries = result.items.take(PAGE_SIZE)
            if (entries.isEmpty()) {
                append("<p class=\"empty\">").append(Html.escape(copy.nothingCaptured)).append("</p>")
            } else {
                append("<ol class=\"list entries\">")
                entries.forEach { entry ->
                    append("<li><article class=\"entry\"><p>")
                    if (entry.transcriptionPending && entry.text.isBlank()) {
                        append("<span class=\"quiet\">").append(Html.escape(copy.transcribing)).append("</span>")
                    } else {
                        append(Html.escape(entry.text))
                    }
                    append("</p>")
                    if (entry.audioId != null) {
                        append("<audio controls preload=\"none\" src=\"/audio/")
                        append(Html.pathSegment(entry.audioId))
                        append("\">Audio playback is not supported by this browser.</audio>")
                    }
                    if (entry.kind == BrowserEntryKind.IMAGE && entry.imageId != null) {
                        append("<img loading=\"lazy\" src=\"/image/")
                        append(Html.pathSegment(entry.imageId))
                        append("\" alt=\"Captured photo\">")
                    }
                    if (entry.markedForReturn) {
                        append("<small class=\"quiet\">").append(Html.escape(copy.returnToThis)).append("</small>")
                    }
                    val previousVersions = entry.history.filterNot(BrowserEntryVersion::isCurrent)
                    if (previousVersions.isNotEmpty()) {
                        append("<details class=\"history\"><summary>")
                        append(Html.escape(copy.editHistory)).append(" · ")
                        append(entry.history.size)
                        append(' ').append(Html.escape(copy.versions)).append("</summary><ol>")
                        previousVersions.forEach { version ->
                            append("<li><small>")
                            append(Html.escape(if (version.number == 1) copy.original else "${copy.versionLabel} ${version.number}"))
                            append(" · <time datetime=\"")
                            append(Html.escape(version.becameCurrentAt.toString()))
                            append("\">")
                            append(Html.escape(version.becameCurrentAt.toString()))
                            append("</time></small><p>")
                            append(Html.escape(version.text))
                            append("</p></li>")
                        }
                        append("</ol></details>")
                    }
                    if (edit != null && !(entry.transcriptionPending && entry.text.isBlank())) {
                        append("<details class=\"editor\"><summary>").append(Html.escape(copy.editAction))
                        append("</summary><form method=\"post\" action=\"/entry/edit\">")
                        writeFields(edit.csrfToken, dayPath)
                        append("<input type=\"hidden\" name=\"id\" value=\"").append(Html.escape(entry.id)).append("\">")
                        append("<textarea name=\"text\" rows=\"4\" required>").append(Html.escape(entry.text))
                        append("</textarea><button type=\"submit\">").append(Html.escape(copy.saveAction))
                        append("</button></form></details>")
                    }
                    append("</article></li>")
                }
                append("</ol>")
            }
            append(pager(dayPath, pageNumber, result.totalCount, languageTag = languageTag))
            if (edit != null) {
                append("<form class=\"composer\" method=\"post\" action=\"/entry/new\">")
                writeFields(edit.csrfToken, dayPath)
                append("<input type=\"hidden\" name=\"date\" value=\"").append(Html.escape(date.toString())).append("\">")
                append("<textarea name=\"text\" rows=\"3\" required placeholder=\"").append(Html.escape(copy.addToDay))
                append("\"></textarea><button type=\"submit\">").append(Html.escape(copy.addAction)).append("</button></form>")
            }
            append("</main>")
        },
    )

    fun todos(
        filter: BrowserTodoFilter,
        pageNumber: Int,
        result: PagedResult<BrowserTodo>,
        lightMode: Boolean = false,
        languageTag: String = "en",
    ): String = page(
        title = "Todos",
        active = "todos",
        lightMode = lightMode,
        languageTag = languageTag,
        body = buildString {
            val copy = BrowserWebCopy.forLanguage(languageTag)
            append("<main><header><h1>").append(Html.escape(copy.important)).append("</h1><p>")
            append(Html.escape(if (filter == BrowserTodoFilter.OPEN) copy.stillOpen else copy.doneArchived))
            append("</p></header><nav class=\"tabs\" aria-label=\"").append(Html.escape(copy.important)).append("\">")
            tab(copy.openTab, "/todos?state=open", filter == BrowserTodoFilter.OPEN)
            tab(copy.doneArchived, "/todos?state=completed", filter == BrowserTodoFilter.COMPLETED)
            append("</nav>")
            val todos = result.items.take(PAGE_SIZE)
            if (todos.isEmpty()) {
                append("<p class=\"empty\">")
                append(Html.escape(if (filter == BrowserTodoFilter.OPEN) copy.nothingOpen else copy.nothingHere))
                append("</p>")
            } else {
                append("<ol class=\"list todos\">")
                todos.forEach { todo ->
                    append("<li><article class=\"entry\"><p>")
                    append(Html.escape(todo.text))
                    append("</p><small>")
                    append(Html.escape(todo.createdOn.toString()))
                    todo.sourceDate?.let { sourceDate ->
                        append(" · <a href=\"/day/")
                        append(Html.pathSegment(sourceDate.toString()))
                        append("\">").append(Html.escape(copy.sourceNote)).append("</a>")
                    }
                    append("</small></article></li>")
                }
                append("</ol>")
            }
            val base = if (filter == BrowserTodoFilter.OPEN) {
                "/todos?state=open"
            } else {
                "/todos?state=completed"
            }
            append(pager(base, pageNumber, result.totalCount, hasQuery = true, languageTag = languageTag))
            append("</main>")
        },
    )

    fun logs(
        filter: BrowserLogFilter,
        pageNumber: Int,
        result: PagedResult<BrowserLog>,
        lightMode: Boolean = false,
        languageTag: String = "en",
    ): String {
        val copy = BrowserWebCopy.forLanguage(languageTag)
        val selectedLabel = when (filter) {
            BrowserLogFilter.MEALS -> copy.meals
            BrowserLogFilter.RECIPES -> copy.recipes
            BrowserLogFilter.WORKOUTS -> copy.workouts
            BrowserLogFilter.RECEIPTS -> copy.receipts
            BrowserLogFilter.ARCHIVED -> copy.archived
        }
        return page(
            title = copy.logs,
            lightMode = lightMode,
            languageTag = languageTag,
            active = "logs",
            body = buildString {
                append("<main><header><h1>").append(Html.escape(copy.logs)).append("</h1><p>")
                    .append(Html.escape(copy.confirmedRecords)).append(" · ")
                    .append(Html.escape(selectedLabel)).append("</p></header>")
                append("<nav class=\"tabs\" aria-label=\"").append(Html.escape(copy.logs)).append("\">")
                tab(copy.meals, "/logs?kind=meal", filter == BrowserLogFilter.MEALS)
                tab(copy.recipes, "/logs?kind=recipe", filter == BrowserLogFilter.RECIPES)
                tab(copy.workouts, "/logs?kind=workout", filter == BrowserLogFilter.WORKOUTS)
                tab(copy.receipts, "/logs?kind=receipt", filter == BrowserLogFilter.RECEIPTS)
                tab(copy.archived, "/logs?kind=archived", filter == BrowserLogFilter.ARCHIVED)
                append("</nav>")
                val logs = result.items.take(PAGE_SIZE)
                if (logs.isEmpty()) {
                    append("<p class=\"empty\">").append(Html.escape(copy.noLogs)).append("</p>")
                } else {
                    append("<ol class=\"list log-list\">")
                    logs.forEach { log ->
                        append("<li><article class=\"entry log-entry\"><header class=\"log-header\"><h2>")
                            .append(Html.escape(log.title)).append("</h2><time datetime=\"")
                            .append(Html.escape(log.occurredAt.toString())).append("\">")
                            .append(Html.escape(log.occurredLabel)).append("</time></header>")
                        // Receipt notes use Soma's stable editable interchange syntax. The
                        // structured fields below are the calm human view; do not duplicate the
                        // internal merchant/item/total lines as a paragraph.
                        if (log.kind != BrowserLogKind.RECEIPT && log.note.isNotBlank()) {
                            append("<p class=\"log-note\">").append(Html.escape(log.note)).append("</p>")
                        }
                        if (log.foods.isNotEmpty()) {
                            append("<ul class=\"log-parts\">")
                            log.foods.forEach { food ->
                                append("<li><span><strong>").append(Html.escape(food.name)).append("</strong>")
                                food.quantity?.let { append(" <span class=\"quiet\">").append(Html.escape(it)).append("</span>") }
                                append("</span>")
                                if (food.nutrition != null || food.provenance != null) {
                                    append("<small>")
                                    listOfNotNull(food.nutrition, food.provenance).forEachIndexed { index, value ->
                                        if (index > 0) append(" · ")
                                        append(Html.escape(value))
                                    }
                                    append("</small>")
                                }
                                append("</li>")
                            }
                            append("</ul>")
                        }
                        if (log.exercises.isNotEmpty()) {
                            append("<ul class=\"log-parts\">")
                            log.exercises.forEach { exercise ->
                                append("<li><strong>").append(Html.escape(exercise.name)).append("</strong>")
                                exercise.machine?.let { machine ->
                                    append(" <span class=\"quiet\">· ").append(Html.escape(machine)).append("</span>")
                                }
                                exercise.sets.forEachIndexed { index, set ->
                                    append("<small>").append(index + 1).append(" · ").append(Html.escape(set)).append("</small>")
                                }
                                append("</li>")
                            }
                            append("</ul>")
                        }
                        log.receipt?.let { receipt ->
                            if (!receipt.merchant.isNullOrBlank() && receipt.merchant != log.title) {
                                append("<p class=\"quiet\">").append(Html.escape(receipt.merchant)).append("</p>")
                            }
                            if (receipt.items.isNotEmpty()) {
                                append("<ul class=\"log-parts\">")
                                receipt.items.forEach { item ->
                                    append("<li><span><strong>").append(Html.escape(item.name)).append("</strong>")
                                    item.quantity?.let { append(" <span class=\"quiet\">× ").append(Html.escape(it)).append("</span>") }
                                    append("</span>")
                                    listOfNotNull(item.total, item.category).takeIf(List<String>::isNotEmpty)?.let { details ->
                                        append("<small>").append(Html.escape(details.joinToString(" · "))).append("</small>")
                                    }
                                    append("</li>")
                                }
                                append("</ul>")
                            }
                            append("<dl class=\"receipt-totals\">")
                            receipt.subtotal?.let { append("<dt>").append(Html.escape(copy.receiptSubtotal)).append("</dt><dd>").append(Html.escape(it)).append("</dd>") }
                            receipt.tax?.let { append("<dt>").append(Html.escape(copy.receiptTax)).append("</dt><dd>").append(Html.escape(it)).append("</dd>") }
                            receipt.total?.let { append("<dt>").append(Html.escape(copy.receiptTotal)).append("</dt><dd><strong>").append(Html.escape(it)).append("</strong></dd>") }
                            append("</dl>")
                        }
                        append("<footer class=\"log-footer\">")
                        log.sourceDate?.let { sourceDate ->
                            append("<a href=\"/day/").append(Html.pathSegment(sourceDate.toString())).append("\">")
                                .append(Html.escape(copy.sourceNote)).append("</a>")
                        }
                        if (log.revisionCount > 1) {
                            append("<span>").append(log.revisionCount).append(' ')
                                .append(Html.escape(copy.versions)).append("</span>")
                        }
                        append("</footer></article></li>")
                    }
                    append("</ol>")
                }
                val base = when (filter) {
                    BrowserLogFilter.MEALS -> "/logs?kind=meal"
                    BrowserLogFilter.RECIPES -> "/logs?kind=recipe"
                    BrowserLogFilter.WORKOUTS -> "/logs?kind=workout"
                    BrowserLogFilter.RECEIPTS -> "/logs?kind=receipt"
                    BrowserLogFilter.ARCHIVED -> "/logs?kind=archived"
                }
                append(pager(base, pageNumber, result.totalCount, hasQuery = true, languageTag = languageTag))
                append("</main>")
            },
        )
    }

    fun insights(
        pageNumber: Int,
        insights: BrowserInsights,
        lightMode: Boolean = false,
        exportEnabled: Boolean = false,
        languageTag: String = "en",
    ): String = page(
        title = "Insights",
        lightMode = lightMode,
        languageTag = languageTag,
        active = "insights",
        body = buildString {
            val copy = BrowserWebCopy.forLanguage(languageTag)
            append("<main><header><h1>").append(Html.escape(copy.insights)).append("</h1><p>")
                .append(Html.escape(copy.localMetadataOnly)).append("</p></header>")
            if (exportEnabled) {
                append("<p><a href=\"/export\">")
                append(Html.escape(ExportCopy.forLanguage(languageTag).link))
                append("</a></p>")
            }
            append("<dl class=\"summary\">")
            metric(copy.statEntries, insights.annotatedEntryCount)
            metric(copy.statManual, insights.manualLayerCount)
            metric(copy.statAi, insights.aiLayerCount)
            metric(copy.statLocal, insights.localLayerCount)
            metric(copy.statTags, insights.tagOccurrenceCount)
            metric(copy.statLinks, insights.linkCount)
            append("</dl><h2>").append(Html.escape(copy.connections)).append("</h2>")
            val connections = insights.connections.items.take(PAGE_SIZE)
            if (connections.isEmpty()) {
                append("<p class=\"empty\">").append(Html.escape(copy.noMetadata)).append("</p>")
            } else {
                append("<ol class=\"list\">")
                connections.forEach { item ->
                    append("<li><div class=\"row\"><span><strong>")
                    append(Html.escape(item.label))
                    append("</strong><small>")
                    append(
                        Html.escape(
                            when (item.kind) {
                                BrowserInsightKind.TAG -> copy.kindTag
                                BrowserInsightKind.DATE -> copy.kindDateLink
                                BrowserInsightKind.ENTRY -> copy.kindEntryLink
                            },
                        ),
                    )
                    append("</small></span><span class=\"count\">")
                    append(item.occurrenceCount)
                    append("</span></div></li>")
                }
                append("</ol>")
            }
            append(pager("/insights", pageNumber, insights.connections.totalCount, languageTag = languageTag))
            append("</main>")
        },
    )

    fun export(lightMode: Boolean = false, languageTag: String = "en"): String {
        val copy = ExportCopy.forLanguage(languageTag)
        return page(
            title = copy.title,
            lightMode = lightMode,
            languageTag = languageTag,
            body = buildString {
                append("<main><header><h1>").append(Html.escape(copy.title)).append("</h1><p>")
                    .append(Html.escape(copy.subtitle)).append("</p></header><p>")
                    .append(Html.escape(copy.contents)).append("</p><p><strong>")
                    .append(Html.escape(copy.warningTitle)).append("</strong> ")
                    .append(Html.escape(copy.warningBody)).append("</p>")
                append("<p><a class=\"download\" href=\"/export/vault.zip\" download>")
                    .append(Html.escape(copy.download)).append("</a></p>")
                append("<p><a class=\"back\" href=\"/insights\">")
                    .append(Html.escape(copy.back)).append("</a></p>")
                append("</main>")
            },
        )
    }

    fun graph(
        pageNumber: Int,
        result: PagedResult<BrowserGraphEdge>,
        lightMode: Boolean = false,
        languageTag: String = "en",
    ): String = page(
        title = "Graph",
        lightMode = lightMode,
        languageTag = languageTag,
        active = "graph",
        body = buildString {
            val copy = BrowserWebCopy.forLanguage(languageTag)
            append("<main><header><h1>").append(Html.escape(copy.graph)).append("</h1><p>")
                .append(Html.escape(copy.graphSubtitle)).append("</p></header>")
            val edges = result.items.take(PAGE_SIZE)
            if (edges.isEmpty()) {
                append("<p class=\"empty\">").append(Html.escape(copy.noConnections)).append("</p>")
            } else {
                val height = 36 + edges.size * GRAPH_EDGE_HEIGHT
                append("<svg class=\"connection-graph\" viewBox=\"0 0 900 ")
                append(height)
                append("\" role=\"img\" aria-labelledby=\"graph-title graph-description\">")
                append("<title id=\"graph-title\">Entry connection graph</title>")
                append("<desc id=\"graph-description\">Five or fewer local metadata connections.</desc>")
                edges.forEachIndexed { index, edge ->
                    val centerY = 38 + index * GRAPH_EDGE_HEIGHT
                    append("<g class=\"graph-edge\">")
                    append("<line class=\"edge-line\" x1=\"286\" y1=\"")
                    append(centerY)
                    append("\" x2=\"594\" y2=\"")
                    append(centerY)
                    append("\"/><circle cx=\"286\" cy=\"")
                    append(centerY)
                    append("\" r=\"6\"/><circle cx=\"594\" cy=\"")
                    append(centerY)
                    append("\" r=\"6\"/>")
                    svgDayLink(edge.sourceDate, edge.sourceLabel, 0, centerY - 7)
                    append("<text class=\"node-detail\" x=\"0\" y=\"")
                    append(centerY + 17)
                    append("\">")
                    append(Html.escape(edge.sourceDate.toString()))
                    append("</text>")
                    append("<text class=\"edge-label\" x=\"440\" y=\"")
                    append(centerY - 10)
                    append("\" text-anchor=\"middle\">")
                    append(Html.escape(edge.relation ?: edge.targetKind.defaultRelation()))
                    append("</text><text class=\"edge-source\" x=\"440\" y=\"")
                    append(centerY + 17)
                    append("\" text-anchor=\"middle\">")
                    append(
                        when (edge.metadataSource) {
                            BrowserMetadataSource.MANUAL -> "manual"
                            BrowserMetadataSource.AI -> "AI"
                            BrowserMetadataSource.LOCAL -> "local"
                        },
                    )
                    append("</text>")
                    if (edge.targetKind == BrowserGraphNodeKind.ENTRY && edge.targetDate != null) {
                        svgDayLink(edge.targetDate, edge.targetLabel, 620, centerY - 7)
                    } else {
                        append("<text class=\"node-label\" x=\"620\" y=\"")
                        append(centerY - 7)
                        append("\">")
                        append(Html.escape(edge.targetLabel))
                        append("</text>")
                    }
                    append("<text class=\"node-detail\" x=\"620\" y=\"")
                    append(centerY + 17)
                    append("\">")
                    append(edge.targetKind.displayName())
                    append("</text></g>")
                }
                append("</svg><p class=\"quiet graph-note\">Lines are derived metadata. Entry wording stays unchanged.</p>")
            }
            append(pager("/graph", pageNumber, result.totalCount, languageTag = languageTag))
            append("</main>")
        },
    )

    fun error(status: Int, title: String, message: String, lightMode: Boolean = false): String = page(
        title = title,
        lightMode = lightMode,
        body = "<main><header><h1>${Html.escape(status.toString())}</h1>" +
            "<p>${Html.escape(title)}</p></header><p>${Html.escape(message)}</p></main>",
    )

    private fun StringBuilder.tab(label: String, href: String, selected: Boolean) {
        append("<a href=\"")
        append(href)
        append("\"")
        if (selected) append(" aria-current=\"page\"")
        append(">")
        append(Html.escape(label))
        append("</a>")
    }

    private fun StringBuilder.metric(label: String, value: Int) {
        append("<div><dt>")
        append(Html.escape(label))
        append("</dt><dd>")
        append(value.coerceAtLeast(0))
        append("</dd></div>")
    }

    private fun StringBuilder.svgDayLink(date: LocalDate, label: String, x: Int, y: Int) {
        append("<a href=\"/day/")
        append(Html.pathSegment(date.toString()))
        append("\"><text class=\"node-label\" x=\"")
        append(x)
        append("\" y=\"")
        append(y)
        append("\">")
        append(Html.escape(label))
        append("</text></a>")
    }

    private fun BrowserGraphNodeKind.defaultRelation(): String = when (this) {
        BrowserGraphNodeKind.TAG -> "tag"
        BrowserGraphNodeKind.DATE -> "date"
        BrowserGraphNodeKind.ENTRY -> "entry"
    }

    private fun BrowserGraphNodeKind.displayName(): String = when (this) {
        BrowserGraphNodeKind.TAG -> "tag"
        BrowserGraphNodeKind.DATE -> "date"
        BrowserGraphNodeKind.ENTRY -> "entry"
    }

    private fun pager(
        base: String,
        pageNumber: Int,
        totalCount: Int,
        hasQuery: Boolean = false,
        languageTag: String = "en",
    ): String {
        val copy = BrowserWebCopy.forLanguage(languageTag)
        val safeTotal = totalCount.coerceAtLeast(0).toLong()
        val pageCount = max(1L, (safeTotal + PAGE_SIZE - 1) / PAGE_SIZE).toInt()
        val separator = if (hasQuery) "&amp;" else "?"
        return buildString {
            append("<nav class=\"pager\" aria-label=\"Pages\">")
            if (pageNumber > 1) {
                append("<a rel=\"prev\" href=\"")
                append(base)
                append(separator)
                append("page=")
                append(pageNumber - 1)
                append("\">").append(Html.escape(copy.previous)).append("</a>")
            } else {
                append("<span></span>")
            }
            append("<span>")
            append(pageNumber)
            append(" / ")
            append(pageCount)
            append("</span>")
            if (pageNumber < pageCount) {
                append("<a rel=\"next\" href=\"")
                append(base)
                append(separator)
                append("page=")
                append(pageNumber + 1)
                append("\">").append(Html.escape(copy.next)).append("</a>")
            } else {
                append("<span></span>")
            }
            append("</nav>")
        }
    }

    private fun page(
        title: String,
        body: String,
        navigation: Boolean = true,
        lightMode: Boolean = false,
        languageTag: String = "en",
        active: String = "",
    ): String = buildString {
        val copy = BrowserWebCopy.forLanguage(languageTag)
        append("<!doctype html><html lang=\"")
        append(Html.escape(ExportCopy.supportedLanguageTag(languageTag)))
        append("\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>")
        append(Html.escape(title))
        append(" · Soma</title><style>")
        append(if (lightMode) LIGHT_THEME else DARK_THEME)
        append(STYLES)
        append("</style></head><body>")
        if (navigation) {
            append("<nav class=\"primary\" aria-label=\"Main\"><a class=\"mark\" href=\"/days\">Soma</a>")
            navLink("/days", copy.days, active == "days")
            navLink("/todos", copy.important, active == "todos")
            navLink("/logs", copy.logs, active == "logs")
            navLink("/insights", copy.insights, active == "insights")
            navLink("/graph", copy.graph, active == "graph")
            append("</nav>")
        }
        append(body)
        append("</body></html>")
    }

    private fun StringBuilder.navLink(href: String, label: String, current: Boolean) {
        append("<a href=\"").append(href).append('"')
        if (current) append(" aria-current=\"page\"")
        append('>').append(Html.escape(label)).append("</a>")
    }

    /** The CSRF token and same-origin return path every write form must carry. */
    private fun StringBuilder.writeFields(csrfToken: String, returnPath: String) {
        append("<input type=\"hidden\" name=\"csrf\" value=\"").append(Html.escape(csrfToken)).append("\">")
        append("<input type=\"hidden\" name=\"return\" value=\"").append(Html.escape(returnPath)).append("\">")
    }

    private const val DARK_THEME = ":root{color-scheme:dark;--paper:#0a0b0a;--ink:#f4f2ec;--dim:#c8c9c2;--faint:#9a9c93;--line:rgba(244,242,236,.2);--hair:rgba(244,242,236,.12);--edge:rgba(255,255,255,.17);--glass:rgba(14,16,14,.16);--nav:rgba(9,10,9,.42);--forest-opacity:1;--veil:rgba(0,0,0,.26);--shadow:0 1px 3px rgba(0,0,0,.6)}"
    private const val LIGHT_THEME = ":root{color-scheme:light;--paper:#e7e5dd;--ink:#141410;--dim:#3f403a;--faint:#63645d;--line:rgba(20,20,15,.26);--hair:rgba(20,20,15,.14);--edge:rgba(255,255,255,.55);--glass:rgba(245,244,238,.26);--nav:rgba(240,238,231,.52);--forest-opacity:.58;--veil:rgba(255,255,255,.2);--shadow:0 1px 3px rgba(255,255,255,.55)}"

    // A moonlit reading room: warm off-white ink on near-black, the localized
    // forest genuinely present through a frosted reading panel, an editorial type
    // scale with uppercase tracked micro-labels, and a masthead that marks the
    // active section. Monochrome throughout; no scripts (CSP forbids them).
    private const val STYLES = """
        *{box-sizing:border-box}html{height:100%;font-family:"Akkurat LL",-apple-system,"Segoe UI","Helvetica Neue",Arial,sans-serif;color:var(--ink);background:var(--paper);-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}
        body{margin:0;height:100vh;height:100dvh;overflow:hidden;display:flex;flex-direction:column;padding:46px 0 24px;font-size:17px;line-height:1.52;letter-spacing:-.003em}
        body:before{content:"";position:fixed;inset:0;background:url('/assets/forest.webp') center/cover no-repeat;filter:grayscale(1) contrast(1.05) brightness(.82);opacity:var(--forest-opacity);pointer-events:none;z-index:-2}
        body:after{content:"";position:fixed;inset:0;background:radial-gradient(135% 95% at 50% -10%,transparent 46%,var(--veil) 100%);pointer-events:none;z-index:-1}
        .primary{flex:none;z-index:3;width:min(calc(100% - 28px),720px);margin:0 auto 14px;display:flex;flex-wrap:wrap;align-items:baseline;gap:5px 22px;padding:14px 30px;background:var(--nav);backdrop-filter:blur(13px) saturate(.85);-webkit-backdrop-filter:blur(13px) saturate(.85);border:1px solid var(--edge)}
        .primary .mark{color:var(--ink);font-weight:600;text-transform:uppercase;letter-spacing:.28em;font-size:13px;margin-right:6px;text-decoration:none}
        .primary a:not(.mark){color:var(--dim);text-decoration:none;font-size:14.5px;letter-spacing:.01em;padding:3px 0}
        .primary a:not(.mark):hover{color:var(--ink)}.primary a[aria-current=page]{color:var(--ink);box-shadow:inset 0 -2px 0 var(--ink)}
        main{position:relative;z-index:1;flex:1 1 auto;min-height:0;overflow-y:auto;width:min(calc(100% - 28px),720px);margin:0 auto;padding:34px 34px 44px;background:var(--glass);backdrop-filter:blur(8px) saturate(.92);-webkit-backdrop-filter:blur(8px) saturate(.92);border:1px solid var(--edge);text-shadow:var(--shadow);overscroll-behavior:contain}
        a{color:var(--ink);text-decoration:underline;text-underline-offset:3px;text-decoration-thickness:1px}a:hover{text-decoration-thickness:2px}
        header{padding:0 0 24px;border-bottom:1px solid var(--line);margin-bottom:8px}
        h1{font-size:clamp(33px,8.6vw,50px);line-height:1.02;letter-spacing:-.028em;font-weight:600;margin:0}
        header p{color:var(--dim);margin:14px 0 0;font-size:12.5px;letter-spacing:.15em;text-transform:uppercase}
        .quiet,small{color:var(--dim)}.back{display:inline-block;margin-bottom:24px;color:var(--dim);text-decoration:none;font-size:14px;letter-spacing:.02em}.back:hover{color:var(--ink)}
        .list{list-style:none;margin:0;padding:0}.list>li{border-bottom:1px solid var(--hair)}
        .row,.entry{display:flex;width:100%;padding:21px 0;justify-content:space-between;gap:22px;align-items:baseline}
        .row{text-decoration:none;margin:0 -8px;padding-left:8px;padding-right:8px;transition:background .14s ease}.row:hover{background:var(--hair)}
        .row strong{font-weight:600;letter-spacing:-.012em}.row small{display:block;margin-top:7px;color:var(--dim);line-height:1.45}
        .count{color:var(--dim);font-variant-numeric:tabular-nums;font-size:13px;letter-spacing:.05em;white-space:nowrap;align-self:center}
        .entry{display:block;padding:26px 0}.entry p{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font-size:18px;line-height:1.56}
        .entry>small{display:block;margin-top:13px;color:var(--faint);font-size:11.5px;letter-spacing:.13em;text-transform:uppercase}
        .history{margin-top:16px}.history summary{cursor:pointer;color:var(--dim);font-size:14px;list-style:none}.history summary::-webkit-details-marker{display:none}.history summary:hover{color:var(--ink)}.history ol{margin:14px 0 0;padding:0 0 0 20px;border-left:1px solid var(--line);list-style:none}.history li{padding:9px 0}.history li p{margin:6px 0 0}
        audio{display:block;width:100%;margin-top:16px;filter:grayscale(1) contrast(1.03);border-radius:0}
        audio::-webkit-media-controls-enclosure{background:var(--hair);border-radius:0}
        .entry img{display:block;width:100%;height:auto;max-height:68vh;object-fit:contain;margin-top:16px;border:1px solid var(--hair)}.empty{padding:52px 0;color:var(--dim)}
        .pager{display:grid;grid-template-columns:1fr auto 1fr;gap:16px;padding:32px 0 0;margin-top:14px;border-top:1px solid var(--line);align-items:center;color:var(--faint);font-size:14px;font-variant-numeric:tabular-nums}.pager a{color:var(--ink);text-decoration:none}.pager a:hover{text-decoration:underline}.pager>*:last-child{text-align:right}
        .tabs{display:flex;flex-wrap:wrap;gap:7px 22px;margin:0 0 26px;padding-bottom:2px}.tabs a{color:var(--dim);text-decoration:none;font-size:15px}.tabs a:hover{color:var(--ink)}.tabs [aria-current=page]{color:var(--ink);box-shadow:inset 0 -2px 0 var(--ink)}
        .log-header{display:flex;justify-content:space-between;align-items:baseline;gap:18px;padding:0}.log-header h2{margin:0}.log-header time{color:var(--dim);font-variant-numeric:tabular-nums;white-space:nowrap;font-size:13.5px}.log-note{margin:12px 0 0!important;color:var(--dim)}.log-parts{list-style:none;margin:16px 0 0;padding:0}.log-parts>li{padding:12px 0;border-top:1px solid var(--hair)}.log-parts small{display:block;margin-top:6px;color:var(--dim)}.receipt-totals{display:grid;grid-template-columns:1fr auto;gap:6px 20px;margin:18px 0 0;padding-top:14px;border-top:1px solid var(--line)}.receipt-totals dt{color:var(--dim)}.receipt-totals dd{margin:0;text-align:right;font-variant-numeric:tabular-nums}.log-footer{display:flex;justify-content:space-between;gap:20px;margin-top:16px;color:var(--faint);font-size:11.5px;letter-spacing:.11em;text-transform:uppercase}.log-footer a{color:var(--faint)}.log-footer a:hover{color:var(--ink)}.log-footer:empty{display:none}
        h2{font-size:23px;margin:32px 0 10px;letter-spacing:-.016em;font-weight:600}.summary{display:grid;grid-template-columns:repeat(3,1fr);gap:22px 16px;margin:6px 0 0}.summary div{min-width:0}.summary dt{color:var(--faint);font-size:11px;letter-spacing:.13em;text-transform:uppercase}.summary dd{font-size:30px;margin:5px 0 0;font-variant-numeric:tabular-nums;letter-spacing:-.02em}
        .connection-graph{display:block;width:100%;height:auto;overflow:visible}.connection-graph circle{fill:var(--ink)}.edge-line{stroke:var(--dim);stroke-width:2}.node-label{fill:var(--ink);font-size:18px;font-weight:bold}.node-detail,.edge-source{fill:var(--dim);font-size:14px}.edge-label{fill:var(--ink);font-size:14px}.connection-graph a{text-decoration:underline}.graph-note{margin-top:18px;color:var(--dim)}
        form{border-top:1px solid var(--line);padding-top:26px;margin-top:8px}label{display:block;margin-bottom:10px;color:var(--dim);font-size:12.5px;letter-spacing:.14em;text-transform:uppercase}input,button{font:inherit;border:1px solid var(--ink);background:transparent;color:var(--ink);border-radius:0;padding:14px}
        input{width:100%;letter-spacing:.34em;font-size:20px;text-align:center}input:focus-visible,button:focus-visible,a:focus-visible,summary:focus-visible,textarea:focus-visible{outline:2px solid var(--ink);outline-offset:3px}button{width:100%;margin-top:16px;font-weight:600;letter-spacing:.04em;cursor:pointer;background:var(--ink);color:var(--paper)}button:hover{opacity:.9}.message{border:1px solid var(--ink);padding:13px;margin-bottom:20px}
        textarea{width:100%;font:inherit;font-size:17px;line-height:1.5;color:var(--ink);background:var(--hair);border:1px solid var(--line);border-radius:0;padding:13px;resize:vertical;min-height:84px}textarea::placeholder{color:var(--faint)}
        .editor{margin-top:14px}.editor>summary{cursor:pointer;color:var(--dim);font-size:12px;letter-spacing:.13em;text-transform:uppercase;list-style:none}.editor>summary::-webkit-details-marker{display:none}.editor>summary:hover{color:var(--ink)}.editor form{border:none;margin:0;padding:12px 0 0}
        .composer{border-top:1px solid var(--line);padding-top:26px;margin-top:12px}.editor button,.composer button{width:auto;margin-top:12px;padding:11px 26px}
        @media(max-width:520px){body{padding:34px 0 16px;font-size:16.5px}.primary,main{width:calc(100% - 20px)}.primary{padding:13px 20px;gap:4px 18px;margin-bottom:12px}main{padding:26px 20px 38px}.summary{grid-template-columns:repeat(3,1fr)}.log-header{display:block}.log-header time{display:block;margin-top:7px}.log-footer{display:block}.log-footer>*{display:block;margin-top:8px}}
    """

    private const val GRAPH_EDGE_HEIGHT = 104
}

private data class BrowserWebCopy(
    val days: String,
    val important: String,
    val logs: String,
    val insights: String,
    val graph: String,
    val confirmedRecords: String,
    val meals: String,
    val recipes: String,
    val workouts: String,
    val receipts: String,
    val receiptSubtotal: String,
    val receiptTax: String,
    val receiptTotal: String,
    val archived: String,
    val noLogs: String,
    val sourceNote: String,
    val versions: String,
    val browserView: String,
    val accessCode: String,
    val continueLabel: String,
    val useCode: String,
    val dailyNotes: String,
    val dailyNote: String,
    val noNotes: String,
    val nothingCaptured: String,
    val transcribing: String,
    val returnToThis: String,
    val editHistory: String,
    val original: String,
    val versionLabel: String,
    val stillOpen: String,
    val doneArchived: String,
    val openTab: String,
    val nothingOpen: String,
    val nothingHere: String,
    val localMetadataOnly: String,
    val connections: String,
    val noMetadata: String,
    val statEntries: String,
    val statManual: String,
    val statAi: String,
    val statLocal: String,
    val statTags: String,
    val statLinks: String,
    val kindTag: String,
    val kindDateLink: String,
    val kindEntryLink: String,
    val graphSubtitle: String,
    val noConnections: String,
    val previous: String,
    val next: String,
    val editAction: String,
    val saveAction: String,
    val addAction: String,
    val addToDay: String,
) {
    companion object {
        fun forLanguage(languageTag: String): BrowserWebCopy = when (languageTag.substringBefore('-').lowercase()) {
            "lv" -> BrowserWebCopy(
                "Dienas", "Svarīgais", "Žurnāli", "Ieskati", "Grafiks",
                "Apstiprināti ieraksti", "Ēdienreizes", "Receptes", "Treniņi", "Čeki", "Starpsumma", "Nodoklis", "Kopā", "Arhīvs",
                "Te vēl nekas nav saglabāts.", "avota piezīme", "versijas",
                "Pārlūka skats", "Piekļuves kods", "Turpināt", "Ievadi tālrunī redzamo vienreizējo kodu.",
                dailyNotes = "Ikdienas piezīmes", dailyNote = "Dienas piezīme", noNotes = "Vēl nav piezīmju.",
                nothingCaptured = "Nekas nav pierakstīts.", transcribing = "balss piezīme, transkribē…",
                returnToThis = "atgriezties pie šī", editHistory = "Labojumu vēsture", original = "Sākotnējais", versionLabel = "Versija",
                stillOpen = "Vēl atvērts", doneArchived = "Pabeigts / arhivēts", openTab = "Atvērtie",
                nothingOpen = "Nekas nav atvērts.", nothingHere = "Te vēl nekā nav.",
                localMetadataOnly = "Tikai vietējie metadati", connections = "Savienojumi", noMetadata = "Vēl nav metadatu.",
                statEntries = "Ieraksti", statManual = "Manuāli", statAi = "MI", statLocal = "Vietējie", statTags = "Birkas", statLinks = "Saites",
                kindTag = "birka", kindDateLink = "datuma saite", kindEntryLink = "ieraksta saite",
                graphSubtitle = "Vietējie savienojumi · pieci lapā", noConnections = "Vēl nav savienojumu.",
                previous = "Iepriekšējā", next = "Nākamā",
                editAction = "labot", saveAction = "saglabāt", addAction = "pievienot", addToDay = "pievienot šai dienai…",
            )
            "et" -> BrowserWebCopy(
                "Päevad", "Oluline", "Logid", "Ülevaade", "Graafik",
                "Kinnitatud kirjed", "Toidukorrad", "Retseptid", "Treeningud", "Kviitungid", "Vahesumma", "Maks", "Kokku", "Arhiiv",
                "Siin pole veel midagi salvestatud.", "lähtekirje", "versiooni",
                "Brauserivaade", "Pääsukood", "Jätka", "Sisesta telefonis kuvatav ühekordne kood.",
                dailyNotes = "Igapäevased märkmed", dailyNote = "Päeva märge", noNotes = "Märkmeid veel pole.",
                nothingCaptured = "Midagi pole jäädvustatud.", transcribing = "häälmärge, transkribeerin…",
                returnToThis = "tule selle juurde tagasi", editHistory = "Muudatuste ajalugu", original = "Algne", versionLabel = "Versioon",
                stillOpen = "Veel avatud", doneArchived = "Tehtud / arhiveeritud", openTab = "Avatud",
                nothingOpen = "Midagi pole avatud.", nothingHere = "Siin pole veel midagi.",
                localMetadataOnly = "Ainult kohalikud metaandmed", connections = "Seosed", noMetadata = "Metaandmeid veel pole.",
                statEntries = "Kirjed", statManual = "Käsitsi", statAi = "AI", statLocal = "Kohalik", statTags = "Sildid", statLinks = "Lingid",
                kindTag = "silt", kindDateLink = "kuupäevalink", kindEntryLink = "kirje link",
                graphSubtitle = "Kohalikud seosed · viis lehel", noConnections = "Seoseid veel pole.",
                previous = "Eelmine", next = "Järgmine",
                editAction = "muuda", saveAction = "salvesta", addAction = "lisa", addToDay = "lisa sellele päevale…",
            )
            "lt" -> BrowserWebCopy(
                "Dienos", "Svarbu", "Žurnalai", "Įžvalgos", "Grafas",
                "Patvirtinti įrašai", "Valgiai", "Receptai", "Treniruotės", "Kvitai", "Tarpinė suma", "Mokestis", "Iš viso", "Archyvas",
                "Čia dar nieko neišsaugota.", "šaltinio pastaba", "versijos",
                "Naršyklės rodinys", "Prieigos kodas", "Tęsti", "Įveskite telefone rodomą vienkartinį kodą.",
                dailyNotes = "Kasdienės pastabos", dailyNote = "Dienos pastaba", noNotes = "Pastabų dar nėra.",
                nothingCaptured = "Nieko neužfiksuota.", transcribing = "balso pastaba, transkribuojama…",
                returnToThis = "grįžti prie šio", editHistory = "Keitimų istorija", original = "Originalas", versionLabel = "Versija",
                stillOpen = "Vis dar atviri", doneArchived = "Atlikta / archyvuota", openTab = "Atviri",
                nothingOpen = "Nieko atviro.", nothingHere = "Čia dar nieko nėra.",
                localMetadataOnly = "Tik vietiniai metaduomenys", connections = "Ryšiai", noMetadata = "Metaduomenų dar nėra.",
                statEntries = "Įrašai", statManual = "Rankiniai", statAi = "DI", statLocal = "Vietiniai", statTags = "Žymos", statLinks = "Nuorodos",
                kindTag = "žyma", kindDateLink = "datos nuoroda", kindEntryLink = "įrašo nuoroda",
                graphSubtitle = "Vietiniai ryšiai · penki puslapyje", noConnections = "Ryšių dar nėra.",
                previous = "Ankstesnis", next = "Kitas",
                editAction = "redaguoti", saveAction = "išsaugoti", addAction = "pridėti", addToDay = "pridėti prie šios dienos…",
            )
            "fi" -> BrowserWebCopy(
                "Päivät", "Tärkeät", "Lokit", "Kooste", "Verkko",
                "Vahvistetut kirjaukset", "Ateriat", "Reseptit", "Harjoitukset", "Kuitit", "Välisumma", "Vero", "Yhteensä", "Arkisto",
                "Ei vielä tallennettuja kirjauksia.", "lähdemuistiinpano", "versiota",
                "Selainnäkymä", "Käyttökoodi", "Jatka", "Syötä puhelimessa näkyvä kertakäyttökoodi.",
                dailyNotes = "Päivittäiset muistiinpanot", dailyNote = "Päivän muistiinpano", noNotes = "Ei vielä muistiinpanoja.",
                nothingCaptured = "Ei mitään tallennettua.", transcribing = "äänimuistiinpano, litteroidaan…",
                returnToThis = "palaa tähän", editHistory = "Muokkaushistoria", original = "Alkuperäinen", versionLabel = "Versio",
                stillOpen = "Yhä auki", doneArchived = "Valmis / arkistoitu", openTab = "Avoimet",
                nothingOpen = "Ei mitään auki.", nothingHere = "Täällä ei ole vielä mitään.",
                localMetadataOnly = "Vain paikalliset metatiedot", connections = "Yhteydet", noMetadata = "Ei vielä metatietoja.",
                statEntries = "Merkinnät", statManual = "Manuaaliset", statAi = "AI", statLocal = "Paikalliset", statTags = "Tunnisteet", statLinks = "Linkit",
                kindTag = "tunniste", kindDateLink = "päivämäärälinkki", kindEntryLink = "merkintälinkki",
                graphSubtitle = "Paikalliset yhteydet · viisi sivulla", noConnections = "Ei vielä yhteyksiä.",
                previous = "Edellinen", next = "Seuraava",
                editAction = "muokkaa", saveAction = "tallenna", addAction = "lisää", addToDay = "lisää tähän päivään…",
            )
            "sv" -> BrowserWebCopy(
                "Dagar", "Viktigt", "Loggar", "Insikter", "Graf",
                "Bekräftade poster", "Måltider", "Recept", "Träning", "Kvitton", "Delsumma", "Skatt", "Totalt", "Arkiv",
                "Inget sparat här ännu.", "källanteckning", "versioner",
                "Webbläsarvy", "Åtkomstkod", "Fortsätt", "Ange engångskoden som visas på telefonen.",
                dailyNotes = "Dagliga anteckningar", dailyNote = "Dagens anteckning", noNotes = "Inga anteckningar än.",
                nothingCaptured = "Inget sparat.", transcribing = "röstanteckning, transkriberar…",
                returnToThis = "återkom till detta", editHistory = "Ändringshistorik", original = "Original", versionLabel = "Version",
                stillOpen = "Fortfarande öppna", doneArchived = "Klart / arkiverat", openTab = "Öppna",
                nothingOpen = "Inget öppet.", nothingHere = "Inget här än.",
                localMetadataOnly = "Endast lokala metadata", connections = "Kopplingar", noMetadata = "Inga metadata än.",
                statEntries = "Poster", statManual = "Manuella", statAi = "AI", statLocal = "Lokala", statTags = "Taggar", statLinks = "Länkar",
                kindTag = "tagg", kindDateLink = "datumlänk", kindEntryLink = "postlänk",
                graphSubtitle = "Lokala kopplingar · fem per sida", noConnections = "Inga kopplingar än.",
                previous = "Föregående", next = "Nästa",
                editAction = "redigera", saveAction = "spara", addAction = "lägg till", addToDay = "lägg till denna dag…",
            )
            "de" -> BrowserWebCopy(
                "Tage", "Wichtig", "Protokolle", "Einblicke", "Graph",
                "Bestätigte Einträge", "Mahlzeiten", "Rezepte", "Training", "Kassenbons", "Zwischensumme", "Steuer", "Gesamt", "Archiv",
                "Noch nichts gespeichert.", "Quellnotiz", "Versionen",
                "Browseransicht", "Zugangscode", "Weiter", "Gib den einmaligen Code vom Telefon ein.",
                dailyNotes = "Tägliche Notizen", dailyNote = "Tagesnotiz", noNotes = "Noch keine Notizen.",
                nothingCaptured = "Nichts erfasst.", transcribing = "Sprachnotiz, wird transkribiert…",
                returnToThis = "hierher zurückkehren", editHistory = "Bearbeitungsverlauf", original = "Original", versionLabel = "Version",
                stillOpen = "Noch offen", doneArchived = "Erledigt / archiviert", openTab = "Offen",
                nothingOpen = "Nichts offen.", nothingHere = "Hier ist noch nichts.",
                localMetadataOnly = "Nur lokale Metadaten", connections = "Verbindungen", noMetadata = "Noch keine Metadaten.",
                statEntries = "Einträge", statManual = "Manuell", statAi = "KI", statLocal = "Lokal", statTags = "Tags", statLinks = "Verknüpfungen",
                kindTag = "Tag", kindDateLink = "Datumsverknüpfung", kindEntryLink = "Eintragsverknüpfung",
                graphSubtitle = "Lokale Verbindungen · fünf pro Seite", noConnections = "Noch keine Verbindungen.",
                previous = "Zurück", next = "Weiter",
                editAction = "bearbeiten", saveAction = "speichern", addAction = "hinzufügen", addToDay = "zu diesem Tag hinzufügen…",
            )
            "sk" -> BrowserWebCopy(
                "Dni", "Dôležité", "Záznamy", "Prehľad", "Graf",
                "Potvrdené záznamy", "Jedlá", "Recepty", "Tréningy", "Účtenky", "Medzisúčet", "Daň", "Spolu", "Archív",
                "Zatiaľ tu nič nie je uložené.", "zdrojová poznámka", "verzie",
                "Zobrazenie v prehliadači", "Prístupový kód", "Pokračovať", "Zadajte jednorazový kód zobrazený v telefóne.",
                dailyNotes = "Denné poznámky", dailyNote = "Denná poznámka", noNotes = "Zatiaľ žiadne poznámky.",
                nothingCaptured = "Nič nezaznamenané.", transcribing = "hlasová poznámka, prepisuje sa…",
                returnToThis = "vrátiť sa k tomuto", editHistory = "História úprav", original = "Pôvodné", versionLabel = "Verzia",
                stillOpen = "Stále otvorené", doneArchived = "Hotové / archivované", openTab = "Otvorené",
                nothingOpen = "Nič otvorené.", nothingHere = "Zatiaľ tu nič nie je.",
                localMetadataOnly = "Iba lokálne metadáta", connections = "Spojenia", noMetadata = "Zatiaľ žiadne metadáta.",
                statEntries = "Záznamy", statManual = "Ručné", statAi = "AI", statLocal = "Lokálne", statTags = "Značky", statLinks = "Odkazy",
                kindTag = "značka", kindDateLink = "odkaz na dátum", kindEntryLink = "odkaz na záznam",
                graphSubtitle = "Lokálne spojenia · päť na stranu", noConnections = "Zatiaľ žiadne spojenia.",
                previous = "Predchádzajúca", next = "Ďalšia",
                editAction = "upraviť", saveAction = "uložiť", addAction = "pridať", addToDay = "pridať k tomuto dňu…",
            )
            else -> BrowserWebCopy(
                "Days", "Important", "Logs", "Insights", "Graph",
                "Confirmed records", "Meals", "Recipes", "Workouts", "Receipts", "Subtotal", "Tax", "Total", "Archived",
                "Nothing saved here yet.", "source note", "versions",
                "Browser view", "Access code", "Continue", "Use the one-time code shown on your phone.",
                dailyNotes = "Daily notes", dailyNote = "Daily note", noNotes = "No notes yet.",
                nothingCaptured = "Nothing captured.", transcribing = "voice note, transcribing…",
                returnToThis = "return to this", editHistory = "Edit history", original = "Original", versionLabel = "Version",
                stillOpen = "Still open", doneArchived = "Done / archived", openTab = "Open",
                nothingOpen = "Nothing open.", nothingHere = "Nothing here yet.",
                localMetadataOnly = "Local metadata only", connections = "Connections", noMetadata = "No metadata yet.",
                statEntries = "Entries", statManual = "Manual", statAi = "AI", statLocal = "Local", statTags = "Tags", statLinks = "Links",
                kindTag = "tag", kindDateLink = "date link", kindEntryLink = "entry link",
                graphSubtitle = "Local connections · five per page", noConnections = "No connections yet.",
                previous = "Previous", next = "Next",
                editAction = "edit", saveAction = "save", addAction = "add", addToDay = "add to this day…",
            )
        }
    }
}

private data class ExportCopy(
    val title: String,
    val subtitle: String,
    val link: String,
    val contents: String,
    val warningTitle: String,
    val warningBody: String,
    val download: String,
    val back: String,
) {
    companion object {
        fun forLanguage(tag: String): ExportCopy = COPIES[tag.substringBefore('-').lowercase()] ?: COPIES.getValue("en")

        fun supportedLanguageTag(tag: String): String =
            tag.substringBefore('-').lowercase().takeIf(COPIES::containsKey) ?: "en"

        private val COPIES = mapOf(
            "en" to ExportCopy(
                "Export for analysis", "Your data, your AI", "Export for analysis →",
                "The ZIP contains daily notes, Important items, meal, workout and receipt logs, purchased items, exact totals, tags, dates, links, and the complete edit history, including every earlier wording. Audio and photos are not included.",
                "Plaintext export.",
                "It is not encrypted and travels over plain HTTP on this local network. Anyone able to observe the network can read or replace it. Save it only to a device you trust and delete it when finished.",
                "I understand — download vault (.zip)", "← Insights",
            ),
            "lv" to ExportCopy(
                "Eksports analīzei", "Tavi dati, tavs MI", "Eksportēt analīzei →",
                "ZIP failā ir dienas piezīmes, svarīgie ieraksti, ēdienreižu un treniņu žurnāli, čeki, nopirktās preces, precīzās kopsummas, atzīmes, datumi, saites un pilna labojumu vēsture, ieskaitot visus iepriekšējos teksta variantus. Audio un fotoattēli nav iekļauti.",
                "Nešifrēts eksports.",
                "Tas nav šifrēts un tiek pārsūtīts pa parastu HTTP šajā lokālajā tīklā. Ikviens, kas var novērot tīklu, var to izlasīt vai aizstāt. Saglabā tikai uzticamā ierīcē un pēc lietošanas izdzēs.",
                "Es saprotu — lejupielādēt arhīvu (.zip)", "← Ieskati",
            ),
            "et" to ExportCopy(
                "Eksport analüüsiks", "Sinu andmed, sinu tehisaru", "Ekspordi analüüsiks →",
                "ZIP sisaldab päevamärkmeid, olulisi üksusi, söögi- ja treeningulogisid, kviitungeid, ostetud tooteid, täpseid summasid, silte, kuupäevi, linke ning täielikku muutmisajalugu koos kõigi varasemate sõnastustega. Heli ja fotosid ei lisata.",
                "Krüpteerimata eksport.",
                "See liigub selles kohalikus võrgus tavalise HTTP kaudu. Igaüks, kes võrku jälgib, võib seda lugeda või muuta. Salvesta ainult usaldusväärsesse seadmesse ja kustuta pärast kasutamist.",
                "Saan aru — laadi varamu alla (.zip)", "← Ülevaade",
            ),
            "lt" to ExportCopy(
                "Eksportas analizei", "Jūsų duomenys, jūsų DI", "Eksportuoti analizei →",
                "ZIP faile yra dienos užrašai, svarbūs įrašai, maisto, treniruočių ir kvitų žurnalai, pirktos prekės, tikslios sumos, žymos, datos, nuorodos ir visa taisymų istorija, įskaitant ankstesnes formuluotes. Garso įrašai ir nuotraukos neįtraukiami.",
                "Nešifruotas eksportas.",
                "Jis perduodamas paprastu HTTP šiame vietiniame tinkle. Tinklą stebintys asmenys gali jį perskaityti arba pakeisti. Saugokite tik patikimame įrenginyje ir panaudoję ištrinkite.",
                "Suprantu — atsisiųsti saugyklą (.zip)", "← Įžvalgos",
            ),
            "fi" to ExportCopy(
                "Vienti analyysiin", "Sinun tietosi, sinun tekoälysi", "Vie analyysiin →",
                "ZIP sisältää päivämuistiinpanot, tärkeät kohteet, ruoka-, harjoitus- ja kuittilokit, ostetut tuotteet, tarkat summat, tunnisteet, päivämäärät, linkit sekä koko muokkaushistorian kaikkine aiempine sanamuotoineen. Ääntä ja kuvia ei sisällytetä.",
                "Salaamaton vienti.",
                "Se siirtyy tavallisella HTTP-yhteydellä tässä lähiverkossa. Verkon liikennettä seuraava voi lukea tai muuttaa sitä. Tallenna vain luotettuun laitteeseen ja poista käytön jälkeen.",
                "Ymmärrän — lataa holvi (.zip)", "← Kooste",
            ),
            "sv" to ExportCopy(
                "Export för analys", "Dina data, din AI", "Exportera för analys →",
                "ZIP-filen innehåller dagliga anteckningar, viktiga poster, mat-, tränings- och kvittologgar, köpta varor, exakta summor, taggar, datum, länkar och fullständig redigeringshistorik med alla tidigare formuleringar. Ljud och foton ingår inte.",
                "Okrypterad export.",
                "Den skickas via vanlig HTTP i det lokala nätverket. Den som kan övervaka nätverket kan läsa eller ersätta den. Spara endast på en betrodd enhet och radera efter användning.",
                "Jag förstår — hämta valv (.zip)", "← Insikter",
            ),
            "de" to ExportCopy(
                "Export zur Analyse", "Deine Daten, deine KI", "Zur Analyse exportieren →",
                "Die ZIP-Datei enthält Tagesnotizen, wichtige Einträge, Essens-, Trainings- und Kassenbonprotokolle, gekaufte Artikel, exakte Summen, Tags, Daten, Verknüpfungen und den vollständigen Bearbeitungsverlauf mit allen früheren Formulierungen. Audio und Fotos sind nicht enthalten.",
                "Unverschlüsselter Export.",
                "Die Übertragung erfolgt über einfaches HTTP in diesem lokalen Netzwerk. Wer den Netzwerkverkehr beobachten kann, kann die Datei lesen oder ersetzen. Nur auf einem vertrauenswürdigen Gerät speichern und danach löschen.",
                "Verstanden — Vault herunterladen (.zip)", "← Einblicke",
            ),
            "sk" to ExportCopy(
                "Export na analýzu", "Vaše údaje, vaša AI", "Exportovať na analýzu →",
                "ZIP obsahuje denné poznámky, dôležité položky, záznamy jedál, tréningov a účteniek, kúpené položky, presné sumy, značky, dátumy, odkazy a úplnú históriu úprav vrátane všetkých predchádzajúcich znení. Zvuk a fotografie nie sú zahrnuté.",
                "Nešifrovaný export.",
                "Prenáša sa cez obyčajné HTTP v tejto lokálnej sieti. Kto môže sledovať sieť, môže ho čítať alebo nahradiť. Uložte ho iba do dôveryhodného zariadenia a po použití odstráňte.",
                "Rozumiem — stiahnuť archív (.zip)", "← Prehľad",
            ),
        )
    }
}

internal object Html {
    fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

    fun pathSegment(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (
                character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                character == '-' || character == '.' || character == '_' || character == '~'
            ) {
                append(character)
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

internal const val PAGE_SIZE = 5
