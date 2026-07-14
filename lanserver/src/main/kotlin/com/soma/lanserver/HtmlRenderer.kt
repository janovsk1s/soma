package com.soma.lanserver

import java.time.LocalDate
import kotlin.math.max

internal object HtmlRenderer {
    fun login(error: String? = null, lightMode: Boolean = false): String = page(
        title = "Browser view",
        navigation = false,
        lightMode = lightMode,
        body = buildString {
            append("<main><header><h1>Soma</h1><p>Browser view</p></header>")
            if (error != null) {
                append("<p class=\"message\" role=\"alert\">")
                append(Html.escape(error))
                append("</p>")
            }
            append(
                """
                <form method="post" action="/auth">
                  <label for="code">Access code</label>
                  <input id="code" name="code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required autofocus>
                  <button type="submit">Continue</button>
                </form>
                <p class="quiet">Use the one-time code shown on your phone.</p></main>
                """.trimIndent(),
            )
        },
    )

    fun days(pageNumber: Int, result: PagedResult<BrowserDay>, lightMode: Boolean = false): String = page(
        title = "Days",
        lightMode = lightMode,
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
    ): String = page(
        title = date.toString(),
        lightMode = lightMode,
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
                    if (entry.kind == BrowserEntryKind.VOICE && entry.audioId != null) {
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
    ): String = page(
        title = "Todos",
        lightMode = lightMode,
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
    ): String = buildString {
        append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>")
        append(Html.escape(title))
        append(" · Soma</title><style>")
        append(if (lightMode) LIGHT_THEME else DARK_THEME)
        append(STYLES)
        append("</style></head><body>")
        if (navigation) {
            append(
                "<nav class=\"primary\" aria-label=\"Main\">" +
                    "<a href=\"/days\">Days</a><a href=\"/todos\">Todos</a></nav>",
            )
        }
        append(body)
        append("</body></html>")
    }

    private const val DARK_THEME = ":root{color-scheme:dark;--paper:#000;--ink:#fff;--dim:#888}"
    private const val LIGHT_THEME = ":root{color-scheme:light;--paper:#fff;--ink:#000;--dim:#555}"

    private const val STYLES = """
        *{box-sizing:border-box}html{font-family:"Akkurat LL","Helvetica Neue",Arial,sans-serif;color:var(--ink);background:var(--paper)}
        body{margin:0;font-size:18px;line-height:1.35}main,.primary{width:min(100%,760px);margin:0 auto;padding:24px}
        .primary{display:flex;gap:32px;padding-top:18px;padding-bottom:18px}
        a{color:var(--ink);text-decoration:underline;text-underline-offset:3px}header{padding:20px 0 24px}
        h1{font-size:32px;line-height:1.1;margin:8px 0}header p,.quiet,small{color:var(--dim)}.back{display:inline-block;margin-bottom:16px}
        .list{list-style:none;margin:0;padding:0}.list>li{min-height:92px}
        .row,.entry{display:flex;width:100%;min-height:92px;padding:18px 0;justify-content:space-between;gap:24px;align-items:center}
        .row{text-decoration:none}.row small{display:block;margin-top:5px}.count{font-variant-numeric:tabular-nums}
        .entry{display:block}.entry p{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.entry small{display:block;margin-top:10px}
        .history{margin-top:18px}.history summary{cursor:pointer;color:var(--dim)}.history ol{margin:14px 0 0;padding-left:24px}.history li{padding:10px 0}.history li p{margin-top:5px}
        audio{display:block;width:100%;margin-top:14px}.entry img{display:block;width:100%;height:auto;max-height:520px;object-fit:contain;margin-top:14px}.empty{padding:36px 0}
        .pager{display:grid;grid-template-columns:1fr auto 1fr;gap:20px;padding:28px 0;align-items:center}.pager>*:last-child{text-align:right}
        .tabs{display:flex;gap:24px;margin-bottom:20px}.tabs [aria-current=page]{font-weight:bold;text-decoration-thickness:2px}
        form{border-top:2px solid var(--ink);padding-top:24px}label{display:block;margin-bottom:8px}input,button{font:inherit;border:2px solid var(--ink);background:var(--paper);color:var(--ink);border-radius:0;padding:12px}
        input{width:100%;letter-spacing:.2em}button{width:100%;margin-top:16px;font-weight:bold}.message{border:2px solid var(--ink);padding:12px}
        @media(max-width:520px){main,.primary{padding-left:18px;padding-right:18px}body{font-size:17px}}
    """
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
