package com.soma.app

import com.soma.core.metadata.LocalMetadataDeriver
import com.soma.core.model.EntryMetadata
import com.soma.core.model.MetadataSource
import com.soma.core.model.NoteEntry
import com.soma.core.model.SupportedLanguage
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Derivation is deliberately outside capture persistence. The text is checked
 * again after the provider returns so a slow response can never attach stale
 * metadata after a newer user edit.
 */
internal suspend fun deriveAndPersistAiMetadata(
    app: SomaApplication,
    repositories: SomaRepositories,
    entry: NoteEntry,
    languages: Set<SupportedLanguage>,
    clock: Clock,
): Boolean {
    if (entry.isDeleted || entry.text.isBlank()) return false
    val sourceText = entry.text
    val result = cloudFeatures(app).deriveEntryMetadata(sourceText, languages) ?: return false
    if (!SomaPrefs.aiAutoMetadata(app)) return false
    val current = repositories.notes.getEntry(entry.id)
    if (current == null || current.isDeleted || current.text != sourceText) return false
    if (result.tags.isEmpty() && result.links.isEmpty()) {
        repositories.metadata.delete(entry.id, MetadataSource.AI)
        return true
    }
    return repositories.metadata.upsert(
        EntryMetadata(
            entryId = entry.id,
            tags = result.tags,
            links = result.links,
            derivedAt = clock.instant(),
            source = MetadataSource.AI,
        ),
    )
}

/**
 * The offline counterpart: a deterministic LOCAL layer derived on-device in every
 * flavor, with no network or model. It is independent of the AI layer and, like
 * it, re-checks the text after deriving so a later edit is never overwritten.
 */
internal suspend fun deriveAndPersistLocalMetadata(
    app: SomaApplication,
    repositories: SomaRepositories,
    entry: NoteEntry,
    clock: Clock,
): Boolean {
    if (!SomaPrefs.localAutoMetadata(app)) return false
    return deriveAndPersistLocalMetadata(
        repositories = repositories,
        entry = entry,
        languages = SomaPrefs.speechLanguages(app) + SomaPrefs.language(app),
        clock = clock,
    )
}

/** Repository-only entry point used by the incremental upgrade backfill. */
internal suspend fun deriveAndPersistLocalMetadata(
    repositories: SomaRepositories,
    entry: NoteEntry,
    languages: Set<SupportedLanguage>,
    clock: Clock,
    referenceDate: LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate(),
): Boolean {
    if (entry.isDeleted || entry.text.isBlank()) return false
    val sourceText = entry.text
    val result = LocalMetadataDeriver.derive(sourceText, languages, referenceDate)
    val current = repositories.notes.getEntry(entry.id)
    if (current == null || current.isDeleted || current.text != sourceText) return false
    if (result.tags.isEmpty() && result.links.isEmpty()) {
        repositories.metadata.delete(entry.id, MetadataSource.LOCAL)
        return true
    }
    return repositories.metadata.upsert(
        EntryMetadata(
            entryId = entry.id,
            tags = result.tags,
            links = result.links,
            derivedAt = clock.instant(),
            source = MetadataSource.LOCAL,
        ),
    )
}
