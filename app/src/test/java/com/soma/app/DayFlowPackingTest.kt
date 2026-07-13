package com.soma.app

import com.soma.core.model.NoteEntry
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayFlowPackingTest {
    private fun block(heightPx: Int, spacingPx: Int = 10, index: Int = 0): FlowBlock = FlowBlock(
        entry = NoteEntry.text(
            id = "entry-$heightPx-$index",
            noteDate = LocalDate.of(2026, 7, 13),
            position = index,
            text = "x",
            createdAt = Instant.EPOCH,
        ),
        text = "x",
        maxLines = 1,
        heightPx = heightPx,
        spacingPx = spacingPx,
    )

    @Test
    fun `blocks that fit share one page`() {
        val pages = packBlocks(listOf(block(100), block(100, index = 1), block(100, index = 2)), 400)
        assertEquals(1, pages.size)
        assertEquals(3, pages.single().size)
    }

    @Test
    fun `overflow starts a new page at an entry boundary`() {
        val pages = packBlocks(listOf(block(300), block(300, index = 1)), 400)
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].size)
        assertEquals(1, pages[1].size)
    }

    @Test
    fun `spacing between blocks counts toward the page`() {
        // 200 + 10 + 200 > 400, so the second block moves on despite raw fit.
        val pages = packBlocks(listOf(block(200), block(200, index = 1)), 400)
        assertEquals(2, pages.size)
    }

    @Test
    fun `an oversize block still gets a page of its own`() {
        val pages = packBlocks(listOf(block(50), block(900, index = 1), block(50, index = 2)), 400)
        assertEquals(3, pages.size)
        assertTrue(pages[1].single().heightPx == 900)
    }

    @Test
    fun `empty input packs to no pages`() {
        assertEquals(emptyList<List<FlowBlock>>(), packBlocks(emptyList(), 400))
        assertEquals(emptyList<List<FlowBlock>>(), packBlocks(listOf(block(10)), 0))
    }
}
