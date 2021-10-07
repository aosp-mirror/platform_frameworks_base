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

package com.android.server.pm.test.install

import com.android.server.pm.utils.RequestThrottle
import com.android.server.testutils.TestHandler
import com.google.common.collect.Range
import com.google.common.truth.LongSubject
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RequestThrottleTest {

    private val counter = AtomicInteger(0)

    private val handler = TestHandler(null)

    @Before
    fun resetValues() {
        handler.flush()
        counter.set(0)
        assertThat(counter.get()).isEqualTo(0)
    }

    @Test
    fun simpleThrottle() {
        val request = RequestThrottle(handler) {
            counter.incrementAndGet()
            true
        }

        fun sendRequests() {
            request.schedule()
            val thread = startThread { request.schedule() }
            request.schedule()
            thread.joinForTest()
        }

        sendRequests()
        handler.flush()
        assertThat(counter.get()).isEqualTo(1)

        sendRequests()
        handler.flush()
        assertThat(counter.get()).isEqualTo(2)
    }

    @Test
    fun exceptionInRequest() {
        val shouldThrow = AtomicBoolean(true)
        val request = RequestThrottle(handler) {
            if (shouldThrow.get()) {
                throw RuntimeException()
            }
            counter.incrementAndGet()
            true
        }

        fun sendRequests() {
            request.schedule()
            val thread = startThread { request.schedule() }
            request.schedule()
            thread.joinForTest()
        }

        sendRequests()
        try {
            handler.flush()
        } catch (ignored: Exception) {
        }
        assertThat(counter.get()).isEqualTo(0)

        shouldThrow.set(false)

        sendRequests()
        handler.flush()
        assertThat(counter.get()).isEqualTo(1)
    }

    @Test
    fun scheduleWhileRunning() {
        val latchForStartRequest = CountDownLatch(1)
        val latchForEndRequest = CountDownLatch(1)
        val request = RequestThrottle(handler) {
            latchForStartRequest.countDown()
            counter.incrementAndGet()
            latchForEndRequest.awaitForTest()
            true
        }

        // Schedule and block a request
        request.schedule()
        val handlerThread = startThread { handler.timeAdvance() }
        latchForStartRequest.awaitForTest()

        // Hit it with other requests
        request.schedule()
        (0..5).map { startThread { request.schedule() } }
                .forEach { it.joinForTest() }

        // Release everything
        latchForEndRequest.countDown()
        handlerThread.join()
        handler.flush()

        // Ensure another request was run after initial blocking request ends
        assertThat(counter.get()).isEqualTo(2)
    }

    @Test
    fun backoffRetry() {
        val time = AtomicLong(0)
        val handler = TestHandler(null) { time.get() }
        val returnValue = AtomicBoolean(false)
        val request = RequestThrottle(handler, 3, 1000, 2) {
            counter.incrementAndGet()
            returnValue.get()
        }

        request.schedule()

        handler.timeAdvance()
        handler.pendingMessages.apply {
            assertThat(size).isEqualTo(1)
            assertThat(single().sendTime).isAround(1000)
        }

        time.set(1000)
        handler.timeAdvance()
        handler.pendingMessages.apply {
            assertThat(size).isEqualTo(1)
            assertThat(single().sendTime).isAround(3000)
        }

        time.set(3000)
        handler.timeAdvance()
        handler.pendingMessages.apply {
            assertThat(size).isEqualTo(1)
            assertThat(single().sendTime).isAround(7000)
        }

        returnValue.set(true)
        time.set(7000)
        handler.timeAdvance()
        assertThat(handler.pendingMessages).isEmpty()

        // Ensure another request was run after initial blocking request ends
        assertThat(counter.get()).isEqualTo(4)
    }

    @Test
    fun forceWriteMultiple() {
        val request = RequestThrottle(handler) {
            counter.incrementAndGet()
            true
        }

        request.runNow()
        request.runNow()
        request.runNow()

        assertThat(counter.get()).isEqualTo(3)
    }

    @Test
    fun forceWriteNowWithoutSync() {
        // When forcing a write without synchronizing the request block, 2 instances will be run.
        // There is no test for "with sync" because any logic to avoid multiple runs is left
        // entirely up to the caller.

        val barrierForEndRequest = CyclicBarrier(2)
        val request = RequestThrottle(handler) {
            counter.incrementAndGet()
            barrierForEndRequest.awaitForTest()
            true
        }

        // Schedule and block a request
        request.schedule()
        val thread = startThread { handler.timeAdvance() }

        request.runNow()

        thread.joinForTest()

        assertThat(counter.get()).isEqualTo(2)
    }

    private fun CountDownLatch.awaitForTest() = assertThat(await(5, TimeUnit.SECONDS)).isTrue()
    private fun CyclicBarrier.awaitForTest() = await(5, TimeUnit.SECONDS)
    private fun Thread.joinForTest() = join(5000)

    private fun startThread(block: () -> Unit) = Thread { block() }.apply { start() }

    // Float math means time calculations are not exact, so use a loose range
    private fun LongSubject.isAround(value: Long, threshold: Long = 10) =
            isIn(Range.closed(value - threshold, value + threshold))
}
