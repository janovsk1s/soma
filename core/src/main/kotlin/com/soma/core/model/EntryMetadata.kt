package com.soma.core.model

import java.time.Instant
import java.time.LocalDate
import java.util.Locale

enum class MetadataSource {
    MANUAL,
    AI,

    /** Deterministic on-device enrichment (hashtags, dates); no network or model. */
    LOCAL,
}

enum class EntryLinkKind {
    ENTRY,
    DATE,
    TAG,
}

/** A derived or manual connection. It never changes the authored entry text. */
data class EntryLink(
    val kind: EntryLinkKind,
    val target: String,
    val relation: String? = null,
) {
    init {
        require(target.isNotBlank() && target.length <= MAX_TARGET_LENGTH && target.none(Char::isISOControl)) {
            "Metadata link target is invalid"
        }
        when (kind) {
            EntryLinkKind.DATE -> require(runCatching { LocalDate.parse(target) }.isSuccess) {
                "Metadata date link must use ISO-8601"
            }
            EntryLinkKind.TAG -> require(normalizeMetadataTag(target) == target) {
                "Metadata tag link must be normalized"
            }
            EntryLinkKind.ENTRY -> Unit
        }
        require(relation == null || isValidMetadataToken(relation, MAX_RELATION_LENGTH)) {
            "Metadata link relation is invalid"
        }
    }

    companion object {
        const val MAX_TARGET_LENGTH = 128
        const val MAX_RELATION_LENGTH = 40
    }
}

/** One independently replaceable metadata layer per source for an entry. */
data class EntryMetadata(
    val entryId: String,
    val tags: List<String>,
    val links: List<EntryLink>,
    val derivedAt: Instant,
    val source: MetadataSource,
) {
    init {
        require(entryId.isNotBlank()) { "Metadata entry id must not be blank" }
        require(tags.size <= MAX_TAGS && tags.distinct().size == tags.size) {
            "Metadata tags must be unique and bounded"
        }
        require(tags.all { normalizeMetadataTag(it) == it }) { "Metadata tags must be normalized" }
        require(links.size <= MAX_LINKS && links.distinct().size == links.size) {
            "Metadata links must be unique and bounded"
        }
    }

    companion object {
        const val MAX_TAGS = 24
        const val MAX_LINKS = 32
    }
}

fun normalizeMetadataTag(value: String): String? {
    val normalized = value.trim().removePrefix("#").lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), "-")
        .trim('-')
    return normalized.takeIf { isValidMetadataToken(it, MAX_TAG_LENGTH) }
}

private fun isValidMetadataToken(value: String, maximumLength: Int): Boolean =
    value.isNotBlank() && value.length <= maximumLength && value.all { character ->
        character.isLetterOrDigit() || character == '-' || character == '_'
    }

private const val MAX_TAG_LENGTH = 48
