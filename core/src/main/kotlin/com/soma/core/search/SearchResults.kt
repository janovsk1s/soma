package com.soma.core.search

import com.soma.core.model.DailyNote
import com.soma.core.model.LogRecord
import com.soma.core.model.NoteEntry
import com.soma.core.model.Todo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class SearchResultKind {
    ENTRY,
    IMPORTANT,
    LOG,
}

/** One hit, newest-first sortable, carrying the object needed to open it. */
data class SearchResult(
    val kind: SearchResultKind,
    val at: Instant,
    val date: LocalDate,
    val match: SearchMatch,
    val entry: NoteEntry? = null,
    val todo: Todo? = null,
    val log: LogRecord? = null,
)

/**
 * Brute-force search over already-decrypted content. Personal-notes volume
 * makes an index unnecessary; scanning everything keeps deleted entries out,
 * ordering global, and no derived plaintext at rest.
 */
object SearchResults {
    const val MAX_RESULTS = 200

    fun searchAll(
        notes: List<DailyNote>,
        todos: List<Todo>,
        logs: List<LogRecord>,
        query: String,
        zone: ZoneId,
    ): List<SearchResult> {
        val folded = FoldedText.foldQuery(query)
        if (folded.isEmpty()) return emptyList()
        val results = mutableListOf<SearchResult>()
        notes.forEach { note ->
            note.entries.forEach { entry ->
                if (!entry.isDeleted) {
                    TextSearch.match(entry.text, folded)?.let { match ->
                        results += SearchResult(
                            kind = SearchResultKind.ENTRY,
                            at = entry.createdAt,
                            date = note.date,
                            match = match,
                            entry = entry,
                        )
                    }
                }
            }
        }
        todos.forEach { todo ->
            TextSearch.match(todo.text, folded)?.let { match ->
                results += SearchResult(
                    kind = SearchResultKind.IMPORTANT,
                    at = todo.createdAt,
                    date = todo.createdAt.atZone(zone).toLocalDate(),
                    match = match,
                    todo = todo,
                )
            }
        }
        logs.forEach { log ->
            val text = if (log.note.isBlank()) log.title else log.title + "\n" + log.note
            TextSearch.match(text, folded)?.let { match ->
                results += SearchResult(
                    kind = SearchResultKind.LOG,
                    at = log.occurredAt,
                    date = log.occurredAt.atZone(zone).toLocalDate(),
                    match = match,
                    log = log,
                )
            }
        }
        return results.sortedByDescending(SearchResult::at).take(MAX_RESULTS)
    }
}
