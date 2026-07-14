package com.soma.app

import com.soma.core.model.EntryLinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMetadataPolicyTest {
    @Test
    fun `provider metadata is normalized bounded and deduplicated`() {
        val result = normalizeCloudMetadata(
            rawTags = listOf(" Milk Rice ", "#milk-rice", "Rīga", "!!!") +
                (1..12).map { "topic $it" },
            rawDateLinks = listOf(
                "2026-07-14" to "mentioned on",
                "2026-07-14" to "mentioned on",
                "14.07.2026" to "invalid date",
            ),
        )

        assertEquals("milk-rice", result.tags.first())
        assertTrue("rīga" in result.tags)
        assertEquals(8, result.tags.size)
        assertEquals(1, result.links.size)
        assertEquals(EntryLinkKind.DATE, result.links.single().kind)
        assertEquals("mentioned-on", result.links.single().relation)
    }

    @Test
    fun `empty safe provider response stays distinguishable from failure`() {
        assertEquals(CloudMetadataResult(emptyList(), emptyList()), normalizeCloudMetadata(emptyList(), emptyList()))
    }
}
