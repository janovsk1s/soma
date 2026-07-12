package com.soma.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.NoteEntry
import com.soma.core.model.TodoSuggestion
import com.soma.voice.PlaybackState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HomeScreen(
    viewModel: SomaViewModel,
    onTodos: () -> Unit,
    onSettings: () -> Unit,
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
    val playback by viewModel.playbackState.collectAsState()
    var input by remember(date) { mutableStateOf("") }
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
            onTodos = onTodos,
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().daySwipe(
                canGoNewer = date.isBefore(viewModel.today()),
                onOlder = viewModel::showOlderDay,
                onNewer = viewModel::showNewerDay,
            ),
        ) {
            if (
                date == viewModel.today() &&
                !stillOpenDismissed &&
                (openTodos.isNotEmpty() || returnLater.isNotEmpty())
            ) {
                StillOpenBlock(
                    openCount = openTodos.size,
                    returnCount = returnLater.size,
                    onOpen = {
                        returnLater.firstOrNull()?.let { entry ->
                            viewModel.showDay(entry.noteDate)
                            onReadEntry(entry)
                        } ?: onTodos()
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
                    PagedList(
                        items = entries,
                        resetKey = date,
                        startAtEnd = true,
                        followEndOnGrowth = true,
                    ) { entry ->
                        EntryRow(
                            entry = entry,
                            suggestion = suggestions[entry.id],
                            recording = recordingEntryId == entry.id,
                            playing = (playback as? PlaybackState.Playing)?.audioId == entry.audio?.fileId,
                            onRead = { onReadEntry(entry) },
                            onOptions = { onEntryOptions(entry) },
                            onSuggestion = { viewModel.acceptSuggestion(entry) },
                            onPlay = { viewModel.togglePlayback(entry) },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineInput(
                value = input,
                onValueChange = { input = it },
                placeholder = stringResource(R.string.entry_hint),
                modifier = Modifier.weight(1f),
                onDone = {
                    val submitted = input
                    input = ""
                    viewModel.addText(submitted) { saved ->
                        if (!saved) {
                            input = if (input.isBlank()) submitted else "$submitted\n$input"
                        }
                    }
                },
            )
            RecordButton(
                recording = recordingEntryId != null,
                onClick = {
                    if (recordingEntryId == null) onRecordRequested() else viewModel.stopRecording()
                },
                modifier = Modifier.offset(x = 8.dp),
            )
        }
    }
}

@Composable
private fun HomeHeader(
    date: LocalDate,
    today: LocalDate,
    demo: Boolean,
    onSettings: () -> Unit,
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
        Text(
            title,
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 64.dp),
        )
        Box(
            modifier = Modifier.align(Alignment.CenterEnd).width(64.dp).then(tapModifier(onTodos, "todos")),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(stringResource(R.string.todos), color = Ink, fontSize = 14.sp)
        }
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
private fun EntryRow(
    entry: NoteEntry,
    suggestion: TodoSuggestion?,
    recording: Boolean,
    playing: Boolean,
    onRead: () -> Unit,
    onOptions: () -> Unit,
    onSuggestion: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().then(tapLongModifier(onRead, onOptions, "note entry")),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
            val displayed = when {
                recording -> stringResource(R.string.recording_now)
                entry.text.isNotBlank() -> entry.text
                entry.transcription?.state == EntryTranscriptionState.FAILED -> stringResource(R.string.voice_failed)
                else -> stringResource(R.string.voice_transcribing)
            }
            Text(
                displayed,
                color = Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry.returnLater) {
                    Text(stringResource(R.string.return_later), color = DimInk, fontSize = 12.sp, fontWeight = FontWeight.Light)
                }
                if (suggestion != null) {
                    Text(
                        stringResource(R.string.todo_suggestion),
                        color = DimInk,
                        fontSize = 14.sp,
                        modifier = Modifier.then(tapModifier(onSuggestion, stringResource(R.string.todo_suggestion))),
                    )
                }
            }
        }
        if (entry.audio != null && !recording) PlayButton(playing, onPlay)
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

private enum class EntryAction { EDIT, RETURN, PLAY, DELETE_AUDIO, DELETE_ENTRY }

@Composable
fun EntryOptionsScreen(
    entry: NoteEntry,
    onEdit: () -> Unit,
    onReturn: () -> Unit,
    onPlay: () -> Unit,
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
        add(EntryAction.RETURN)
        if (entry.audio != null) {
            add(EntryAction.PLAY)
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
                        EntryAction.RETURN -> stringResource(if (entry.returnLater) R.string.returned else R.string.return_later)
                        EntryAction.PLAY -> stringResource(R.string.play_original)
                        EntryAction.DELETE_AUDIO -> stringResource(R.string.delete_audio)
                        EntryAction.DELETE_ENTRY -> stringResource(R.string.delete)
                    },
                    onClick = when (action) {
                        EntryAction.EDIT -> onEdit
                        EntryAction.RETURN -> onReturn
                        EntryAction.PLAY -> onPlay
                        EntryAction.DELETE_AUDIO -> onDeleteAudio
                        EntryAction.DELETE_ENTRY -> onDeleteEntry
                    },
                )
            }
        }
    }
}

@Composable
fun TextEditorScreen(
    title: String,
    initialText: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(title, onBack, trailing = stringResource(R.string.save))
        LineInput(
            value = text,
            onValueChange = { text = it },
            placeholder = stringResource(R.string.entry_hint),
            singleLine = false,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp).then(
                tapModifier({ if (text.isNotBlank()) onSave(text) }, stringResource(R.string.save)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.save), color = Ink, fontSize = 20.sp)
        }
    }
}
