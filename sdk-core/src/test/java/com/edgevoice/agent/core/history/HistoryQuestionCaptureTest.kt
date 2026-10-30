package com.edgevoice.agent.core.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistoryQuestionCaptureTest {
    @Test fun serverOffsetsKeepQuestionStartEvenWhenSpeechStartEventIsLate() {
        val capture = HistoryQuestionCapture(bytesPerSecond = 1_000)
        capture.append(ByteArray(4_000) { 1 })
        capture.speechStartedAt(1_000)
        capture.append(ByteArray(2_000) { 2 })
        capture.speechEndedAt(5_000)
        val question = capture.speechStopped()!!
        assertThat(question.size).isEqualTo(4_000)
        assertThat(question.take(3_000).all { it == 1.toByte() }).isTrue()
        assertThat(question.drop(3_000).all { it == 2.toByte() }).isTrue()
    }

    @Test fun replayGapAndUnrecognizedServerTimelineFailClosed() {
        val capture = HistoryQuestionCapture(bytesPerSecond = 1_000)
        capture.append(ByteArray(1_000))
        capture.clear()
        capture.append(ByteArray(2_000), absoluteStartBytes = 7_000)
        capture.speechStartedAt(1_000)
        capture.speechEndedAt(8_500)
        assertThat(capture.speechStopped()).isNull()
        capture.speechStartedAt(7_000)
        capture.speechEndedAt(8_500)
        assertThat(capture.speechStopped()!!.size).isEqualTo(1_500)
    }

    @Test fun snapshotContainsPrerollAndCompleteQuestionButNotLaterHttpWait() {
        val capture = HistoryQuestionCapture(bytesPerSecond = 1_000)
        capture.append(ByteArray(2_000) { 1 })
        capture.speechStarted()
        capture.append(ByteArray(2_000) { 2 })
        val question = capture.speechStopped()!!
        capture.append(ByteArray(10_000) { 3 })
        assertThat(question.size).isEqualTo(3_000)
        assertThat(question.take(1_000).all { it == 1.toByte() }).isTrue()
        assertThat(question.drop(1_000).all { it == 2.toByte() }).isTrue()
    }

    @Test fun tooLongQuestionFailsRatherThanReplayItsTail() {
        val capture = HistoryQuestionCapture(bytesPerSecond = 1_000, maxQuestionSeconds = 2, preRollMs = 0)
        capture.speechStarted()
        repeat(40) { capture.append(ByteArray(200)) }
        assertThat(capture.speechStopped()).isNull()
    }

    @Test fun noSpeechStartOrNewSessionCannotReusePreviousAudio() {
        val capture = HistoryQuestionCapture(bytesPerSecond = 1_000)
        capture.append(ByteArray(1_000))
        assertThat(capture.speechStopped()).isNull()
        capture.speechStarted()
        capture.append(ByteArray(2_000))
        capture.clear()
        assertThat(capture.speechStopped()).isNull()
    }
}
