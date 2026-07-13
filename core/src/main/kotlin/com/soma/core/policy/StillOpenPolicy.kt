package com.soma.core.policy

import com.soma.core.model.NoteEntry
import com.soma.core.model.ImportantKind
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import java.time.LocalDate

data class ReturnLaterItem(
    val entryId: String,
    val noteDate: LocalDate,
    val position: Int,
    val preview: String,
)

data class ResurfacingImportantItem(
    val todoId: String,
    val showAgainOn: LocalDate,
    val preview: String,
)

sealed interface StillOpenTarget {
    data object Todos : StillOpenTarget

    data class Important(val todoId: String) : StillOpenTarget

    data class Entry(
        val entryId: String,
        val noteDate: LocalDate,
    ) : StillOpenTarget
}

data class StillOpenContent(
    val openTodoCount: Int,
    val returnLaterItems: List<ReturnLaterItem>,
    val resurfacingItems: List<ResurfacingImportantItem> = emptyList(),
) {
    init {
        require(openTodoCount >= 0) { "Open todo count must not be negative" }
    }

    val defaultTarget: StillOpenTarget?
        get() = when {
            resurfacingItems.isNotEmpty() -> StillOpenTarget.Important(resurfacingItems.first().todoId)
            openTodoCount > 0 -> StillOpenTarget.Todos
            returnLaterItems.isNotEmpty() -> returnLaterItems.first().let {
                StillOpenTarget.Entry(entryId = it.entryId, noteDate = it.noteDate)
            }
            else -> null
        }
}

object StillOpenPolicy {
    /**
     * Returns null when there is nothing unresolved or the user dismissed today's block.
     * Future-dated entries are ignored defensively; no age or guilt language is produced.
     */
    fun content(
        today: LocalDate,
        todos: Iterable<Todo>,
        entries: Iterable<NoteEntry>,
        dismissal: StillOpenDismissal?,
        previewLength: Int = 80,
    ): StillOpenContent? {
        require(previewLength > 0) { "Preview length must be positive" }
        if (dismissal?.date == today) return null

        val openTodos = todos.filter { it.state == TodoState.OPEN }
        val resurfacingItems = openTodos.asSequence()
            .filter { it.resurfaceOn != null && !it.resurfaceOn.isAfter(today) }
            .sortedWith(compareBy({ it.resurfaceOn }, Todo::createdAt))
            .map { todo ->
                ResurfacingImportantItem(
                    todoId = todo.id,
                    showAgainOn = requireNotNull(todo.resurfaceOn),
                    preview = todo.text.trim().replace(Regex("\\s+"), " ").take(previewLength),
                )
            }
            .toList()
        // References and excerpts are safely kept in Important, but are not
        // unresolved work. They return to Today only when explicitly scheduled.
        // A future show-again date also snoozes an action/list from this count.
        val openTodoCount = openTodos.count {
            it.kind in ACTIONABLE_KINDS && (it.resurfaceOn == null || !it.resurfaceOn.isAfter(today))
        }
        val markedEntries = entries
            .asSequence()
            .filter { it.returnLater && !it.noteDate.isAfter(today) }
            .sortedWith(compareBy(NoteEntry::noteDate, NoteEntry::position))
            .map { entry ->
                ReturnLaterItem(
                    entryId = entry.id,
                    noteDate = entry.noteDate,
                    position = entry.position,
                    preview = entry.text
                        .trim()
                        .replace(Regex("\\s+"), " ")
                        .take(previewLength),
                )
            }
            .toList()

        if (openTodoCount == 0 && markedEntries.isEmpty() && resurfacingItems.isEmpty()) return null
        return StillOpenContent(
            openTodoCount = openTodoCount,
            returnLaterItems = markedEntries,
            resurfacingItems = resurfacingItems,
        )
    }

    private val ACTIONABLE_KINDS = setOf(ImportantKind.ACTION, ImportantKind.LIST)
}
