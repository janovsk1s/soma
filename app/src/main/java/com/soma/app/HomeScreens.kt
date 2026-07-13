package com.soma.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.NoteEntry
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionFallbackReason
import com.soma.core.model.TranscriptionProvenance
import com.soma.core.policy.StillOpenPolicy
import com.soma.core.policy.StillOpenTarget
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HomeScreen(
    viewModel: SomaViewModel,
    onTodos: () -> Unit,
    onSettings: () -> Unit,
    onCalendar: () -> Unit,
    onCapture: () -> Unit,
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
                    onOpen = {
                        val target = stillOpen.defaultTarget
                        val entry = (target as? StillOpenTarget.Entry)
                            ?.let { open -> returnLater.firstOrNull { it.id == open.entryId } }
                        if (entry != null) {
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CaptureBar(
                modifier = Modifier.weight(1f),
                placeholder = stringResource(R.string.entry_hint),
                onOpen = {
                    if (recordingEntryId != null) viewModel.stopRecording()
                    onCapture()
                },
                onLongPress = {
                    if (recordingEntryId == null) onRecordRequested() else viewModel.stopRecording()
                },
            )
            if (recordingEntryId == null) {
                PlusButton(
                    onClick = onCapture,
                    onLongClick = onCapture,
                    modifier = Modifier.offset(x = 8.dp),
                )
            } else {
                StopButton(
                    onClick = viewModel::stopRecording,
                    modifier = Modifier.offset(x = 8.dp),
                )
            }
        }
    }
}

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
) {
    Box(
        modifier = modifier
            .then(tapLongModifier(onOpen, onLongPress, placeholder))
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
        TodosButton(onTodos, Modifier.align(Alignment.CenterEnd).offset(x = 10.dp))
    }
}

@Composable
private fun StillOpenBlock(
    openCount: Int,
    returnCount: Int,
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
                stringResource(R.string.still_open_summary, openCount, returnCount),
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
            contentAlignment = Alignment.CenterStart,
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
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Normal,
            )
        }
        if (entry.audio != null) {
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

private enum class EntryAction { EDIT, IMPORTANT, RETURN, PLAY, RETRANSCRIBE, DELETE_AUDIO, DELETE_ENTRY }

@Composable
fun EntryOptionsScreen(
    entry: NoteEntry,
    onEdit: () -> Unit,
    onMarkImportant: () -> Unit,
    onReturn: () -> Unit,
    onPlay: () -> Unit,
    onRetranscribe: () -> Unit,
    onDeleteAudio: () -> Unit,
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
        if (entry.text.isNotBlank()) add(EntryAction.IMPORTANT)
        add(EntryAction.RETURN)
        if (entry.audio != null) {
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
        add(EntryAction.DELETE_ENTRY)
    }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.entry_title), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(actions) { action ->
                SettingsItem(
                    label = when (action) {
                        EntryAction.EDIT -> stringResource(R.string.edit)
                        EntryAction.IMPORTANT -> stringResource(R.string.mark_important)
                        EntryAction.RETURN -> stringResource(if (entry.returnLater) R.string.returned else R.string.return_later)
                        EntryAction.PLAY -> stringResource(R.string.play_original)
                        EntryAction.RETRANSCRIBE -> stringResource(R.string.transcribe_again)
                        EntryAction.DELETE_AUDIO -> stringResource(R.string.delete_audio)
                        EntryAction.DELETE_ENTRY -> stringResource(R.string.delete)
                    },
                    onClick = when (action) {
                        EntryAction.EDIT -> onEdit
                        EntryAction.IMPORTANT -> onMarkImportant
                        EntryAction.RETURN -> onReturn
                        EntryAction.PLAY -> onPlay
                        EntryAction.RETRANSCRIBE -> onRetranscribe
                        EntryAction.DELETE_AUDIO -> onDeleteAudio
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
