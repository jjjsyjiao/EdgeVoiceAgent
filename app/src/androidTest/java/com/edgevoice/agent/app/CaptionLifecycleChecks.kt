package com.edgevoice.agent.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.edgevoice.agent.core.ConversationCaption
import com.edgevoice.agent.core.ConversationTurn
import com.edgevoice.agent.core.OrchestrationEngine
import com.edgevoice.agent.ui.model.ConnectionPhase
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Display-only regression: real Home/launcher routing, recreation, and fresh-VM saved state. */
internal object CaptionLifecycleChecks {
    fun run(instrumentation: Instrumentation): Bundle = Checks(instrumentation).run()

    private class Failure(val reason: String) : AssertionError(reason)

    private class Checks(private val instrumentation: Instrumentation) {
        private val result = Bundle()
        private val application = instrumentation.targetContext.applicationContext as Application
        private val createdCount = AtomicInteger()
        private val resumed = AtomicReference<MainActivity?>()
        private val unsafeObserved = AtomicBoolean()
        private val activities = mutableListOf<MainActivity>() // Main thread only.
        private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var recreatedStore: ViewModelStore? = null
        private var viewModel: AgentViewModel? = null
        private var stage = "captions_initializing"
        private var homeReturns = 0
        private var stoppedSamples = 0
        private var callbacksRegistered = false
        private var caption = ConversationCaption(
            "第五轮：当前问题", "第五轮：当前回答", true,
            previousTurns = (1..4).map { index ->
                ConversationTurn("第${index}轮：之前的问题", "第${index}轮：之前的回答")
            },
        )

        private val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is MainActivity) {
                    createdCount.incrementAndGet()
                    activities.add(activity)
                }
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) resumed.set(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity is MainActivity) resumed.compareAndSet(activity, null)
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity) activities.remove(activity)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }

        fun run(): Bundle = runBlocking {
            try {
                withTimeout(60_000L) {
                    report("captions_bare_activity_launch")
                    onMain {
                        application.registerActivityLifecycleCallbacks(callbacks)
                        callbacksRegistered = true
                    }
                    // Deliberately reproduce the bare adb-style entry, with no MAIN/LAUNCHER.
                    val original = instrumentation.startActivitySync(
                        Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    ) as MainActivity
                    val vm = onMain {
                        original.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        ViewModelProvider(original)[AgentViewModel::class.java]
                    }
                    viewModel = vm
                    observe(vm)
                    await("captions_initial_activity_not_resumed") { isResumed(original) }
                    check(createdCount.get() == 1, "captions_initial_activity_count_mismatch")
                    assertStopped(vm)

                    report("captions_seed_display")
                    check(onMain { engine(vm).restoreConversation(caption) }, "captions_restore_rejected")
                    awaitCaption(vm)
                    awaitVisibleCaption()
                    report("captions_scroll_five_turns")
                    scrollToEdge(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    check(hasScrollActionInWindow(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
                        "captions_history_not_scrollable_up")
                    scrollToEdge(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    check(hasScrollActionInWindow(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
                        "captions_history_not_scrollable_down")
                    scrollToEdge(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    result.putBoolean("five_turns_scrollable", true)

                    report("captions_streaming_follow")
                    repeat(12) { index ->
                        caption = caption.copy(assistantText = caption.assistantText + " 新回答${index + 1}")
                        check(onMain { engine(vm).restoreConversation(caption) }, "captions_streaming_update_rejected")
                        awaitCaption(vm)
                        delay(45L)
                    }
                    delay(300L)
                    check(!hasScrollActionInWindow(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
                        "captions_streaming_did_not_follow_latest")
                    awaitVisibleCaption()
                    result.putBoolean("streaming_follows_latest", true)

                    scrollToEdge(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    check(hasScrollActionInWindow(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
                        "captions_manual_history_scroll_missing")
                    report("captions_manual_scroll_not_stolen")
                    caption = caption.copy(assistantText = caption.assistantText + "\n" + "后续回答".repeat(90))
                    check(onMain { engine(vm).restoreConversation(caption) }, "captions_second_streaming_update_rejected")
                    awaitCaption(vm)
                    delay(400L)
                    check(hasScrollActionInWindow(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
                        "captions_manual_scroll_was_stolen")
                    scrollToEdge(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    result.putBoolean("manual_scroll_position_preserved", true)

                    repeat(3) { index ->
                        report("captions_home_${index + 1}")
                        check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME),
                            "captions_home_global_action_rejected")
                        await("captions_home_did_not_stop_activity") {
                            assertStopped(vm)
                            onMain { original.lifecycle.currentState < Lifecycle.State.STARTED }
                        }
                        report("captions_launcher_return_${index + 1}")
                        onMain {
                            // Ordinary launcher flags only. SINGLE_TOP, REORDER_TO_FRONT and
                            // CLEAR_TOP would hide the duplicate-Activity regression.
                            instrumentation.targetContext.startActivity(
                                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setComponent(ComponentName(instrumentation.targetContext, MainActivity::class.java))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                            )
                        }
                        await("captions_launcher_did_not_resume_original") {
                            check(createdCount.get() == 1, "captions_launcher_created_duplicate_activity")
                            assertStopped(vm)
                            isResumed(original)
                        }
                        check(onMain { ViewModelProvider(original)[AgentViewModel::class.java] === vm },
                            "captions_launcher_changed_view_model")
                        awaitCaption(vm)
                        awaitVisibleCaption()
                        observeStopped(vm, 500L)
                        homeReturns++
                    }
                    result.putInt("launcher_main_activity_instances", createdCount.get())
                    result.putBoolean("launcher_retained_original_activity", true)
                    result.putBoolean("launcher_retained_original_view_model", true)

                    report("captions_activity_recreate")
                    onMain { original.recreate() }
                    await("captions_recreated_activity_not_resumed") {
                        assertStopped(vm)
                        val next = resumed.get()
                        next != null && next !== original && isResumed(next)
                    }
                    val replacement = requireNotNull(resumed.get())
                    check(createdCount.get() == 2, "captions_recreate_activity_count_mismatch")
                    check(onMain { ViewModelProvider(replacement)[AgentViewModel::class.java] === vm },
                        "captions_recreate_did_not_retain_view_model")
                    awaitCaption(vm)
                    awaitVisibleCaption()
                    observeStopped(vm, 500L)
                    result.putBoolean("activity_recreate_retained_caption", true)
                    result.putBoolean("activity_recreate_retained_view_model", true)

                    report("captions_new_vm_saved_state_reconstruction")
                    var snapshot = emptyMap<String, Any?>()
                    await("captions_saved_state_did_not_capture_caption") {
                        snapshot = onMain {
                            val handle = fieldByType(vm, SavedStateHandle::class.java)
                            handle.keys().associateWith { handle.get<Any?>(it) }
                        }
                        snapshot.isNotEmpty() && SavedConversation(SavedStateHandle(snapshot)).restore() == caption
                    }
                    check(snapshot.isNotEmpty() &&
                        SavedConversation(SavedStateHandle(snapshot)).restore() == caption,
                        "captions_saved_state_did_not_restore_five_turns")
                    val restored = onMain {
                        val store = ViewModelStore().also { recreatedStore = it }
                        val factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                check(modelClass == AgentViewModel::class.java, "captions_unexpected_view_model_class")
                                return AgentViewModel(application, SavedStateHandle(snapshot)) as T
                            }
                        }
                        ViewModelProvider(store, factory)[AgentViewModel::class.java]
                    }
                    check(restored !== vm, "captions_saved_state_reused_view_model")
                    observe(restored)
                    awaitCaption(restored)
                    observeStopped(restored, 500L)
                    result.putInt("saved_state_field_count", snapshot.size)
                    result.putInt("restored_conversation_turns", caption.visibleTurns.size)
                    result.putBoolean("new_vm_saved_state_reconstructed", true)
                    result.putBoolean("os_process_death_tested", false)
                    result.putBoolean("caption_visible_in_real_ui", true)
                    result.putString("status", "PASS")
                }
            } catch (error: Throwable) {
                result.putString("status", "FAIL")
                result.putString("failed_stage", stage)
                val controlled = generateSequence(error) { it.cause }.take(8).filterIsInstance<Failure>().firstOrNull()
                // Exception messages can contain configuration or caption text; only fixed codes/classes escape.
                result.putString("failure", controlled?.reason ?: error.javaClass.simpleName.take(100))
            } finally {
                observers.coroutineContext[Job]?.cancelAndJoin()
                runCatching {
                    onMain {
                        recreatedStore?.clear()
                        recreatedStore = null
                        viewModel?.stopSession()
                        activities.toList().forEach { it.finish() }
                    }
                    instrumentation.waitForIdleSync()
                }.onFailure {
                    result.putString("status", "FAIL")
                    result.putString("cleanup_failure", it.javaClass.simpleName.take(100))
                }
                runCatching {
                    if (callbacksRegistered) onMain { application.unregisterActivityLifecycleCallbacks(callbacks) }
                }.onFailure {
                    result.putString("status", "FAIL")
                    result.putString("cleanup_failure", it.javaClass.simpleName.take(100))
                }
                if (unsafeObserved.get()) {
                    result.putString("status", "FAIL")
                    result.putString("failure", "captions_session_or_camera_activated")
                }
                result.putInt("home_launcher_returns", homeReturns)
                result.putInt("total_main_activity_instances", createdCount.get())
                result.putInt("stopped_state_samples", stoppedSamples)
                result.putInt("user_caption_length", caption.userText.length)
                result.putInt("assistant_caption_length", caption.assistantText.length)
                result.putBoolean("unsafe_session_observed", unsafeObserved.get())
                result.putBoolean("microphone_requested", false)
                result.putBoolean("camera_requested", false)
                result.putBoolean("lock_screen_tested", false)
            }
            result
        }

        private fun observe(vm: AgentViewModel) {
            observers.launch { vm.uiState.collect { state ->
                if (state.session.phase != ConnectionPhase.STOPPED || state.sessionRunning || state.visionEnabled) {
                    unsafeObserved.set(true)
                }
            } }
            observers.launch { vm.running.collect { if (it) unsafeObserved.set(true) } }
            observers.launch { vm.visionEnabled.collect { if (it) unsafeObserved.set(true) } }
            observers.launch { engine(vm).sessionStatus.collect { if (it.phase.name != "STOPPED") unsafeObserved.set(true) } }
        }

        private suspend fun awaitCaption(vm: AgentViewModel) {
            // This runs on the instrumentation worker, so Main remains free to combine/render UI state.
            withTimeout(5_000L) {
                vm.uiState.first {
                    it.userText == caption.userText && it.assistantText == caption.assistantText &&
                        it.assistantInterrupted == caption.assistantInterrupted &&
                        it.conversationTurns == caption.visibleTurns
                }
            }
            assertStopped(vm)
        }

        private fun assertStopped(vm: AgentViewModel) {
            check(!unsafeObserved.get(), "captions_session_or_camera_activated")
            onMain {
                check(!vm.running.value && vm.uiState.value.session.phase == ConnectionPhase.STOPPED,
                    "captions_session_not_stopped")
                check(!vm.visionEnabled.value, "captions_camera_enabled")
                val media = engine(vm).mediaTelemetry()
                check(!media.microphoneActive && media.captureBytes == 0L && media.uplinkBytes == 0L && !media.visionActive,
                    "captions_media_started")
            }
            check(Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "evs-audio-capture" },
                "captions_capture_worker_started")
            stoppedSamples++
        }

        private suspend fun observeStopped(vm: AgentViewModel, durationMs: Long) {
            val until = SystemClock.elapsedRealtime() + durationMs
            do {
                assertStopped(vm)
                delay(40L)
            } while (SystemClock.elapsedRealtime() < until)
            assertStopped(vm)
        }

        private suspend fun awaitVisibleCaption() {
            val condition: () -> Boolean = condition@{
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@condition false
                try {
                    val answerVisible = containsVisibleText(root, caption.assistantText.takeLast(8))
                    // A long answer legitimately moves the question outside the viewport.
                    answerVisible && (caption.assistantText.length > 200 || containsVisibleText(root, caption.userText))
                }
                finally { @Suppress("DEPRECATION") root.recycle() }
            }
            try {
                await("captions_real_ui_caption_missing", condition)
            } catch (failure: Failure) {
                result.putBoolean("caption_ui_can_scroll_forward", hasScrollActionInWindow(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                result.putBoolean("caption_ui_can_scroll_backward", hasScrollActionInWindow(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null) {
                    try {
                        result.putBoolean("caption_ui_answer_node_present", containsVisibleText(root, caption.assistantText.takeLast(8)))
                        result.putBoolean("caption_ui_question_node_present", containsVisibleText(root, caption.userText))
                        val texts = mutableListOf<String>()
                        collectVisibleTexts(root, texts)
                        result.putString("caption_ui_visible_texts", texts.joinToString(" | ").take(600))
                    } finally { @Suppress("DEPRECATION") root.recycle() }
                }
                throw failure
            }
        }

        private suspend fun scrollToEdge(direction: Int) {
            repeat(12) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                    ?: throw Failure("captions_scroll_root_missing")
                try {
                    val scrollable = findScrollableWithAction(root, direction) ?: return
                    try { swipe(scrollable, direction) }
                    finally { if (scrollable !== root) { @Suppress("DEPRECATION") scrollable.recycle() } }
                } finally { @Suppress("DEPRECATION") root.recycle() }
                delay(150L)
            }
            throw Failure("captions_scroll_did_not_reach_edge")
        }

        private fun hasScrollActionInWindow(direction: Int): Boolean {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
            return try { hasScrollAction(root, direction) }
            finally { @Suppress("DEPRECATION") root.recycle() }
        }

        private fun findScrollableWithAction(node: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
            if (node.isScrollable && node.actionList.any { it.id == direction }) return node
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                val found = findScrollableWithAction(child, direction)
                if (found != null) return found
                @Suppress("DEPRECATION") child.recycle()
            }
            return null
        }

        private fun swipe(node: AccessibilityNodeInfo, direction: Int) {
            val bounds = Rect().also(node::getBoundsInScreen)
            check(bounds.width() > 30 && bounds.height() > 60, "captions_scroll_bounds_invalid")
            val x = bounds.centerX().toFloat()
            val low = bounds.top + bounds.height() * 0.75f
            val high = bounds.top + bounds.height() * 0.25f
            val from = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) low else high
            val to = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) high else low
            val started = SystemClock.uptimeMillis()
            fun inject(action: Int, y: Float, time: Long) {
                val event = MotionEvent.obtain(started, time, action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(instrumentation.uiAutomation.injectInputEvent(event, true), "captions_swipe_rejected") }
                finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN, from, started)
            repeat(8) { step -> inject(MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 8f,
                SystemClock.uptimeMillis().coerceAtLeast(started + step + 1)) }
            inject(MotionEvent.ACTION_UP, to, SystemClock.uptimeMillis().coerceAtLeast(started + 9))
        }

        private fun hasScrollAction(node: AccessibilityNodeInfo, direction: Int): Boolean {
            if (node.isScrollable && node.actionList.any { it.id == direction }) return true
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                try { if (hasScrollAction(child, direction)) return true }
                finally { @Suppress("DEPRECATION") child.recycle() }
            }
            return false
        }

        private fun containsVisibleText(node: AccessibilityNodeInfo, expected: String): Boolean {
            if (node.isVisibleToUser && node.text?.toString()?.contains(expected) == true) return true
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                try { if (containsVisibleText(child, expected)) return true }
                finally { @Suppress("DEPRECATION") child.recycle() }
            }
            return false
        }

        private fun collectVisibleTexts(node: AccessibilityNodeInfo, output: MutableList<String>) {
            if (node.isVisibleToUser) node.text?.toString()?.take(50)?.let(output::add)
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                try { collectVisibleTexts(child, output) }
                finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }

        private fun isResumed(activity: MainActivity): Boolean = onMain {
            resumed.get() === activity && activity.lifecycle.currentState == Lifecycle.State.RESUMED
        }

        private suspend fun await(reason: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 8_000L
            do {
                if (condition()) return
                delay(40L)
            } while (SystemClock.elapsedRealtime() < until)
            throw Failure(reason)
        }

        private fun engine(vm: AgentViewModel) = fieldByType(vm, OrchestrationEngine::class.java)

        private fun <T> fieldByType(owner: Any, type: Class<T>): T {
            val field = owner.javaClass.declaredFields.singleOrNull { type.isAssignableFrom(it.type) }
                ?: throw Failure("captions_required_state_field_missing")
            field.isAccessible = true
            return type.cast(field.get(owner)) ?: throw Failure("captions_required_state_field_null")
        }

        private fun report(next: String) {
            stage = next
            instrumentation.sendStatus(1, Bundle().apply {
                putString("stage", next)
                putInt("home_launcher_returns", homeReturns)
                putInt("main_activity_instances", createdCount.get())
            })
        }

        private fun check(condition: Boolean, reason: String) {
            if (!condition) throw Failure(reason)
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
