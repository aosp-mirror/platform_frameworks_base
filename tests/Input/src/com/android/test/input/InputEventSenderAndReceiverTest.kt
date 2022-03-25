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
import android.os.Looper
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.KeyEvent
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

private fun getTestKeyEvent(): KeyEvent {
    return KeyEvent(1 /*downTime*/, 1 /*eventTime*/, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_A, 0 /*repeat*/)
}

private class CrashingInputEventReceiver(channel: InputChannel, looper: Looper) :
        InputEventReceiver(channel, looper) {
    override fun onInputEvent(event: InputEvent) {
        try {
            throw IllegalArgumentException("This receiver crashes when it receives input event")
        } finally {
            finishInputEvent(event, true /*handled*/)
        }
    }
}

class InputEventSenderAndReceiverTest {
    companion object {
        private const val TAG = "InputEventSenderAndReceiverTest"
    }
    private val mHandlerThread = HandlerThread("Process input events")
    private lateinit var mReceiver: SpyInputEventReceiver
    private lateinit var mSender: SpyInputEventSender

    @Before
    fun setUp() {
        val channels = InputChannel.openInputChannelPair("TestChannel")
        mHandlerThread.start()

        val looper = mHandlerThread.getLooper()
        mSender = SpyInputEventSender(channels[0], looper)
        mReceiver = SpyInputEventReceiver(channels[1], looper)
    }

    @After
    fun tearDown() {
        mHandlerThread.quitSafely()
    }

    @Test
    fun testSendAndReceiveKey() {
        val key = getTestKeyEvent()
        val seq = 10
        mSender.sendInputEvent(seq, key)
        val receivedKey = mReceiver.getInputEvent() as KeyEvent
        val finishedSignal = mSender.getFinishedSignal()

        // Check receiver
        assertKeyEvent(key, receivedKey)

        // Check sender
        assertEquals(SpyInputEventSender.FinishedSignal(seq, handled = true), finishedSignal)
    }

    // The timeline case is slightly unusual because it goes from InputConsumer to InputPublisher.
    @Test
    fun testSendAndReceiveTimeline() {
        val sent = SpyInputEventSender.Timeline(
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
        val sent = SpyInputEventSender.Timeline(
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

    /**
     * If a receiver throws an exception during 'onInputEvent' execution, the 'finally' block still
     * completes, and therefore, finishInputEvent is called. Make sure that there's no crash in the
     * native layer in these circumstances.
     * In this test, we are reusing the 'mHandlerThread', but we are creating new sender and
     * receiver.
     */
    @Test
    fun testCrashingReceiverDoesNotCrash() {
        val channels = InputChannel.openInputChannelPair("TestChannel2")
        val sender = SpyInputEventSender(channels[0], mHandlerThread.getLooper())

        // Need a separate thread for the receiver so that the sender can still get the response
        // after the receiver crashes
        val receiverThread = HandlerThread("Receive input events")
        receiverThread.start()
        val crashingReceiver = CrashingInputEventReceiver(channels[1], receiverThread.getLooper())
        receiverThread.setUncaughtExceptionHandler { thread, exception ->
            if (thread == receiverThread && exception is IllegalArgumentException) {
                // do nothing - this is the exception that we need to ignore
            } else {
                throw exception
            }
        }

        val key = getTestKeyEvent()
        val seq = 11
        sender.sendInputEvent(seq, key)
        val finishedSignal = sender.getFinishedSignal()
        assertEquals(SpyInputEventSender.FinishedSignal(seq, handled = true), finishedSignal)

        // Clean up
        crashingReceiver.dispose()
        sender.dispose()
        receiverThread.quitSafely()
    }
}
