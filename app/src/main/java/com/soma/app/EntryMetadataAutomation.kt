package com.soma.app

import com.soma.core.model.EntryMetadata
import com.soma.core.model.MetadataSource
import com.soma.core.model.NoteEntry
import com.soma.core.model.SupportedLanguage
import java.time.Clock

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
