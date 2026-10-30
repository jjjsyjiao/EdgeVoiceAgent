package com.edgevoice.agent.media.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioRouteConfirmationTest {
    @Test
    fun pending_old_route_is_allowed_only_within_confirmation_window() {
        val confirmation = AudioRouteConfirmation()
        confirmation.begin(targetId = 2, nowMs = 1_000)
        assertThat(confirmation.observe(actualId = 1, nowMs = 1_050))
            .isEqualTo(AudioRouteConfirmation.Result.PENDING)
        assertThat(confirmation.observe(actualId = null, nowMs = 3_999))
            .isEqualTo(AudioRouteConfirmation.Result.PENDING)
        assertThat(confirmation.remainingMs(3_999)).isEqualTo(1L)
        assertThat(confirmation.observe(actualId = 2, nowMs = 4_000))
            .isEqualTo(AudioRouteConfirmation.Result.CONFIRMED)
    }

    @Test
    fun confirmed_bluetooth_switching_to_speaker_is_lost_even_if_headset_still_available() {
        val confirmation = AudioRouteConfirmation()
        confirmation.begin(targetId = 2, nowMs = 0)
        assertThat(confirmation.observe(actualId = 2, nowMs = 10))
            .isEqualTo(AudioRouteConfirmation.Result.CONFIRMED)
        // Physical availability is deliberately irrelevant: actual communication
        // routing to a different non-null device must stop this voice session.
        assertThat(confirmation.observe(actualId = 1, nowMs = 20))
            .isEqualTo(AudioRouteConfirmation.Result.LOST)
        assertThat(confirmation.observe(actualId = null, nowMs = 30))
            .isEqualTo(AudioRouteConfirmation.Result.LOST)
    }

    @Test
    fun target_not_reached_by_deadline_fails_instead_of_waiting_indefinitely() {
        val confirmation = AudioRouteConfirmation()
        confirmation.begin(targetId = 2, nowMs = 1_000)
        assertThat(confirmation.observe(actualId = 1, nowMs = 4_000))
            .isEqualTo(AudioRouteConfirmation.Result.TIMED_OUT)
        assertThat(confirmation.remainingMs(4_001)).isEqualTo(0)
    }

    @Test
    fun a_new_intentional_switch_gets_its_own_window_and_old_route_does_not_fail_it() {
        val confirmation = AudioRouteConfirmation()
        confirmation.begin(targetId = 2, nowMs = 0)
        confirmation.observe(actualId = 2, nowMs = 10)
        confirmation.begin(targetId = 3, nowMs = 100)
        assertThat(confirmation.observe(actualId = 2, nowMs = 150))
            .isEqualTo(AudioRouteConfirmation.Result.PENDING)
        assertThat(confirmation.observe(actualId = 3, nowMs = 200))
            .isEqualTo(AudioRouteConfirmation.Result.CONFIRMED)
    }

    @Test
    fun release_clears_pending_and_confirmed_route_state() {
        val confirmation = AudioRouteConfirmation()
        confirmation.begin(targetId = 2, nowMs = 0)
        confirmation.clear()
        assertThat(confirmation.observe(actualId = 1, nowMs = 9_000))
            .isEqualTo(AudioRouteConfirmation.Result.IDLE)
        confirmation.begin(targetId = 3, nowMs = 10_000)
        confirmation.observe(actualId = 3, nowMs = 10_001)
        confirmation.clear()
        assertThat(confirmation.observe(actualId = null, nowMs = 20_000))
            .isEqualTo(AudioRouteConfirmation.Result.IDLE)
    }
}
