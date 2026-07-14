package com.soma.core.metadata

import com.soma.core.model.EntryLink
import com.soma.core.model.EntryLinkKind
import com.soma.core.model.EntryMetadata
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.normalizeMetadataTag
import java.time.LocalDate

/** The tags and links a deterministic, offline pass derived from one entry. */
data class DerivedEntryMetadata(
    val tags: List<String>,
    val links: List<EntryLink>,
)

/**
 * Deterministic, offline entry enrichment for the LOCAL metadata layer. It turns
 * hashtags the user already typed into normalized topic tags and a recognised due
 * date into a DATE link. It uses no network and no model, and deliberately avoids
 * proper-noun guessing (noisy, and every noun is capitalized in German). It never
 * changes the authored text; the output is an additive, replaceable layer.
 */
object LocalMetadataDeriver {

    fun derive(text: String, language: SupportedLanguage, today: LocalDate): DerivedEntryMetadata {
        return derive(text, setOf(language), today)
    }

    fun derive(
        text: String,
        languages: Set<SupportedLanguage>,
        today: LocalDate,
    ): DerivedEntryMetadata {
        if (text.isBlank()) return EMPTY
        val bounded = text.take(MAX_INPUT_CHARS)

        val tags = HASHTAG.findAll(bounded)
            .mapNotNull { normalizeMetadataTag(it.value) }
            .distinct()
            .take(EntryMetadata.MAX_TAGS)
            .toList()

        val links = buildList {
            ImportantResurfaceDeriver.deriveDate(bounded, languages, today)?.let { date ->
                add(EntryLink(kind = EntryLinkKind.DATE, target = date.toString(), relation = "due"))
            }
        }.take(EntryMetadata.MAX_LINKS)

        return DerivedEntryMetadata(tags, links)
    }

    private val EMPTY = DerivedEntryMetadata(emptyList(), emptyList())
    private const val MAX_INPUT_CHARS = 50_000
    private val HASHTAG = Regex("#[\\p{L}\\p{N}_-]+")
}
