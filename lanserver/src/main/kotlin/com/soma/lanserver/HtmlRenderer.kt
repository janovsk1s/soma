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
        body = buildString {
            append("<main><header><h1>Days</h1><p>Daily notes</p></header>")
            val days = result.items.take(PAGE_SIZE)
            if (days.isEmpty()) {
                append("<p class=\"empty\">No notes yet.</p>")
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
            append(pager("/days", pageNumber, result.totalCount))
            append("</main>")
        },
    )

    fun day(
        date: LocalDate,
        pageNumber: Int,
        result: PagedResult<BrowserEntry>,
        lightMode: Boolean = false,
        languageTag: String = "en",
    ): String = page(
        title = date.toString(),
        lightMode = lightMode,
        languageTag = languageTag,
        body = buildString {
            append("<main><header><a class=\"back\" href=\"/days\">← Days</a><h1>")
            append(Html.escape(date.toString()))
            append("</h1><p>Daily note</p></header>")
            val entries = result.items.take(PAGE_SIZE)
            if (entries.isEmpty()) {
                append("<p class=\"empty\">Nothing captured.</p>")
            } else {
                append("<ol class=\"list entries\">")
                entries.forEach { entry ->
                    append("<li><article class=\"entry\"><p>")
                    if (entry.transcriptionPending && entry.text.isBlank()) {
                        append("<span class=\"quiet\">voice note, transcribing…</span>")
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
                        append("<small class=\"quiet\">return to this</small>")
                    }
                    val previousVersions = entry.history.filterNot(BrowserEntryVersion::isCurrent)
                    if (previousVersions.isNotEmpty()) {
                        append("<details class=\"history\"><summary>Edit history · ")
                        append(entry.history.size)
                        append(" versions</summary><ol>")
                        previousVersions.forEach { version ->
                            append("<li><small>")
                            append(if (version.number == 1) "Original" else "Version ${version.number}")
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
                    append("</article></li>")
                }
                append("</ol>")
            }
            append(pager("/day/${Html.pathSegment(date.toString())}", pageNumber, result.totalCount))
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
        lightMode = lightMode,
        languageTag = languageTag,
        body = buildString {
            append("<main><header><h1>Todos</h1><p>")
            append(if (filter == BrowserTodoFilter.OPEN) "Still open" else "Done / archived")
            append("</p></header><nav class=\"tabs\" aria-label=\"Todo lists\">")
            tab("Open", "/todos?state=open", filter == BrowserTodoFilter.OPEN)
            tab("Done / archived", "/todos?state=completed", filter == BrowserTodoFilter.COMPLETED)
            append("</nav>")
            val todos = result.items.take(PAGE_SIZE)
            if (todos.isEmpty()) {
                append("<p class=\"empty\">")
                append(if (filter == BrowserTodoFilter.OPEN) "Nothing open." else "Nothing here yet.")
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
                        append("\">source note</a>")
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
            append(pager(base, pageNumber, result.totalCount, hasQuery = true))
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
            BrowserLogFilter.ARCHIVED -> copy.archived
        }
        return page(
            title = copy.logs,
            lightMode = lightMode,
            languageTag = languageTag,
            body = buildString {
                append("<main><header><h1>").append(Html.escape(copy.logs)).append("</h1><p>")
                    .append(Html.escape(copy.confirmedRecords)).append(" · ")
                    .append(Html.escape(selectedLabel)).append("</p></header>")
                append("<nav class=\"tabs\" aria-label=\"").append(Html.escape(copy.logs)).append("\">")
                tab(copy.meals, "/logs?kind=meal", filter == BrowserLogFilter.MEALS)
                tab(copy.recipes, "/logs?kind=recipe", filter == BrowserLogFilter.RECIPES)
                tab(copy.workouts, "/logs?kind=workout", filter == BrowserLogFilter.WORKOUTS)
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
                        if (log.note.isNotBlank()) {
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
                    BrowserLogFilter.ARCHIVED -> "/logs?kind=archived"
                }
                append(pager(base, pageNumber, result.totalCount, hasQuery = true))
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
        body = buildString {
            append("<main><header><h1>Insights</h1><p>Local metadata only</p></header>")
            if (exportEnabled) {
                append("<p><a href=\"/export\">")
                append(Html.escape(ExportCopy.forLanguage(languageTag).link))
                append("</a></p>")
            }
            append("<dl class=\"summary\">")
            metric("Entries", insights.annotatedEntryCount)
            metric("Manual", insights.manualLayerCount)
            metric("AI", insights.aiLayerCount)
            metric("Local", insights.localLayerCount)
            metric("Tags", insights.tagOccurrenceCount)
            metric("Links", insights.linkCount)
            append("</dl><h2>Connections</h2>")
            val connections = insights.connections.items.take(PAGE_SIZE)
            if (connections.isEmpty()) {
                append("<p class=\"empty\">No metadata yet.</p>")
            } else {
                append("<ol class=\"list\">")
                connections.forEach { item ->
                    append("<li><div class=\"row\"><span><strong>")
                    append(Html.escape(item.label))
                    append("</strong><small>")
                    append(
                        when (item.kind) {
                            BrowserInsightKind.TAG -> "tag"
                            BrowserInsightKind.DATE -> "date link"
                            BrowserInsightKind.ENTRY -> "entry link"
                        },
                    )
                    append("</small></span><span class=\"count\">")
                    append(item.occurrenceCount)
                    append("</span></div></li>")
                }
                append("</ol>")
            }
            append(pager("/insights", pageNumber, insights.connections.totalCount))
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
        body = buildString {
            append("<main><header><h1>Graph</h1><p>Local connections · five per page</p></header>")
            val edges = result.items.take(PAGE_SIZE)
            if (edges.isEmpty()) {
                append("<p class=\"empty\">No connections yet.</p>")
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
            append(pager("/graph", pageNumber, result.totalCount))
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
    ): String {
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
                append("\">Previous</a>")
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
                append("\">Next</a>")
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
            append("<nav class=\"primary\" aria-label=\"Main\"><a href=\"/days\">")
                .append(Html.escape(copy.days)).append("</a><a href=\"/todos\">")
                .append(Html.escape(copy.important)).append("</a><a href=\"/logs\">")
                .append(Html.escape(copy.logs)).append("</a><a href=\"/insights\">")
                .append(Html.escape(copy.insights)).append("</a><a href=\"/graph\">")
                .append(Html.escape(copy.graph)).append("</a></nav>")
        }
        append(body)
        append("</body></html>")
    }

    private const val DARK_THEME = ":root{color-scheme:dark;--paper:#000;--ink:#fff;--dim:#aaa;--line:rgba(255,255,255,.24);--surface:rgba(0,0,0,.88);--forest-opacity:.58}"
    private const val LIGHT_THEME = ":root{color-scheme:light;--paper:#fff;--ink:#000;--dim:#555;--line:rgba(0,0,0,.25);--surface:rgba(255,255,255,.91);--forest-opacity:.22}"

    private const val STYLES = """
        *{box-sizing:border-box}html{font-family:"Akkurat LL","Helvetica Neue",Arial,sans-serif;color:var(--ink);background:#000}
        body{margin:0;min-height:100vh;font-size:18px;line-height:1.4;background:var(--paper)}body:before{content:"";position:fixed;inset:0;background:url('/assets/forest.webp') center/cover no-repeat;filter:grayscale(1) contrast(1.08);opacity:var(--forest-opacity);pointer-events:none}
        main,.primary{position:relative;z-index:1;width:min(calc(100% - 32px),820px);margin:0 auto;padding:24px;background:var(--surface)}
        main{min-height:calc(100vh - 76px);padding-top:34px;padding-bottom:42px}.primary{position:sticky;top:0;z-index:2;display:flex;gap:30px;padding-top:20px;padding-bottom:20px;border-bottom:1px solid var(--line);white-space:nowrap;overflow-x:auto}
        a{color:var(--ink);text-decoration:underline;text-underline-offset:4px;text-decoration-thickness:1px}header{padding:18px 0 26px}
        h1{font-size:32px;line-height:1.1;margin:8px 0}header p,.quiet,small{color:var(--dim)}.back{display:inline-block;margin-bottom:16px}
        .list{list-style:none;margin:0;padding:0;border-top:1px solid var(--line)}.list>li{min-height:92px;border-bottom:1px solid var(--line)}
        .row,.entry{display:flex;width:100%;min-height:92px;padding:18px 0;justify-content:space-between;gap:24px;align-items:center}
        .row{text-decoration:none}.row small{display:block;margin-top:5px}.count{font-variant-numeric:tabular-nums}
        .entry{display:block}.entry p{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.entry small{display:block;margin-top:10px}
        .history{margin-top:18px}.history summary{cursor:pointer;color:var(--dim)}.history ol{margin:14px 0 0;padding-left:24px}.history li{padding:10px 0}.history li p{margin-top:5px}
        audio{display:block;width:100%;margin-top:14px}.entry img{display:block;width:100%;height:auto;max-height:520px;object-fit:contain;margin-top:14px}.empty{padding:36px 0}
        .pager{display:grid;grid-template-columns:1fr auto 1fr;gap:20px;padding:28px 0;align-items:center}.pager>*:last-child{text-align:right}
        .tabs{display:flex;gap:24px;margin-bottom:28px;padding-bottom:4px;overflow-x:auto;white-space:nowrap}.tabs [aria-current=page]{font-weight:bold;text-decoration-thickness:2px}
        .log-header{display:flex;justify-content:space-between;align-items:baseline;gap:20px;padding:0}.log-header h2{margin:0;font-size:22px}.log-header time{color:var(--dim);font-variant-numeric:tabular-nums;white-space:nowrap}.log-note{margin-top:12px!important}.log-parts{list-style:none;margin:18px 0 0;padding:0}.log-parts>li{padding:10px 0;border-top:1px solid var(--line)}.log-parts small{display:block;margin-top:5px}.log-footer{display:flex;justify-content:space-between;gap:20px;margin-top:18px;color:var(--dim);font-size:14px}.log-footer:empty{display:none}
        h2{font-size:22px;margin:34px 0 10px}.summary{display:grid;grid-template-columns:repeat(5,1fr);gap:16px;margin:0}.summary div{min-width:0}.summary dt{color:var(--dim);font-size:14px}.summary dd{font-size:24px;margin:3px 0 0;font-variant-numeric:tabular-nums}
        .connection-graph{display:block;width:100%;height:auto;overflow:visible}.connection-graph circle{fill:var(--ink)}.edge-line{stroke:var(--dim);stroke-width:2}.node-label{fill:var(--ink);font-size:18px;font-weight:bold}.node-detail,.edge-source{fill:var(--dim);font-size:14px}.edge-label{fill:var(--ink);font-size:14px}.connection-graph a{text-decoration:underline}.graph-note{margin-top:18px}
        form{border-top:2px solid var(--ink);padding-top:24px}label{display:block;margin-bottom:8px}input,button{font:inherit;border:2px solid var(--ink);background:var(--paper);color:var(--ink);border-radius:0;padding:12px}
        input{width:100%;letter-spacing:.2em}input:focus-visible,button:focus-visible,a:focus-visible,summary:focus-visible{outline:2px solid var(--ink);outline-offset:3px}button{width:100%;margin-top:16px;font-weight:bold}.message{border:2px solid var(--ink);padding:12px}
        @media(max-width:520px){main,.primary{width:100%;padding-left:18px;padding-right:18px}body{font-size:17px}.primary{gap:20px}.summary{grid-template-columns:repeat(3,1fr)}.log-header{display:block}.log-header time{display:block;margin-top:6px}.log-footer{display:block}.log-footer>*{display:block;margin-top:8px}}
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
    val archived: String,
    val noLogs: String,
    val sourceNote: String,
    val versions: String,
    val browserView: String,
    val accessCode: String,
    val continueLabel: String,
    val useCode: String,
) {
    companion object {
        fun forLanguage(languageTag: String): BrowserWebCopy = when (languageTag.substringBefore('-').lowercase()) {
            "lv" -> BrowserWebCopy(
                "Dienas", "Svarīgais", "Žurnāli", "Ieskati", "Grafiks",
                "Apstiprināti ieraksti", "Ēdienreizes", "Receptes", "Treniņi", "Arhīvs",
                "Te vēl nekas nav saglabāts.", "avota piezīme", "versijas",
                "Pārlūka skats", "Piekļuves kods", "Turpināt", "Ievadi tālrunī redzamo vienreizējo kodu.",
            )
            "et" -> BrowserWebCopy(
                "Päevad", "Oluline", "Logid", "Ülevaade", "Graafik",
                "Kinnitatud kirjed", "Toidukorrad", "Retseptid", "Treeningud", "Arhiiv",
                "Siin pole veel midagi salvestatud.", "lähtekirje", "versiooni",
                "Brauserivaade", "Pääsukood", "Jätka", "Sisesta telefonis kuvatav ühekordne kood.",
            )
            "lt" -> BrowserWebCopy(
                "Dienos", "Svarbu", "Žurnalai", "Įžvalgos", "Grafas",
                "Patvirtinti įrašai", "Valgiai", "Receptai", "Treniruotės", "Archyvas",
                "Čia dar nieko neišsaugota.", "šaltinio pastaba", "versijos",
                "Naršyklės rodinys", "Prieigos kodas", "Tęsti", "Įveskite telefone rodomą vienkartinį kodą.",
            )
            "fi" -> BrowserWebCopy(
                "Päivät", "Tärkeät", "Lokit", "Kooste", "Verkko",
                "Vahvistetut kirjaukset", "Ateriat", "Reseptit", "Harjoitukset", "Arkisto",
                "Ei vielä tallennettuja kirjauksia.", "lähdemuistiinpano", "versiota",
                "Selainnäkymä", "Käyttökoodi", "Jatka", "Syötä puhelimessa näkyvä kertakäyttökoodi.",
            )
            "sv" -> BrowserWebCopy(
                "Dagar", "Viktigt", "Loggar", "Insikter", "Graf",
                "Bekräftade poster", "Måltider", "Recept", "Träning", "Arkiv",
                "Inget sparat här ännu.", "källanteckning", "versioner",
                "Webbläsarvy", "Åtkomstkod", "Fortsätt", "Ange engångskoden som visas på telefonen.",
            )
            "de" -> BrowserWebCopy(
                "Tage", "Wichtig", "Protokolle", "Einblicke", "Graph",
                "Bestätigte Einträge", "Mahlzeiten", "Rezepte", "Training", "Archiv",
                "Noch nichts gespeichert.", "Quellnotiz", "Versionen",
                "Browseransicht", "Zugangscode", "Weiter", "Gib den einmaligen Code vom Telefon ein.",
            )
            "sk" -> BrowserWebCopy(
                "Dni", "Dôležité", "Záznamy", "Prehľad", "Graf",
                "Potvrdené záznamy", "Jedlá", "Recepty", "Tréningy", "Archív",
                "Zatiaľ tu nič nie je uložené.", "zdrojová poznámka", "verzie",
                "Zobrazenie v prehliadači", "Prístupový kód", "Pokračovať", "Zadajte jednorazový kód zobrazený v telefóne.",
            )
            else -> BrowserWebCopy(
                "Days", "Important", "Logs", "Insights", "Graph",
                "Confirmed records", "Meals", "Recipes", "Workouts", "Archived",
                "Nothing saved here yet.", "source note", "versions",
                "Browser view", "Access code", "Continue", "Use the one-time code shown on your phone.",
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
                "The ZIP contains daily notes, Important items, meal and workout logs, tags, dates, links, and the complete edit history, including every earlier wording. Audio and photos are not included.",
                "Plaintext export.",
                "It is not encrypted and travels over plain HTTP on this local network. Anyone able to observe the network can read or replace it. Save it only to a device you trust and delete it when finished.",
                "I understand — download vault (.zip)", "← Insights",
            ),
            "lv" to ExportCopy(
                "Eksports analīzei", "Tavi dati, tavs MI", "Eksportēt analīzei →",
                "ZIP failā ir dienas piezīmes, svarīgie ieraksti, ēdienreižu un treniņu žurnāli, atzīmes, datumi, saites un pilna labojumu vēsture, ieskaitot visus iepriekšējos teksta variantus. Audio un fotoattēli nav iekļauti.",
                "Nešifrēts eksports.",
                "Tas nav šifrēts un tiek pārsūtīts pa parastu HTTP šajā lokālajā tīklā. Ikviens, kas var novērot tīklu, var to izlasīt vai aizstāt. Saglabā tikai uzticamā ierīcē un pēc lietošanas izdzēs.",
                "Es saprotu — lejupielādēt arhīvu (.zip)", "← Ieskati",
            ),
            "et" to ExportCopy(
                "Eksport analüüsiks", "Sinu andmed, sinu tehisaru", "Ekspordi analüüsiks →",
                "ZIP sisaldab päevamärkmeid, olulisi üksusi, söögi- ja treeningulogisid, silte, kuupäevi, linke ning täielikku muutmisajalugu koos kõigi varasemate sõnastustega. Heli ja fotosid ei lisata.",
                "Krüpteerimata eksport.",
                "See liigub selles kohalikus võrgus tavalise HTTP kaudu. Igaüks, kes võrku jälgib, võib seda lugeda või muuta. Salvesta ainult usaldusväärsesse seadmesse ja kustuta pärast kasutamist.",
                "Saan aru — laadi varamu alla (.zip)", "← Ülevaade",
            ),
            "lt" to ExportCopy(
                "Eksportas analizei", "Jūsų duomenys, jūsų DI", "Eksportuoti analizei →",
                "ZIP faile yra dienos užrašai, svarbūs įrašai, maisto ir treniruočių žurnalai, žymos, datos, nuorodos ir visa taisymų istorija, įskaitant ankstesnes formuluotes. Garso įrašai ir nuotraukos neįtraukiami.",
                "Nešifruotas eksportas.",
                "Jis perduodamas paprastu HTTP šiame vietiniame tinkle. Tinklą stebintys asmenys gali jį perskaityti arba pakeisti. Saugokite tik patikimame įrenginyje ir panaudoję ištrinkite.",
                "Suprantu — atsisiųsti saugyklą (.zip)", "← Įžvalgos",
            ),
            "fi" to ExportCopy(
                "Vienti analyysiin", "Sinun tietosi, sinun tekoälysi", "Vie analyysiin →",
                "ZIP sisältää päivämuistiinpanot, tärkeät kohteet, ruoka- ja harjoituslokit, tunnisteet, päivämäärät, linkit sekä koko muokkaushistorian kaikkine aiempine sanamuotoineen. Ääntä ja kuvia ei sisällytetä.",
                "Salaamaton vienti.",
                "Se siirtyy tavallisella HTTP-yhteydellä tässä lähiverkossa. Verkon liikennettä seuraava voi lukea tai muuttaa sitä. Tallenna vain luotettuun laitteeseen ja poista käytön jälkeen.",
                "Ymmärrän — lataa holvi (.zip)", "← Kooste",
            ),
            "sv" to ExportCopy(
                "Export för analys", "Dina data, din AI", "Exportera för analys →",
                "ZIP-filen innehåller dagliga anteckningar, viktiga poster, mat- och träningsloggar, taggar, datum, länkar och fullständig redigeringshistorik med alla tidigare formuleringar. Ljud och foton ingår inte.",
                "Okrypterad export.",
                "Den skickas via vanlig HTTP i det lokala nätverket. Den som kan övervaka nätverket kan läsa eller ersätta den. Spara endast på en betrodd enhet och radera efter användning.",
                "Jag förstår — hämta valv (.zip)", "← Insikter",
            ),
            "de" to ExportCopy(
                "Export zur Analyse", "Deine Daten, deine KI", "Zur Analyse exportieren →",
                "Die ZIP-Datei enthält Tagesnotizen, wichtige Einträge, Essens- und Trainingsprotokolle, Tags, Daten, Verknüpfungen und den vollständigen Bearbeitungsverlauf mit allen früheren Formulierungen. Audio und Fotos sind nicht enthalten.",
                "Unverschlüsselter Export.",
                "Die Übertragung erfolgt über einfaches HTTP in diesem lokalen Netzwerk. Wer den Netzwerkverkehr beobachten kann, kann die Datei lesen oder ersetzen. Nur auf einem vertrauenswürdigen Gerät speichern und danach löschen.",
                "Verstanden — Vault herunterladen (.zip)", "← Einblicke",
            ),
            "sk" to ExportCopy(
                "Export na analýzu", "Vaše údaje, vaša AI", "Exportovať na analýzu →",
                "ZIP obsahuje denné poznámky, dôležité položky, záznamy jedál a tréningov, značky, dátumy, odkazy a úplnú históriu úprav vrátane všetkých predchádzajúcich znení. Zvuk a fotografie nie sú zahrnuté.",
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
