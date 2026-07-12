package com.soma.core.paging

const val SOMA_PAGE_SIZE: Int = 5

data class Page<T>(
    val items: List<T>,
    val index: Int,
    val pageSize: Int,
    val totalItems: Int,
    /** Empty collections deliberately expose one stable, empty UI page. */
    val totalPages: Int,
) {
    val hasPrevious: Boolean
        get() = index > 0

    val hasNext: Boolean
        get() = index + 1 < totalPages
}

object FiveItemPaging {
    fun pageCount(itemCount: Int): Int {
        require(itemCount >= 0) { "Item count must not be negative" }
        return maxOf(1, (itemCount + SOMA_PAGE_SIZE - 1) / SOMA_PAGE_SIZE)
    }

    fun clampPage(requestedPage: Int, itemCount: Int): Int =
        requestedPage.coerceIn(0, pageCount(itemCount) - 1)

    fun pageForItem(itemIndex: Int): Int {
        require(itemIndex >= 0) { "Item index must not be negative" }
        return itemIndex / SOMA_PAGE_SIZE
    }

    fun <T> page(items: List<T>, requestedPage: Int): Page<T> {
        val totalPages = pageCount(items.size)
        val index = requestedPage.coerceIn(0, totalPages - 1)
        val from = index * SOMA_PAGE_SIZE
        val to = minOf(from + SOMA_PAGE_SIZE, items.size)
        return Page(
            items = items.subList(from, to),
            index = index,
            pageSize = SOMA_PAGE_SIZE,
            totalItems = items.size,
            totalPages = totalPages,
        )
    }
}

fun <T> List<T>.fiveItemPage(pageIndex: Int): Page<T> =
    FiveItemPaging.page(this, pageIndex)
