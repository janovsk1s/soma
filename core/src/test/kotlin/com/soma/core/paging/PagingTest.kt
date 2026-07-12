package com.soma.core.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingTest {
    @Test
    fun `empty list has one stable empty page`() {
        val page = emptyList<Int>().fiveItemPage(9)

        assertEquals(emptyList<Int>(), page.items)
        assertEquals(0, page.index)
        assertEquals(1, page.totalPages)
        assertFalse(page.hasPrevious)
        assertFalse(page.hasNext)
    }

    @Test
    fun `exact page contains five items`() {
        val page = (1..5).toList().fiveItemPage(0)

        assertEquals((1..5).toList(), page.items)
        assertEquals(5, page.pageSize)
        assertEquals(1, page.totalPages)
    }

    @Test
    fun `sixth item starts a second page`() {
        val first = (1..6).toList().fiveItemPage(0)
        val second = (1..6).toList().fiveItemPage(1)

        assertEquals((1..5).toList(), first.items)
        assertTrue(first.hasNext)
        assertEquals(listOf(6), second.items)
        assertTrue(second.hasPrevious)
        assertFalse(second.hasNext)
    }

    @Test
    fun `requested pages clamp predictably`() {
        val items = (1..12).toList()

        assertEquals(0, items.fiveItemPage(-10).index)
        assertEquals((1..5).toList(), items.fiveItemPage(-10).items)
        assertEquals(2, items.fiveItemPage(99).index)
        assertEquals(listOf(11, 12), items.fiveItemPage(99).items)
    }

    @Test
    fun `page counts and item locations use fixed groups of five`() {
        assertEquals(1, FiveItemPaging.pageCount(0))
        assertEquals(1, FiveItemPaging.pageCount(5))
        assertEquals(2, FiveItemPaging.pageCount(6))
        assertEquals(3, FiveItemPaging.pageCount(15))
        assertEquals(0, FiveItemPaging.pageForItem(4))
        assertEquals(1, FiveItemPaging.pageForItem(5))
        assertEquals(3, FiveItemPaging.pageForItem(19))
    }
}
