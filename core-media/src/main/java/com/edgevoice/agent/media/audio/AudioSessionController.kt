package com.edgevoice.agent.media.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioRouteKind { SPEAKER, WIRED_HEADSET, BLUETOOTH, EARPIECE, UNKNOWN }

data class AudioRouteInfo(
    val kind: AudioRouteKind = AudioRouteKind.UNKNOWN,
    val label: String = "音频未启动",
    /** True only when the selected platform route is ready for physical media. */
    val active: Boolean = false,
)

/** Owns call audio focus/routing, independently of the model connection. */
interface AudioSessionController {
    val route: StateFlow<AudioRouteInfo>
    /** Failure throws before media starts. An interruption never restarts capture. */
    fun start(onInterruption: (String) -> Unit)
    fun stop()
}

internal data class AudioRouteCandidate(
    val id: Int,
    val kind: AudioRouteKind,
    val label: String,
    val preferred: Boolean = false,
)

internal interface AudioSessionBackend {
    fun acquire(onInterruption: (String) -> Unit, onDevicesChanged: () -> Unit)
    fun availableRoutes(): List<AudioRouteCandidate>
    fun select(candidate: AudioRouteCandidate)
    /** A successful route request may still be waiting for its asynchronous confirmation. */
    fun isSelectedRouteReady(candidate: AudioRouteCandidate): Boolean = true
    /** Releases every acquired resource, including partially completed acquisition. */
    fun release()
}

internal object AudioRoutePolicy {
    fun choose(routes: List<AudioRouteCandidate>): AudioRouteCandidate? = routes.minWithOrNull(
        compareBy<AudioRouteCandidate> {
            when (it.kind) {
                AudioRouteKind.WIRED_HEADSET -> 0
                AudioRouteKind.BLUETOOTH -> 1
                AudioRouteKind.SPEAKER -> 2
                AudioRouteKind.EARPIECE -> 3
                AudioRouteKind.UNKNOWN -> 4
            }
        }.thenBy { !it.preferred }.thenBy { it.id },
    )
}

/** Pure lifecycle/policy layer, exercised with a fake platform in local JVM tests. */
internal class ManagedAudioSessionController(
    private val backend: AudioSessionBackend,
) : AudioSessionController {
    private val lock = Any()
    private val _route = MutableStateFlow(AudioRouteInfo())
    override val route: StateFlow<AudioRouteInfo> = _route.asStateFlow()
    private var active = false
    private var epoch = 0L
    private var selected: AudioRouteCandidate? = null
    private var interruption: ((String) -> Unit)? = null

    override fun start(onInterruption: (String) -> Unit) {
        synchronized(lock) {
            if (active) return
            active = true
            val current = ++epoch
            interruption = onInterruption
            try {
                backend.acquire(
                    onInterruption = { interrupt(current, it) },
                    onDevicesChanged = { updateRoutes(current) },
                )
                check(active && epoch == current) { "通话音频在启动时被系统中断" }
                selectBestRoute(backend.availableRoutes())
            } catch (failure: Throwable) {
                if (active && epoch == current) stopLocked()
                throw failure
            }
        }
    }

    private fun selectBestRoute(routes: List<AudioRouteCandidate>) {
        val next = AudioRoutePolicy.choose(routes)
            ?: throw IllegalStateException("没有可用的通话音频设备，请检查耳机或系统音频设置")
        if (selected?.id != next.id) backend.select(next)
        selected = next
        val ready = backend.isSelectedRouteReady(next)
        _route.value = AudioRouteInfo(
            next.kind,
            if (ready) next.label else "${next.label}连接中",
            active = ready,
        )
    }

    private fun updateRoutes(expectedEpoch: Long) {
        var failure: String? = null
        synchronized(lock) {
            if (!active || epoch != expectedEpoch) return
            try {
                val routes = backend.availableRoutes()
                val previous = selected
                if (previous != null && routes.none { it.id == previous.id }) {
                    // Never send private speech to the speaker after headset removal.
                    failure = "${previous.label}已断开，会话已停止，请确认音频设备后重新开始"
                } else {
                    selectBestRoute(routes)
                }
            } catch (error: Throwable) {
                failure = error.message ?: "通话音频设备已不可用"
            }
        }
        failure?.let { interrupt(expectedEpoch, it) }
    }

    private fun interrupt(expectedEpoch: Long, reason: String) {
        val callback = synchronized(lock) {
            if (!active || epoch != expectedEpoch) return
            interruption.also { stopLocked() }
        }
        callback?.invoke(reason)
    }

    private fun stopLocked() {
        active = false
        epoch++
        selected = null
        interruption = null
        try {
            backend.release()
        } finally {
            _route.value = AudioRouteInfo()
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (active) stopLocked()
        }
    }
}
