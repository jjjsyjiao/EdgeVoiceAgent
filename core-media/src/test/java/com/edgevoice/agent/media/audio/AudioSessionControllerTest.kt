package com.edgevoice.agent.media.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioSessionControllerTest {
    private val speaker = AudioRouteCandidate(1, AudioRouteKind.SPEAKER, "扬声器")
    private val bluetooth = AudioRouteCandidate(2, AudioRouteKind.BLUETOOTH, "蓝牙耳机")
    private val wired = AudioRouteCandidate(3, AudioRouteKind.WIRED_HEADSET, "有线耳机")

    private class FakeBackend : AudioSessionBackend {
        var routes = emptyList<AudioRouteCandidate>()
        var acquired = 0
        var released = 0
        var focusHeld = false
        var modeChanged = false
        var routeClaimed = false
        var selectedRouteReady = true
        var acquisitionFailure: Throwable? = null
        var selectionFailure: Throwable? = null
        lateinit var lost: (String) -> Unit
        lateinit var changed: () -> Unit
        val selections = mutableListOf<Int>()
        override fun acquire(onInterruption: (String) -> Unit, onDevicesChanged: () -> Unit) {
            acquired++
            focusHeld = true
            modeChanged = true
            lost = onInterruption
            changed = onDevicesChanged
            acquisitionFailure?.let { throw it }
        }
        override fun availableRoutes() = routes
        override fun select(candidate: AudioRouteCandidate) {
            selectionFailure?.let { throw it }
            routeClaimed = true
            selections.add(candidate.id)
        }
        override fun isSelectedRouteReady(candidate: AudioRouteCandidate) = selectedRouteReady
        override fun release() {
            released++
            focusHeld = false
            modeChanged = false
            routeClaimed = false
        }
    }

    @Test
    fun external_routes_precede_speaker_and_system_preference_breaks_ties() {
        assertThat(AudioRoutePolicy.choose(listOf(speaker, bluetooth, wired))).isEqualTo(wired)
        assertThat(AudioRoutePolicy.choose(listOf(speaker, bluetooth))).isEqualTo(bluetooth)
        val preferred = bluetooth.copy(id = 9, preferred = true)
        assertThat(AudioRoutePolicy.choose(listOf(bluetooth, preferred))).isEqualTo(preferred)
        assertThat(AudioRoutePolicy.choose(listOf(speaker))).isEqualTo(speaker)
        assertThat(AudioRoutePolicy.choose(emptyList())).isNull()
    }

    @Test
    fun start_stop_are_idempotent_and_release_focus_mode_and_route() {
        val backend = FakeBackend().apply { routes = listOf(speaker) }
        val controller = ManagedAudioSessionController(backend)
        controller.start { error("unexpected interruption") }
        controller.start { error("unexpected second callback") }
        assertThat(backend.acquired).isEqualTo(1)
        assertThat(controller.route.value).isEqualTo(AudioRouteInfo(AudioRouteKind.SPEAKER, "扬声器", true))
        controller.stop()
        controller.stop()
        assertThat(backend.released).isEqualTo(1)
        assertThat(backend.focusHeld || backend.modeChanged || backend.routeClaimed).isFalse()
        assertThat(controller.route.value.active).isFalse()
    }

    @Test
    fun focus_loss_stops_once_and_late_callbacks_never_restart_capture() {
        val backend = FakeBackend().apply { routes = listOf(bluetooth, speaker) }
        val controller = ManagedAudioSessionController(backend)
        val interruptions = mutableListOf<String>()
        controller.start(interruptions::add)
        val staleDeviceCallback = backend.changed
        backend.lost("电话占用音频")
        backend.lost("重复的焦点丢失")
        staleDeviceCallback()
        assertThat(interruptions).containsExactly("电话占用音频")
        assertThat(backend.released).isEqualTo(1)
        assertThat(backend.acquired).isEqualTo(1)
        assertThat(controller.route.value.active).isFalse()
    }

    @Test
    fun removing_current_headset_stops_instead_of_leaking_speech_to_speaker() {
        val backend = FakeBackend().apply { routes = listOf(wired, speaker) }
        val controller = ManagedAudioSessionController(backend)
        val interruptions = mutableListOf<String>()
        controller.start(interruptions::add)
        backend.routes = listOf(speaker)
        backend.changed()
        assertThat(backend.selections).containsExactly(wired.id)
        assertThat(interruptions.single()).contains("已断开")
        assertThat(controller.route.value.active).isFalse()
        assertThat(backend.focusHeld).isFalse()
    }

    @Test
    fun adding_headset_switches_route_and_repeated_notifications_do_not_reselect() {
        val backend = FakeBackend().apply { routes = listOf(speaker) }
        val controller = ManagedAudioSessionController(backend)
        controller.start { error("unexpected interruption") }
        backend.routes = listOf(speaker, wired)
        backend.changed()
        backend.changed()
        assertThat(backend.selections).containsExactly(speaker.id, wired.id).inOrder()
        assertThat(controller.route.value.kind).isEqualTo(AudioRouteKind.WIRED_HEADSET)
        controller.stop()
    }

    @Test
    fun accepted_bluetooth_request_stays_pending_until_the_actual_route_is_confirmed() {
        val backend = FakeBackend().apply {
            routes = listOf(speaker, bluetooth)
            selectedRouteReady = false
        }
        val controller = ManagedAudioSessionController(backend)
        controller.start { error("unexpected interruption") }
        assertThat(backend.focusHeld).isTrue()
        assertThat(controller.route.value.kind).isEqualTo(AudioRouteKind.BLUETOOTH)
        assertThat(controller.route.value.active).isFalse()
        assertThat(controller.route.value.label).contains("连接中")

        // Repeated pending notifications do not issue another route request.
        backend.changed()
        assertThat(controller.route.value.active).isFalse()
        backend.selectedRouteReady = true
        backend.changed()
        assertThat(controller.route.value).isEqualTo(AudioRouteInfo(AudioRouteKind.BLUETOOTH, "蓝牙耳机", true))
        assertThat(backend.acquired).isEqualTo(1)
        assertThat(backend.selections).containsExactly(bluetooth.id)
        controller.stop()
    }

    @Test
    fun late_route_confirmation_cannot_reactivate_a_stopped_pending_session() {
        val backend = FakeBackend().apply {
            routes = listOf(bluetooth)
            selectedRouteReady = false
        }
        val controller = ManagedAudioSessionController(backend)
        controller.start { error("unexpected interruption") }
        val staleConfirmation = backend.changed
        controller.stop()
        backend.selectedRouteReady = true
        staleConfirmation()
        assertThat(controller.route.value).isEqualTo(AudioRouteInfo())
        assertThat(backend.focusHeld).isFalse()
        assertThat(backend.released).isEqualTo(1)
    }

    @Test
    fun changing_to_an_added_headset_marks_the_route_pending_until_it_is_ready() {
        val backend = FakeBackend().apply { routes = listOf(speaker) }
        val controller = ManagedAudioSessionController(backend)
        controller.start { error("unexpected interruption") }
        assertThat(controller.route.value.active).isTrue()
        backend.routes = listOf(speaker, wired)
        backend.selectedRouteReady = false
        backend.changed()
        assertThat(controller.route.value.kind).isEqualTo(AudioRouteKind.WIRED_HEADSET)
        assertThat(controller.route.value.active).isFalse()
        backend.selectedRouteReady = true
        backend.changed()
        assertThat(controller.route.value).isEqualTo(AudioRouteInfo(AudioRouteKind.WIRED_HEADSET, "有线耳机", true))
        assertThat(backend.selections).containsExactly(speaker.id, wired.id).inOrder()
        controller.stop()
    }

    @Test
    fun acquisition_failure_rolls_back_partial_resources_before_throwing() {
        val backend = FakeBackend().apply {
            routes = listOf(speaker)
            acquisitionFailure = IllegalStateException("focus denied")
        }
        val controller = ManagedAudioSessionController(backend)
        val failure = assertThrows(IllegalStateException::class.java) { controller.start {} }
        assertThat(failure).hasMessageThat().contains("focus denied")
        assertThat(backend.released).isEqualTo(1)
        assertThat(backend.focusHeld || backend.modeChanged || backend.routeClaimed).isFalse()
        assertThat(controller.route.value.active).isFalse()
    }

    @Test
    fun rejected_or_missing_route_rolls_back_focus_and_mode() {
        val backend = FakeBackend().apply { routes = listOf(speaker) }
        val controller = ManagedAudioSessionController(backend)
        backend.selectionFailure = IllegalStateException("route rejected")
        assertThrows(IllegalStateException::class.java) { controller.start {} }
        assertThat(backend.released).isEqualTo(1)
        backend.selectionFailure = null
        backend.routes = emptyList()
        assertThrows(IllegalStateException::class.java) { controller.start {} }
        assertThat(backend.released).isEqualTo(2)
        assertThat(controller.route.value.active).isFalse()
    }

    @Test
    fun stale_platform_callback_cannot_stop_a_new_session() {
        val backend = FakeBackend().apply { routes = listOf(speaker) }
        val controller = ManagedAudioSessionController(backend)
        controller.start { error("unexpected old interruption") }
        val oldLoss = backend.lost
        val oldChange = backend.changed
        controller.stop()
        controller.start { error("stale event affected the new session") }
        oldLoss("late focus callback")
        oldChange()
        assertThat(controller.route.value.active).isTrue()
        assertThat(backend.released).isEqualTo(1)
        controller.stop()
    }
}
