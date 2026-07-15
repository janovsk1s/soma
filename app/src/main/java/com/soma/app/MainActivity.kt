package com.soma.app

import android.Manifest
import android.app.ActivityManager
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.soma.core.model.NoteEntry
import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.LogRevision
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.Todo
import com.soma.voice.PlaybackState

class MainActivity : ComponentActivity() {
    private val somaViewModel: SomaViewModel by viewModels()
    private var homeResetSignal by mutableIntStateOf(0)
    private var externalFlowActive = false
    private var returnHomeOnResume = false

    override fun attachBaseContext(newBase: Context) {
        val locale = SomaPrefs.language(newBase).let { java.util.Locale.forLanguageTag(it.languageTag) }
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Match Paka: dark is native/default; Developer light mode is loaded
        // before the first Compose frame so there is no inverted app flash.
        SomaPalette.lightMode = SomaPrefs.lightMode(this)
        enableEdgeToEdge()
        applySystemPalette(SomaPalette.lightMode)
        setContent { SomaTheme { SomaApp(somaViewModel, homeResetSignal) } }
    }

    internal fun applySystemPalette(lightMode: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightMode
            isAppearanceLightNavigationBars = lightMode
        }
        val color = if (lightMode) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        @Suppress("DEPRECATION")
        setTaskDescription(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription.Builder()
                    .setLabel(getString(R.string.app_name))
                    .setPrimaryColor(color)
                    .setBackgroundColor(color)
                    .setStatusBarColor(color)
                    .setNavigationBarColor(color)
                    .build()
            } else {
                ActivityManager.TaskDescription(getString(R.string.app_name), null, color)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        val returnHome = returnHomeOnResume && SomaPrefs.returnHome(this)
        returnHomeOnResume = false
        somaViewModel.refreshCalendar(returnHome)
        if (returnHome) homeResetSignal++
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!externalFlowActive && SomaPrefs.returnHome(this)) {
            somaViewModel.stopRecording()
            somaViewModel.refreshCalendar(returnHome = true)
            homeResetSignal++
        }
    }

    override fun onStop() {
        somaViewModel.flushEditorDrafts()
        somaViewModel.stopRecording()
        if (!externalFlowActive && SomaPrefs.returnHome(this)) returnHomeOnResume = true
        super.onStop()
    }

    fun setExternalFlowActive(active: Boolean) {
        externalFlowActive = active
    }

    fun switchLanguage(language: SupportedLanguage) {
        SomaPrefs.setLanguage(this, language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language.languageTag)
        } else {
            recreate()
        }
    }
}

fun Context.setExternalFlowActive(active: Boolean) {
    (this as? MainActivity)?.setExternalFlowActive(active)
}

private sealed interface AppRoute {
    data object Home : AppRoute
    data object Todos : AppRoute
    data object Logs : AppRoute
    data object Settings : AppRoute
    data object Backup : AppRoute
    data object Browser : AppRoute
    data object About : AppRoute
    data object Licenses : AppRoute
    data object Developer : AppRoute
    data object CloudDeveloper : AppRoute
    data object Language : AppRoute
    data object AddTodo : AppRoute
    data object Capture : AppRoute
    data object Photo : AppRoute
    data object Calendar : AppRoute
    data object Search : AppRoute
    data object SpeechLanguages : AppRoute
    data object TranscriptionVocabulary : AppRoute
    data object Deleted : AppRoute
    data class ChooseLogKind(val sourceEntry: NoteEntry? = null) : AppRoute
    data class AddLog(
        val kind: LogKind,
        val sourceEntry: NoteEntry? = null,
        val structuredWorkout: Boolean = false,
        val autoSuggest: Boolean = false,
    ) : AppRoute
    data class LogDetail(val log: LogRecord, val fromSearch: Boolean = false) : AppRoute
    data class LogOptions(val log: LogRecord) : AppRoute
    data class EditLog(val log: LogRecord) : AppRoute
    data class LogHistory(val log: LogRecord) : AppRoute
    data class LogHistoryVersion(val log: LogRecord, val revision: LogRevision) : AppRoute
    data class FoodLookup(val log: LogRecord, val foodIndex: Int) : AppRoute
    data class ReadEntry(val entry: NoteEntry, val fromTodos: Boolean = false) : AppRoute
    data class EntryOptions(
        val entry: NoteEntry,
        val returnToRead: Boolean = false,
        val fromTodos: Boolean = false,
    ) : AppRoute
    data class EntryHistory(
        val entry: NoteEntry,
        val returnToRead: Boolean,
        val fromTodos: Boolean,
    ) : AppRoute
    data class EntryHistoryVersion(
        val entry: NoteEntry,
        val version: com.soma.app.EntryHistoryVersion,
        val returnToRead: Boolean,
        val fromTodos: Boolean,
    ) : AppRoute
    data class DeletedOptions(val entry: NoteEntry) : AppRoute
    data class EditEntry(val entry: NoteEntry, val fromTodos: Boolean = false) : AppRoute
    data class SelectImportant(val entry: NoteEntry, val fromTodos: Boolean = false) : AppRoute
    data class TodoOptions(val todo: Todo) : AppRoute
    data class TodoDetail(val todo: Todo, val fromSearch: Boolean = false) : AppRoute
    data class EditTodo(val todo: Todo) : AppRoute
    data class ResurfaceTodo(val todo: Todo) : AppRoute
}

@Composable
private fun SomaApp(viewModel: SomaViewModel, homeResetSignal: Int) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val lightMode = SomaPalette.lightMode
    // Saved so an interrupted, no-payload editor (Capture, add-todo) is restored
    // after process death; entry-carrying routes fall back to Home (see AppRouteSaver).
    var route: AppRoute by rememberSaveable(stateSaver = AppRouteSaver) { mutableStateOf(AppRoute.Home) }
    LaunchedEffect(route) {
        // The reader and its options screen both expose a visible play/stop
        // control. Everywhere else, especially Today, must not inherit audio
        // that the user can hear but no longer control.
        if (route !is AppRoute.ReadEntry && route !is AppRoute.EntryOptions) {
            viewModel.stopPlayback()
        }
    }
    var pendingRecordingComment by remember { mutableStateOf<NoteEntry?>(null) }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val comment = pendingRecordingComment
        pendingRecordingComment = null
        if (granted) viewModel.startRecording(comment)
        else Toast.makeText(context, R.string.microphone_permission_needed, Toast.LENGTH_LONG).show()
    }
    val requestRecording: (NoteEntry?) -> Unit = { comment ->
        pendingRecordingComment = comment
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecordingComment = null
            viewModel.startRecording(comment)
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) route = AppRoute.Photo
        else Toast.makeText(context, R.string.photo_permission_needed, Toast.LENGTH_LONG).show()
    }
    val requestPhoto = {
        viewModel.stopRecording()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            route = AppRoute.Photo
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(homeResetSignal) {
        if (homeResetSignal > 0) route = AppRoute.Home
    }
    LaunchedEffect(activity, lightMode) {
        activity?.applySystemPalette(lightMode)
    }
    LaunchedEffect(Unit) {
        viewModel.messageEvents.collect { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    when (val current = route) {
        AppRoute.Home -> HomeScreen(
            viewModel = viewModel,
            onTodos = {
                viewModel.stopRecording()
                viewModel.clearPhotoCommentPrompt()
                route = AppRoute.Todos
            },
            onLogs = {
                viewModel.stopRecording()
                viewModel.clearPhotoCommentPrompt()
                route = AppRoute.Logs
            },
            onResurfacedImportant = { route = AppRoute.TodoOptions(it) },
            onSettings = {
                viewModel.stopRecording()
                viewModel.clearPhotoCommentPrompt()
                route = AppRoute.Settings
            },
            onCalendar = {
                viewModel.stopRecording()
                viewModel.clearPhotoCommentPrompt()
                route = AppRoute.Calendar
            },
            onCapture = { route = AppRoute.Capture },
            onPhotoRequested = requestPhoto,
            onPhotoCaption = { entry ->
                viewModel.clearPhotoCommentPrompt()
                route = AppRoute.EditEntry(entry)
            },
            onPhotoCommentRecord = { entry ->
                viewModel.clearPhotoCommentPrompt()
                requestRecording(entry)
            },
            onReadEntry = {
                viewModel.stopRecording()
                viewModel.clearPhotoCommentPrompt()
                route = AppRoute.ReadEntry(it)
            },
            onEntryOptions = {
                viewModel.stopRecording()
                viewModel.clearPhotoCommentPrompt()
                route = AppRoute.EntryOptions(it)
            },
            onTrackingSuggestion = { entry, kind ->
                viewModel.stopRecording()
                viewModel.clearPhotoCommentPrompt()
                route = AppRoute.AddLog(kind, sourceEntry = entry, autoSuggest = true)
            },
            onRecordRequested = { requestRecording(null) },
        )
        AppRoute.Todos -> TodosScreen(
            viewModel = viewModel,
            onTodoOptions = { route = AppRoute.TodoOptions(it) },
            onTodoDetail = { route = AppRoute.TodoDetail(it) },
            onDetailedAdd = { route = AppRoute.AddTodo },
            onSource = { todo ->
                todo.source?.let { source ->
                    viewModel.showDay(source.noteDate)
                    viewModel.findEntry(source.entryId) {
                        route = AppRoute.ReadEntry(it, fromTodos = true)
                    }
                }
            },
            onBack = { route = AppRoute.Home },
        )
        AppRoute.Logs -> TrackingLogsScreen(
            viewModel = viewModel,
            onAdd = { kind ->
                route = kind?.let { AppRoute.AddLog(it, structuredWorkout = it == LogKind.WORKOUT) }
                    ?: AppRoute.ChooseLogKind()
            },
            onDetail = { route = AppRoute.LogDetail(it) },
            onOptions = { route = AppRoute.LogOptions(it) },
            onBack = { route = AppRoute.Home },
        )
        is AppRoute.ChooseLogKind -> LogKindScreen(
            fromEntry = current.sourceEntry != null,
            onSelect = {
                route = AppRoute.AddLog(
                    it,
                    sourceEntry = current.sourceEntry,
                    autoSuggest = current.sourceEntry != null,
                )
            },
            onBack = {
                route = current.sourceEntry?.let { AppRoute.EntryOptions(it, returnToRead = true) }
                    ?: AppRoute.Logs
            },
        )
        is AppRoute.AddLog -> {
            val sourceText = if (current.kind == LogKind.RECEIPT && current.sourceEntry?.activeImage != null) {
                // A spoken photo caption is useful evidence, but it is not necessarily the
                // merchant name. Keep the structured form blank until the user or OCR confirms it.
                ""
            } else {
                current.sourceEntry?.text.orEmpty()
            }
            var saving by remember(current.kind, current.sourceEntry?.id) { mutableStateOf(false) }
            var saveFailed by remember(current.kind, current.sourceEntry?.id) { mutableStateOf(false) }
            var editorText by remember(current.kind, current.sourceEntry?.id, sourceText) {
                mutableStateOf(sourceText)
            }
            var editorChangedByUser by remember(current.kind, current.sourceEntry?.id) { mutableStateOf(false) }
            val latestEditorChangedByUser by rememberUpdatedState(editorChangedByUser)
            var suggesting by remember(current.kind, current.sourceEntry?.id) { mutableStateOf(false) }
            var suggestionFailed by remember(current.kind, current.sourceEntry?.id) { mutableStateOf(false) }
            LaunchedEffect(current.kind, current.sourceEntry?.id, current.autoSuggest) {
                val source = current.sourceEntry
                if (current.autoSuggest && source != null && viewModel.trackingSuggestionAvailable && !suggesting) {
                    suggesting = true
                    suggestionFailed = false
                    viewModel.suggestTrackingText(current.kind, source) { proposal ->
                        suggesting = false
                        if (proposal == null) {
                            suggestionFailed = true
                        } else if (!latestEditorChangedByUser) {
                            editorText = proposal
                        }
                    }
                }
            }
            if (current.structuredWorkout && current.sourceEntry == null) {
                WorkoutQuickAddScreen(
                    saving = saving,
                    saveFailed = saveFailed,
                    onSave = { text ->
                        if (!saving) {
                            saving = true
                            saveFailed = false
                            viewModel.addTrackingLog(current.kind, text) { saved ->
                                saving = false
                                if (saved) route = AppRoute.Logs else saveFailed = true
                            }
                        }
                    },
                    onBack = { route = AppRoute.Logs },
                )
            } else if (current.kind == LogKind.RECEIPT) {
                val canSuggest = current.sourceEntry != null && viewModel.trackingSuggestionAvailable
                val requestSuggestion = current.sourceEntry?.let { source ->
                    {
                        if (!suggesting) {
                            suggesting = true
                            suggestionFailed = false
                            viewModel.suggestTrackingText(current.kind, source) { proposal ->
                                suggesting = false
                                if (proposal == null) {
                                    suggestionFailed = true
                                } else {
                                    editorText = proposal
                                }
                            }
                        }
                    }
                }
                ReceiptEditorScreen(
                    sourceEntry = current.sourceEntry,
                    draftText = editorText,
                    saving = saving,
                    saveFailed = saveFailed,
                    suggestionFailed = suggestionFailed,
                    suggesting = suggesting,
                    canSuggest = canSuggest,
                    onDraftChange = {
                        editorChangedByUser = true
                        editorText = it
                    },
                    onSuggest = requestSuggestion,
                    onSave = { text ->
                        if (!saving) {
                            saving = true
                            saveFailed = false
                            viewModel.addTrackingLog(current.kind, text, current.sourceEntry) { saved ->
                                saving = false
                                if (saved) route = AppRoute.Logs else saveFailed = true
                            }
                        }
                    },
                    onBack = {
                        route = if (current.sourceEntry != null) {
                            AppRoute.ChooseLogKind(current.sourceEntry)
                        } else {
                            AppRoute.Logs
                        }
                    },
                )
            } else TextEditorScreen(
                title = logEditorTitle(current.kind),
                initialText = sourceText,
                persistedDraft = editorText,
                onDraftChange = {
                    editorChangedByUser = true
                    editorText = it
                },
                saving = saving,
                saveFailed = saveFailed,
                supportingText = buildString {
                    append(logEditorHelp(current.kind))
                    if (suggestionFailed) append("\n").append(stringResourceCompat(R.string.tracking_suggestion_failed))
                },
                secondaryActionLabel = if (current.sourceEntry != null && viewModel.trackingSuggestionAvailable) {
                    stringResourceCompat(R.string.suggest_with_groq)
                } else {
                    null
                },
                secondaryActionRunning = suggesting,
                onSecondaryAction = current.sourceEntry?.let { source ->
                    {
                        if (!suggesting) {
                            suggesting = true
                            suggestionFailed = false
                            viewModel.suggestTrackingText(current.kind, source) { proposal ->
                                suggesting = false
                                if (proposal == null) {
                                    suggestionFailed = true
                                } else {
                                    editorText = proposal
                                }
                            }
                        }
                    }
                },
                onSave = { text ->
                    if (!saving) {
                        saving = true
                        saveFailed = false
                        viewModel.addTrackingLog(current.kind, text, current.sourceEntry) { saved ->
                            saving = false
                            if (saved) route = AppRoute.Logs else saveFailed = true
                        }
                    }
                },
                onBack = {
                    route = if (current.sourceEntry != null) {
                        AppRoute.ChooseLogKind(current.sourceEntry)
                    } else {
                        AppRoute.Logs
                    }
                },
            )
        }
        is AppRoute.LogDetail -> {
            val log = rememberLiveLog(viewModel, current.log)
            var historyCount by remember(log.id, log.revision) { mutableStateOf<Int?>(null) }
            LaunchedEffect(log.id, log.revision) {
                historyCount = viewModel.trackingLogHistory(log.id).size
            }
            TrackingLogDetailScreen(
                log = log,
                historyCount = historyCount,
                onHistory = if (log.revision > 0) ({ route = AppRoute.LogHistory(log) }) else null,
                onSource = log.source?.let { source ->
                    {
                        viewModel.showDay(source.noteDate)
                        viewModel.findEntry(source.entryId) { route = AppRoute.ReadEntry(it) }
                    }
                },
                onFood = { index -> route = AppRoute.FoodLookup(log, index) },
                onBack = { route = if (current.fromSearch) AppRoute.Search else AppRoute.Logs },
            )
        }
        is AppRoute.LogOptions -> {
            val log = rememberLiveLog(viewModel, current.log)
            TrackingLogOptionsScreen(
                log = log,
                onEdit = { route = AppRoute.EditLog(log) },
                onHistory = { route = AppRoute.LogHistory(log) },
                onArchive = {
                    viewModel.archiveTrackingLog(log) { saved ->
                        if (saved) route = AppRoute.Logs
                    }
                },
                onBack = { route = AppRoute.Logs },
            )
        }
        is AppRoute.EditLog -> {
            val log = rememberLiveLog(viewModel, current.log)
            var saving by remember(log.id) { mutableStateOf(false) }
            var saveFailed by remember(log.id) { mutableStateOf(false) }
            if (log.kind == LogKind.RECEIPT) {
                var receiptDraft by remember(log.id, log.revision) { mutableStateOf(log.note.ifBlank { log.title }) }
                var sourceEntry by remember(log.id) { mutableStateOf<NoteEntry?>(null) }
                LaunchedEffect(log.id, log.source) {
                    log.source?.let { source ->
                        viewModel.findEntry(source.entryId) { sourceEntry = it }
                    }
                }
                ReceiptEditorScreen(
                    sourceEntry = sourceEntry,
                    draftText = receiptDraft,
                    saving = saving,
                    saveFailed = saveFailed,
                    suggestionFailed = false,
                    suggesting = false,
                    canSuggest = false,
                    onDraftChange = { receiptDraft = it },
                    onSuggest = null,
                    onSave = { text ->
                        if (!saving) {
                            saving = true
                            saveFailed = false
                            viewModel.editTrackingLog(log, text) { saved ->
                                saving = false
                                if (saved) route = AppRoute.LogDetail(log) else saveFailed = true
                            }
                        }
                    },
                    onBack = { route = AppRoute.LogOptions(log) },
                )
            } else {
                TextEditorScreen(
                    title = stringResourceCompat(R.string.edit),
                    initialText = log.note.ifBlank { log.title },
                    saving = saving,
                    saveFailed = saveFailed,
                    supportingText = logEditorHelp(log.kind),
                    onSave = { text ->
                        if (!saving) {
                            saving = true
                            saveFailed = false
                            viewModel.editTrackingLog(log, text) { saved ->
                                saving = false
                                if (saved) route = AppRoute.LogDetail(log) else saveFailed = true
                            }
                        }
                    },
                    onBack = { route = AppRoute.LogOptions(log) },
                )
            }
        }
        is AppRoute.LogHistory -> {
            val log = rememberLiveLog(viewModel, current.log)
            var revisions by remember(log.id, log.revision) { mutableStateOf<List<LogRevision>?>(null) }
            LaunchedEffect(log.id, log.revision) {
                revisions = viewModel.trackingLogHistory(log.id)
            }
            TrackingLogHistoryScreen(
                revisions = revisions,
                onRevision = { route = AppRoute.LogHistoryVersion(log, it) },
                onBack = { route = AppRoute.LogOptions(log) },
            )
        }
        is AppRoute.LogHistoryVersion -> TrackingLogDetailScreen(
            log = current.revision.snapshot,
            historyCount = null,
            onHistory = null,
            onSource = current.revision.snapshot.source?.let { source ->
                {
                    viewModel.showDay(source.noteDate)
                    viewModel.findEntry(source.entryId) { route = AppRoute.ReadEntry(it) }
                }
            },
            onFood = null,
            onBack = { route = AppRoute.LogHistory(current.log) },
        )
        is AppRoute.FoodLookup -> {
            val log = rememberLiveLog(viewModel, current.log)
            val food = log.foods.getOrNull(current.foodIndex)
            if (food == null) {
                LaunchedEffect(log.id, current.foodIndex) { route = AppRoute.LogDetail(log) }
            } else {
                EuropeanFoodSearchScreen(
                    viewModel = viewModel,
                    initialQuery = food.name,
                    onSelect = { reference ->
                        viewModel.applyEuropeanFood(log, current.foodIndex, reference) { saved ->
                            if (saved) route = AppRoute.LogDetail(log)
                        }
                    },
                    onBack = { route = AppRoute.LogDetail(log) },
                )
            }
        }
        AppRoute.Settings -> {
            val deleted by viewModel.deletedEntries.collectAsState()
            SettingsScreen(
                onSpeechLanguages = { route = AppRoute.SpeechLanguages },
                onTranscriptionVocabulary = { route = AppRoute.TranscriptionVocabulary },
                deletedCount = deleted.size,
                onDeleted = { route = AppRoute.Deleted },
                onBackup = { route = AppRoute.Backup },
                onBrowser = { route = AppRoute.Browser },
                onAbout = { route = AppRoute.About },
                onBack = { route = AppRoute.Home },
            )
        }
        AppRoute.Deleted -> {
            val deleted by viewModel.deletedEntries.collectAsState()
            DeletedItemsScreen(
                entries = deleted,
                onOptions = { route = AppRoute.DeletedOptions(it) },
                onBack = { route = AppRoute.Settings },
            )
        }
        is AppRoute.DeletedOptions -> {
            var busy by remember(current.entry.id) { mutableStateOf(false) }
            var failed by remember(current.entry.id) { mutableStateOf(false) }
            DeletedItemOptionsScreen(
                busy = busy,
                failed = failed,
                onRestore = {
                    if (!busy) {
                        busy = true
                        failed = false
                        viewModel.restoreDeleted(current.entry) { restored ->
                            busy = false
                            if (restored) route = AppRoute.Deleted else failed = true
                        }
                    }
                },
                onPurge = {
                    if (!busy) {
                        busy = true
                        failed = false
                        viewModel.purgeDeleted(current.entry) { purged ->
                            busy = false
                            if (purged) route = AppRoute.Deleted else failed = true
                        }
                    }
                },
                onBack = { route = AppRoute.Deleted },
            )
        }
        AppRoute.SpeechLanguages -> SpeechLanguagesScreen { route = AppRoute.Settings }
        AppRoute.TranscriptionVocabulary -> TranscriptionVocabularyScreen { route = AppRoute.Settings }
        AppRoute.Backup -> BackupScreen(viewModel) { route = AppRoute.Settings }
        AppRoute.Browser -> BrowserScreen { route = AppRoute.Settings }
        AppRoute.About -> AboutScreen(
            onDeveloper = { route = AppRoute.Developer },
            onLicenses = { route = AppRoute.Licenses },
            onBack = { route = AppRoute.Settings },
        )
        AppRoute.Licenses -> LicensesScreen { route = AppRoute.About }
        AppRoute.Developer -> DeveloperScreen(
            onLanguage = { route = AppRoute.Language },
            onCloud = { route = AppRoute.CloudDeveloper },
            onRestart = {
                activity?.let {
                    val restart = Intent(it, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                    it.startActivity(restart)
                    it.finish()
                }
            },
            onBack = { route = AppRoute.About },
        )
        AppRoute.CloudDeveloper -> CloudDeveloperScreen { route = AppRoute.Developer }
        AppRoute.Language -> LanguageScreen(
            onSelected = { language -> activity?.switchLanguage(language) },
            onBack = { route = AppRoute.Developer },
        )
        AppRoute.Capture -> {
            val draft by viewModel.captureDraft.collectAsState()
            var saving by remember { mutableStateOf(false) }
            var saveFailed by remember { mutableStateOf(false) }
            TextEditorScreen(
                title = stringResourceCompat(R.string.add_entry),
                initialText = "",
                persistedDraft = draft,
                onDraftChange = viewModel::updateCaptureDraft,
                saving = saving,
                saveFailed = saveFailed,
                onSave = { text ->
                    if (!saving) {
                        saving = true
                        saveFailed = false
                        viewModel.addText(text) { saved ->
                            saving = false
                            if (saved) {
                                viewModel.clearCaptureDraft()
                                route = AppRoute.Home
                            } else {
                                saveFailed = true
                            }
                        }
                    }
                },
                onBack = { route = AppRoute.Home },
            )
        }
        AppRoute.Photo -> {
            var saving by remember { mutableStateOf(false) }
            CameraCaptureScreen(
                saving = saving,
                onCaptured = { photo ->
                    if (!saving) {
                        saving = true
                        viewModel.addPhoto(photo) { route = AppRoute.Home }
                    } else {
                        photo.jpegBytes.fill(0)
                    }
                },
                onBack = { route = AppRoute.Home },
            )
        }
        AppRoute.Calendar -> CalendarScreen(
            viewModel = viewModel,
            onSelect = { picked ->
                viewModel.showDay(picked)
                route = AppRoute.Home
            },
            onSearch = { route = AppRoute.Search },
            onBack = { route = AppRoute.Home },
        )
        AppRoute.Search -> SearchScreen(
            viewModel = viewModel,
            onOpenDay = { picked ->
                viewModel.showDay(picked)
                route = AppRoute.Home
            },
            onOpenTodo = { todo -> route = AppRoute.TodoDetail(todo, fromSearch = true) },
            onOpenLog = { log -> route = AppRoute.LogDetail(log, fromSearch = true) },
            onBack = {
                viewModel.clearSearch()
                route = AppRoute.Calendar
            },
        )
        AppRoute.AddTodo -> {
            val draft by viewModel.importantDraft.collectAsState()
            var saving by remember { mutableStateOf(false) }
            var saveFailed by remember { mutableStateOf(false) }
            TextEditorScreen(
                title = stringResourceCompat(R.string.add_details),
                initialText = "",
                persistedDraft = draft,
                onDraftChange = viewModel::updateImportantDraft,
                saving = saving,
                saveFailed = saveFailed,
                onSave = { text ->
                    if (!saving) {
                        saving = true
                        saveFailed = false
                        viewModel.addTodo(text) { saved ->
                            saving = false
                            if (saved) {
                                viewModel.clearImportantDraft()
                                route = AppRoute.Todos
                            } else {
                                saveFailed = true
                            }
                        }
                    }
                },
                onBack = { route = AppRoute.Todos },
            )
        }
        is AppRoute.ReadEntry -> {
            val entry = rememberLiveEntry(viewModel, current.entry)
            val playback by viewModel.playbackState.collectAsState()
            val origin: AppRoute = if (current.fromTodos) AppRoute.Todos else AppRoute.Home
            EntryReadScreen(
                entry = entry,
                playing = (playback as? PlaybackState.Playing)?.audioId == entry.activeAudio?.fileId,
                onPlay = { viewModel.togglePlayback(entry) },
                onOptions = {
                    route = AppRoute.EntryOptions(entry, returnToRead = true, fromTodos = current.fromTodos)
                },
                onBack = { route = origin },
            )
        }
        is AppRoute.EntryOptions -> {
            val entry = rememberLiveEntry(viewModel, current.entry)
            val origin: AppRoute = if (current.fromTodos) AppRoute.Todos else AppRoute.Home
            EntryOptionsScreen(
                entry = entry,
                onEdit = { route = AppRoute.EditEntry(entry, fromTodos = current.fromTodos) },
                onHistory = {
                    route = AppRoute.EntryHistory(entry, current.returnToRead, current.fromTodos)
                },
                onMarkImportant = { route = AppRoute.SelectImportant(entry, current.fromTodos) },
                onLog = { route = AppRoute.ChooseLogKind(entry) },
                onReturn = {
                    viewModel.toggleReturnLater(entry)
                    route = origin
                },
                onRecordAboutPhoto = {
                    viewModel.showDay(entry.noteDate)
                    route = AppRoute.Home
                    requestRecording(entry)
                },
                onPlay = { viewModel.togglePlayback(entry) },
                onRetranscribe = {
                    viewModel.retryTranscription(entry)
                    route = AppRoute.ReadEntry(entry, current.fromTodos)
                },
                onDeleteAudio = {
                    viewModel.deleteAudio(entry)
                    route = origin
                },
                onDeleteImage = {
                    viewModel.deleteImage(entry)
                    route = origin
                },
                onDeleteEntry = {
                    viewModel.deleteEntry(entry)
                    route = origin
                },
                onBack = {
                    route = if (current.returnToRead) {
                        AppRoute.ReadEntry(entry, current.fromTodos)
                    } else {
                        origin
                    }
                },
            )
        }
        is AppRoute.EntryHistory -> {
            val entry = rememberLiveEntry(viewModel, current.entry)
            var versions by remember(entry.id, entry.text, entry.lastUserEditedAt) {
                mutableStateOf<List<com.soma.app.EntryHistoryVersion>?>(null)
            }
            LaunchedEffect(entry.id, entry.text, entry.lastUserEditedAt) {
                versions = viewModel.entryHistory(entry)
            }
            EntryHistoryScreen(
                versions = versions,
                onVersion = { version ->
                    route = AppRoute.EntryHistoryVersion(
                        entry,
                        version,
                        current.returnToRead,
                        current.fromTodos,
                    )
                },
                onBack = {
                    route = AppRoute.EntryOptions(entry, current.returnToRead, current.fromTodos)
                },
            )
        }
        is AppRoute.EntryHistoryVersion -> {
            val entry = rememberLiveEntry(viewModel, current.entry)
            var restoring by remember(entry.id, current.version.number) { mutableStateOf(false) }
            var restoreFailed by remember(entry.id, current.version.number) { mutableStateOf(false) }
            EntryHistoryVersionScreen(
                version = current.version,
                restoring = restoring,
                restoreFailed = restoreFailed,
                onRestore = {
                    if (!restoring) {
                        restoring = true
                        restoreFailed = false
                        viewModel.editEntry(entry, current.version.text) { saved ->
                            restoring = false
                            if (saved) {
                                route = AppRoute.ReadEntry(entry, current.fromTodos)
                            } else {
                                restoreFailed = true
                            }
                        }
                    }
                },
                onBack = {
                    route = AppRoute.EntryHistory(entry, current.returnToRead, current.fromTodos)
                },
            )
        }
        is AppRoute.SelectImportant -> {
            val entry = rememberLiveEntry(viewModel, current.entry)
            var saving by remember(entry.id) { mutableStateOf(false) }
            var saveFailed by remember(entry.id) { mutableStateOf(false) }
            ImportantSelectionScreen(
                entry = entry,
                saving = saving,
                saveFailed = saveFailed,
                onSave = { selected ->
                    if (!saving) {
                        saving = true
                        saveFailed = false
                        viewModel.addImportantExcerpt(entry, selected) { saved ->
                            saving = false
                            if (saved) {
                                route = AppRoute.ReadEntry(entry, current.fromTodos)
                            } else {
                                saveFailed = true
                            }
                        }
                    }
                },
                onBack = { route = AppRoute.EntryOptions(entry, returnToRead = true, fromTodos = current.fromTodos) },
            )
        }
        is AppRoute.EditEntry -> {
            val entry = rememberLiveEntry(viewModel, current.entry)
            var saving by remember(entry.id) { mutableStateOf(false) }
            var saveFailed by remember(entry.id) { mutableStateOf(false) }
            TextEditorScreen(
                title = stringResourceCompat(R.string.edit),
                initialText = entry.text,
                saving = saving,
                saveFailed = saveFailed,
                onSave = { text ->
                    if (!saving) {
                        saving = true
                        saveFailed = false
                        viewModel.editEntry(entry, text) { saved ->
                            saving = false
                            if (saved) {
                                route = if (current.fromTodos) AppRoute.Todos else AppRoute.Home
                            } else {
                                saveFailed = true
                            }
                        }
                    }
                },
                onBack = { route = AppRoute.EntryOptions(entry, fromTodos = current.fromTodos) },
            )
        }
        is AppRoute.TodoOptions -> TodoOptionsScreen(
            todo = current.todo,
            onEdit = { route = AppRoute.EditTodo(current.todo) },
            onResurface = { route = AppRoute.ResurfaceTodo(current.todo) },
            onToggle = {
                viewModel.toggleTodo(current.todo)
                route = AppRoute.Todos
            },
            onLetGo = {
                viewModel.letGo(current.todo)
                route = AppRoute.Todos
            },
            onBack = { route = AppRoute.Todos },
        )
        is AppRoute.TodoDetail -> TodoDetailScreen(
            todo = current.todo,
            onSource = {
                current.todo.source?.let { source ->
                    viewModel.showDay(source.noteDate)
                    viewModel.findEntry(source.entryId) {
                        route = AppRoute.ReadEntry(it, fromTodos = true)
                    }
                }
            },
            onBack = { route = if (current.fromSearch) AppRoute.Search else AppRoute.Todos },
        )
        is AppRoute.ResurfaceTodo -> TodoResurfaceScreen(
            todo = current.todo,
            today = viewModel.today(),
            onSelect = { date ->
                viewModel.showTodoAgain(current.todo, date) { saved ->
                    if (saved) route = AppRoute.Todos
                }
            },
            onBack = { route = AppRoute.TodoOptions(current.todo) },
        )
        is AppRoute.EditTodo -> {
            var saving by remember(current.todo.id) { mutableStateOf(false) }
            var saveFailed by remember(current.todo.id) { mutableStateOf(false) }
            TextEditorScreen(
                title = stringResourceCompat(R.string.edit),
                initialText = current.todo.text,
                saving = saving,
                saveFailed = saveFailed,
                onSave = { text ->
                    if (!saving) {
                        saving = true
                        saveFailed = false
                        viewModel.editTodo(current.todo, text) { saved ->
                            saving = false
                            if (saved) route = AppRoute.Todos else saveFailed = true
                        }
                    }
                },
                onBack = { route = AppRoute.TodoOptions(current.todo) },
            )
        }
    }
}

@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)

/**
 * Resolves the latest version of [initial] from the observed note, collecting the
 * note flow only while an entry screen is composed so unrelated screens do not
 * recompose on every note change.
 */
@Composable
private fun rememberLiveEntry(viewModel: SomaViewModel, initial: NoteEntry): NoteEntry {
    val liveNote by viewModel.note.collectAsState()
    return liveNote?.entries?.firstOrNull { it.id == initial.id } ?: initial
}

@Composable
private fun rememberLiveLog(viewModel: SomaViewModel, initial: LogRecord): LogRecord {
    val logs by viewModel.trackingLogs.collectAsState()
    return logs.firstOrNull { it.id == initial.id } ?: initial
}

@Composable
private fun logEditorTitle(kind: LogKind): String = stringResourceCompat(
    when (kind) {
        LogKind.MEAL -> R.string.add_meal
        LogKind.RECIPE -> R.string.add_recipe
        LogKind.WORKOUT -> R.string.add_workout
        LogKind.RECEIPT -> R.string.add_receipt
    },
)

@Composable
private fun logEditorHelp(kind: LogKind): String = stringResourceCompat(
    when (kind) {
        LogKind.MEAL -> R.string.log_meal_help
        LogKind.RECIPE -> R.string.log_recipe_help
        LogKind.WORKOUT -> R.string.log_workout_help
        LogKind.RECEIPT -> R.string.log_receipt_help
    },
)

/**
 * Persists navigation across process death for routes that carry no in-memory
 * payload — notably the Capture and add-Important editors. Their text lives in
 * [EditorDraftStore], while saved instance state contains only this small route
 * key. Entry/todo-specific routes cannot be rebuilt from a key alone and safely
 * fall back to Home.
 */
private val AppRouteSaver: Saver<AppRoute, String> = Saver(
    save = { route ->
        when (route) {
            AppRoute.Home -> "home"
            AppRoute.Todos -> "todos"
            AppRoute.Logs -> "logs"
            AppRoute.Settings -> "settings"
            AppRoute.Backup -> "backup"
            AppRoute.Browser -> "browser"
            AppRoute.About -> "about"
            AppRoute.Licenses -> "licenses"
            AppRoute.Developer -> "developer"
            AppRoute.CloudDeveloper -> "cloudDeveloper"
            AppRoute.Language -> "language"
            AppRoute.AddTodo -> "addTodo"
            AppRoute.Capture -> "capture"
            AppRoute.Photo -> "home"
            AppRoute.Calendar -> "calendar"
            AppRoute.Search -> "search"
            AppRoute.SpeechLanguages -> "speechLanguages"
            AppRoute.TranscriptionVocabulary -> "transcriptionVocabulary"
            AppRoute.Deleted -> "deleted"
            else -> "home"
        }
    },
    restore = { key ->
        when (key) {
            "todos" -> AppRoute.Todos
            "logs" -> AppRoute.Logs
            "settings" -> AppRoute.Settings
            "backup" -> AppRoute.Backup
            "browser" -> AppRoute.Browser
            "about" -> AppRoute.About
            "licenses" -> AppRoute.Licenses
            "developer" -> AppRoute.Developer
            "cloudDeveloper" -> AppRoute.CloudDeveloper
            "language" -> AppRoute.Language
            "addTodo" -> AppRoute.AddTodo
            "capture" -> AppRoute.Capture
            "calendar" -> AppRoute.Calendar
            "search" -> AppRoute.Search
            "speechLanguages" -> AppRoute.SpeechLanguages
            "transcriptionVocabulary" -> AppRoute.TranscriptionVocabulary
            "deleted" -> AppRoute.Deleted
            else -> AppRoute.Home
        }
    },
)
