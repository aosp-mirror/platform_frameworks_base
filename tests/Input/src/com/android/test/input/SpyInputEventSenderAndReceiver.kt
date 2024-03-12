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

import org.junit.Assert.assertNull

private fun <T> getEvent(queue: LinkedBlockingQueue<T>): T? {
    return queue.poll(DEFAULT_DISPATCHING_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
}

private fun <T> assertNoEvents(queue: LinkedBlockingQueue<T>) {
    // Poll the queue with a shorter timeout, to make the check faster.
    assertNull(queue.poll(100L, TimeUnit.MILLISECONDS))
}

class SpyInputEventReceiver(channel: InputChannel, looper: Looper) :
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

    fun getInputEvent(): InputEvent? {
        return getEvent(mInputEvents)
    }
}

class SpyInputEventSender(channel: InputChannel, looper: Looper) :
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

    fun getFinishedSignal(): FinishedSignal? {
        return getEvent(mFinishedSignals)
    }

    fun getTimeline(): Timeline? {
        return getEvent(mTimelines)
    }

    fun assertNoEvents() {
        assertNoEvents(mFinishedSignals)
        assertNoEvents(mTimelines)
    }
}
