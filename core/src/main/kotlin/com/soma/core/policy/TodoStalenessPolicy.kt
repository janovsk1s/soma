package com.soma.core.policy

import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Calendar-day-based aging avoids a DST day making a prompt appear an hour early or late. */
class TodoStalenessPolicy(
    private val clock: Clock,
    private val zoneId: ZoneId,
    val staleAfterDays: Long = DEFAULT_STALE_AFTER_DAYS,
) {
    init {
        require(staleAfterDays > 0) { "Stale threshold must be positive" }
    }

    fun isStale(todo: Todo, now: Instant = clock.instant()): Boolean {
        if (todo.state != TodoState.OPEN) return false
        val touchedDate = todo.lastTouchedAt.atZone(zoneId).toLocalDate()
        val currentDate = now.atZone(zoneId).toLocalDate()
        return !currentDate.isBefore(touchedDate.plusDays(staleAfterDays))
    }

    fun shouldPrompt(todo: Todo, now: Instant = clock.instant()): Boolean =
        todo.stalePromptShownAt == null && isStale(todo, now)

    /** Records display of the single quiet prompt without treating display as user activity. */
    fun markPromptShown(todo: Todo, at: Instant = clock.instant()): Todo {
        require(shouldPrompt(todo, at)) { "Todo is not eligible for a stale prompt" }
        return todo.copy(
            updatedAt = at,
            stalePromptShownAt = at,
        )
    }

    /** "Keep" is a deliberate touch; the todo remains open and ages from now. */
    fun keep(todo: Todo, at: Instant = clock.instant()): Todo {
        require(todo.state == TodoState.OPEN) { "Only open todos can be kept" }
        return todo.copy(
            updatedAt = at,
            lastTouchedAt = at,
            stalePromptShownAt = todo.stalePromptShownAt ?: at,
        )
    }

    /** "Let go" silently archives the todo. */
    fun letGo(todo: Todo, at: Instant = clock.instant()): Todo = todo.archive(at)

    companion object {
        const val DEFAULT_STALE_AFTER_DAYS: Long = 30
    }
}

val OLDEST_OPEN_TODO_FIRST: Comparator<Todo> =
    compareBy<Todo>(Todo::createdAt).thenBy(Todo::id)
