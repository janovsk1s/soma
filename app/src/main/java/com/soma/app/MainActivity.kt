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
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        @Suppress("DEPRECATION")
        setTaskDescription(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription.Builder()
                    .setLabel(getString(R.string.app_name))
                    .setPrimaryColor(android.graphics.Color.WHITE)
                    .setBackgroundColor(android.graphics.Color.WHITE)
                    .setStatusBarColor(android.graphics.Color.WHITE)
                    .setNavigationBarColor(android.graphics.Color.WHITE)
                    .build()
            } else {
                ActivityManager.TaskDescription(getString(R.string.app_name), null, android.graphics.Color.WHITE)
            },
        )
        setContent { SomaTheme { SomaApp(somaViewModel, homeResetSignal) } }
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
    data object Language : AppRoute
    data object AddTodo : AppRoute
    data class ReadEntry(val entry: NoteEntry) : AppRoute
    data class EntryOptions(val entry: NoteEntry, val returnToRead: Boolean = false) : AppRoute
    data class EditEntry(val entry: NoteEntry) : AppRoute
    data class TodoOptions(val todo: Todo) : AppRoute
    data class EditTodo(val todo: Todo) : AppRoute
}

@Composable
private fun SomaApp(viewModel: SomaViewModel, homeResetSignal: Int) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var route: AppRoute by remember { mutableStateOf(AppRoute.Home) }
    val playback by viewModel.playbackState.collectAsState()
    val liveNote by viewModel.note.collectAsState()
    fun liveEntry(initial: NoteEntry): NoteEntry =
        liveNote?.entries?.firstOrNull { it.id == initial.id } ?: initial
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
            onSettings = {
                viewModel.stopRecording()
                route = AppRoute.Settings
            },
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
                    viewModel.findEntry(source.entryId) { route = AppRoute.ReadEntry(it) }
                }
            },
            onBack = { route = AppRoute.Home },
        )
        AppRoute.Settings -> SettingsScreen(
            onBackup = { route = AppRoute.Backup },
            onBrowser = { route = AppRoute.Browser },
            onAbout = { route = AppRoute.About },
            onBack = { route = AppRoute.Home },
        )
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
        AppRoute.Language -> LanguageScreen(
            onSelected = { language -> activity?.switchLanguage(language) },
            onBack = { route = AppRoute.Developer },
        )
        AppRoute.AddTodo -> TextEditorScreen(
            title = stringResourceCompat(R.string.add_details),
            initialText = "",
            onSave = {
                viewModel.addTodo(it)
                route = AppRoute.Todos
            },
            onBack = { route = AppRoute.Todos },
        )
        is AppRoute.ReadEntry -> {
            val entry = liveEntry(current.entry)
            EntryReadScreen(
                entry = entry,
                playing = (playback as? PlaybackState.Playing)?.audioId == entry.audio?.fileId,
                onPlay = { viewModel.togglePlayback(entry) },
                onOptions = { route = AppRoute.EntryOptions(entry, returnToRead = true) },
                onBack = { route = AppRoute.Home },
            )
        }
        is AppRoute.EntryOptions -> {
            val entry = liveEntry(current.entry)
            EntryOptionsScreen(
                entry = entry,
                onEdit = { route = AppRoute.EditEntry(entry) },
                onReturn = {
                    viewModel.toggleReturnLater(entry)
                    route = AppRoute.Home
                },
                onPlay = { viewModel.togglePlayback(entry) },
                onDeleteAudio = {
                    viewModel.deleteAudio(entry)
                    route = AppRoute.Home
                },
                onDeleteEntry = {
                    viewModel.deleteEntry(entry)
                    route = AppRoute.Home
                },
                onBack = {
                    route = if (current.returnToRead) AppRoute.ReadEntry(entry) else AppRoute.Home
                },
            )
        }
        is AppRoute.EditEntry -> {
            val entry = liveEntry(current.entry)
            TextEditorScreen(
                title = stringResourceCompat(R.string.edit),
                initialText = entry.text,
                onSave = {
                    viewModel.editEntry(entry, it)
                    route = AppRoute.Home
                },
                onBack = { route = AppRoute.EntryOptions(entry) },
            )
        }
        is AppRoute.TodoOptions -> TodoOptionsScreen(
            todo = current.todo,
            onEdit = { route = AppRoute.EditTodo(current.todo) },
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
        is AppRoute.EditTodo -> TextEditorScreen(
            title = stringResourceCompat(R.string.edit),
            initialText = current.todo.text,
            onSave = {
                viewModel.editTodo(current.todo, it)
                route = AppRoute.Todos
            },
            onBack = { route = AppRoute.TodoOptions(current.todo) },
        )
    }
}

@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)
