package com.soma.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.core.model.NoteEntry
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DeletedItemsScreen(
    entries: List<NoteEntry>,
    onRestore: (NoteEntry) -> Unit,
    onOptions: (NoteEntry) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.deleted_items), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                EmptyHint(stringResource(R.string.deleted_items_empty))
            } else {
                PagedList(entries, resetKey = entries.map(NoteEntry::id)) { entry ->
                    DeletedItemRow(
                        entry = entry,
                        onRestore = { onRestore(entry) },
                        onOptions = { onOptions(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeletedItemRow(
    entry: NoteEntry,
    onRestore: () -> Unit,
    onOptions: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().then(
            tapLongModifier(onRestore, onOptions, stringResource(R.string.restore_deleted_item)),
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.text.ifBlank {
                    stringResource(if (entry.image != null) R.string.photo_title else R.string.voice_note)
                },
                color = Ink,
                fontSize = 24.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    when {
                        entry.deletedAt != null -> R.string.entry_removed
                        entry.imageDeletedAt != null -> R.string.image_removed
                        else -> R.string.audio_removed
                    },
                ),
                color = DimInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
            )
        }
        Text(
            entry.noteDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)),
            color = DimInk,
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
fun DeletedItemOptionsScreen(
    busy: Boolean,
    failed: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.deleted_item), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(listOf(DeletedAction.RESTORE, DeletedAction.PURGE)) { action ->
                SettingsItem(
                    label = when {
                        busy -> stringResource(R.string.working)
                        failed -> stringResource(R.string.delete_action_failed)
                        action == DeletedAction.RESTORE -> stringResource(R.string.restore_deleted_item)
                        else -> stringResource(R.string.delete_forever)
                    },
                    onClick = if (busy) null else when (action) {
                        DeletedAction.RESTORE -> onRestore
                        DeletedAction.PURGE -> onPurge
                    },
                )
            }
        }
    }
}

private enum class DeletedAction { RESTORE, PURGE }
