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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.soma.core.model.NoteEntry
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
    data object Calendar : AppRoute
    data object SpeechLanguages : AppRoute
    data object TranscriptionVocabulary : AppRoute
    data class ReadEntry(val entry: NoteEntry, val fromTodos: Boolean = false) : AppRoute
    data class EntryOptions(
        val entry: NoteEntry,
        val returnToRead: Boolean = false,
        val fromTodos: Boolean = false,
    ) : AppRoute
    data class EditEntry(val entry: NoteEntry, val fromTodos: Boolean = false) : AppRoute
    data class SelectImportant(val entry: NoteEntry, val fromTodos: Boolean = false) : AppRoute
    data class TodoOptions(val todo: Todo) : AppRoute
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
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startRecording()
        else Toast.makeText(context, R.string.microphone_permission_needed, Toast.LENGTH_LONG).show()
    }
    val requestRecording = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startRecording()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
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
                route = AppRoute.Todos
            },
            onResurfacedImportant = { route = AppRoute.TodoOptions(it) },
            onSettings = {
                viewModel.stopRecording()
                route = AppRoute.Settings
            },
            onCalendar = {
                viewModel.stopRecording()
                route = AppRoute.Calendar
            },
            onCapture = { route = AppRoute.Capture },
            onReadEntry = {
                viewModel.stopRecording()
                route = AppRoute.ReadEntry(it)
            },
            onEntryOptions = {
                viewModel.stopRecording()
                route = AppRoute.EntryOptions(it)
            },
            onRecordRequested = requestRecording,
        )
        AppRoute.Todos -> TodosScreen(
            viewModel = viewModel,
            onTodoOptions = { route = AppRoute.TodoOptions(it) },
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
        AppRoute.Settings -> SettingsScreen(
            onSpeechLanguages = { route = AppRoute.SpeechLanguages },
            onTranscriptionVocabulary = { route = AppRoute.TranscriptionVocabulary },
            onBackup = { route = AppRoute.Backup },
            onBrowser = { route = AppRoute.Browser },
            onAbout = { route = AppRoute.About },
            onBack = { route = AppRoute.Home },
        )
        AppRoute.SpeechLanguages -> SpeechLanguagesScreen { route = AppRoute.Settings }
        AppRoute.TranscriptionVocabulary -> {
            val store = remember { TranscriptionVocabularyStore(context) }
            val initial = remember { com.soma.core.model.TranscriptionVocabulary.asEditableText(store.read()) }
            var saving by remember { mutableStateOf(false) }
            var saveFailed by remember { mutableStateOf(false) }
            TextEditorScreen(
                title = stringResourceCompat(R.string.settings_transcription_vocabulary),
                initialText = initial,
                saving = saving,
                saveFailed = saveFailed,
                allowBlank = true,
                supportingText = stringResourceCompat(R.string.transcription_vocabulary_help),
                onSave = { text ->
                    if (!saving) {
                        saving = true
                        saveFailed = false
                        val characters = text.toCharArray()
                        val saved = runCatching { store.write(characters) }.isSuccess
                        characters.fill('\u0000')
                        saving = false
                        if (saved) route = AppRoute.Settings else saveFailed = true
                    }
                },
                onBack = { route = AppRoute.Settings },
            )
        }
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
        AppRoute.Calendar -> CalendarScreen(
            viewModel = viewModel,
            onSelect = { picked ->
                viewModel.showDay(picked)
                route = AppRoute.Home
            },
            onBack = { route = AppRoute.Home },
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
                playing = (playback as? PlaybackState.Playing)?.audioId == entry.audio?.fileId,
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
                onMarkImportant = { route = AppRoute.SelectImportant(entry, current.fromTodos) },
                onReturn = {
                    viewModel.toggleReturnLater(entry)
                    route = origin
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
            AppRoute.Calendar -> "calendar"
            AppRoute.SpeechLanguages -> "speechLanguages"
            AppRoute.TranscriptionVocabulary -> "transcriptionVocabulary"
            else -> "home"
        }
    },
    restore = { key ->
        when (key) {
            "todos" -> AppRoute.Todos
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
            "speechLanguages" -> AppRoute.SpeechLanguages
            "transcriptionVocabulary" -> AppRoute.TranscriptionVocabulary
            else -> AppRoute.Home
        }
    },
)
