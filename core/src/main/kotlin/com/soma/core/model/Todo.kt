package com.soma.core.model

import java.time.Instant
import java.time.LocalDate

enum class TodoState {
    OPEN,
    DONE,
    ARCHIVED,
}

/**
 * The user-facing Important section is broader than a task list. Keeping the
 * kind explicit makes manual excerpts and detected lists predictable without
 * forcing them through opaque "smart" behaviour.
 */
enum class ImportantKind {
    ACTION,
    LIST,
    EXCERPT,
    /** A phone, booking, order, tracking, or other deliberate reference number. */
    REFERENCE,
}

data class EntrySource(
    val noteDate: LocalDate,
    val entryId: String,
) {
    init {
        require(entryId.isNotBlank()) { "Source entry id must not be blank" }
    }
}

data class Todo(
    val id: String,
    val text: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastTouchedAt: Instant = updatedAt,
    val state: TodoState = TodoState.OPEN,
    val kind: ImportantKind = ImportantKind.ACTION,
    val source: EntrySource? = null,
    val closedAt: Instant? = null,
    /** Non-null after the one quiet "keep / let go" prompt has been shown. */
    val stalePromptShownAt: Instant? = null,
    /** Optional local day on which this item should be brought back to Today. */
    val resurfaceOn: LocalDate? = null,
) {
    init {
        require(id.isNotBlank()) { "Todo id must not be blank" }
        require(text.isNotBlank()) { "Todo text must not be blank" }
        require(!updatedAt.isBefore(createdAt)) { "Todo update cannot precede creation" }
        require(!lastTouchedAt.isBefore(createdAt)) { "Todo touch cannot precede creation" }
        require((state == TodoState.OPEN) == (closedAt == null)) {
            "Only open todos may have no closedAt timestamp"
        }
    }

    fun markDone(at: Instant): Todo = copy(
        state = TodoState.DONE,
        updatedAt = at,
        lastTouchedAt = at,
        closedAt = at,
        resurfaceOn = null,
    )

    fun reopen(at: Instant): Todo = copy(
        state = TodoState.OPEN,
        updatedAt = at,
        lastTouchedAt = at,
        closedAt = null,
    )

    fun archive(at: Instant): Todo = copy(
        state = TodoState.ARCHIVED,
        updatedAt = at,
        lastTouchedAt = at,
        closedAt = at,
        resurfaceOn = null,
    )

    fun edit(newText: String, at: Instant): Todo = copy(
        text = newText.trim(),
        updatedAt = at,
        lastTouchedAt = at,
    )

    fun showAgainOn(date: LocalDate, at: Instant): Todo = copy(
        resurfaceOn = date,
        updatedAt = at,
        lastTouchedAt = at,
    )

    fun clearShowAgain(at: Instant): Todo = copy(
        resurfaceOn = null,
        updatedAt = at,
        lastTouchedAt = at,
    )
}

enum class TodoSuggestionState {
    PENDING,
    ACCEPTED,
    DISMISSED,
}

enum class TodoSuggestionReason {
    TRIGGER_PHRASE,
    IMPERATIVE,
    LIST_PATTERN,
    REFERENCE_PATTERN,
    AI_EXTRACTED,
}

data class TodoSuggestion(
    val id: String,
    val entryId: String,
    val suggestedText: String,
    val suggestedKind: ImportantKind = ImportantKind.ACTION,
    val language: SupportedLanguage,
    val reason: TodoSuggestionReason,
    val matchedRule: String,
    val state: TodoSuggestionState,
    val createdAt: Instant,
    val resolvedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "Suggestion id must not be blank" }
        require(entryId.isNotBlank()) { "Suggestion entry id must not be blank" }
        require(suggestedText.isNotBlank()) { "Suggested todo text must not be blank" }
        require(matchedRule.isNotBlank()) { "Matched rule must not be blank" }
        require((state == TodoSuggestionState.PENDING) == (resolvedAt == null)) {
            "Only pending suggestions may have no resolvedAt timestamp"
        }
    }
}

data class StillOpenDismissal(
    val date: LocalDate,
    val dismissedAt: Instant,
)
