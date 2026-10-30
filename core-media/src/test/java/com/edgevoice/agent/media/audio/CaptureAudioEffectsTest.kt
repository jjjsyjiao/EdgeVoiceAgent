package com.edgevoice.agent.media.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureAudioEffectsTest {
    private class Effect(
        override var enabled: Boolean = false,
        override val hasControl: Boolean = true,
        private val failEnable: Boolean = false,
        private val failClose: Boolean = false,
    ) : CaptureEffectHandle {
        var enableCalls = 0
        var closeCalls = 0
        override fun enable(): Boolean {
            enableCalls++
            if (failEnable) error("effect control failed")
            enabled = true
            return true
        }
        override fun close() {
            closeCalls++
            if (failClose) error("release failed")
        }
    }

    @Test
    fun unsupported_effects_do_not_prevent_capture_and_report_unavailable() {
        val effects = CaptureAudioEffects { _, _ -> null }
        val info = effects.attach(12)
        assertThat(info.echoCancellation).isEqualTo(CaptureEffectStatus.UNAVAILABLE)
        assertThat(info.noiseSuppression).isEqualTo(CaptureEffectStatus.UNAVAILABLE)
        assertThat(info.audioSessionId).isEqualTo(12)
        effects.close()
    }

    @Test
    fun already_enabled_platform_effects_are_not_enabled_twice() {
        val aec = Effect(enabled = true)
        val ns = Effect(enabled = true)
        var creations = 0
        val effects = CaptureAudioEffects { kind, session ->
            assertThat(session).isEqualTo(20)
            creations++
            if (kind == CaptureEffectKind.ECHO_CANCELLATION) aec else ns
        }
        assertThat(effects.attach(20).echoCancellation).isEqualTo(CaptureEffectStatus.ENABLED)
        effects.attach(20)
        assertThat(creations).isEqualTo(2)
        assertThat(aec.enableCalls + ns.enableCalls).isEqualTo(0)
        effects.close()
        effects.close()
        assertThat(aec.closeCalls).isEqualTo(1)
        assertThat(ns.closeCalls).isEqualTo(1)
    }

    @Test
    fun controllable_effect_is_enabled_once_and_no_control_does_not_force_it() {
        val aec = Effect()
        val ns = Effect(hasControl = false)
        val effects = CaptureAudioEffects { kind, _ ->
            if (kind == CaptureEffectKind.ECHO_CANCELLATION) aec else ns
        }
        val info = effects.attach(30)
        assertThat(info.echoCancellation).isEqualTo(CaptureEffectStatus.ENABLED)
        assertThat(info.noiseSuppression).isEqualTo(CaptureEffectStatus.NO_CONTROL)
        assertThat(aec.enableCalls).isEqualTo(1)
        assertThat(ns.enableCalls).isEqualTo(0)
        effects.close()
    }

    @Test
    fun changing_record_session_releases_old_handles_before_attaching_new_ones() {
        val events = mutableListOf<String>()
        val effects = CaptureAudioEffects { kind, session ->
            events.add("create:$session:$kind")
            object : CaptureEffectHandle {
                override val enabled = true
                override val hasControl = true
                override fun enable() = true
                override fun close() { events.add("close:$session:$kind") }
            }
        }
        effects.attach(1)
        effects.attach(2)
        assertThat(events).containsExactly(
            "create:1:ECHO_CANCELLATION", "create:1:NOISE_SUPPRESSION",
            "close:1:ECHO_CANCELLATION", "close:1:NOISE_SUPPRESSION",
            "create:2:ECHO_CANCELLATION", "create:2:NOISE_SUPPRESSION",
        ).inOrder()
        effects.close()
        assertThat(effects.info.audioSessionId).isNull()
    }

    @Test
    fun broken_vendor_effect_is_reported_and_every_handle_is_released() {
        val aec = Effect(failEnable = true, failClose = true)
        val ns = Effect()
        val effects = CaptureAudioEffects { kind, _ ->
            if (kind == CaptureEffectKind.ECHO_CANCELLATION) aec else ns
        }
        val info = effects.attach(40)
        assertThat(info.echoCancellation).isEqualTo(CaptureEffectStatus.FAILED)
        assertThat(info.noiseSuppression).isEqualTo(CaptureEffectStatus.ENABLED)
        effects.close()
        assertThat(aec.closeCalls).isEqualTo(1)
        assertThat(ns.closeCalls).isEqualTo(1)
    }
}
