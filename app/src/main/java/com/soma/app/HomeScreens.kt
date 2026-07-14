package com.soma.app

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.NoteEntry
import com.soma.core.model.Todo
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionFallbackReason
import com.soma.core.model.TranscriptionProvenance
import com.soma.core.policy.StillOpenPolicy
import com.soma.core.policy.StillOpenTarget
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun HomeScreen(
    viewModel: SomaViewModel,
    onTodos: () -> Unit,
    onLogs: () -> Unit,
    onResurfacedImportant: (Todo) -> Unit,
    onSettings: () -> Unit,
    onCalendar: () -> Unit,
    onCapture: () -> Unit,
    onPhotoRequested: () -> Unit,
    onPhotoCaption: (NoteEntry) -> Unit,
    onPhotoCommentRecord: (NoteEntry) -> Unit,
    onReadEntry: (NoteEntry) -> Unit,
    onEntryOptions: (NoteEntry) -> Unit,
    onRecordRequested: () -> Unit,
) {
    val date by viewModel.selectedDate.collectAsState()
    val note by viewModel.note.collectAsState()
    val openTodos by viewModel.openTodos.collectAsState()
    val returnLater by viewModel.returnLater.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val recordingEntryId by viewModel.recordingEntryId.collectAsState()
    val recordingUiState by viewModel.recordingUiState.collectAsState()
    val photoCommentEntryId by viewModel.photoCommentEntryId.collectAsState()
    val deletionUndo by viewModel.deletionUndo.collectAsState()
    val photoComment = note?.entries?.firstOrNull { entry ->
        entry.id == photoCommentEntryId && entry.activeImage != null &&
            entry.audio == null && entry.text.isBlank()
    }
    // The one-tap Undo is a brief affordance, not a persistent banner: let it go
    // after a short window so a stale "removed" line never lingers on Today.
    LaunchedEffect(deletionUndo) {
        if (deletionUndo != null) {
            delay(UNDO_VISIBLE_MILLIS)
            viewModel.clearDeletionUndo()
        }
    }
    var stillOpenDismissed by remember(viewModel.today()) { mutableStateOf(false) }
    LaunchedEffect(viewModel.today()) {
        stillOpenDismissed = viewModel.isStillOpenDismissed()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp),
    ) {
        HomeHeader(
            date = date,
            today = viewModel.today(),
            demo = viewModel.isDemo,
            onSettings = onSettings,
            onCalendar = onCalendar,
            onTodos = onTodos,
            onLogs = onLogs,
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().daySwipe(
                canGoNewer = date.isBefore(viewModel.today()),
                onOlder = viewModel::showOlderDay,
                onNewer = viewModel::showNewerDay,
            ),
        ) {
            val stillOpen = if (date == viewModel.today() && !stillOpenDismissed) {
                StillOpenPolicy.content(
                    today = viewModel.today(),
                    todos = openTodos,
                    entries = returnLater,
                    dismissal = null,
                )
            } else {
                null
            }
            if (stillOpen != null) {
                StillOpenBlock(
                    openCount = stillOpen.openTodoCount,
                    returnCount = stillOpen.returnLaterItems.size,
                    resurfacingCount = stillOpen.resurfacingItems.size,
                    onOpen = {
                        val target = stillOpen.defaultTarget
                        val important = (target as? StillOpenTarget.Important)
                            ?.let { open -> openTodos.firstOrNull { it.id == open.todoId } }
                        val entry = (target as? StillOpenTarget.Entry)
                            ?.let { open -> returnLater.firstOrNull { it.id == open.entryId } }
                        if (important != null) {
                            viewModel.acknowledgeResurfacedTodo(important)
                            onResurfacedImportant(important)
                        } else if (entry != null) {
                            viewModel.showDay(entry.noteDate)
                            onReadEntry(entry)
                        } else {
                            onTodos()
                        }
                    },
                    onDismiss = {
                        stillOpenDismissed = true
                        viewModel.dismissStillOpen()
                    },
                )
            }
            val entries = note?.entries.orEmpty()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (entries.isEmpty()) {
                    EmptyHint(stringResource(R.string.entries_empty))
                } else {
                    DayFlowPager(
                        entries = entries,
                        suggestions = suggestions,
                        recordingEntryId = recordingEntryId,
                        resetKey = date,
                        onRead = onReadEntry,
                        onOptions = onEntryOptions,
                        onSuggestion = viewModel::acceptSuggestion,
                    )
                }
            }
        }
        if (deletionUndo != null) {
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp)
                    .then(tapModifier(viewModel::undoLastDeletion, stringResource(R.string.undo_removal))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.removed),
                    color = DimInk,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.undo),
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val idleLabel = stringResource(
                if (photoComment != null) R.string.photo_comment_hint else R.string.entry_hint,
            )
            val recordingLabel = recordingBarLabel(recordingUiState, idleLabel)
            CaptureBar(
                modifier = Modifier.weight(1f),
                placeholder = recordingLabel,
                onOpen = {
                    when (recordingUiState) {
                        RecordingUiState.Idle -> photoComment?.let(onPhotoCaption) ?: onCapture()
                        RecordingUiState.Starting, is RecordingUiState.Recording -> viewModel.stopRecording()
                        RecordingUiState.Saving -> Unit
                    }
                },
                onLongPress = {
                    when (recordingUiState) {
                        RecordingUiState.Idle -> photoComment?.let(onPhotoCommentRecord) ?: onRecordRequested()
                        RecordingUiState.Starting, is RecordingUiState.Recording -> viewModel.stopRecording()
                        RecordingUiState.Saving -> Unit
                    }
                },
                enabled = recordingUiState != RecordingUiState.Saving,
            )
            if (recordingUiState == RecordingUiState.Idle) {
                PlusButton(
                    onClick = {
                        viewModel.clearPhotoCommentPrompt()
                        onCapture()
                    },
                    onLongClick = {
                        viewModel.clearPhotoCommentPrompt()
                        onPhotoRequested()
                    },
                    modifier = Modifier.offset(x = 8.dp),
                )
            } else {
                StopButton(
                    onClick = viewModel::stopRecording,
                    modifier = Modifier.offset(x = 8.dp),
                    enabled = recordingUiState != RecordingUiState.Saving,
                )
            }
        }
    }
}

@Composable
private fun recordingBarLabel(state: RecordingUiState, idleLabel: String): String {
    var elapsedSeconds by remember(state) { mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        val recording = state as? RecordingUiState.Recording ?: return@LaunchedEffect
        while (isActive) {
            elapsedSeconds = (
                (SystemClock.elapsedRealtime() - recording.startedAtElapsedRealtimeMillis) /
                    MILLIS_PER_SECOND
                ).coerceAtLeast(0L)
            delay(RECORDING_TIMER_REFRESH_MILLIS)
        }
    }
    return when (state) {
        RecordingUiState.Idle -> idleLabel
        RecordingUiState.Starting -> stringResource(R.string.recording_starting)
        is RecordingUiState.Recording -> stringResource(
            R.string.recording_elapsed,
            formatRecordingElapsed(elapsedSeconds),
        )
        RecordingUiState.Saving -> stringResource(R.string.recording_saving)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val RECORDING_TIMER_REFRESH_MILLIS = 250L
private const val UNDO_VISIBLE_MILLIS = 5_000L

/**
 * Looks like the familiar input line but never focuses inline: the Light Phone
 * has no navigation gestures, so typing happens in the full-screen editor with
 * its explicit back arrow instead of trapping focus under the keyboard.
 */
@Composable
fun CaptureBar(
    modifier: Modifier = Modifier,
    placeholder: String,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .then(if (enabled) tapLongModifier(onOpen, onLongPress, placeholder) else Modifier)
            .drawBehind {
                drawLine(
                    DimInk,
                    androidx.compose.ui.geometry.Offset(0f, size.height - 1.dp.toPx()),
                    androidx.compose.ui.geometry.Offset(size.width, size.height - 1.dp.toPx()),
                    1.dp.toPx(),
                )
            }
            .padding(horizontal = 2.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(placeholder, color = DimInk, fontSize = 18.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun HomeHeader(
    date: LocalDate,
    today: LocalDate,
    demo: Boolean,
    onSettings: () -> Unit,
    onCalendar: () -> Unit,
    onTodos: () -> Unit,
    onLogs: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        GearButton(onSettings, Modifier.align(Alignment.CenterStart).offset(x = (-10).dp))
        val title = when {
            demo -> stringResource(R.string.demo_title)
            date == today -> stringResource(R.string.today)
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
        Box(
            modifier = Modifier.then(
                longPressModifier(onCalendar, stringResource(R.string.calendar_title)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 64.dp),
            )
        }
        TodosButton(onTodos, onLogs, Modifier.align(Alignment.CenterEnd).offset(x = 10.dp))
    }
}

@Composable
private fun StillOpenBlock(
    openCount: Int,
    returnCount: Int,
    resurfacingCount: Int,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).then(tapModifier(onOpen, "still open"))) {
            Text(stringResource(R.string.still_open), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Normal)
            Text(
                if (resurfacingCount > 0) {
                    stringResource(R.string.still_open_summary_scheduled, resurfacingCount, openCount, returnCount)
                } else {
                    stringResource(R.string.still_open_summary, openCount, returnCount)
                },
                color = DimInk,
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
            )
        }
        Text(
            "×",
            color = DimInk,
            fontSize = 24.sp,
            modifier = Modifier.then(tapModifier(onDismiss, stringResource(R.string.dismiss_today))).padding(horizontal = 12.dp),
        )
    }
}

@Composable
fun EntryReadScreen(
    entry: NoteEntry,
    playing: Boolean,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(entry.noteDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), onBack)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                stringResource(
                    R.string.entry_added_at,
                    entry.createdAt.atZone(ZoneId.systemDefault()).format(ENTRY_TIME_FORMATTER),
                ),
                color = DimInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
            )
            entry.lastUserEditedAt?.let { editedAt ->
                Text(
                    stringResource(
                        R.string.entry_edited_at,
                        editedAt.atZone(ZoneId.systemDefault()).format(ENTRY_TIME_FORMATTER),
                    ),
                    color = DimInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light,
                )
            }
        }
        if (entry.transcription?.state == EntryTranscriptionState.SUCCEEDED) {
            Text(
                transcriptionSourceLabel(entry.transcription?.provenance),
                color = DimInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .then(longPressModifier(onOptions, "entry options")),
            contentAlignment = Alignment.TopStart,
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp)) {
                entry.activeImage?.let { image ->
                    val rotated = image.rotationDegrees % 180 != 0
                    val displayWidth = if (rotated) image.height else image.width
                    val displayHeight = if (rotated) image.width else image.height
                    EncryptedEntryImage(
                        entry = entry,
                        modifier = Modifier.fillMaxWidth().aspectRatio(
                            displayWidth.toFloat() / displayHeight.coerceAtLeast(1).toFloat(),
                        ),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        backgroundColor = Paper,
                    )
                }
                if (
                    entry.text.isNotBlank() || entry.activeImage == null ||
                    entry.transcription?.state in setOf(
                        EntryTranscriptionState.QUEUED,
                        EntryTranscriptionState.RUNNING,
                        EntryTranscriptionState.FAILED,
                    )
                ) {
                    Text(
                        entry.text.ifBlank {
                            stringResource(
                                if (entry.transcription?.state == EntryTranscriptionState.FAILED) {
                                    R.string.voice_failed
                                } else {
                                    R.string.voice_transcribing
                                },
                            )
                        },
                        color = Ink,
                        fontSize = 18.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = if (entry.activeImage != null) 18.dp else 0.dp),
                    )
                }
            }
        }
        if (entry.activeAudio != null) {
            Box(Modifier.fillMaxWidth().padding(bottom = 18.dp), contentAlignment = Alignment.Center) {
                PlayButton(playing, onPlay)
            }
        }
    }
}

@Composable
private fun transcriptionSourceLabel(provenance: TranscriptionProvenance?): String {
    if (provenance == null) return stringResource(R.string.transcription_source_unknown)
    val requested = when (provenance.requestedEngine) {
        TranscriptionEngine.LOCAL_WHISPER_TINY -> stringResource(R.string.transcription_engine_local)
        TranscriptionEngine.ELEVENLABS_SCRIBE_V2 -> stringResource(R.string.transcription_engine_elevenlabs)
        TranscriptionEngine.GROQ_WHISPER_LARGE_V3_TURBO ->
            stringResource(R.string.transcription_engine_groq_turbo)
        TranscriptionEngine.GROQ_WHISPER_LARGE_V3 -> stringResource(R.string.transcription_engine_groq)
    }
    return when (provenance.fallbackReason) {
        null -> if (provenance.usedEngine == TranscriptionEngine.LOCAL_WHISPER_TINY) {
            stringResource(R.string.transcription_source_local)
        } else {
            stringResource(R.string.transcription_source_cloud, requested)
        }
        TranscriptionFallbackReason.WIFI_REQUIRED ->
            stringResource(R.string.transcription_fallback_wifi, requested)
        TranscriptionFallbackReason.API_KEY_MISSING ->
            stringResource(R.string.transcription_fallback_key, requested)
        TranscriptionFallbackReason.PROVIDER_ERROR ->
            stringResource(R.string.transcription_fallback_error, requested)
        TranscriptionFallbackReason.AUTHENTICATION_ERROR ->
            stringResource(R.string.transcription_fallback_authentication, requested)
        TranscriptionFallbackReason.PERMISSION_ERROR ->
            stringResource(R.string.transcription_fallback_permission, requested)
        TranscriptionFallbackReason.PAYMENT_REQUIRED ->
            stringResource(R.string.transcription_fallback_payment, requested)
        TranscriptionFallbackReason.RATE_LIMITED ->
            stringResource(R.string.transcription_fallback_rate_limit, requested)
        TranscriptionFallbackReason.INVALID_REQUEST ->
            stringResource(R.string.transcription_fallback_invalid_request, requested)
        TranscriptionFallbackReason.NETWORK_ERROR ->
            stringResource(R.string.transcription_fallback_network, requested)
    }
}

private val ENTRY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@Composable
internal fun EntryHistoryScreen(
    versions: List<EntryHistoryVersion>?,
    onVersion: (EntryHistoryVersion) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.entry_history), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                versions == null -> EmptyHint(stringResource(R.string.entry_history_loading))
                versions.isEmpty() -> EmptyHint(stringResource(R.string.entry_history_empty))
                else -> PagedList(versions) { version ->
                    SettingsItem(
                        label = entryHistoryVersionLabel(version),
                        trailing = version.becameCurrentAt.atZone(ZoneId.systemDefault())
                            .format(ENTRY_TIME_FORMATTER),
                        onClick = { onVersion(version) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun EntryHistoryVersionScreen(
    version: EntryHistoryVersion,
    restoring: Boolean,
    restoreFailed: Boolean,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(entryHistoryVersionLabel(version), onBack)
        Text(
            stringResource(
                if (version.isOriginal) R.string.entry_added_at else R.string.entry_edited_at,
                version.becameCurrentAt.atZone(ZoneId.systemDefault()).format(ENTRY_TIME_FORMATTER),
            ),
            color = DimInk,
            fontSize = 12.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                version.text,
                color = Ink,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Normal,
            )
        }
        if (!version.isCurrent) {
            Box(Modifier.fillMaxWidth().height(86.dp)) {
                SettingsItem(
                    label = when {
                        restoring -> stringResource(R.string.restoring_version)
                        restoreFailed -> stringResource(R.string.restore_version_failed)
                        else -> stringResource(R.string.restore_version)
                    },
                    onClick = if (restoring) null else onRestore,
                )
            }
        }
    }
}

@Composable
private fun entryHistoryVersionLabel(version: EntryHistoryVersion): String = when {
    version.isCurrent && version.isOriginal -> stringResource(R.string.original_current)
    version.isOriginal -> stringResource(R.string.original)
    version.isCurrent -> stringResource(R.string.current)
    else -> stringResource(R.string.entry_version, version.number)
}

private enum class EntryAction {
    EDIT,
    HISTORY,
    IMPORTANT,
    LOG,
    RETURN,
    RECORD_ABOUT_PHOTO,
    PLAY,
    RETRANSCRIBE,
    DELETE_AUDIO,
    DELETE_IMAGE,
    DELETE_ENTRY,
}

@Composable
fun EntryOptionsScreen(
    entry: NoteEntry,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onMarkImportant: () -> Unit,
    onLog: () -> Unit,
    onReturn: () -> Unit,
    onRecordAboutPhoto: () -> Unit,
    onPlay: () -> Unit,
    onRetranscribe: () -> Unit,
    onDeleteAudio: () -> Unit,
    onDeleteImage: () -> Unit,
    onDeleteEntry: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val actions = buildList {
        if (entry.transcription?.state !in setOf(
                EntryTranscriptionState.QUEUED,
                EntryTranscriptionState.RUNNING,
            )
        ) {
            add(EntryAction.EDIT)
        }
        if (entry.lastUserEditedAt != null) add(EntryAction.HISTORY)
        if (entry.text.isNotBlank()) add(EntryAction.IMPORTANT)
        if (entry.text.isNotBlank() || entry.activeImage != null) add(EntryAction.LOG)
        add(EntryAction.RETURN)
        if (entry.activeImage != null && entry.activeAudio == null && entry.text.isBlank()) {
            add(EntryAction.RECORD_ABOUT_PHOTO)
        }
        if (entry.activeAudio != null) {
            add(EntryAction.PLAY)
            if (entry.transcription?.state !in setOf(
                    EntryTranscriptionState.QUEUED,
                    EntryTranscriptionState.RUNNING,
                )
            ) {
                add(EntryAction.RETRANSCRIBE)
            }
            add(EntryAction.DELETE_AUDIO)
        }
        if (entry.activeImage != null) add(EntryAction.DELETE_IMAGE)
        add(EntryAction.DELETE_ENTRY)
    }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.entry_title), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(actions) { action ->
                SettingsItem(
                    label = when (action) {
                        EntryAction.EDIT -> stringResource(R.string.edit)
                        EntryAction.HISTORY -> stringResource(R.string.entry_history)
                        EntryAction.IMPORTANT -> stringResource(R.string.mark_important)
                        EntryAction.LOG -> stringResource(R.string.log_from_entry)
                        EntryAction.RETURN -> stringResource(if (entry.returnLater) R.string.returned else R.string.return_later)
                        EntryAction.RECORD_ABOUT_PHOTO -> stringResource(R.string.record_about_photo)
                        EntryAction.PLAY -> stringResource(R.string.play_original)
                        EntryAction.RETRANSCRIBE -> stringResource(R.string.transcribe_again)
                        EntryAction.DELETE_AUDIO -> stringResource(R.string.delete_audio)
                        EntryAction.DELETE_IMAGE -> stringResource(R.string.delete_photo)
                        EntryAction.DELETE_ENTRY -> stringResource(R.string.delete)
                    },
                    onClick = when (action) {
                        EntryAction.EDIT -> onEdit
                        EntryAction.HISTORY -> onHistory
                        EntryAction.IMPORTANT -> onMarkImportant
                        EntryAction.LOG -> onLog
                        EntryAction.RETURN -> onReturn
                        EntryAction.RECORD_ABOUT_PHOTO -> onRecordAboutPhoto
                        EntryAction.PLAY -> onPlay
                        EntryAction.RETRANSCRIBE -> onRetranscribe
                        EntryAction.DELETE_AUDIO -> onDeleteAudio
                        EntryAction.DELETE_IMAGE -> onDeleteImage
                        EntryAction.DELETE_ENTRY -> onDeleteEntry
                    },
                )
            }
        }
    }
}

/** A faithful port of Paka's focused full-screen text editor. */
@Composable
fun TextEditorScreen(
    title: String,
    initialText: String,
    persistedDraft: String? = null,
    onDraftChange: ((String) -> Unit)? = null,
    saving: Boolean = false,
    saveFailed: Boolean = false,
    allowBlank: Boolean = false,
    supportingText: String? = null,
    secondaryActionLabel: String? = null,
    secondaryActionRunning: Boolean = false,
    onSecondaryAction: (() -> Unit)? = null,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    // Capture and Important-add editors hoist text to an encrypted local store.
    // Other editors keep transient text in memory; no user-authored text enters
    // Android's size-limited saved-instance-state Bundle.
    var localText by remember(initialText) { mutableStateOf(initialText) }
    val text = persistedDraft ?: localText
    val updateText: (String) -> Unit = onDraftChange ?: { localText = it }
    val canSave = !saving && if (allowBlank) text != initialText else text.isNotBlank()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val languageTag = SomaPrefs.language(LocalContext.current).languageTag
    val navigateBack = { if (!saving) onBack() }
    BackHandler(onBack = navigateBack)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 28.dp),
    ) {
        SimpleTopBar(title, navigateBack)
        if (supportingText != null) {
            Text(
                supportingText,
                color = DimInk,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BasicTextField(
                value = text,
                onValueChange = updateText,
                singleLine = false,
                textStyle = TextStyle(
                    color = Ink,
                    fontSize = 30.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Normal,
                ).withSomaFont(),
                cursorBrush = SolidColor(Ink),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                    hintLocales = LocaleList(languageTag),
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 22.dp, bottom = 14.dp)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = title },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink).align(Alignment.BottomCenter))
        }
        if (saveFailed) {
            Text(
                stringResource(R.string.save_failed_kept),
                color = DimInk,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!saving && !secondaryActionRunning) {
                            tapModifier(onSecondaryAction, secondaryActionLabel)
                        } else {
                            Modifier
                        },
                    )
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    if (secondaryActionRunning) stringResource(R.string.suggesting_with_groq) else secondaryActionLabel,
                    color = if (!saving && !secondaryActionRunning) Ink else DimInk,
                    fontSize = 18.sp,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canSave) {
                        tapModifier(
                            {
                                keyboard?.hide()
                                onSave(text.trim())
                            },
                            stringResource(R.string.save),
                        )
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                stringResource(R.string.save),
                color = if (canSave) Ink else DimInk,
                fontSize = 18.sp,
            )
        }
    }
}

/**
 * A read-only selection surface: the original entry never changes. Android's
 * native selection handles do the precise word/phrase work, while the only
 * Soma action is copying the selected range into Important with a source link.
 */
@Composable
fun ImportantSelectionScreen(
    entry: NoteEntry,
    saving: Boolean,
    saveFailed: Boolean,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var value by remember(entry.id, entry.text) {
        mutableStateOf(
            TextFieldValue(
                text = entry.text,
                selection = TextRange(0, entry.text.length),
            ),
        )
    }
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, entry.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, entry.text.length)
    val selected = entry.text.substring(start, end).trim()
    val canSave = selected.isNotEmpty() && !saving
    val selectionDescription = stringResource(R.string.select_important_phrase)
    val navigateBack = { if (!saving) onBack() }
    BackHandler(onBack = navigateBack)
    LaunchedEffect(entry.id) { focusRequester.requestFocus() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        SimpleTopBar(stringResource(R.string.mark_important), navigateBack)
        Text(
            stringResource(R.string.select_important_phrase),
            color = DimInk,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = { changed ->
                value = value.copy(
                    selection = TextRange(
                        changed.selection.start.coerceIn(0, entry.text.length),
                        changed.selection.end.coerceIn(0, entry.text.length),
                    ),
                    composition = null,
                )
            },
            readOnly = true,
            textStyle = TextStyle(
                color = Ink,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Normal,
            ).withSomaFont(),
            cursorBrush = SolidColor(Ink),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .semantics { contentDescription = selectionDescription },
        )
        if (saveFailed) {
            Text(
                stringResource(R.string.save_failed_kept),
                color = DimInk,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(
                    if (canSave) {
                        tapModifier({ onSave(selected) }, stringResource(R.string.add_to_important))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                stringResource(R.string.add_to_important),
                color = if (canSave) Ink else DimInk,
                fontSize = 18.sp,
            )
        }
    }
}
