package com.soma.core.policy

import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoStalenessPolicyTest {
    private val vienna = ZoneId.of("Europe/Vienna")

    @Test
    fun `todo becomes stale on the thirtieth local calendar day`() {
        val touched = localInstant(2026, 3, 1)
        val todo = todo(touchedAt = touched)
        val before = localInstant(2026, 3, 30)
        val threshold = localInstant(2026, 3, 31)
        val policy = TodoStalenessPolicy(Clock.fixed(threshold, ZoneOffset.UTC), vienna)

        assertFalse(policy.isStale(todo, before))
        assertTrue(policy.isStale(todo, threshold))
        assertTrue(policy.shouldPrompt(todo, threshold))
    }

    @Test
    fun `DST transition does not change the thirty day boundary`() {
        val touched = ZonedDateTime.of(
            LocalDate.of(2026, 3, 20),
            LocalTime.of(23, 30),
            vienna,
        ).toInstant()
        val todo = todo(touchedAt = touched)
        val april18 = localInstant(2026, 4, 18)
        val april19 = localInstant(2026, 4, 19)
        val policy = TodoStalenessPolicy(Clock.fixed(april19, ZoneOffset.UTC), vienna)

        assertFalse(policy.isStale(todo, april18))
        assertTrue(policy.isStale(todo, april19))
    }

    @Test
    fun `done and archived todos never prompt`() {
        val created = Instant.parse("2026-01-01T00:00:00Z")
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val policy = TodoStalenessPolicy(Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC)
        val open = todo(touchedAt = created)

        assertFalse(policy.shouldPrompt(open.markDone(created.plusSeconds(1)), now))
        assertFalse(policy.shouldPrompt(open.archive(created.plusSeconds(1)), now))
    }

    @Test
    fun `showing prompt is recorded once without touching the todo`() {
        val touched = Instant.parse("2026-01-01T12:00:00Z")
        val now = Instant.parse("2026-02-01T12:00:00Z")
        val policy = TodoStalenessPolicy(Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC)
        val original = todo(touchedAt = touched)

        val shown = policy.markPromptShown(original)

        assertEquals(touched, shown.lastTouchedAt)
        assertEquals(now, shown.stalePromptShownAt)
        assertFalse(policy.shouldPrompt(shown))
    }

    @Test
    fun `keep remains open and resets last touch`() {
        val touched = Instant.parse("2026-01-01T12:00:00Z")
        val now = Instant.parse("2026-02-01T12:00:00Z")
        val policy = TodoStalenessPolicy(Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC)

        val kept = policy.keep(todo(touchedAt = touched))

        assertEquals(TodoState.OPEN, kept.state)
        assertEquals(now, kept.lastTouchedAt)
        assertEquals(now, kept.stalePromptShownAt)
        assertFalse(policy.isStale(kept))
    }

    @Test
    fun `let go archives silently`() {
        val touched = Instant.parse("2026-01-01T12:00:00Z")
        val now = Instant.parse("2026-02-01T12:00:00Z")
        val policy = TodoStalenessPolicy(Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC)

        val archived = policy.letGo(todo(touchedAt = touched))

        assertEquals(TodoState.ARCHIVED, archived.state)
        assertEquals(now, archived.closedAt)
    }

    @Test
    fun `oldest first comparator is deterministic for equal timestamps`() {
        val firstTime = Instant.parse("2026-01-01T00:00:00Z")
        val later = Instant.parse("2026-01-02T00:00:00Z")
        val todos = listOf(
            todo(id = "c", touchedAt = later),
            todo(id = "b", touchedAt = firstTime),
            todo(id = "a", touchedAt = firstTime),
        )

        assertEquals(listOf("a", "b", "c"), todos.sortedWith(OLDEST_OPEN_TODO_FIRST).map(Todo::id))
    }

    private fun localInstant(year: Int, month: Int, day: Int): Instant = ZonedDateTime.of(
        LocalDate.of(year, month, day),
        LocalTime.NOON,
        vienna,
    ).toInstant()

    private fun todo(
        id: String = "todo-1",
        touchedAt: Instant,
    ): Todo = Todo(
        id = id,
        text = "Call Ada",
        createdAt = touchedAt,
        updatedAt = touchedAt,
        lastTouchedAt = touchedAt,
    )
}
