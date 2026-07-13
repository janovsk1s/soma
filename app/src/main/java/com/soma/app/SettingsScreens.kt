package com.soma.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.core.model.SupportedLanguage

private enum class SettingsAction { VIBRATION, REMINDER, TRANSCRIPTION, BACKUP, BROWSER, ABOUT }

@Composable
fun SettingsScreen(
    onBackup: () -> Unit,
    onBrowser: () -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var vibration by remember { mutableStateOf(SomaPrefs.vibration(context)) }
    var reminder by remember { mutableStateOf(SomaPrefs.reminder(context)) }
    var transcription by remember { mutableStateOf(SomaPrefs.transcription(context)) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        reminder = granted
        DailyReminderScheduler.setEnabled(context, granted)
    }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.settings_title), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(SettingsAction.entries) { action ->
                SettingsItem(
                    label = when (action) {
                        SettingsAction.VIBRATION -> stringResource(R.string.settings_vibration)
                        SettingsAction.REMINDER -> stringResource(R.string.settings_reminder)
                        SettingsAction.TRANSCRIPTION -> stringResource(R.string.settings_transcription)
                        SettingsAction.BACKUP -> stringResource(R.string.settings_backup)
                        SettingsAction.BROWSER -> stringResource(R.string.settings_browser)
                        SettingsAction.ABOUT -> stringResource(R.string.settings_about)
                    },
                    trailing = when (action) {
                        SettingsAction.VIBRATION -> stringResource(if (vibration) R.string.on else R.string.off)
                        SettingsAction.REMINDER -> stringResource(if (reminder) R.string.on else R.string.off)
                        SettingsAction.TRANSCRIPTION -> stringResource(if (transcription) R.string.on else R.string.off)
                        else -> null
                    },
                    onClick = {
                        when (action) {
                            SettingsAction.VIBRATION -> {
                                vibration = !vibration
                                SomaPrefs.setVibration(context, vibration)
                            }
                            SettingsAction.REMINDER -> {
                                if (reminder) {
                                    reminder = false
                                    DailyReminderScheduler.setEnabled(context, false)
                                } else if (NotificationPermission.granted(context)) {
                                    reminder = true
                                    DailyReminderScheduler.setEnabled(context, true)
                                } else {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            SettingsAction.TRANSCRIPTION -> {
                                transcription = !transcription
                                SomaPrefs.setTranscription(context, transcription)
                                if (transcription) TranscriptionScheduler.enqueue(context)
                            }
                            SettingsAction.BACKUP -> onBackup()
                            SettingsAction.BROWSER -> onBrowser()
                            SettingsAction.ABOUT -> onAbout()
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun AboutScreen(onDeveloper: () -> Unit, onLicenses: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val feedback = LocalHapticFeedback.current
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    val hiddenTap = {
        val now = System.currentTimeMillis()
        taps = if (now - lastTap < 600L) taps + 1 else 1
        lastTap = now
        if (taps >= 3) {
            taps = 0
            performSomaHaptic(context, feedback)
            onDeveloper()
        }
    }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.about_title), onBack)
        Column(Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).pointerInput(Unit) {
                    detectTapGestures(onTap = { hiddenTap() })
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.app_name), color = Ink, fontSize = 30.sp, modifier = Modifier.weight(1f))
                Text(
                    "v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",
                    color = DimInk,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.End,
                )
            }
            Text(
                stringResource(R.string.app_description),
                color = Ink,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                stringResource(R.string.about_byline),
                color = Ink,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 36.dp),
            )
            Text(
                stringResource(R.string.about_license),
                color = DimInk,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 12.dp)
                    .then(tapModifier(onLicenses, stringResource(R.string.about_license))),
            )
        }
    }
}

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val resources = LocalResources.current
    val notices = remember(resources) {
        resources.openRawResource(R.raw.legal_notices).bufferedReader().use { it.readText() }
    }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.about_license), onBack)
        Text(
            text = notices,
            color = Ink,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(vertical = 20.dp),
        )
    }
}

private enum class DeveloperAction { LIGHT_MODE, DEMO, RETURN_HOME, BATTERY_SAVER, LANGUAGE, CLOUD }

@Composable
fun DeveloperScreen(
    onLanguage: () -> Unit,
    onCloud: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var lightMode by remember { mutableStateOf(SomaPrefs.lightMode(context)) }
    var demo by remember { mutableStateOf(SomaPrefs.demoMode(context)) }
    var returnHome by remember { mutableStateOf(SomaPrefs.returnHome(context)) }
    var batterySaver by remember { mutableStateOf(SomaPrefs.transcribeInBatterySaver(context)) }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.developer_title), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(DeveloperAction.entries) { action ->
                SettingsItem(
                    label = when (action) {
                        DeveloperAction.LIGHT_MODE -> stringResource(R.string.developer_light_mode)
                        DeveloperAction.DEMO -> stringResource(R.string.developer_demo)
                        DeveloperAction.RETURN_HOME -> stringResource(R.string.developer_return_home)
                        DeveloperAction.BATTERY_SAVER -> stringResource(R.string.developer_transcribe_power_saver)
                        DeveloperAction.LANGUAGE -> stringResource(R.string.developer_language)
                        DeveloperAction.CLOUD -> stringResource(R.string.developer_cloud)
                    },
                    trailing = when (action) {
                        DeveloperAction.LIGHT_MODE -> stringResource(if (lightMode) R.string.on else R.string.off)
                        DeveloperAction.DEMO -> stringResource(if (demo) R.string.on else R.string.off)
                        DeveloperAction.RETURN_HOME -> stringResource(if (returnHome) R.string.on else R.string.off)
                        DeveloperAction.BATTERY_SAVER -> stringResource(if (batterySaver) R.string.on else R.string.off)
                        DeveloperAction.LANGUAGE -> SomaPrefs.language(context).languageTag
                        DeveloperAction.CLOUD -> stringResource(
                            if (BuildConfig.CLOUD_FEATURES_AVAILABLE) R.string.developer_experimental else R.string.developer_unavailable,
                        )
                    },
                    onClick = {
                        when (action) {
                            DeveloperAction.LIGHT_MODE -> {
                                lightMode = !lightMode
                                SomaPalette.lightMode = lightMode
                                SomaPrefs.setLightMode(context, lightMode)
                            }
                            DeveloperAction.DEMO -> {
                                demo = !demo
                                SomaPrefs.setDemoMode(context, demo)
                                (context.applicationContext as SomaApplication).regenerateDemo()
                                onRestart()
                            }
                            DeveloperAction.RETURN_HOME -> {
                                returnHome = !returnHome
                                SomaPrefs.setReturnHome(context, returnHome)
                            }
                            DeveloperAction.BATTERY_SAVER -> {
                                batterySaver = !batterySaver
                                SomaPrefs.setTranscribeInBatterySaver(context, batterySaver)
                            }
                            DeveloperAction.LANGUAGE -> onLanguage()
                            DeveloperAction.CLOUD -> onCloud()
                        }
                    },
                )
            }
        }
    }
}

private enum class CloudDeveloperAction { TRANSCRIPTION, PROVIDER, WIFI_ONLY, AI_TODOS, GROQ_KEY, ELEVENLABS_KEY }

@Composable
fun CloudDeveloperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { cloudFeatures(context) }
    var settings by remember { mutableStateOf(controller.settings()) }
    var editingKey by remember { mutableStateOf<CloudSpeechProvider?>(null) }
    var keyText by remember { mutableStateOf("") }

    fun navigateBack() {
        if (editingKey != null) {
            keyText = ""
            editingKey = null
        } else {
            onBack()
        }
    }
    BackHandler(onBack = ::navigateBack)

    fun refresh() {
        settings = controller.settings()
    }

    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(
            stringResource(R.string.developer_cloud),
            ::navigateBack,
        )
        if (editingKey != null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp)) {
                Text(
                    stringResource(
                        if (editingKey == CloudSpeechProvider.GROQ) R.string.developer_groq_key else R.string.developer_elevenlabs_key,
                    ),
                    color = Ink,
                    fontSize = 20.sp,
                )
                LineInput(
                    value = keyText,
                    onValueChange = { keyText = it },
                    placeholder = stringResource(R.string.developer_api_key_hint),
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    password = true,
                )
                Text(
                    stringResource(R.string.developer_key_storage),
                    color = DimInk,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 22.dp),
                )
            }
            BackupBottomAction(stringResource(R.string.save)) {
                val provider = editingKey ?: return@BackupBottomAction
                controller.setApiKey(provider, keyText.toCharArray())
                keyText = ""
                editingKey = null
                refresh()
            }
            return@Column
        }

        if (!settings.available) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.developer_cloud_flavor_required),
                    color = DimInk,
                    fontSize = 18.sp,
                    lineHeight = 25.sp,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(CloudDeveloperAction.entries) { action ->
                SettingsItem(
                    label = when (action) {
                        CloudDeveloperAction.TRANSCRIPTION -> stringResource(R.string.developer_cloud_transcription)
                        CloudDeveloperAction.PROVIDER -> stringResource(R.string.developer_provider)
                        CloudDeveloperAction.WIFI_ONLY -> stringResource(R.string.developer_wifi_only)
                        CloudDeveloperAction.AI_TODOS -> stringResource(R.string.developer_ai_todos)
                        CloudDeveloperAction.GROQ_KEY -> stringResource(R.string.developer_groq_key)
                        CloudDeveloperAction.ELEVENLABS_KEY -> stringResource(R.string.developer_elevenlabs_key)
                    },
                    trailing = when (action) {
                        CloudDeveloperAction.TRANSCRIPTION -> stringResource(if (settings.transcriptionEnabled) R.string.on else R.string.off)
                        CloudDeveloperAction.PROVIDER -> when (settings.provider) {
                            CloudSpeechProvider.GROQ -> "Groq"
                            CloudSpeechProvider.ELEVENLABS -> "ElevenLabs"
                        }
                        CloudDeveloperAction.WIFI_ONLY -> stringResource(if (settings.wifiOnly) R.string.on else R.string.off)
                        CloudDeveloperAction.AI_TODOS -> stringResource(if (settings.aiTodoSuggestions) R.string.on else R.string.off)
                        CloudDeveloperAction.GROQ_KEY -> stringResource(if (settings.hasGroqKey) R.string.developer_key_saved else R.string.developer_key_missing)
                        CloudDeveloperAction.ELEVENLABS_KEY -> stringResource(if (settings.hasElevenLabsKey) R.string.developer_key_saved else R.string.developer_key_missing)
                    },
                    onClick = {
                        when (action) {
                            CloudDeveloperAction.TRANSCRIPTION -> controller.setTranscriptionEnabled(!settings.transcriptionEnabled)
                            CloudDeveloperAction.PROVIDER -> controller.setProvider(
                                if (settings.provider == CloudSpeechProvider.GROQ) CloudSpeechProvider.ELEVENLABS else CloudSpeechProvider.GROQ,
                            )
                            CloudDeveloperAction.WIFI_ONLY -> controller.setWifiOnly(!settings.wifiOnly)
                            CloudDeveloperAction.AI_TODOS -> controller.setAiTodoSuggestions(!settings.aiTodoSuggestions)
                            CloudDeveloperAction.GROQ_KEY -> editingKey = CloudSpeechProvider.GROQ
                            CloudDeveloperAction.ELEVENLABS_KEY -> editingKey = CloudSpeechProvider.ELEVENLABS
                        }
                        refresh()
                    },
                )
            }
        }
        Text(
            stringResource(R.string.developer_cloud_privacy),
            color = DimInk,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
fun LanguageScreen(onSelected: (SupportedLanguage) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val current = SomaPrefs.language(context)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.developer_language), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(SupportedLanguage.entries) { language ->
                SettingsItem(
                    label = language.displayName(),
                    trailing = if (language == current) "✓" else null,
                    onClick = if (language == current) null else ({ onSelected(language) }),
                )
            }
        }
    }
}

@Composable
private fun SupportedLanguage.displayName(): String = stringResource(
    when (this) {
        SupportedLanguage.ENGLISH -> R.string.language_name_english
        SupportedLanguage.LATVIAN -> R.string.language_name_latvian
        SupportedLanguage.ESTONIAN -> R.string.language_name_estonian
        SupportedLanguage.LITHUANIAN -> R.string.language_name_lithuanian
        SupportedLanguage.FINNISH -> R.string.language_name_finnish
        SupportedLanguage.SWEDISH -> R.string.language_name_swedish
        SupportedLanguage.GERMAN -> R.string.language_name_german
        SupportedLanguage.SLOVAK -> R.string.language_name_slovak
    },
)
