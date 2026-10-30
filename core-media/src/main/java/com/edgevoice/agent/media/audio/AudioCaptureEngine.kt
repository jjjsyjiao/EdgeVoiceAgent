package com.edgevoice.agent.media.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.Process
import android.os.SystemClock
import com.edgevoice.agent.media.ring.NativeRingBuffer
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频采集引擎：AudioRecord 采集线程作为唯一生产者，把 PCM 写入无锁 SPSC [NativeRingBuffer]。
 *
 * 零拷贝路径：
 *   AudioRecord.read(DirectByteBuffer)  ->  ring.write(sameDirectBuffer)
 * 全程无 `ByteArray` 分配，规避高频 GC 抖动（对齐技术文档 §3.1「消灭高频 GC 导致的卡顿」）。
 *
 * 线程模型：本类持有 1 个采集线程；消费者（transport 上行）从**同一个** ring 引用读取，
 * 天然满足 SPSC 约束，无需加锁。回调 [onLevel] 在采集线程上执行，务必轻量（仅更新 UI 状态）。
 */
class AudioCaptureEngine(
    private val config: AudioConfig,
    private val ring: NativeRingBuffer,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    private val frameMs: Int = 20,
    private val levelIntervalMs: Long = 50,
    private val onLevel: ((Float) -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null,
) : Closeable {

    @Volatile private var running = false
    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val captureEffects = CaptureAudioEffects()

    /** Actual platform AEC/NS availability for diagnostics; not an echo-quality promise. */
    val audioProcessing: CaptureProcessingInfo get() = captureEffects.info

    /** Effective microphone bytes accepted by the uplink ring in this session. */
    @Volatile var capturedBytes: Long = 0L
        private set

    private val frameBytes: Int = config.bytesFor(frameMs).toInt()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (running) return
        check(thread?.isAlive != true) { "上一条音频采集线程尚未退出" }
        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuf > 0) { "设备不支持 ${config.sampleRateHz}Hz/单声道采集（minBuf=$minBuf）" }

        val format = AudioFormat.Builder()
            .setSampleRate(config.sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val rec = AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, frameBytes * 4))
            .build()
        var recordingMonitor: AudioManager.AudioRecordingCallback? = null
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
            ring.clear()
            capturedBytes = 0L
            val processing = captureEffects.attach(rec.audioSessionId)
            Log.i("EdgeVoiceAudio", "capture effects: aec=${processing.echoCancellation}, ns=${processing.noiseSuppression}")
            recordingMonitor = monitorRecording(rec)
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "系统未能启动麦克风，请结束其他录音或通话后重试"
            }
            record = rec
            running = true
            val monitor = recordingMonitor
            thread = Thread({ captureLoop(rec, monitor) }, "evs-audio-capture").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            running = false
            record = null
            captureEffects.close()
            unregisterMonitor(rec, recordingMonitor)
            runCatching { rec.release() }
            throw error
        }
    }

    private fun captureLoop(rec: AudioRecord, monitor: AudioManager.AudioRecordingCallback?) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val direct = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
            var lastLevelAt = 0L
            while (running && record === rec) {
                direct.clear()
                val n = rec.read(direct, frameBytes, AudioRecord.READ_BLOCKING)
                if (!running || record !== rec) break
                check(n >= 0) { captureFailureReason(n) }
                if (n == 0) {
                    Thread.sleep(2L)
                    continue
                }
                // AudioRecord writes at the base address without advancing position.
                // flip() here would produce limit=0 and discard every captured frame.
                capturedPcmWindow(direct, n)

                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelAt >= levelIntervalMs) {
                    onLevel?.invoke(PcmUtils.rms(direct, n))
                    lastLevelAt = now
                }

                capturedBytes += ring.write(direct)
            }
        } catch (error: Throwable) {
            if (running && record === rec) runCatching { onError?.invoke(error) }
        } finally {
            // The worker owns release; stop() first unblocks read and then joins it.
            runCatching { rec.stop() }
            unregisterMonitor(rec, monitor)
            captureEffects.close()
            runCatching { rec.release() }
            if (record === rec) {
                running = false
                record = null
            }
            runCatching { onLevel?.invoke(0f) }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        val rec = record
        // stop before join: READ_BLOCKING must be released before waiting for exit.
        runCatching { rec?.stop() }
        val worker = thread
        if (worker !== Thread.currentThread()) {
            worker?.join(1_000L)
        }
        if (worker?.isAlive != true) {
            thread = null
            ring.clear()
        }
    }

    override fun close() = stop()

    /** Terminal cleanup on a dedicated IO thread, after stop(); never uses running as completion. */
    @Synchronized
    fun awaitStopped() {
        check(!running) { "请先停止采集，再等待采集线程退出" }
        val worker = thread
        check(worker !== Thread.currentThread()) { "采集线程不能等待自身退出" }
        worker?.join()
        if (thread === worker) thread = null
    }

    private fun monitorRecording(rec: AudioRecord): AudioManager.AudioRecordingCallback? {
        if (Build.VERSION.SDK_INT < 29) return null
        val reported = AtomicBoolean(false)
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                if (Build.VERSION.SDK_INT < 29) return
                if (running && record === rec && configs.any {
                        it.clientAudioSessionId == rec.audioSessionId && it.isClientSilenced
                    } && reported.compareAndSet(false, true)
                ) {
                    runCatching {
                        onError?.invoke(IllegalStateException(
                            "系统已暂停麦克风输入，请检查麦克风隐私开关或结束其他录音后手动重试",
                        ))
                    }
                }
            }
        }
        val handler = Handler(Looper.getMainLooper())
        return try {
            rec.registerAudioRecordingCallback(Executor { handler.post(it) }, callback)
            callback
        } catch (failure: RuntimeException) {
            runCatching { rec.unregisterAudioRecordingCallback(callback) }
            Log.w("EdgeVoiceAudio", "Recording status monitor unavailable: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun unregisterMonitor(rec: AudioRecord, callback: AudioManager.AudioRecordingCallback?) {
        if (Build.VERSION.SDK_INT >= 29 && callback != null) {
            runCatching { rec.unregisterAudioRecordingCallback(callback) }
        }
    }
}

/** Kept separate so the platform ByteBuffer contract has a pure JVM regression test. */
internal fun capturedPcmWindow(buffer: ByteBuffer, bytesRead: Int): ByteBuffer {
    require(bytesRead in 0..buffer.capacity())
    buffer.position(0)
    buffer.limit(bytesRead)
    return buffer
}

internal fun captureFailureReason(code: Int): String = when (code) {
    AudioRecord.ERROR_DEAD_OBJECT -> "系统音频服务或麦克风设备已失效，请重新连接设备后启动会话（错误码 $code）"
    AudioRecord.ERROR_INVALID_OPERATION -> "麦克风采集状态被系统回收，请结束其他录音或通话后重试（错误码 $code）"
    AudioRecord.ERROR_BAD_VALUE -> "当前音频设备不支持采集参数，请切换耳机或外放后重试（错误码 $code）"
    else -> "麦克风读取失败，请检查系统麦克风权限与音频设备后重试（错误码 $code）"
}
