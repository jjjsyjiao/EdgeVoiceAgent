package com.edgevoice.agent.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.edgevoice.agent.ui.model.ConnectionPhase
import java.util.concurrent.atomic.AtomicReference

/** Real Activity and accessibility actions, deliberately offline so media cannot reach Ready. */
internal object LifecycleChecks {
    fun run(instrumentation: Instrumentation, arguments: Bundle): Bundle =
        Checks(instrumentation, arguments).run()

    private class Failure(val reason: String) : AssertionError(reason)

    private class Checks(private val instrumentation: Instrumentation, private val args: Bundle) {
        private val result = Bundle()
        private val network = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
        private var activity: MainActivity? = null
        private var viewModel: AgentViewModel? = null
        private var stage = "lifecycle_initializing"
        private var cycles = 0
        private var clicks = 0
        private var stoppedSamples = 0
        private var offlineRequired = true
        private var readyObserved = false

        fun run(): Bundle {
            try {
                check(instrumentation.targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED, "record_audio_must_be_pregranted")
                requireOffline()
                check(BuildConfig.OPENAI_API_KEY.isNotBlank(), "configured_cloud_credentials_required")
                report("lifecycle_activity_launch")
                activity = instrumentation.startActivitySync(launchIntent(clearTask = true)) as MainActivity
                viewModel = onMain {
                    requireNotNull(activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    ViewModelProvider(requireNotNull(activity))[AgentViewModel::class.java]
                }
                instrumentation.waitForIdleSync()
                await(8_000, "activity_not_resumed") {
                    onMain { requireNotNull(activity).lifecycle.currentState == Lifecycle.State.RESUMED }
                }
                awaitStopped("initial_activity_not_stopped")

                report("lifecycle_rapid_ui_toggles")
                repeat(10) {
                    clickButton("开始对话")
                    awaitActive()
                    clickButton("停止会话")
                    awaitStopped("ui_stop_did_not_cancel_connection")
                    cycles++
                }

                report("lifecycle_connect_then_home")
                clickButton("开始对话")
                awaitActive()
                // Perform the system Home action; this exercises the real ON_STOP observer.
                injectHome()
                await(5_000, "home_did_not_stop_activity") {
                    onMain { requireNotNull(activity).lifecycle.currentState < Lifecycle.State.STARTED }
                }
                awaitStopped("home_did_not_cancel_connection")

                report("lifecycle_return_to_foreground")
                // Reuse the existing Activity. startActivitySync waits for onCreate and is unsuitable
                // for delivering onNewIntent to an already-created Activity after Home.
                onMain {
                    instrumentation.targetContext.startActivity(launchIntent(clearTask = false).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
                await(8_000, "returned_activity_not_resumed") {
                    onMain { requireNotNull(activity).lifecycle.currentState == Lifecycle.State.RESUMED }
                }
                check(onMain { ViewModelProvider(requireNotNull(activity))[AgentViewModel::class.java] === viewModel },
                    "foreground_return_changed_view_model")
                observeStopped(20_000, "foreground_return_resurrected_session")
                result.putLong("foreground_stopped_observation_ms", 20_000L)

                if (args.getString("restore_network").equals("true", ignoreCase = true)) {
                    report("lifecycle_restore_network", "Restore the network now; the test never changes device networking")
                    offlineRequired = false
                    await(70_000, "operator_did_not_restore_network") {
                        assertStopped("waiting_for_network_resurrected_session")
                        network.activeNetwork != null
                    }
                    report("lifecycle_network_restored_observe_stopped")
                    observeStopped(20_000, "network_restore_resurrected_session")
                    result.putLong("restored_network_stopped_observation_ms", 20_000L)
                    result.putBoolean("network_restored", true)
                } else {
                    result.putBoolean("network_restored", false)
                }
                result.putString("status", "PASS")
                result.putString("coverage", "real_activity_offline_lifecycle")
                result.putString("summary", "10 real microphone-button start/stop cycles and Home/foreground cancellation passed without reaching Ready")
            } catch (error: Throwable) {
                result.putString("status", "FAIL")
                result.putString("failed_stage", stage)
                val controlled = generateSequence(error) { it.cause }.take(8).filterIsInstance<Failure>().firstOrNull()
                result.putString("failure", (controlled?.reason ?: error.javaClass.simpleName).take(180))
            } finally {
                // Cleanup also runs when an accessibility lookup, activity start or assertion fails.
                runCatching {
                    onMain {
                        viewModel?.stopSession()
                        activity?.finish()
                    }
                    instrumentation.waitForIdleSync()
                }.onFailure {
                    result.putString("status", "FAIL")
                    result.putString("cleanup_failure", it.javaClass.simpleName)
                }
                result.putInt("ui_start_stop_cycles", cycles)
                result.putInt("ui_accessibility_clicks", clicks)
                result.putInt("vm_action_fallbacks", 0)
                result.putInt("stopped_state_samples", stoppedSamples)
                result.putBoolean("camera_requested", false)
                result.putBoolean("ready_observed", readyObserved)
            }
            return result
        }

        private fun launchIntent(clearTask: Boolean): Intent =
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (clearTask) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

        private fun report(next: String, action: String? = null) {
            stage = next
            instrumentation.sendStatus(1, Bundle().apply {
                putString("stage", next)
                putInt("ui_start_stop_cycles", cycles)
                action?.let { putString("operator_action", it) }
            })
        }

        private fun check(condition: Boolean, reason: String) {
            if (!condition) throw Failure(reason)
        }

        private fun requireOffline() {
            check(network.activeNetwork == null, "active_network_must_be_null_before_and_during_offline_checks")
        }

        private fun assertMediaNotReady() {
            if (offlineRequired) requireOffline()
            onMain {
                val vm = requireNotNull(viewModel)
                if (vm.uiState.value.session.phase == ConnectionPhase.READY) readyObserved = true
                check(!readyObserved, "unexpected_ready_during_offline_test")
                check(!vm.visionEnabled.value, "unexpected_camera_enabled")
            }
        }

        private fun awaitActive() {
            await(3_000, "ui_start_did_not_begin_connection") {
                assertMediaNotReady()
                onMain { requireNotNull(viewModel).running.value }
            }
        }

        private fun awaitStopped(reason: String) {
            await(3_000, reason) {
                assertMediaNotReady()
                onMain {
                    val vm = requireNotNull(viewModel)
                    !vm.running.value && vm.uiState.value.session.phase == ConnectionPhase.STOPPED
                }
            }
        }

        private fun assertStopped(reason: String) {
            assertMediaNotReady()
            onMain {
                val vm = requireNotNull(viewModel)
                check(!vm.running.value && vm.uiState.value.session.phase == ConnectionPhase.STOPPED, reason)
            }
            stoppedSamples++
        }

        private fun observeStopped(durationMs: Long, reason: String) {
            val until = SystemClock.elapsedRealtime() + durationMs
            do {
                assertStopped(reason)
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < until)
            assertStopped(reason)
        }

        private fun await(timeoutMs: Long, reason: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + timeoutMs
            do {
                if (condition()) return
                SystemClock.sleep(40)
            } while (SystemClock.elapsedRealtime() < until)
            throw Failure(reason)
        }

        private fun clickButton(description: String) {
            await(5_000, "accessibility_button_missing_or_not_clickable") {
                assertMediaNotReady()
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                try {
                    clickMatchingNode(root, description)
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
            clicks++
        }

        private fun clickMatchingNode(node: AccessibilityNodeInfo, description: String): Boolean {
            if (node.contentDescription?.toString() == description && node.isVisibleToUser && node.isEnabled) {
                // Compose can expose the icon description on the clickable parent or its child.
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val parent = node.parent
                if (parent != null) {
                    try {
                        if (parent.isClickable && parent.isEnabled && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    } finally {
                        @Suppress("DEPRECATION")
                        parent.recycle()
                    }
                }
            }
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                try {
                    if (clickMatchingNode(child, description)) return true
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
            return false
        }

        private fun injectHome() {
            check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME),
                "home_global_action_rejected")
        }

        private fun <T> onMain(block: () -> T): T {
            val value = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                try { value.set(block()) } catch (error: Throwable) { failure.set(error) }
            }
            failure.get()?.let { throw it }
            return value.get()
        }
    }
}
