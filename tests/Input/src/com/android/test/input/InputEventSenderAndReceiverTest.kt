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
import android.view.InputEventSender
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.CountDownLatch
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

class TestInputEventReceiver(channel: InputChannel, looper: Looper) :
        InputEventReceiver(channel, looper) {
    companion object {
        const val TAG = "TestInputEventReceiver"
    }

    var lastEvent: InputEvent? = null

    override fun onInputEvent(event: InputEvent) {
        lastEvent = when (event) {
            is KeyEvent -> KeyEvent.obtain(event)
            is MotionEvent -> MotionEvent.obtain(event)
            else -> throw Exception("Received $event is neither a key nor a motion")
        }
        finishInputEvent(event, true /*handled*/)
    }
}

class TestInputEventSender(channel: InputChannel, looper: Looper) :
        InputEventSender(channel, looper) {
    companion object {
        const val TAG = "TestInputEventSender"
    }
    data class FinishedResult(val seq: Int, val handled: Boolean)

    private var mFinishedSignal = CountDownLatch(1)
    override fun onInputEventFinished(seq: Int, handled: Boolean) {
        finishedResult = FinishedResult(seq, handled)
        mFinishedSignal.countDown()
    }
    lateinit var finishedResult: FinishedResult

    fun waitForFinish() {
        mFinishedSignal.await()
        mFinishedSignal = CountDownLatch(1) // Ready for next event
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
        mSender.waitForFinish()

        // Check receiver
        assertKeyEvent(key, mReceiver.lastEvent!! as KeyEvent)

        // Check sender
        assertEquals(seq, mSender.finishedResult.seq)
        assertEquals(true, mSender.finishedResult.handled)
    }
}
