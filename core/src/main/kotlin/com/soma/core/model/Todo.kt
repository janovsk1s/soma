package com.soma.core.model

import java.time.Instant
import java.time.LocalDate

enum class TodoState {
    OPEN,
    DONE,
    ARCHIVED,
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
    val source: EntrySource? = null,
    val closedAt: Instant? = null,
    /** Non-null after the one quiet "keep / let go" prompt has been shown. */
    val stalePromptShownAt: Instant? = null,
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
    )

    fun edit(newText: String, at: Instant): Todo = copy(
        text = newText.trim(),
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
    AI_EXTRACTED,
}

data class TodoSuggestion(
    val id: String,
    val entryId: String,
    val suggestedText: String,
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
