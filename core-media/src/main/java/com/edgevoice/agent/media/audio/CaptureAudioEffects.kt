package com.edgevoice.agent.media.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import java.io.Closeable

enum class CaptureEffectStatus { ENABLED, UNAVAILABLE, NO_CONTROL, FAILED }

data class CaptureProcessingInfo(
    val audioSessionId: Int? = null,
    val echoCancellation: CaptureEffectStatus = CaptureEffectStatus.UNAVAILABLE,
    val noiseSuppression: CaptureEffectStatus = CaptureEffectStatus.UNAVAILABLE,
)

internal enum class CaptureEffectKind { ECHO_CANCELLATION, NOISE_SUPPRESSION }

internal interface CaptureEffectHandle : Closeable {
    val enabled: Boolean
    val hasControl: Boolean
    fun enable(): Boolean
}

internal fun interface CaptureEffectFactory {
    fun create(kind: CaptureEffectKind, audioSessionId: Int): CaptureEffectHandle?
}

/**
 * One AEC/NS handle per AudioRecord session. VOICE_COMMUNICATION can already enable
 * these platform effects: query existing state before enabling, and never add a
 * second software DSP chain or turn a shared platform effect off during release.
 * Availability/control varies by device and is reported, rather than assumed.
 */
internal class CaptureAudioEffects(
    private val factory: CaptureEffectFactory = AndroidCaptureEffectFactory,
) : Closeable {
    private val handles = mutableListOf<CaptureEffectHandle>()
    @Volatile var info = CaptureProcessingInfo()
        private set

    @Synchronized
    fun attach(audioSessionId: Int): CaptureProcessingInfo {
        require(audioSessionId > 0) { "无效的麦克风音频会话 ID" }
        if (info.audioSessionId == audioSessionId) return info
        close()
        fun attachOne(kind: CaptureEffectKind): CaptureEffectStatus = try {
            val effect = factory.create(kind, audioSessionId)
            if (effect == null) {
                CaptureEffectStatus.UNAVAILABLE
            } else {
                handles.add(effect)
                when {
                    effect.enabled -> CaptureEffectStatus.ENABLED
                    !effect.hasControl -> CaptureEffectStatus.NO_CONTROL
                    effect.enable() && effect.enabled -> CaptureEffectStatus.ENABLED
                    else -> CaptureEffectStatus.FAILED
                }
            }
        } catch (_: Exception) {
            CaptureEffectStatus.FAILED
        }
        info = CaptureProcessingInfo(
            audioSessionId,
            attachOne(CaptureEffectKind.ECHO_CANCELLATION),
            attachOne(CaptureEffectKind.NOISE_SUPPRESSION),
        )
        return info
    }

    @Synchronized
    override fun close() {
        handles.forEach { runCatching { it.close() } }
        handles.clear()
        info = CaptureProcessingInfo()
    }
}

private object AndroidCaptureEffectFactory : CaptureEffectFactory {
    override fun create(kind: CaptureEffectKind, audioSessionId: Int): CaptureEffectHandle? {
        val effect = when (kind) {
            CaptureEffectKind.ECHO_CANCELLATION ->
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioSessionId) else null
            CaptureEffectKind.NOISE_SUPPRESSION ->
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioSessionId) else null
        } ?: return null
        return object : CaptureEffectHandle {
            override val enabled: Boolean get() = effect.enabled
            override val hasControl: Boolean get() = effect.hasControl()
            override fun enable(): Boolean = effect.setEnabled(true) == AudioEffect.SUCCESS
            override fun close() = effect.release()
        }
    }
}
