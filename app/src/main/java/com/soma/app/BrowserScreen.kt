package com.soma.app

import android.Manifest
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = context as? LifecycleOwner
    val app = context.applicationContext as SomaApplication
    val repositories = remember { app.repositories() }
    val lightMode = SomaPrefs.lightMode(context)
    val controller = remember(lightMode) {
        val audio = EncryptedBrowserViewAudioProvider(app::encryptedAudioFile, app.audioKeyProvider)
        val images = EncryptedBrowserViewImageProvider(app::encryptedImageFile, app.imageKeyProvider)
        val source = RepositoryBrowserViewDataSource(repositories.notes, repositories.todos, audio, images)
        BrowserViewController(source, lightMode)
    }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val running = state as? BrowserViewState.Running
    var permissionDenied by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        if (granted) scope.launch { controller.start() }
    }
    val requestStart: () -> Unit = {
        if (NotificationPermission.granted(context)) {
            scope.launch { controller.start() }
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun stopAndBack() {
        controller.stop()
        onBack()
    }
    BackHandler(onBack = ::stopAndBack)

    DisposableEffect(controller, activity, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stop()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.close()
            NotificationCenter.cancelBrowser(context)
        }
    }
    LaunchedEffect(running != null) {
        if (running != null) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            NotificationCenter.showBrowserRunning(context, running.endpoint.url)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            NotificationCenter.cancelBrowser(context)
        }
    }

    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.browser_title), ::stopAndBack)
        if (!controller.available) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.browser_unavailable),
                    color = DimInk,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when (val current = state) {
                BrowserViewState.Off -> BrowserStartAction(requestStart)
                BrowserViewState.Starting -> StatusText(stringResource(R.string.browser_starting))
                is BrowserViewState.Running -> {
                    Text(
                        current.endpoint.url,
                        color = Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 48.dp),
                    )
                    if (!current.authenticated) {
                        Text(
                            stringResource(R.string.browser_code, current.endpoint.accessCode),
                            color = Ink,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.padding(top = 32.dp),
                        )
                    }
                    Text(
                        stringResource(if (current.authenticated) R.string.browser_connected else R.string.browser_waiting),
                        color = DimInk,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    Text(
                        stringResource(R.string.browser_security_note),
                        color = DimInk,
                        fontSize = 16.sp,
                        lineHeight = 23.sp,
                        modifier = Modifier.padding(top = 36.dp),
                    )
                }
                is BrowserViewState.StartFailed -> StatusText(
                    if (current.reason == BrowserViewStartFailure.NO_ACTIVE_WIFI) {
                        stringResource(R.string.browser_wifi_required)
                    } else {
                        stringResource(R.string.browser_start_failed)
                    },
                )
                is BrowserViewState.Stopped -> StatusText(stringResource(R.string.browser_stopped))
                BrowserViewState.Unavailable -> Unit
            }
            if (permissionDenied) {
                Text(
                    stringResource(R.string.browser_notification_permission),
                    color = DimInk,
                    fontSize = 14.sp,
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp).then(
                tapModifier(
                    onClick = {
                        if (running != null) controller.stop() else requestStart()
                    },
                    label = if (running != null) stringResource(R.string.browser_stop) else stringResource(R.string.browser_start),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (running != null) R.string.browser_stop else R.string.browser_start),
                color = Ink,
                fontSize = 20.sp,
            )
        }
    }
}

@Composable
private fun BrowserStartAction(onStart: () -> Unit) {
    Box(Modifier.fillMaxSize().then(tapModifier(onStart, stringResource(R.string.browser_start))), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.browser_start), color = Ink, fontSize = 24.sp)
    }
}

@Composable
private fun StatusText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = DimInk, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}
