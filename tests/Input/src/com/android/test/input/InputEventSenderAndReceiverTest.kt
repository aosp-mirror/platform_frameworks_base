/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.test.input

import android.os.HandlerThread
import android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS
import android.os.Looper
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputEventSender
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

private fun assertKeyEvent(expected: KeyEvent, received: KeyEvent) {
    assertEquals(expected.action, received.action)
    assertEquals(expected.deviceId, received.deviceId)
    assertEquals(expected.downTime, received.downTime)
    assertEquals(expected.eventTime, received.eventTime)
    assertEquals(expected.keyCode, received.keyCode)
    assertEquals(expected.scanCode, received.scanCode)
    assertEquals(expected.repeatCount, received.repeatCount)
    assertEquals(expected.metaState, received.metaState)
    assertEquals(expected.flags, received.flags)
    assertEquals(expected.source, received.source)
    assertEquals(expected.displayId, received.displayId)
}

private fun <T> getEvent(queue: LinkedBlockingQueue<T>): T {
    try {
        return queue.poll(DEFAULT_DISPATCHING_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        throw RuntimeException("Unexpectedly interrupted while waiting for event")
    }
}

class TestInputEventReceiver(channel: InputChannel, looper: Looper) :
        InputEventReceiver(channel, looper) {
    private val mInputEvents = LinkedBlockingQueue<InputEvent>()

    override fun onInputEvent(event: InputEvent) {
        when (event) {
            is KeyEvent -> mInputEvents.put(KeyEvent.obtain(event))
            is MotionEvent -> mInputEvents.put(MotionEvent.obtain(event))
            else -> throw Exception("Received $event is neither a key nor a motion")
        }
        finishInputEvent(event, true /*handled*/)
    }

    fun getInputEvent(): InputEvent {
        return getEvent(mInputEvents)
    }
}

class TestInputEventSender(channel: InputChannel, looper: Looper) :
        InputEventSender(channel, looper) {
    data class FinishedSignal(val seq: Int, val handled: Boolean)
    data class Timeline(val inputEventId: Int, val gpuCompletedTime: Long, val presentTime: Long)

    private val mFinishedSignals = LinkedBlockingQueue<FinishedSignal>()
    private val mTimelines = LinkedBlockingQueue<Timeline>()

    override fun onInputEventFinished(seq: Int, handled: Boolean) {
        mFinishedSignals.put(FinishedSignal(seq, handled))
    }

    override fun onTimelineReported(inputEventId: Int, gpuCompletedTime: Long, presentTime: Long) {
        mTimelines.put(Timeline(inputEventId, gpuCompletedTime, presentTime))
    }

    fun getFinishedSignal(): FinishedSignal {
        return getEvent(mFinishedSignals)
    }

    fun getTimeline(): Timeline {
        return getEvent(mTimelines)
    }
}

class InputEventSenderAndReceiverTest {
    companion object {
        private const val TAG = "InputEventSenderAndReceiverTest"
    }
    private val mHandlerThread = HandlerThread("Process input events")
    private lateinit var mReceiver: TestInputEventReceiver
    private lateinit var mSender: TestInputEventSender

    @Before
    fun setUp() {
        val channels = InputChannel.openInputChannelPair("TestChannel")
        mHandlerThread.start()

        val looper = mHandlerThread.getLooper()
        mSender = TestInputEventSender(channels[0], looper)
        mReceiver = TestInputEventReceiver(channels[1], looper)
    }

    @After
    fun tearDown() {
        mHandlerThread.quitSafely()
    }

    @Test
    fun testSendAndReceiveKey() {
        val key = KeyEvent(1 /*downTime*/, 1 /*eventTime*/, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_A, 0 /*repeat*/)
        val seq = 10
        mSender.sendInputEvent(seq, key)
        val receivedKey = mReceiver.getInputEvent() as KeyEvent
        val finishedSignal = mSender.getFinishedSignal()

        // Check receiver
        assertKeyEvent(key, receivedKey)

        // Check sender
        assertEquals(TestInputEventSender.FinishedSignal(seq, handled = true), finishedSignal)
    }

    // The timeline case is slightly unusual because it goes from InputConsumer to InputPublisher.
    @Test
    fun testSendAndReceiveTimeline() {
        val sent = TestInputEventSender.Timeline(
            inputEventId = 1, gpuCompletedTime = 2, presentTime = 3)
        mReceiver.reportTimeline(sent.inputEventId, sent.gpuCompletedTime, sent.presentTime)
        val received = mSender.getTimeline()
        assertEquals(sent, received)
    }

    // If an invalid timeline is sent, the channel should get closed. This helps surface any
    // app-originating bugs early, and forces the work-around to happen in the early stages of the
    // event processing.
    @Test
    fun testSendAndReceiveInvalidTimeline() {
        val sent = TestInputEventSender.Timeline(
            inputEventId = 1, gpuCompletedTime = 3, presentTime = 2)
        mReceiver.reportTimeline(sent.inputEventId, sent.gpuCompletedTime, sent.presentTime)
        val received = mSender.getTimeline()
        assertEquals(null, received)
        // Sender will no longer receive callbacks for this fd, even if receiver sends a valid
        // timeline later
        mReceiver.reportTimeline(2 /*inputEventId*/, 3 /*gpuCompletedTime*/, 4 /*presentTime*/)
        val receivedSecondTimeline = mSender.getTimeline()
        assertEquals(null, receivedSecondTimeline)
    }
}
