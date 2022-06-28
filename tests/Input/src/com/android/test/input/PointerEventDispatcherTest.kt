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
import android.view.InputChannel
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManagerPolicyConstants.PointerEventListener

import com.android.server.UiThread
import com.android.server.wm.PointerEventDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test

private class CrashingPointerEventListener : PointerEventListener {
    override fun onPointerEvent(motionEvent: MotionEvent) {
        throw IllegalArgumentException("This listener crashes when input event occurs")
    }
}

class PointerEventDispatcherTest {
    companion object {
        private const val TAG = "PointerEventDispatcherTest"
    }
    private val mHandlerThread = HandlerThread("Process input events")
    private lateinit var mSender: SpyInputEventSender
    private lateinit var mPointerEventDispatcher: PointerEventDispatcher
    private val mListener = CrashingPointerEventListener()

    @Before
    fun setUp() {
        val channels = InputChannel.openInputChannelPair("TestChannel")

        mHandlerThread.start()
        val looper = mHandlerThread.getLooper()
        mSender = SpyInputEventSender(channels[0], looper)

        mPointerEventDispatcher = PointerEventDispatcher(channels[1])
        mPointerEventDispatcher.registerInputEventListener(mListener)
    }

    @After
    fun tearDown() {
        mHandlerThread.quitSafely()
    }

    @Test
    fun testSendMotionToCrashingListenerDoesNotCrash() {
        // The exception will occur on the UiThread, so we can't catch it here on the test thread
        UiThread.get().setUncaughtExceptionHandler { thread, exception ->
            if (thread == UiThread.get() && exception is IllegalArgumentException) {
                // do nothing - this is the exception that we need to ignore
            } else {
                throw exception
            }
        }

        // The MotionEvent properties aren't important for this test, as long as the event
        // is a pointer event, so that it gets processed by CrashingPointerEventListener
        val downTime = 0L
        val motionEvent = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */, 0 /* metaState */)
        motionEvent.source = InputDevice.SOURCE_TOUCHSCREEN
        val seq = 10
        mSender.sendInputEvent(seq, motionEvent)
        val finishedSignal = mSender.getFinishedSignal()

        // Since the listener raises an exception during the event handling, the event should be
        // marked as 'not handled'.
        assertEquals(SpyInputEventSender.FinishedSignal(seq, handled = false), finishedSignal)
        // Ensure that there aren't double finish calls. This would crash if there's a call
        // to finish twice.
        assertNull(mSender.getFinishedSignal())
    }
}
