package com.soma.app

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.EntrySource
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.ImportantKind
import com.soma.core.model.NoteEntry
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import com.soma.core.model.TodoSuggestion
import com.soma.core.model.TodoSuggestionState
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionFailureCode
import com.soma.core.model.TranscriptionInfo
import com.soma.core.model.TranscriptionJob
import com.soma.core.policy.TodoStalenessPolicy
import com.soma.core.todo.RuleBasedTodoDetector
import com.soma.storage.repository.RoomSomaRepository
import com.soma.voice.AudioMetadata
import com.soma.voice.EncryptedAudioPlayer
import com.soma.voice.EncryptedAudioReader
import com.soma.voice.EncryptedAudioRecorder
import com.soma.voice.EncryptedAudioRecovery
import com.soma.voice.PlaybackState
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SomaViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SomaApplication
    private val repositories = app.repositories()
    private val clock = Clock.systemUTC()
    private val detector = RuleBasedTodoDetector()
    private val recorder = EncryptedAudioRecorder(app.audioKeyProvider)
    private val player = EncryptedAudioPlayer(app.audioKeyProvider)
    private val mutableDate = MutableStateFlow(LocalDate.now(ZoneId.systemDefault()))
    private var lastKnownToday = mutableDate.value
    private val mutableSuggestions = MutableStateFlow<Map<String, TodoSuggestion>>(emptyMap())
    private val mutablePromptedTodos = MutableStateFlow<Set<String>>(emptySet())
    private val mutableRecordingEntryId = MutableStateFlow<String?>(null)
    private val mutableRecordingUiState = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    private val draftStore = EditorDraftStore(app)
    private val mutableCaptureDraft = MutableStateFlow("")
    private val mutableImportantDraft = MutableStateFlow("")
    private val messages = Channel<String>(Channel.BUFFERED)
    private val entryAppendMutex = Mutex()
    private val recordingMutex = Mutex()
    private val draftWriteMutex = Mutex()
    private var captureDraftLoaded = false
    private var importantDraftLoaded = false
    private var captureDraftTouched = false
    private var importantDraftTouched = false
    private var captureDraftSaveJob: Job? = null
    private var importantDraftSaveJob: Job? = null
    private var recordingStartInProgress = false
    private var stopRecordingRequested = false
    private var stopRecordingInProgress = false
    private var recordingOperationEntryId: String? = null

    val selectedDate: StateFlow<LocalDate> = mutableDate.asStateFlow()
    // WhileSubscribed lets the Room observers stop shortly after their screens
    // leave, matching the idle-work contract; the retained last value keeps
    // `.value` reads valid. `note` also stays warm via the suggestions combine in init.
    @OptIn(ExperimentalCoroutinesApi::class)
    val note = selectedDate.flatMapLatest(repositories.notes::observe)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), null)
    val openTodos = repositories.todos.observe(setOf(TodoState.OPEN))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), emptyList())
    val closedTodos = repositories.todos.observe(setOf(TodoState.DONE, TodoState.ARCHIVED))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), emptyList())
    val returnLater = repositories.notes.observeReturnLater()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MILLIS), emptyList())
    val suggestions: StateFlow<Map<String, TodoSuggestion>> = mutableSuggestions.asStateFlow()
    val promptedTodoIds: StateFlow<Set<String>> = mutablePromptedTodos.asStateFlow()
    val recordingEntryId: StateFlow<String?> = mutableRecordingEntryId.asStateFlow()
    internal val recordingUiState: StateFlow<RecordingUiState> = mutableRecordingUiState.asStateFlow()
    val captureDraft: StateFlow<String> = mutableCaptureDraft.asStateFlow()
    val importantDraft: StateFlow<String> = mutableImportantDraft.asStateFlow()
    val playbackState: StateFlow<PlaybackState> = player.state
    val messageEvents = messages.receiveAsFlow()
    val isDemo: Boolean get() = SomaPrefs.demoMode(app)

    init {
        viewModelScope.launch {
            // No microphone is opened here. This moves the Android Keystore
            // lookup and fixed audio-format calculation off the capture gesture.
            runCatching { recorder.prepare() }
        }
        viewModelScope.launch {
            repositories.notes.getOrCreate(today(), clock.instant())
            recoverInterruptedRecordings()
        }
        viewModelScope.launch {
            val (capture, important) = withContext(Dispatchers.IO) {
                draftWriteMutex.withLock {
                    draftStore.read(EditorDraftKind.CAPTURE) to draftStore.read(EditorDraftKind.IMPORTANT)
                }
            }
            if (!captureDraftTouched) mutableCaptureDraft.value = capture
            if (!importantDraftTouched) mutableImportantDraft.value = important
            captureDraftLoaded = true
            importantDraftLoaded = true
        }
        viewModelScope.launch {
            combine(note, repositories.suggestions.observePending()) { current, pending ->
                val visibleIds = current?.entries.orEmpty().mapTo(hashSetOf(), NoteEntry::id)
                pending.filter { it.entryId in visibleIds }
                    .groupBy(TodoSuggestion::entryId)
                    .mapValues { (_, values) -> values.first() }
            }.collect { mutableSuggestions.value = it }
        }
        viewModelScope.launch {
            CalendarChangeEvents.changes.collect { refreshCalendar(returnHome = false) }
        }
    }

    fun today(): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

    fun isToday(date: LocalDate = selectedDate.value): Boolean = date == today()

    fun updateCaptureDraft(value: String) {
        captureDraftTouched = true
        mutableCaptureDraft.value = value
        captureDraftSaveJob?.cancel()
        captureDraftSaveJob = scheduleDraftWrite(EditorDraftKind.CAPTURE, value)
    }

    fun updateImportantDraft(value: String) {
        importantDraftTouched = true
        mutableImportantDraft.value = value
        importantDraftSaveJob?.cancel()
        importantDraftSaveJob = scheduleDraftWrite(EditorDraftKind.IMPORTANT, value)
    }

    fun clearCaptureDraft() {
        captureDraftTouched = true
        mutableCaptureDraft.value = ""
        captureDraftSaveJob?.cancel()
        captureDraftSaveJob = persistDraft(EditorDraftKind.CAPTURE, "")
    }

    fun clearImportantDraft() {
        importantDraftTouched = true
        mutableImportantDraft.value = ""
        importantDraftSaveJob?.cancel()
        importantDraftSaveJob = persistDraft(EditorDraftKind.IMPORTANT, "")
    }

    /** Flushes the latest editor values without doing encryption or disk I/O on the UI thread. */
    fun flushEditorDrafts() {
        captureDraftSaveJob?.cancel()
        importantDraftSaveJob?.cancel()
        if (captureDraftLoaded || captureDraftTouched) {
            captureDraftSaveJob = persistDraft(EditorDraftKind.CAPTURE, mutableCaptureDraft.value)
        }
        if (importantDraftLoaded || importantDraftTouched) {
            importantDraftSaveJob = persistDraft(EditorDraftKind.IMPORTANT, mutableImportantDraft.value)
        }
    }

    private fun scheduleDraftWrite(kind: EditorDraftKind, value: String): Job = viewModelScope.launch {
        delay(DRAFT_WRITE_DEBOUNCE_MILLIS)
        writeDraft(kind, value)
    }

    private fun persistDraft(kind: EditorDraftKind, value: String): Job = viewModelScope.launch {
        writeDraft(kind, value)
    }

    private suspend fun writeDraft(kind: EditorDraftKind, value: String) {
        withContext(Dispatchers.IO) {
            draftWriteMutex.withLock { runCatching { draftStore.write(kind, value) } }
        }
    }

    fun refreshCalendar(returnHome: Boolean) {
        viewModelScope.launch {
            val now = today()
            repositories.notes.getOrCreate(now, clock.instant())
            if (returnHome || mutableDate.value.isAfter(now) || mutableDate.value == lastKnownToday) {
                mutableDate.value = now
            }
            lastKnownToday = now
        }
    }

    fun showOlderDay() {
        stopRecording()
        mutableDate.value = mutableDate.value.minusDays(1)
    }

    fun showNewerDay() {
        stopRecording()
        val newer = mutableDate.value.plusDays(1)
        if (!newer.isAfter(today())) mutableDate.value = newer
    }

    fun showDay(date: LocalDate) {
        if (!date.isAfter(today())) {
            stopRecording()
            mutableDate.value = date
        }
    }

    fun findEntry(entryId: String, onFound: (NoteEntry) -> Unit) {
        viewModelScope.launch { repositories.notes.getEntry(entryId)?.let(onFound) }
    }

    suspend fun datesWithEntries(from: LocalDate, to: LocalDate): List<LocalDate> =
        repositories.notes.datesWithEntries(from, to)

    fun addText(text: String, onSaved: (Boolean) -> Unit = {}) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            onSaved(false)
            return
        }
        viewModelScope.launch {
            val entry = runCatching {
                entryAppendMutex.withLock {
                    val now = clock.instant()
                    val date = selectedDate.value
                    val current = repositories.notes.getOrCreate(date, now)
                    val candidate = NoteEntry.text(
                        id = UUID.randomUUID().toString(),
                        noteDate = date,
                        position = (current.entries.maxOfOrNull(NoteEntry::position) ?: -1) + 1,
                        text = clean,
                        createdAt = now,
                    )
                    candidate.takeIf { repositories.notes.insertEntry(it) }
                }
            }.getOrNull()
            onSaved(entry != null)
            if (entry != null) runCatching { detectSuggestions(entry) }
        }
    }

    fun editEntry(entry: NoteEntry, text: String, onSaved: (Boolean) -> Unit = {}) {
        val clean = text.trim()
        if (clean.isEmpty() || recordingOperationEntryId == entry.id) {
            onSaved(false)
            return
        }
        viewModelScope.launch {
            val mutation = runCatching {
                val current = repositories.notes.getEntry(entry.id) ?: return@runCatching null
                if (current.transcription?.state in setOf(
                        EntryTranscriptionState.QUEUED,
                        EntryTranscriptionState.RUNNING,
                    )
                ) return@runCatching null
                repositories.notes.editEntryText(entry.id, clean, clock.instant())
            }.getOrNull()
            val updated = mutation?.current
            onSaved(updated != null)
            if (mutation != null && updated != null && updated.text == clean && mutation.previous.text != clean) {
                dismissPendingSuggestions(entry.id)
                runCatching { detectSuggestions(updated) }
            }
        }
    }

    fun toggleReturnLater(entry: NoteEntry) {
        if (recordingOperationEntryId == entry.id) return
        viewModelScope.launch {
            repositories.notes.mutateEntry(entry.id) { current ->
                current.copy(
                    returnLater = !current.returnLater,
                    updatedAt = maxOf(current.updatedAt, clock.instant()),
                )
            }
        }
    }

    fun deleteEntry(entry: NoteEntry) {
        if (recordingOperationEntryId == entry.id) return
        viewModelScope.launch {
            val mutation = repositories.notes.mutateEntry(entry.id) { null }
            if (mutation != null && !isDemo) {
                mutation.previous.audio?.let { app.encryptedAudioFile(it.fileId).delete() }
            }
        }
    }

    fun deleteAudio(entry: NoteEntry) {
        if (recordingOperationEntryId == entry.id) return
        viewModelScope.launch {
            val mutation = repositories.notes.mutateEntry(entry.id) { current ->
                if (current.audio == null) {
                    current
                } else if (current.text.isBlank()) {
                    null
                } else {
                    current.copy(
                        kind = EntryKind.TEXT,
                        audio = null,
                        transcription = null,
                        updatedAt = maxOf(current.updatedAt, clock.instant()),
                    )
                }
            }
            val previousAudio = mutation?.previous?.audio
            if (previousAudio != null && !isDemo) {
                app.encryptedAudioFile(previousAudio.fileId).delete()
            }
        }
    }

    fun acceptSuggestion(entry: NoteEntry) {
        val suggestion = suggestions.value[entry.id] ?: return
        viewModelScope.launch {
            val now = clock.instant()
            val todo = Todo(
                id = UUID.randomUUID().toString(),
                text = suggestion.suggestedText,
                createdAt = now,
                updatedAt = now,
                kind = suggestion.suggestedKind,
                source = EntrySource(entry.noteDate, entry.id),
            )
            if (repositories.suggestions.accept(suggestion.id, todo, now)) {
                // AI extraction can return more than one explicit action. Keep
                // presenting them one at a time; each still needs a deliberate tap.
                refreshSuggestions(listOf(entry))
            }
        }
    }

    fun addTodo(text: String, onSaved: (Boolean) -> Unit = {}) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            onSaved(false)
            return
        }
        viewModelScope.launch {
            val now = clock.instant()
            val saved = runCatching {
                repositories.todos.insert(
                    Todo(UUID.randomUUID().toString(), clean, now, now),
                )
            }.getOrDefault(false)
            onSaved(saved)
        }
    }

    fun addImportantExcerpt(entry: NoteEntry, selectedText: String, onSaved: (Boolean) -> Unit = {}) {
        val clean = selectedText.trim()
        if (clean.isEmpty()) {
            onSaved(false)
            return
        }
        viewModelScope.launch {
            val now = clock.instant()
            val saved = runCatching {
                repositories.todos.insert(
                    Todo(
                        id = UUID.randomUUID().toString(),
                        text = clean,
                        createdAt = now,
                        updatedAt = now,
                        kind = ImportantKind.EXCERPT,
                        source = EntrySource(entry.noteDate, entry.id),
                    ),
                )
            }.getOrDefault(false)
            onSaved(saved)
        }
    }

    fun editTodo(todo: Todo, text: String, onSaved: (Boolean) -> Unit = {}) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            onSaved(false)
            return
        }
        viewModelScope.launch {
            val saved = runCatching {
                repositories.todos.update(todo.edit(clean, clock.instant()))
            }.getOrDefault(false)
            onSaved(saved)
        }
    }

    fun toggleTodo(todo: Todo) {
        viewModelScope.launch {
            val now = clock.instant()
            repositories.todos.update(if (todo.state == TodoState.OPEN) todo.markDone(now) else todo.reopen(now))
            mutablePromptedTodos.value = mutablePromptedTodos.value - todo.id
        }
    }

    fun todoViewed(todo: Todo) {
        val staleness = stalenessPolicy()
        if (!staleness.shouldPrompt(todo) || todo.id in mutablePromptedTodos.value) return
        viewModelScope.launch {
            if (repositories.todos.update(staleness.markPromptShown(todo))) {
                mutablePromptedTodos.value = mutablePromptedTodos.value + todo.id
            }
        }
    }

    fun keepTodo(todo: Todo) {
        val staleness = stalenessPolicy()
        viewModelScope.launch {
            repositories.todos.update(staleness.keep(todo))
            mutablePromptedTodos.value = mutablePromptedTodos.value - todo.id
        }
    }

    fun letGo(todo: Todo) {
        val staleness = stalenessPolicy()
        viewModelScope.launch {
            repositories.todos.update(staleness.letGo(todo))
            mutablePromptedTodos.value = mutablePromptedTodos.value - todo.id
        }
    }

    fun showTodoAgain(todo: Todo, date: LocalDate?, onSaved: (Boolean) -> Unit = {}) {
        if (date != null && !date.isAfter(today())) {
            onSaved(false)
            return
        }
        viewModelScope.launch {
            val now = clock.instant()
            val updated = if (date == null) todo.clearShowAgain(now) else todo.showAgainOn(date, now)
            val saved = runCatching { repositories.todos.update(updated) }.getOrDefault(false)
            onSaved(saved)
        }
    }

    /** Consumes a one-shot resurfacing when its item is deliberately opened. */
    fun acknowledgeResurfacedTodo(todo: Todo) {
        if (todo.resurfaceOn == null) return
        viewModelScope.launch {
            repositories.todos.update(todo.clearShowAgain(clock.instant()))
        }
    }

    fun dismissStillOpen() {
        viewModelScope.launch {
            val now = clock.instant()
            openTodos.value
                .filter { it.resurfaceOn?.isAfter(today()) == false }
                .forEach { repositories.todos.update(it.clearShowAgain(now)) }
            repositories.stillOpen.dismiss(StillOpenDismissal(today(), now))
        }
    }

    suspend fun isStillOpenDismissed(): Boolean = repositories.stillOpen.dismissal(today()) != null

    private fun stalenessPolicy(): TodoStalenessPolicy =
        TodoStalenessPolicy(clock, ZoneId.systemDefault())

    fun startRecording() {
        if (isDemo) {
            messages.trySend(app.getString(R.string.recording_demo_disabled))
            return
        }
        if (mutableRecordingEntryId.value != null || recordingStartInProgress || stopRecordingInProgress) return
        recordingStartInProgress = true
        stopRecordingRequested = false
        mutableRecordingUiState.value = RecordingUiState.Starting
        val now = clock.instant()
        val date = selectedDate.value
        val id = UUID.randomUUID().toString()
        recordingOperationEntryId = id
        viewModelScope.launch {
            recordingMutex.withLock {
                try {
                    // Start capture before touching Room. The encrypted partial
                    // container is itself crash-recoverable, and startup repairs
                    // the tiny window where audio exists before its entry row.
                    try {
                        recorder.start(id, app.audioDirectory)
                        mutableRecordingEntryId.value = id
                        mutableRecordingUiState.value = if (stopRecordingRequested) {
                            RecordingUiState.Saving
                        } else {
                            RecordingUiState.Recording(
                                entryId = id,
                                startedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                            )
                        }
                    } catch (error: Throwable) {
                        File(app.audioDirectory, "$id.partial").delete()
                        recordingOperationEntryId = null
                        mutableRecordingUiState.value = RecordingUiState.Idle
                        messages.trySend(app.getString(R.string.recording_start_failed))
                        return@withLock
                    }

                    val audio = AudioAttachment(
                        fileId = id,
                        format = AudioFormat.WAV,
                        durationMillis = 0,
                        byteCount = 0,
                    )
                    val entry = entryAppendMutex.withLock {
                        val current = repositories.notes.getOrCreate(date, now)
                        val candidate = NoteEntry.voice(
                            id = id,
                            noteDate = date,
                            position = (current.entries.maxOfOrNull(NoteEntry::position) ?: -1) + 1,
                            audio = audio,
                            createdAt = now,
                            transcriptionEnabled = SomaPrefs.transcription(app),
                        )
                        candidate.takeIf { repositories.notes.insertEntry(it) }
                    }
                    if (entry == null) {
                        mutableRecordingUiState.value = RecordingUiState.Saving
                        runCatching { recorder.stop() }
                        mutableRecordingEntryId.value = null
                        File(app.audioDirectory, "$id.partial").delete()
                        app.encryptedAudioFile(id).delete()
                        recordingOperationEntryId = null
                        mutableRecordingUiState.value = RecordingUiState.Idle
                        messages.trySend(app.getString(R.string.recording_start_failed))
                        return@withLock
                    }

                    if (stopRecordingRequested) {
                        stopRecordingInProgress = true
                        mutableRecordingUiState.value = RecordingUiState.Saving
                        finishRecordingLocked(id)
                    }
                } finally {
                    recordingStartInProgress = false
                    if (mutableRecordingEntryId.value == null) stopRecordingRequested = false
                }
            }
        }
    }

    fun stopRecording() {
        if (!recordingStartInProgress && mutableRecordingEntryId.value == null) return
        stopRecordingRequested = true
        mutableRecordingUiState.value = RecordingUiState.Saving
        if (recordingStartInProgress) return
        val entryId = mutableRecordingEntryId.value ?: return
        if (stopRecordingInProgress) return
        stopRecordingInProgress = true
        viewModelScope.launch {
            recordingMutex.withLock { finishRecordingLocked(entryId) }
        }
    }

    /** Must run under [recordingMutex]. */
    private suspend fun finishRecordingLocked(entryId: String) {
        try {
            val recorded = recorder.stop()
            mutableRecordingEntryId.value = null
            val entry = repositories.notes.getEntry(entryId) ?: return
            if (recorded == null) {
                messages.trySend(app.getString(R.string.recording_recovered))
                return
            }
            val updated = entry.copy(
                audio = entry.audio?.copy(
                    durationMillis = recorded.metadata.durationMillis,
                    byteCount = recorded.file.length(),
                ),
                updatedAt = clock.instant(),
            )
            repositories.notes.updateEntry(updated)
            if (SomaPrefs.transcription(app)) {
                val job = TranscriptionJob.queued(UUID.randomUUID().toString(), entryId, clock.instant())
                if (repositories.transcriptionJobs.enqueue(job)) TranscriptionScheduler.enqueue(app)
            }
        } finally {
            recordingOperationEntryId = null
            stopRecordingInProgress = false
            stopRecordingRequested = false
            mutableRecordingUiState.value = RecordingUiState.Idle
        }
    }

    fun togglePlayback(entry: NoteEntry) {
        val audio = entry.audio ?: return
        if (isDemo) return
        viewModelScope.launch {
            val state = playbackState.value
            if (state is PlaybackState.Playing && state.audioId == audio.fileId) {
                player.stop()
            } else {
                player.play(app.encryptedAudioFile(audio.fileId))
            }
        }
    }

    fun retryTranscription(entry: NoteEntry) {
        if (isDemo || entry.audio == null) return
        SomaPrefs.setTranscription(app, true)
        viewModelScope.launch {
            val current = repositories.notes.getEntry(entry.id)
            if (current?.audio == null) {
                messages.trySend(app.getString(R.string.transcription_retry_failed))
                return@launch
            }
            val now = clock.instant()
            val queued = repositories.transcriptionJobs.restart(
                TranscriptionJob.queued(UUID.randomUUID().toString(), current.id, now),
            )
            if (queued) {
                TranscriptionScheduler.enqueue(app)
            } else {
                messages.trySend(app.getString(R.string.transcription_retry_failed))
            }
        }
    }

    private suspend fun detectSuggestions(entry: NoteEntry) {
        // Typed notes can code-switch just like voice notes. Evaluating the eight
        // small rule sets is deterministic and avoids tying content to the UI locale.
        val candidates = withContext(Dispatchers.Default) {
            detector.detect(entry.text, SupportedLanguage.entries.toSet())
        }
        candidates.forEach { candidate ->
            repositories.suggestions.insert(
                TodoSuggestion(
                    id = UUID.randomUUID().toString(),
                    entryId = entry.id,
                    suggestedText = candidate.suggestedText,
                    suggestedKind = candidate.kind,
                    language = candidate.language,
                    reason = candidate.reason,
                    matchedRule = candidate.matchedRule,
                    state = TodoSuggestionState.PENDING,
                    createdAt = clock.instant(),
                ),
            )
        }
        if (candidates.isEmpty()) {
            cloudFeatures(app).extractTodoCandidates(entry.text).forEach { text ->
                repositories.suggestions.insert(
                    TodoSuggestion(
                        id = UUID.randomUUID().toString(),
                        entryId = entry.id,
                        suggestedText = text,
                        language = SomaPrefs.language(app),
                        reason = com.soma.core.model.TodoSuggestionReason.AI_EXTRACTED,
                        matchedRule = "cloud:groq:$CLOUD_AI_TODO_MODEL",
                        state = TodoSuggestionState.PENDING,
                        createdAt = clock.instant(),
                    ),
                )
            }
        }
        refreshSuggestions(listOf(entry))
    }

    private suspend fun dismissPendingSuggestions(entryId: String) {
        val now = clock.instant()
        repositories.suggestions.pendingForEntry(entryId).forEach { suggestion ->
            repositories.suggestions.update(
                suggestion.copy(state = TodoSuggestionState.DISMISSED, resolvedAt = now),
            )
        }
        mutableSuggestions.value = mutableSuggestions.value - entryId
    }

    private suspend fun refreshSuggestions(entries: List<NoteEntry>) {
        val refreshed = entries.mapNotNull { entry ->
            repositories.suggestions.pendingForEntry(entry.id).firstOrNull()?.let { entry.id to it }
        }.toMap()
        val entryIds = entries.mapTo(hashSetOf(), NoteEntry::id)
        mutableSuggestions.value = mutableSuggestions.value.filterKeys { it !in entryIds } + refreshed
    }

    private suspend fun recoverInterruptedRecordings() {
        if (isDemo) return
        app.audioDirectory.listFiles { file -> file.name.endsWith(".partial") }.orEmpty().forEach { partial ->
            val id = partial.name.removeSuffix(".partial")
            val entry = repositories.notes.getEntry(id) ?: createEntryForOrphanedPartial(id, partial)
                ?: return@forEach
            val final = app.encryptedAudioFile(id)
            try {
                val metadata = if (final.isFile) {
                    EncryptedAudioReader.open(final, app.audioKeyProvider, id).use { it.metadata }
                        .also { partial.delete() }
                } else {
                    EncryptedAudioRecovery.recoverPartial(partial, final, app.audioKeyProvider)
                }
                reconcileFinalizedEntry(entry, metadata, final)
            } catch (error: Throwable) {
                markRecordingUnusable(entry, error)
            }
        }

        val room = repositories.notes as? RoomSomaRepository ?: return
        room.entriesNeedingAudioReconciliation().forEach { entry ->
            val audio = entry.audio ?: return@forEach
            val final = app.encryptedAudioFile(audio.fileId)
            try {
                val metadata = EncryptedAudioReader.open(final, app.audioKeyProvider, audio.fileId).use { it.metadata }
                reconcileFinalizedEntry(entry, metadata, final)
            } catch (error: Throwable) {
                markRecordingUnusable(entry, error)
            }
        }

        val referenced = room.referencedAudioFileIds()
        app.audioDirectory.listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(".importing") -> runCatching { file.delete() }
                file.name.endsWith(".sma") && file.name.removeSuffix(".sma") !in referenced ->
                    runCatching { file.delete() }
            }
        }
    }

    /** Repairs a process death between microphone start and the Room insert. */
    private suspend fun createEntryForOrphanedPartial(id: String, partial: File): NoteEntry? {
        val startedAt = partial.lastModified().takeIf { it > 0L }
            ?.let(Instant::ofEpochMilli)
            ?: clock.instant()
        val recoveredDate = startedAt.atZone(ZoneId.systemDefault()).toLocalDate()
            .let { if (it.isAfter(today())) today() else it }
        return entryAppendMutex.withLock {
            repositories.notes.getEntry(id)?.let { return@withLock it }
            val note = repositories.notes.getOrCreate(recoveredDate, startedAt)
            val candidate = NoteEntry.voice(
                id = id,
                noteDate = recoveredDate,
                position = (note.entries.maxOfOrNull(NoteEntry::position) ?: -1) + 1,
                audio = AudioAttachment(
                    fileId = id,
                    format = AudioFormat.WAV,
                    durationMillis = 0,
                    byteCount = 0,
                ),
                createdAt = startedAt,
                transcriptionEnabled = SomaPrefs.transcription(app),
            )
            candidate.takeIf { repositories.notes.insertEntry(it) }
        }
    }

    private suspend fun reconcileFinalizedEntry(entry: NoteEntry, metadata: AudioMetadata, final: File) {
        val updatedAt = maxOf(entry.updatedAt, clock.instant())
        val updated = entry.copy(
            audio = entry.audio?.copy(
                durationMillis = metadata.durationMillis,
                byteCount = final.length(),
                sampleRateHz = metadata.sampleRate,
                channelCount = metadata.channelCount,
            ),
            updatedAt = updatedAt,
        )
        repositories.notes.updateEntry(updated)
        if (
            SomaPrefs.transcription(app) &&
            updated.transcription?.state != EntryTranscriptionState.DISABLED &&
            repositories.transcriptionJobs.getForEntry(entry.id) == null
        ) {
            if (repositories.transcriptionJobs.enqueue(
                    TranscriptionJob.queued(UUID.randomUUID().toString(), entry.id, clock.instant()),
                )
            ) {
                TranscriptionScheduler.enqueue(app)
            }
        }
    }

    private suspend fun markRecordingUnusable(entry: NoteEntry, error: Throwable) {
        val failedAt = maxOf(entry.updatedAt, clock.instant())
        repositories.notes.updateEntry(
            entry.copy(
                transcription = TranscriptionInfo(
                    state = EntryTranscriptionState.FAILED,
                    attemptCount = entry.transcription?.attemptCount ?: 0,
                    updatedAt = failedAt,
                    failure = TranscriptionFailure(
                        TranscriptionFailureCode.AUDIO_UNAVAILABLE,
                        retryable = false,
                        diagnostic = error.javaClass.simpleName,
                    ),
                ),
                updatedAt = failedAt,
            ),
        )
    }

    override fun onCleared() {
        recorder.close()
        player.close()
    }

    private companion object {
        const val FLOW_STOP_TIMEOUT_MILLIS = 5_000L
        const val DRAFT_WRITE_DEBOUNCE_MILLIS = 250L
    }
}
