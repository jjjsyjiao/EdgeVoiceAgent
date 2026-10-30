package com.edgevoice.agent.media.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor

/** Android 26+ call audio ownership. No Bluetooth scan or device identity access. */
class AndroidAudioSessionController(context: Context) : AudioSessionController by
    ManagedAudioSessionController(AndroidAudioSessionBackend(context.applicationContext))

@Suppress("DEPRECATION")
private class AndroidAudioSessionBackend(private val context: Context) : AudioSessionBackend {
    private val manager = checkNotNull(context.getSystemService(AudioManager::class.java)) {
        "系统音频服务不可用"
    }
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private data class SavedState(val mode: Int, val speaker: Boolean)
    private var saved: SavedState? = null
    private var focus: AudioFocusRequest? = null
    private var devices: AudioDeviceCallback? = null
    private var communicationListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var ownsCommunicationRequest = false
    private var ownsCommunicationMode = false
    private val routeLock = Any()
    private val routeConfirmation = AudioRouteConfirmation()
    private var backendEpoch = 0L
    private var requestEpoch = 0L
    private var confirmationTimeout: Runnable? = null
    private var routeFailure: ((String) -> Unit)? = null
    private var routeChanged: (() -> Unit)? = null

    override fun acquire(onInterruption: (String) -> Unit, onDevicesChanged: () -> Unit) {
        val currentEpoch = synchronized(routeLock) {
            routeFailure = onInterruption
            routeChanged = onDevicesChanged
            ++backendEpoch
        }
        check(context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED) { "应用缺少 MODIFY_AUDIO_SETTINGS 音频设置权限" }
        check(manager.mode != AudioManager.MODE_IN_CALL) { "系统电话正在占用音频，请结束通话后重试" }
        check(!manager.isMicrophoneMute) { "系统麦克风已静音，请在系统设置中解除静音后重试" }
        saved = SavedState(manager.mode, manager.isSpeakerphoneOn)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(communicationAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) {
                    onInterruption("通话音频焦点被电话或其他应用占用，会话已停止，请稍后手动重试")
                }
                // AUDIOFOCUS_GAIN deliberately does not restart a stopped session.
            }, handler)
            .build()
        focus = request
        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "无法获得通话音频焦点，请结束其他通话或录音后重试"
        }
        ownsCommunicationMode = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION

        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
        }
        devices = deviceCallback
        manager.registerAudioDeviceCallback(deviceCallback, handler)
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener {
                // The callback argument can be stale after another queued switch.
                // Query the current route and check the request/session epoch instead.
                checkCommunicationRoute(currentEpoch)
            }
            communicationListener = listener
            manager.addOnCommunicationDeviceChangedListener(executor, listener)
        } else {
            // We do not initiate asynchronous SCO against a fixed 24 kHz pipeline.
            // A system-established SCO route can be reused; losing it stops capture.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val state = intent?.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR,
                    )
                    if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) onDevicesChanged()
                }
            }
            scoReceiver = receiver
            context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        }
    }

    override fun availableRoutes(): List<AudioRouteCandidate> {
        check(manager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            "系统已收回通话音频模式，会话已停止，请结束其他通话后重试"
        }
        val outputs = if (Build.VERSION.SDK_INT >= 31) {
            manager.availableCommunicationDevices
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        val preferredId = if (Build.VERSION.SDK_INT >= 31) manager.communicationDevice?.id else null
        val routes = outputs.mapNotNull { device ->
            val kind = kindOf(device.type) ?: return@mapNotNull null
            if (Build.VERSION.SDK_INT < 31 && kind == AudioRouteKind.BLUETOOTH &&
                !manager.isBluetoothScoOn
            ) return@mapNotNull null
            AudioRouteCandidate(device.id, kind, labelOf(kind), device.id == preferredId)
        }
        if (Build.VERSION.SDK_INT < 31 &&
            routes.none { it.kind == AudioRouteKind.WIRED_HEADSET } &&
            !manager.isBluetoothScoOn && outputs.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        ) {
            throw IllegalStateException(
                "此系统的蓝牙通话通道尚未建立，不能将媒体蓝牙当作麦克风使用；请改用有线耳机或断开蓝牙后重试",
            )
        }
        return routes
    }

    override fun select(candidate: AudioRouteCandidate) {
        if (Build.VERSION.SDK_INT >= 31) {
            val device = manager.availableCommunicationDevices.firstOrNull { it.id == candidate.id }
                ?: error("${candidate.label}已不可用，请重新连接后重试")
            // Permission failures from vendor ROMs propagate with an actionable reason.
            val request = synchronized(routeLock) {
                confirmationTimeout?.let(handler::removeCallbacks)
                confirmationTimeout = null
                routeConfirmation.begin(candidate.id, SystemClock.elapsedRealtime())
                backendEpoch to ++requestEpoch
            }
            val accepted = try {
                manager.setCommunicationDevice(device)
            } catch (denied: SecurityException) {
                throw IllegalStateException("无法选择${candidate.label}，请检查系统音频或蓝牙权限", denied)
            }
            check(accepted) { "系统拒绝切换到${candidate.label}，请重新连接设备后重试" }
            ownsCommunicationRequest = true
            // Never synchronously reenter ManagedAudioSessionController.select()
            // before it has committed its own selected candidate.
            handler.post { checkCommunicationRoute(request.first, request.second) }
        } else {
            when (candidate.kind) {
                AudioRouteKind.BLUETOOTH -> {
                    check(manager.isBluetoothScoOn) { "蓝牙通话音频已断开，请重新连接后重试" }
                    manager.isSpeakerphoneOn = false
                }
                AudioRouteKind.SPEAKER -> manager.isSpeakerphoneOn = true
                else -> manager.isSpeakerphoneOn = false
            }
        }
    }

    override fun isSelectedRouteReady(candidate: AudioRouteCandidate): Boolean =
        Build.VERSION.SDK_INT < 31 || manager.communicationDevice?.id == candidate.id

    /** Observes actual routing without invoking lifecycle callbacks while holding routeLock. */
    private fun checkCommunicationRoute(expectedBackend: Long, expectedRequest: Long? = null) {
        if (Build.VERSION.SDK_INT < 31) return
        val observedRequest = synchronized(routeLock) {
            if (backendEpoch != expectedBackend ||
                (expectedRequest != null && requestEpoch != expectedRequest)
            ) return
            requestEpoch
        }
        val actual = runCatching { manager.communicationDevice?.id }
        var changed: (() -> Unit)? = null
        var failed: ((String) -> Unit)? = null
        var reason: String? = null
        synchronized(routeLock) {
            if (backendEpoch != expectedBackend || requestEpoch != observedRequest) return
            val result = if (actual.isSuccess) {
                routeConfirmation.observe(actual.getOrNull(), SystemClock.elapsedRealtime())
            } else AudioRouteConfirmation.Result.LOST
            when (result) {
                AudioRouteConfirmation.Result.IDLE -> changed = routeChanged
                AudioRouteConfirmation.Result.CONFIRMED -> {
                    confirmationTimeout?.let(handler::removeCallbacks)
                    confirmationTimeout = null
                    changed = routeChanged
                }
                AudioRouteConfirmation.Result.PENDING -> {
                    if (confirmationTimeout == null) {
                        val request = requestEpoch
                        val timeout = Runnable {
                            synchronized(routeLock) {
                                if (backendEpoch != expectedBackend || requestEpoch != request) return@Runnable
                                confirmationTimeout = null
                            }
                            checkCommunicationRoute(expectedBackend, request)
                        }
                        confirmationTimeout = timeout
                        handler.postDelayed(timeout, routeConfirmation.remainingMs(SystemClock.elapsedRealtime()))
                    }
                }
                AudioRouteConfirmation.Result.LOST, AudioRouteConfirmation.Result.TIMED_OUT -> {
                    confirmationTimeout?.let(handler::removeCallbacks)
                    confirmationTimeout = null
                    failed = routeFailure
                    reason = if (result == AudioRouteConfirmation.Result.TIMED_OUT) {
                        "系统未能在 3 秒内接通目标音频设备，会话已停止，请重新连接耳机后重试"
                    } else {
                        "系统通话音频路由已改变或不可用，会话已停止，请确认耳机和音频设置后重试"
                    }
                }
            }
        }
        // ManagedAudioSessionController may itself call back into this backend.
        // Keeping that call outside routeLock avoids reversing the lifecycle lock order.
        reason?.let { failed?.invoke(it) } ?: changed?.invoke()
    }

    override fun release() {
        synchronized(routeLock) {
            backendEpoch++
            requestEpoch++
            confirmationTimeout?.let(handler::removeCallbacks)
            confirmationTimeout = null
            routeConfirmation.clear()
            routeFailure = null
            routeChanged = null
        }
        devices?.let { runCatching { manager.unregisterAudioDeviceCallback(it) }.reportCleanup() }
        devices = null
        if (Build.VERSION.SDK_INT >= 31) {
            communicationListener?.let {
                runCatching { manager.removeOnCommunicationDeviceChangedListener(it) }.reportCleanup()
            }
            communicationListener = null
            if (ownsCommunicationRequest) {
                // Clearing our request restores system routing (including newly added
                // devices) without leaving another persistent request after the call.
                runCatching { manager.clearCommunicationDevice() }.reportCleanup()
            }
        }
        ownsCommunicationRequest = false
        scoReceiver?.let { runCatching { context.unregisterReceiver(it) }.reportCleanup() }
        scoReceiver = null
        val previous = saved
        saved = null
        if (previous != null && ownsCommunicationMode) {
            if (Build.VERSION.SDK_INT < 31 &&
                runCatching { manager.mode != AudioManager.MODE_IN_CALL }.getOrDefault(false)
            ) {
                runCatching { manager.isSpeakerphoneOn = previous.speaker }.reportCleanup()
            }
            // MODE_NORMAL releases this process's mode claim. Other owners (e.g.
            // telephony) retain priority; restoring a copied IN_COMMUNICATION mode
            // would instead leave our own claim alive after the call finishes.
            val restoreMode = if (previous.mode == AudioManager.MODE_NORMAL) previous.mode
                else AudioManager.MODE_NORMAL
            runCatching { manager.mode = restoreMode }.reportCleanup()
        }
        ownsCommunicationMode = false
        focus?.let { runCatching { manager.abandonAudioFocusRequest(it) }.reportCleanup() }
        focus = null
    }

    private fun Result<*>.reportCleanup() {
        exceptionOrNull()?.let { Log.w("EdgeVoiceAudio", "Audio resource cleanup: ${it.javaClass.simpleName}") }
    }

    private fun kindOf(type: Int): AudioRouteKind? = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRouteKind.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> AudioRouteKind.BLUETOOTH
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRouteKind.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteKind.EARPIECE
        else -> null
    }

    private fun labelOf(kind: AudioRouteKind): String = when (kind) {
        AudioRouteKind.WIRED_HEADSET -> "有线耳机"
        AudioRouteKind.BLUETOOTH -> "蓝牙通话耳机"
        AudioRouteKind.SPEAKER -> "扬声器"
        AudioRouteKind.EARPIECE -> "听筒"
        AudioRouteKind.UNKNOWN -> "未知音频设备"
    }
}

internal fun communicationAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()
