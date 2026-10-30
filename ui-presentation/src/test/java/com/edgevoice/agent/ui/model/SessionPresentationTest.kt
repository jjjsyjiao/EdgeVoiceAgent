package com.edgevoice.agent.ui.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionPresentationTest {
    @Test fun connectionAttemptAndRecoveryRemainStoppable() {
        assertThat(ConnectionPhase.CONNECTING.active).isTrue()
        assertThat(ConnectionPhase.CONFIGURING.active).isTrue()
        assertThat(ConnectionPhase.RECONNECTING.active).isTrue()
        assertThat(ConnectionPhase.READY.active).isTrue()
        assertThat(ConnectionPhase.STOPPED.active).isFalse()
        assertThat(ConnectionPhase.FAILED.active).isFalse()
    }

    @Test fun authenticationAndInsufficientQuotaNeedConfigurationRatherThanBlindRetry() {
        assertThat(recoveryHint("AUTHENTICATION", "invalid key")!!.action).isEqualTo(RecoveryAction.NONE)
        assertThat(recoveryHint("RATE_LIMIT", "quota exceeded")!!.action).isEqualTo(RecoveryAction.NONE)
        assertThat(recoveryHint("RATE_LIMIT", "余额不足")!!.action).isEqualTo(RecoveryAction.NONE)
        assertThat(recoveryHint("RATE_LIMIT", "requests too frequent")!!.action).isEqualTo(RecoveryAction.RETRY_SESSION)
    }

    @Test fun recoverableFailuresOfferAnExplicitRetry() {
        listOf("NETWORK", "TIMEOUT", "OVERLOAD", "AUDIO_DEVICE", "UNKNOWN").forEach {
            assertThat(recoveryHint(it, "failure")!!.action).isEqualTo(RecoveryAction.RETRY_SESSION)
        }
        assertThat(recoveryHint("NETWORK", null)).isNull()
    }

    @Test fun missing_output_offers_continued_questions_without_reconnection_or_provider_details() {
        val hint = recoveryHint("MODEL_OUTPUT", "本轮未收到语音 sk-private-value https://private.example")!!
        assertThat(hint.action).isEqualTo(RecoveryAction.NONE)
        assertThat(hint.guidance).isEqualTo("本轮未收到语音，已保留文字，可继续提问或重问。")
        assertThat(hint.guidance).doesNotContain("private")
        assertThat(recoveryHint("MODEL_OUTPUT", "本轮回答未完整生成")!!.guidance).contains("未完整生成")
        assertThat(recoveryHint("MODEL_OUTPUT", "本轮回答完成状态未确认")!!.guidance).contains("未确认")
    }

    @Test fun permanentPermissionDenialLeadsToSettingsForTheCorrectDevice() {
        val camera = permissionHint(PermissionTarget.CAMERA, true)
        assertThat(camera.action).isEqualTo(RecoveryAction.OPEN_SETTINGS)
        assertThat(camera.permissionTarget).isEqualTo(PermissionTarget.CAMERA)
        assertThat(permissionHint(PermissionTarget.CAMERA, false).action).isEqualTo(RecoveryAction.REQUEST_CAMERA)
        assertThat(permissionHint(PermissionTarget.MICROPHONE, false).action).isEqualTo(RecoveryAction.REQUEST_MICROPHONE)
    }

    @Test fun livePreviewDoesNotImplySuccessfulUplink() {
        assertThat(visionDeliveryLabel(false, 0L, 1f).sending).isFalse()
        assertThat(visionDeliveryLabel(true, null, 0f).sending).isFalse()
        assertThat(visionDeliveryLabel(true, 4_001L, 1f).sending).isFalse()
        assertThat(visionDeliveryLabel(true, 500L, 0.8f).sending).isTrue()
        assertThat(visionDeliveryLabel(true, 500L, 0.8f).text).contains("0.8")
    }
}
