package com.edgevoice.agent.core

import com.edgevoice.agent.transport.TransportFailure
import com.edgevoice.agent.executor.ResponseIncompleteReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SessionStatusTest {
    @Test fun wrapped_permission_and_timeout_keep_their_actionable_kind() {
        assertThat(sessionFailureKind(IllegalStateException(SecurityException("denied"))))
            .isEqualTo(SessionErrorKind.PERMISSION)
        assertThat(sessionFailureKind(IllegalStateException(SocketTimeoutException())))
            .isEqualTo(SessionErrorKind.TIMEOUT)
        assertThat(sessionFailureKind(IllegalStateException(IOException("connection reset"))))
            .isEqualTo(SessionErrorKind.NETWORK)
    }

    @Test fun http_status_is_used_before_generic_io_classification() {
        assertThat(sessionFailureKind(TransportFailure("denied", false, statusCode = 401)))
            .isEqualTo(SessionErrorKind.AUTHENTICATION)
        assertThat(sessionFailureKind(TransportFailure("limited", true, statusCode = 429)))
            .isEqualTo(SessionErrorKind.RATE_LIMIT)
        assertThat(sessionFailureKind(IllegalStateException("capture queue overflow")))
            .isEqualTo(SessionErrorKind.OVERLOAD)
    }

    @Test fun provider_bodies_credentials_and_endpoints_never_reach_user_message() {
        val secret = "sk-private-value"
        val error = IOException("$secret wss://private.example/session?token=$secret full provider body")
        SessionErrorKind.entries.forEach { kind ->
            val display = sessionFailureMessage(kind, error)
            assertThat(display).doesNotContain(secret)
            assertThat(display).doesNotContain("private.example")
            assertThat(display).doesNotContain("provider body")
            assertThat(display).isNotEmpty()
        }
    }

    @Test fun quota_and_transient_limits_give_different_recovery_guidance() {
        assertThat(sessionFailureMessage(SessionErrorKind.RATE_LIMIT, IOException("insufficient_quota")))
            .contains("额度")
        assertThat(sessionFailureMessage(SessionErrorKind.RATE_LIMIT, IOException("rate_limit")))
            .contains("稍等")
    }

    @Test fun output_warning_keeps_session_ready_and_next_question_clears_only_that_warning() {
        val ready = SessionStatus(SessionPhase.READY, retryAttempt = 2)
        val warning = ready.withModelOutputWarning(ResponseIncompleteReason.MISSING_AUDIO)
        assertThat(warning.phase).isEqualTo(SessionPhase.READY)
        assertThat(warning.retryAttempt).isEqualTo(2)
        assertThat(warning.errorKind).isEqualTo(SessionErrorKind.MODEL_OUTPUT)
        assertThat(warning.message).contains("本轮未收到语音")
        assertThat(warning.clearModelOutputWarning()).isEqualTo(ready)
        val reconnecting = SessionStatus(SessionPhase.RECONNECTING, message = "正在恢复", errorKind = SessionErrorKind.NETWORK)
        assertThat(reconnecting.withModelOutputWarning(ResponseIncompleteReason.INCOMPLETE)).isEqualTo(reconnecting)
        assertThat(reconnecting.clearModelOutputWarning()).isEqualTo(reconnecting)
    }

    @Test fun partial_and_unconfirmed_outputs_have_distinct_fixed_guidance() {
        val ready = SessionStatus(SessionPhase.READY)
        assertThat(ready.withModelOutputWarning(ResponseIncompleteReason.INCOMPLETE).message).contains("未完整生成")
        assertThat(ready.withModelOutputWarning(ResponseIncompleteReason.UNCONFIRMED).message).contains("未确认")
    }

    private fun expiration(now: Long, responding: Boolean = false) = sessionExpiration(
        nowMs = now, startedAtMs = 0, lastActivityAtMs = 200,
        responding = responding, maxDurationMs = 1_000, idleTimeoutMs = 500,
    )

    @Test fun idle_timeout_stops_at_boundary_but_does_not_cut_off_current_answer() {
        assertThat(expiration(699)).isNull()
        assertThat(expiration(700)).contains("未检测到提问")
        assertThat(expiration(700, responding = true)).isNull()
    }

    @Test fun hard_limit_applies_even_while_model_is_responding() {
        assertThat(expiration(999, responding = true)).isNull()
        assertThat(expiration(1_000, responding = true)).contains("时长上限")
    }
}
