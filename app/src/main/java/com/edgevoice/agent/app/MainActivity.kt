package com.edgevoice.agent.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView
import com.edgevoice.agent.ui.model.AgentUiActions
import com.edgevoice.agent.ui.model.PermissionTarget
import com.edgevoice.agent.ui.model.RecoveryAction
import com.edgevoice.agent.ui.screen.AgentScreen

/**
 * 应用入口：仅做装配层三件事 —— 权限、Compose 宿主、PreviewView 注入。
 * 业务逻辑全部在 [AgentViewModel]，本 Activity 不写任何状态判断以外的逻辑。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EdgeVoiceRoot()
        }
    }
}

@Composable
private fun EdgeVoiceRoot(vm: AgentViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val uiState by vm.uiState.collectAsState()

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun permanentlyDenied(permission: String): Boolean =
        (context as? Activity)?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false

    // 首版仅支持前台会话。权限弹窗通常只触发 ON_PAUSE，故在 ON_STOP 才释放媒体。
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (context as? Activity)?.isChangingConfigurations != true) {
                vm.stopSession()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.checkPermissions(hasPermission(Manifest.permission.RECORD_AUDIO), hasPermission(Manifest.permission.CAMERA))
            }
        }
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) vm.stopSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(screenOff)
        }
    }

    // 轻量操作提示经 Toast 呈现；会话错误同时保留在字幕区。
    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    RenderFpsObserver(onFps = vm::updateRenderFps)

    // ---- 渐进式权限：首次使用对应能力才请求 ----
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startAfterMicGranted()
        else vm.reportPermissionDenied(PermissionTarget.MICROPHONE, permanentlyDenied(Manifest.permission.RECORD_AUDIO))
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.enableCamera()
        else vm.reportPermissionDenied(PermissionTarget.CAMERA, permanentlyDenied(Manifest.permission.CAMERA))
    }

    val micToggle = {
        if (vm.running.value) {
            vm.stopSession()
        } else if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            vm.startAfterMicGranted()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val actions = AgentUiActions(
        onModeChange = vm::changeMode,
        onMicClick = micToggle,
        onVisionToggle = {
            if (vm.visionEnabled.value) {
                vm.unbindCamera()
            } else if (hasPermission(Manifest.permission.CAMERA)) {
                vm.enableCamera()
            } else {
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        // 光球作为麦克风的大热区，语义一致
        onOrbClick = micToggle,
        onHistorySaveConfirm = vm::confirmHistorySave,
        onHistorySaveDiscard = vm::discardHistorySave,
        onRecoveryClick = {
            when (uiState.session.recovery?.action) {
                RecoveryAction.RETRY_SESSION -> {
                    if (vm.running.value) vm.stopSession()
                    if (hasPermission(Manifest.permission.RECORD_AUDIO)) vm.startAfterMicGranted()
                    else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                RecoveryAction.REQUEST_MICROPHONE -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                RecoveryAction.REQUEST_CAMERA -> cameraLauncher.launch(Manifest.permission.CAMERA)
                RecoveryAction.OPEN_SETTINGS -> context.startActivity(Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ))
                RecoveryAction.NONE, null -> Unit
            }
        },
    )

    // CameraX 预览槽：仅 VisionCard 进入组合时创建；factory 即 SurfaceProvider 就绪点
    val cameraPreview: @Composable () -> Unit = {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    vm.onPreviewSurfaceReady(lifecycleOwner, surfaceProvider)
                }
            },
        )
    }

    AgentScreen(
        state = uiState,
        actions = actions,
        cameraPreview = cameraPreview,
    )
}
