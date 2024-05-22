/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.systemui.util.concurrency

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MockExecutorHandlerTest : SysuiTestCase() {
    /** Test FakeExecutor that receives non-delayed items to execute. */
    @Test
    fun testNoDelay() {
        val clock = FakeSystemClock()
        val fakeExecutor = FakeExecutor(clock)
        val handler = mockExecutorHandler(fakeExecutor)
        val runnable = RunnableImpl()
        assertEquals(10000, clock.uptimeMillis())
        assertEquals(0, runnable.mRunCount)

        // Execute two runnables. They should not run and should be left pending.
        handler.post(runnable)
        assertEquals(0, runnable.mRunCount)
        assertEquals(10000, clock.uptimeMillis())
        assertEquals(1, fakeExecutor.numPending())
        handler.post(runnable)
        assertEquals(0, runnable.mRunCount)
        assertEquals(10000, clock.uptimeMillis())
        assertEquals(2, fakeExecutor.numPending())

        // Run one pending runnable.
        assertTrue(fakeExecutor.runNextReady())
        assertEquals(1, runnable.mRunCount)
        assertEquals(10000, clock.uptimeMillis())
        assertEquals(1, fakeExecutor.numPending())
        // Run a second pending runnable.
        assertTrue(fakeExecutor.runNextReady())
        assertEquals(2, runnable.mRunCount)
        assertEquals(10000, clock.uptimeMillis())
        assertEquals(0, fakeExecutor.numPending())

        // No more runnables to run.
        assertFalse(fakeExecutor.runNextReady())

        // Add two more runnables.
        handler.post(runnable)
        handler.post(runnable)
        assertEquals(2, runnable.mRunCount)
        assertEquals(10000, clock.uptimeMillis())
        assertEquals(2, fakeExecutor.numPending())
        // Execute all pending runnables in batch.
        assertEquals(2, fakeExecutor.runAllReady())
        assertEquals(4, runnable.mRunCount)
        assertEquals(10000, clock.uptimeMillis())
        assertEquals(0, fakeExecutor.runAllReady())
    }

    /** Test FakeExecutor that is told to delay execution on items. */
    @Test
    fun testDelayed() {
        val clock = FakeSystemClock()
        val fakeExecutor = FakeExecutor(clock)
        val handler = mockExecutorHandler(fakeExecutor)
        val runnable = RunnableImpl()

        // Add three delayed runnables.
        handler.postDelayed(runnable, 1)
        handler.postDelayed(runnable, 50)
        handler.postDelayed(runnable, 100)
        assertEquals(0, runnable.mRunCount)
        assertEquals(10000, clock.uptimeMillis())
        assertEquals(3, fakeExecutor.numPending())
        // Delayed runnables should not advance the clock and therefore should not run.
        assertFalse(fakeExecutor.runNextReady())
        assertEquals(0, fakeExecutor.runAllReady())
        assertEquals(3, fakeExecutor.numPending())

        // Advance the clock to the next runnable. One runnable should execute.
        assertEquals(1, fakeExecutor.advanceClockToNext())
        assertEquals(1, fakeExecutor.runAllReady())
        assertEquals(2, fakeExecutor.numPending())
        assertEquals(1, runnable.mRunCount)
        // Advance the clock to the last runnable.
        assertEquals(99, fakeExecutor.advanceClockToLast())
        assertEquals(2, fakeExecutor.runAllReady())
        // Now all remaining runnables should execute.
        assertEquals(0, fakeExecutor.numPending())
        assertEquals(3, runnable.mRunCount)
    }

    /** Test FakeExecutor that is told to delay execution on items. */
    @Test
    fun testAtTime() {
        val clock = FakeSystemClock()
        val fakeExecutor = FakeExecutor(clock)
        val handler = mockExecutorHandler(fakeExecutor)
        val runnable = RunnableImpl()

        // Add three delayed runnables.
        handler.postAtTime(runnable, 10001)
        handler.postAtTime(runnable, 10050)
        handler.postAtTime(runnable, 10100)
        assertEquals(0, runnable.mRunCount)
        assertEquals(10000, clock.uptimeMillis())
        assertEquals(3, fakeExecutor.numPending())
        // Delayed runnables should not advance the clock and therefore should not run.
        assertFalse(fakeExecutor.runNextReady())
        assertEquals(0, fakeExecutor.runAllReady())
        assertEquals(3, fakeExecutor.numPending())

        // Advance the clock to the next runnable. One runnable should execute.
        assertEquals(1, fakeExecutor.advanceClockToNext())
        assertEquals(1, fakeExecutor.runAllReady())
        assertEquals(2, fakeExecutor.numPending())
        assertEquals(1, runnable.mRunCount)
        // Advance the clock to the last runnable.
        assertEquals(99, fakeExecutor.advanceClockToLast())
        assertEquals(2, fakeExecutor.runAllReady())
        // Now all remaining runnables should execute.
        assertEquals(0, fakeExecutor.numPending())
        assertEquals(3, runnable.mRunCount)
    }

    @Test
    fun testRemoveCallback_postDelayed() {
        val clock = FakeSystemClock()
        val fakeExecutor = FakeExecutor(clock)
        val handler = mockExecutorHandler(fakeExecutor)
        val runnable = RunnableImpl()

        handler.postDelayed(runnable, 50)
        handler.postDelayed(runnable, 150)
        fakeExecutor.advanceClockToNext()
        fakeExecutor.runAllReady()

        assertEquals(1, runnable.mRunCount)
        assertEquals(1, fakeExecutor.numPending())

        handler.removeCallbacks(runnable)
        assertEquals(0, fakeExecutor.numPending())

        assertEquals(1, runnable.mRunCount)
    }

    @Test
    fun testRemoveCallback_postAtTime() {
        val clock = FakeSystemClock()
        val fakeExecutor = FakeExecutor(clock)
        val handler = mockExecutorHandler(fakeExecutor)
        val runnable = RunnableImpl()
        assertEquals(10000, clock.uptimeMillis())

        handler.postAtTime(runnable, 10050)
        handler.postAtTime(runnable, 10150)
        fakeExecutor.advanceClockToNext()
        fakeExecutor.runAllReady()

        assertEquals(1, runnable.mRunCount)
        assertEquals(1, fakeExecutor.numPending())

        handler.removeCallbacks(runnable)
        assertEquals(0, fakeExecutor.numPending())

        assertEquals(1, runnable.mRunCount)
    }

    @Test
    fun testRemoveCallback_mixed_allRemoved() {
        val clock = FakeSystemClock()
        val fakeExecutor = FakeExecutor(clock)
        val handler = mockExecutorHandler(fakeExecutor)
        val runnable = RunnableImpl()
        assertEquals(10000, clock.uptimeMillis())

        handler.postAtTime(runnable, 10050)
        handler.postDelayed(runnable, 150)

        handler.removeCallbacks(runnable)
        assertEquals(0, fakeExecutor.numPending())

        fakeExecutor.advanceClockToLast()
        fakeExecutor.runAllReady()
        assertEquals(0, runnable.mRunCount)
    }

    @Test
    fun testRemoveCallback_differentRunnables_onlyMatchingRemoved() {
        val clock = FakeSystemClock()
        val fakeExecutor = FakeExecutor(clock)
        val handler = mockExecutorHandler(fakeExecutor)
        val runnable1 = RunnableImpl()
        val runnable2 = RunnableImpl()

        handler.postDelayed(runnable1, 50)
        handler.postDelayed(runnable2, 150)

        handler.removeCallbacks(runnable1)
        assertEquals(1, fakeExecutor.numPending())

        fakeExecutor.advanceClockToLast()
        fakeExecutor.runAllReady()
        assertEquals(0, runnable1.mRunCount)
        assertEquals(1, runnable2.mRunCount)
    }

    /**
     * Verifies that `Handler.removeMessages`, which doesn't make sense with executor backing,
     * causes an error in the test (rather than failing silently like most mocks).
     */
    @Test(expected = RuntimeException::class)
    fun testRemoveMessages_fails() {
        val clock = FakeSystemClock()
        val fakeExecutor = FakeExecutor(clock)
        val handler = mockExecutorHandler(fakeExecutor)

        handler.removeMessages(1)
    }

    private class RunnableImpl : Runnable {
        var mRunCount = 0
        override fun run() {
            mRunCount++
        }
    }
}
