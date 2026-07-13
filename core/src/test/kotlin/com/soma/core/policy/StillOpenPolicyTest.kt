package com.soma.core.policy

import com.soma.core.model.NoteEntry
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.Todo
import com.soma.core.model.ImportantKind
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillOpenPolicyTest {
    private val today = LocalDate.of(2026, 7, 12)
    private val now = Instant.parse("2026-07-12T08:00:00Z")

    @Test
    fun `nothing unresolved means no block`() {
        assertNull(StillOpenPolicy.content(today, emptyList(), emptyList(), null))
    }

    @Test
    fun `todays dismissal hides content without changing it`() {
        val content = StillOpenPolicy.content(
            today = today,
            todos = listOf(todo("open")),
            entries = listOf(entry("marked", today.minusDays(1), returnLater = true)),
            dismissal = StillOpenDismissal(today, now),
        )

        assertNull(content)
    }

    @Test
    fun `old dismissal has no effect on a new day`() {
        val content = StillOpenPolicy.content(
            today = today,
            todos = listOf(todo("open")),
            entries = emptyList(),
            dismissal = StillOpenDismissal(today.minusDays(1), now.minusSeconds(86_400)),
        )

        assertEquals(1, content!!.openTodoCount)
    }

    @Test
    fun `counts only open todos and includes only marked non-future entries`() {
        val done = todo("done").markDone(now)
        val content = StillOpenPolicy.content(
            today = today,
            todos = listOf(todo("open"), done),
            entries = listOf(
                entry("plain", today.minusDays(2), returnLater = false),
                entry("future", today.plusDays(1), returnLater = true),
                entry("marked", today.minusDays(1), returnLater = true),
            ),
            dismissal = null,
        )!!

        assertEquals(1, content.openTodoCount)
        assertEquals(listOf("marked"), content.returnLaterItems.map(ReturnLaterItem::entryId))
        assertEquals(StillOpenTarget.Todos, content.defaultTarget)
    }

    @Test
    fun `marked entries are oldest first and whitespace preview is restrained`() {
        val content = StillOpenPolicy.content(
            today = today,
            todos = emptyList(),
            entries = listOf(
                entry("later", today.minusDays(1), true, position = 2, text = "Later"),
                entry("first", today.minusDays(3), true, text = "  A   calm\nthought that continues  "),
                entry("middle", today.minusDays(1), true, position = 1, text = "Middle"),
            ),
            dismissal = null,
            previewLength = 14,
        )!!

        assertEquals(listOf("first", "middle", "later"), content.returnLaterItems.map { it.entryId })
        assertEquals("A calm thought", content.returnLaterItems.first().preview)
        assertEquals(
            StillOpenTarget.Entry("first", today.minusDays(3)),
            content.defaultTarget,
        )
    }

    @Test
    fun `future show again date snoozes an actionable item`() {
        val content = StillOpenPolicy.content(
            today = today,
            todos = listOf(todo("later").copy(resurfaceOn = today.plusWeeks(1))),
            entries = emptyList(),
            dismissal = null,
        )

        assertNull(content)
    }

    @Test
    fun `due reference returns once and becomes the default target`() {
        val content = StillOpenPolicy.content(
            today = today,
            todos = listOf(
                todo("reference").copy(
                    text = "Booking code H7K2P9",
                    kind = ImportantKind.REFERENCE,
                    resurfaceOn = today.minusDays(1),
                ),
            ),
            entries = emptyList(),
            dismissal = null,
        )!!

        assertEquals(0, content.openTodoCount)
        assertEquals(listOf("reference"), content.resurfacingItems.map { it.todoId })
        assertEquals(StillOpenTarget.Important("reference"), content.defaultTarget)
    }

    @Test
    fun `unscheduled references stay safely in Important without becoming open work`() {
        val content = StillOpenPolicy.content(
            today = today,
            todos = listOf(todo("reference").copy(kind = ImportantKind.REFERENCE)),
            entries = emptyList(),
            dismissal = null,
        )

        assertNull(content)
    }

    private fun todo(id: String): Todo = Todo(
        id = id,
        text = "Open item",
        createdAt = now.minusSeconds(100),
        updatedAt = now.minusSeconds(100),
    )

    private fun entry(
        id: String,
        date: LocalDate,
        returnLater: Boolean,
        position: Int = 0,
        text: String = "Return to this",
    ): NoteEntry = NoteEntry.text(
        id = id,
        noteDate = date,
        position = position,
        text = text,
        createdAt = now.minusSeconds(100),
    ).copy(returnLater = returnLater)
}
