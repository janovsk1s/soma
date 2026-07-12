package com.soma.app

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.storage.backup.BackupSnapshot
import com.soma.storage.backup.PortableBackupCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BackupRoute { MENU, EXPORT, IMPORT_UNLOCK, IMPORT_CONFIRM }
private enum class BackupAction { EXPORT, IMPORT }

@Composable
fun BackupScreen(viewModel: SomaViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val app = context.applicationContext as SomaApplication
    val coordinator = remember { BackupCoordinator(app) }
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf(BackupRoute.MENU) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var includeAudio by remember { mutableStateOf(true) }
    var selectedImport by remember { mutableStateOf<ByteArray?>(null) }
    var decoded by remember { mutableStateOf<BackupSnapshot?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val exportedMessage = stringResource(R.string.backup_exported)
    val exportFailedMessage = stringResource(R.string.backup_export_failed)
    val invalidBackupMessage = stringResource(R.string.backup_wrong_or_damaged)
    val shortPassphraseMessage = stringResource(R.string.backup_short_passphrase)
    val passphraseMismatchMessage = stringResource(R.string.backup_passphrases_mismatch)
    val restoredMessage = stringResource(R.string.backup_restored)
    val restoreFailedMessage = stringResource(R.string.backup_restore_failed)

    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    fun navigateBack() {
        if (route == BackupRoute.MENU) onBack() else {
            route = BackupRoute.MENU
            passphrase = ""
            confirmation = ""
            selectedImport?.fill(0)
            selectedImport = null
            decoded?.audioContainers?.forEach { it.clearPortableBytes() }
            decoded = null
            status = null
        }
    }
    BackHandler(onBack = ::navigateBack)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-soma-backup"),
    ) { uri ->
        context.setExternalFlowActive(false)
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = null
        val chars = passphrase.toCharArray()
        scope.launch {
            runCatching {
                val encoded = coordinator.export(chars, includeAudio)
                chars.fill('\u0000')
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(encoded) }
                            ?: error("Could not open backup destination")
                    }
                } finally {
                    encoded.fill(0)
                }
            }.onSuccess {
                status = exportedMessage
                passphrase = ""
                confirmation = ""
            }.onFailure {
                chars.fill('\u0000')
                status = exportFailedMessage
            }
            busy = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        context.setExternalFlowActive(false)
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            selectedImport?.fill(0)
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_IMPORT_BYTES) }
                        ?: error("Could not open backup")
                }
            }.onSuccess { bytes ->
                selectedImport = bytes
                route = BackupRoute.IMPORT_UNLOCK
                status = null
            }.onFailure {
                status = invalidBackupMessage
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(
            when (route) {
                BackupRoute.MENU -> stringResource(R.string.backup_title)
                BackupRoute.EXPORT -> stringResource(R.string.backup_export)
                BackupRoute.IMPORT_UNLOCK, BackupRoute.IMPORT_CONFIRM -> stringResource(R.string.backup_import)
            },
            ::navigateBack,
        )
        if (viewModel.isDemo) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.backup_demo_disabled),
                    color = DimInk,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
        when (route) {
            BackupRoute.MENU -> {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PagedList(BackupAction.entries) { action ->
                        SettingsItem(
                            label = stringResource(
                                if (action == BackupAction.EXPORT) R.string.backup_export else R.string.backup_import,
                            ),
                            onClick = {
                                if (action == BackupAction.EXPORT) route = BackupRoute.EXPORT
                                else {
                                    context.setExternalFlowActive(true)
                                    importLauncher.launch(arrayOf("application/x-soma-backup", "application/octet-stream", "*/*"))
                                }
                            },
                        )
                    }
                }
                status?.let { Text(it, color = DimInk, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp)) }
                Text(
                    stringResource(R.string.backup_warning),
                    color = DimInk,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }
            BackupRoute.EXPORT -> {
                Column(Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp)) {
                    LineInput(passphrase, { passphrase = it }, stringResource(R.string.backup_passphrase), Modifier.fillMaxWidth(), password = true)
                    LineInput(
                        confirmation,
                        { confirmation = it },
                        stringResource(R.string.backup_confirm_passphrase),
                        Modifier.fillMaxWidth().padding(top = 20.dp),
                        password = true,
                    )
                    SettingsItem(
                        label = stringResource(R.string.backup_include_audio),
                        trailing = stringResource(if (includeAudio) R.string.on else R.string.off),
                        onClick = { includeAudio = !includeAudio },
                    )
                    status?.let { Text(it, color = DimInk, fontSize = 16.sp) }
                }
                BackupBottomAction(if (busy) stringResource(R.string.backup_working) else stringResource(R.string.backup_export)) {
                    if (busy) return@BackupBottomAction
                    status = when {
                        passphrase.length < PortableBackupCodec.MINIMUM_PASSPHRASE_LENGTH -> shortPassphraseMessage
                        passphrase != confirmation -> passphraseMismatchMessage
                        else -> null
                    }
                    if (status == null) {
                        context.setExternalFlowActive(true)
                        exportLauncher.launch("Soma-${LocalDate.now()}.soma")
                    }
                }
            }
            BackupRoute.IMPORT_UNLOCK -> {
                Column(Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp)) {
                    LineInput(passphrase, { passphrase = it }, stringResource(R.string.backup_passphrase), Modifier.fillMaxWidth(), password = true)
                    status?.let { Text(it, color = DimInk, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp)) }
                }
                BackupBottomAction(
                    if (busy) stringResource(R.string.backup_unlocking) else stringResource(R.string.backup_unlock),
                ) {
                    val bytes = selectedImport ?: return@BackupBottomAction
                    if (busy) return@BackupBottomAction
                    busy = true
                    val chars = passphrase.toCharArray()
                    scope.launch {
                        runCatching { coordinator.decode(bytes, chars) }
                            .onSuccess {
                                decoded = it
                                route = BackupRoute.IMPORT_CONFIRM
                                status = null
                                passphrase = ""
                            }
                            .onFailure { status = invalidBackupMessage }
                        chars.fill('\u0000')
                        busy = false
                    }
                }
            }
            BackupRoute.IMPORT_CONFIRM -> {
                val snapshot = decoded
                Column(Modifier.weight(1f).fillMaxWidth().padding(top = 36.dp)) {
                    Text(stringResource(R.string.backup_replace_prompt), color = Ink, fontSize = 24.sp, lineHeight = 32.sp)
                    Text(
                        stringResource(
                            R.string.backup_contents_summary,
                            snapshot?.notes?.size ?: 0,
                            snapshot?.todos?.size ?: 0,
                        ),
                        color = DimInk,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    status?.let { Text(it, color = DimInk, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp)) }
                }
                BackupBottomAction(
                    if (busy) stringResource(R.string.backup_restoring) else stringResource(R.string.backup_replace),
                ) {
                    val value = snapshot ?: return@BackupBottomAction
                    if (busy) return@BackupBottomAction
                    busy = true
                    scope.launch {
                        runCatching { coordinator.restore(value) }
                            .onSuccess {
                                selectedImport?.fill(0)
                                selectedImport = null
                                value.audioContainers.forEach { it.clearPortableBytes() }
                                status = restoredMessage
                                decoded = null
                                route = BackupRoute.MENU
                                viewModel.refreshCalendar(returnHome = true)
                            }
                            .onFailure { status = restoreFailedMessage }
                        busy = false
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupBottomAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp).then(tapModifier(onClick, label)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Normal)
    }
}

private fun readLimited(input: InputStream, maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= maximumBytes) { "Backup is too large for this device" }
        output.write(buffer, 0, read)
    }
    buffer.fill(0)
    return output.toByteArray()
}

private const val MAX_IMPORT_BYTES = 128 * 1024 * 1024 + 1_024
