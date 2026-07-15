package com.soma.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.whisper.LocalWhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LocalModelAction { TINY, BASE, DOWNLOAD, IMPORT, REMOVE }

/**
 * Chooses which local Whisper model transcribes recordings. Tiny ships inside
 * the APK and always works; base arrives either through the Wi-Fi download
 * (cloud flavor only) or through a user-picked file, and either way becomes
 * loadable only after matching the registry's pinned SHA-256.
 */
@Composable
fun LocalModelScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember { LocalModelStore(context) }
    val downloader = remember { cloudFeatures(context).modelDownloader() }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(SomaPrefs.localWhisperModel(context)) }
    var baseInstalled by remember {
        mutableStateOf(store.installedFile(LocalWhisperModel.BASE) != null)
    }
    var downloading by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val readyMessage = stringResource(R.string.local_model_ready)
    val invalidMessage = stringResource(R.string.local_model_invalid)
    val importFailedMessage = stringResource(R.string.local_model_import_failed)
    val wifiRequiredMessage = stringResource(R.string.local_model_wifi_required)
    val downloadFailedMessage = stringResource(R.string.local_model_download_failed)

    fun select(model: LocalWhisperModel) {
        selected = model
        SomaPrefs.setLocalWhisperModel(context, model)
    }

    fun installed() {
        baseInstalled = true
        status = readyMessage
        // Acquiring base is an explicit choice; start using it right away.
        select(LocalWhisperModel.BASE)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        context.setExternalFlowActive(false)
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        status = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        store.importFrom(LocalWhisperModel.BASE, input)
                    } ?: error("Could not open the picked file")
                }
            }.onSuccess {
                installed()
            }.onFailure { error ->
                status = if (error is ModelVerificationException) invalidMessage else importFailedMessage
            }
            importing = false
        }
    }

    fun startDownload() {
        val fetcher = downloader ?: return
        downloading = true
        progressPercent = 0
        status = null
        scope.launch {
            runCatching {
                fetcher.download(LocalWhisperModel.BASE) { downloaded, total ->
                    progressPercent = (downloaded * 100L / total).toInt().coerceIn(0, 100)
                }
            }.onSuccess {
                installed()
            }.onFailure { error ->
                status = when (error) {
                    is ModelWifiRequiredException -> wifiRequiredMessage
                    is ModelVerificationException -> invalidMessage
                    else -> downloadFailedMessage
                }
            }
            downloading = false
        }
    }

    val actions = buildList {
        add(LocalModelAction.TINY)
        add(LocalModelAction.BASE)
        if (!baseInstalled && downloader != null) add(LocalModelAction.DOWNLOAD)
        if (!baseInstalled) add(LocalModelAction.IMPORT)
        if (baseInstalled) add(LocalModelAction.REMOVE)
    }

    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.settings_local_model), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(actions) { action ->
                SettingsItem(
                    label = when (action) {
                        LocalModelAction.TINY -> stringResource(R.string.local_model_tiny)
                        LocalModelAction.BASE -> stringResource(R.string.local_model_base)
                        LocalModelAction.DOWNLOAD ->
                            if (downloading) {
                                stringResource(R.string.local_model_downloading, progressPercent)
                            } else {
                                stringResource(R.string.local_model_download)
                            }
                        LocalModelAction.IMPORT ->
                            if (importing) {
                                stringResource(R.string.local_model_checking)
                            } else {
                                stringResource(R.string.local_model_import)
                            }
                        LocalModelAction.REMOVE ->
                            if (confirmRemove) {
                                stringResource(R.string.local_model_remove_confirm)
                            } else {
                                stringResource(R.string.local_model_remove)
                            }
                    },
                    trailing = when (action) {
                        LocalModelAction.TINY -> "✓".takeIf { selected == LocalWhisperModel.TINY }
                        LocalModelAction.BASE -> when {
                            !baseInstalled -> stringResource(R.string.local_model_not_downloaded)
                            selected == LocalWhisperModel.BASE ->
                                "✓ · " + stringResource(R.string.local_model_size, BASE_MEBIBYTES)
                            else -> stringResource(R.string.local_model_size, BASE_MEBIBYTES)
                        }
                        else -> null
                    },
                    onClick = when (action) {
                        LocalModelAction.TINY -> ({
                            confirmRemove = false
                            select(LocalWhisperModel.TINY)
                        })
                        LocalModelAction.BASE -> if (baseInstalled) {
                            {
                                confirmRemove = false
                                select(LocalWhisperModel.BASE)
                            }
                        } else {
                            null
                        }
                        LocalModelAction.DOWNLOAD -> ({ if (!downloading) startDownload() })
                        LocalModelAction.IMPORT -> ({
                            if (!importing) {
                                context.setExternalFlowActive(true)
                                importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                            }
                        })
                        LocalModelAction.REMOVE -> ({
                            if (confirmRemove) {
                                store.delete(LocalWhisperModel.BASE)
                                baseInstalled = false
                                confirmRemove = false
                                status = null
                                if (selected == LocalWhisperModel.BASE) select(LocalWhisperModel.TINY)
                            } else {
                                confirmRemove = true
                            }
                        })
                    },
                )
            }
        }
        status?.let { Text(it, color = DimInk, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp)) }
        Text(
            stringResource(R.string.local_model_about),
            color = DimInk,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(bottom = 18.dp),
        )
    }
}

/** 59,707,625 bytes rounded to whole mebibytes for the row label. */
private val BASE_MEBIBYTES: Int =
    ((LocalWhisperModel.BASE.byteCount + (1L shl 19)) shr 20).toInt()
