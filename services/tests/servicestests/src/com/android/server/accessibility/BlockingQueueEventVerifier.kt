/*
 * Copyright 2024 The Android Open Source Project
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
package com.android.server.accessibility

import android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS
import android.view.InputEvent
import android.view.MotionEvent
import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail

import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Assert.assertNull

private fun <T> getEvent(queue: BlockingQueue<T>, timeout: Duration): T? {
    return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
}

class BlockingQueueEventVerifier(val queue: BlockingQueue<InputEvent>) {
    fun assertReceivedMotion(matcher: Matcher<MotionEvent>) {
        val event = getMotionEvent()
        assertThat("MotionEvent checks", event, matcher)
    }

    fun assertNoEvents() {
        val event = getEvent(queue, Duration.ofMillis(50))
        assertNull(event)
    }

    private fun getMotionEvent(): MotionEvent {
        val event = getEvent(queue, Duration.ofMillis(DEFAULT_DISPATCHING_TIMEOUT_MILLIS.toLong()))
        if (event == null) {
            fail("Did not get an event")
        }
        if (event is MotionEvent) {
            return event
        }
        fail("Instead of motion, got $event")
        throw RuntimeException("should not reach here")
    }
}

