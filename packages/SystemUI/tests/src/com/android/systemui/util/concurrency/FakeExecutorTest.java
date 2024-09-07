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

package com.android.systemui.util.concurrency;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.time.FakeSystemClock;

import kotlin.jvm.functions.Function4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class FakeExecutorTest extends SysuiTestCase {
    @Before
    public void setUp() throws Exception {
    }

    /**
     * Test FakeExecutor that receives non-delayed items to execute.
     */
    @Test
    public void testNoDelay() {
        FakeSystemClock clock = new FakeSystemClock();
        FakeExecutor fakeExecutor = new FakeExecutor(clock);
        RunnableImpl runnable = new RunnableImpl();

        assertEquals(10000, clock.uptimeMillis());
        assertEquals(0, runnable.mRunCount);

        // Execute two runnables. They should not run and should be left pending.
        fakeExecutor.execute(runnable);
        assertEquals(0, runnable.mRunCount);
        assertEquals(10000, clock.uptimeMillis());
        assertEquals(1, fakeExecutor.numPending());
        fakeExecutor.execute(runnable);
        assertEquals(0, runnable.mRunCount);
        assertEquals(10000, clock.uptimeMillis());
        assertEquals(2, fakeExecutor.numPending());

        // Run one pending runnable.
        assertTrue(fakeExecutor.runNextReady());
        assertEquals(1, runnable.mRunCount);
        assertEquals(10000, clock.uptimeMillis());
        assertEquals(1, fakeExecutor.numPending());
        // Run a second pending runnable.
        assertTrue(fakeExecutor.runNextReady());
        assertEquals(2, runnable.mRunCount);
        assertEquals(10000, clock.uptimeMillis());
        assertEquals(0, fakeExecutor.numPending());

        // No more runnables to run.
        assertFalse(fakeExecutor.runNextReady());

        // Add two more runnables.
        fakeExecutor.execute(runnable);
        fakeExecutor.execute(runnable);
        assertEquals(2, runnable.mRunCount);
        assertEquals(10000, clock.uptimeMillis());
        assertEquals(2, fakeExecutor.numPending());
        // Execute all pending runnables in batch.
        assertEquals(2, fakeExecutor.runAllReady());
        assertEquals(4, runnable.mRunCount);
        assertEquals(10000, clock.uptimeMillis());
        assertEquals(0, fakeExecutor.runAllReady());
    }

    /**
     * Test FakeExecutor that is told to delay execution on items.
     */
    @Test
    public void testDelayed() {
        FakeSystemClock clock = new FakeSystemClock();
        FakeExecutor fakeExecutor = new FakeExecutor(clock);
        RunnableImpl runnable = new RunnableImpl();

        // Add three delayed runnables.
        fakeExecutor.executeDelayed(runnable, 1);
        fakeExecutor.executeDelayed(runnable, 50);
        fakeExecutor.executeDelayed(runnable, 100);
        assertEquals(0, runnable.mRunCount);
        assertEquals(10000, clock.uptimeMillis());
        assertEquals(3, fakeExecutor.numPending());
        // Delayed runnables should not advance the clock and therefore should not run.
        assertFalse(fakeExecutor.runNextReady());
        assertEquals(0, fakeExecutor.runAllReady());
        assertEquals(3, fakeExecutor.numPending());

        // Advance the clock to the next runnable. One runnable should execute.
        assertEquals(1, fakeExecutor.advanceClockToNext());
        assertEquals(1, fakeExecutor.runAllReady());
        assertEquals(2, fakeExecutor.numPending());
        assertEquals(1, runnable.mRunCount);
        // Advance the clock to the last runnable.
        assertEquals(99, fakeExecutor.advanceClockToLast());
        assertEquals(2, fakeExecutor.runAllReady());
        // Now all remaining runnables should execute.
        assertEquals(0, fakeExecutor.numPending());
        assertEquals(3, runnable.mRunCount);
    }

    /**
     * Test FakeExecutor that is told to delay execution on items.
     */
    @Test
    public void testAtTime() {
        FakeSystemClock clock = new FakeSystemClock();
        FakeExecutor fakeExecutor = new FakeExecutor(clock);
        RunnableImpl runnable = new RunnableImpl();

        // Add three delayed runnables.
        fakeExecutor.executeAtTime(runnable, 10001);
        fakeExecutor.executeAtTime(runnable, 10050);
        fakeExecutor.executeAtTime(runnable, 10100);
        assertEquals(0, runnable.mRunCount);
        assertEquals(10000, clock.uptimeMillis());
        assertEquals(3, fakeExecutor.numPending());
        // Delayed runnables should not advance the clock and therefore should not run.
        assertFalse(fakeExecutor.runNextReady());
        assertEquals(0, fakeExecutor.runAllReady());
        assertEquals(3, fakeExecutor.numPending());

        // Advance the clock to the next runnable. One runnable should execute.
        assertEquals(1, fakeExecutor.advanceClockToNext());
        assertEquals(1, fakeExecutor.runAllReady());
        assertEquals(2, fakeExecutor.numPending());
        assertEquals(1, runnable.mRunCount);
        // Advance the clock to the last runnable.
        assertEquals(99, fakeExecutor.advanceClockToLast());
        assertEquals(2, fakeExecutor.runAllReady());
        // Now all remaining runnables should execute.
        assertEquals(0, fakeExecutor.numPending());
        assertEquals(3, runnable.mRunCount);
    }

    /**
     * Test FakeExecutor that is told to delay execution on items.
     */
    @Test
    public void testDelayed_AdvanceAndRun() {
        FakeSystemClock clock = new FakeSystemClock();
        FakeExecutor fakeExecutor = new FakeExecutor(clock);
        RunnableImpl runnable = new RunnableImpl();

        // Add three delayed runnables.
        fakeExecutor.executeDelayed(runnable, 1);
        fakeExecutor.executeDelayed(runnable, 50);
        fakeExecutor.executeDelayed(runnable, 100);
        assertEquals(0, runnable.mRunCount);
        assertEquals(10000, clock.uptimeMillis());
        assertEquals(3, fakeExecutor.numPending());
        // Delayed runnables should not advance the clock and therefore should not run.
        assertFalse(fakeExecutor.runNextReady());
        assertEquals(0, fakeExecutor.runAllReady());
        assertEquals(3, fakeExecutor.numPending());

        // Advance the clock to the next runnable. Check that it is run.
        assertEquals(1, fakeExecutor.advanceClockToNext());
        assertEquals(1, fakeExecutor.runAllReady());
        assertEquals(10001, clock.uptimeMillis());
        assertEquals(2, fakeExecutor.numPending());
        assertEquals(1, runnable.mRunCount);
        assertEquals(49, fakeExecutor.advanceClockToNext());
        assertEquals(1, fakeExecutor.runAllReady());
        assertEquals(10050, clock.uptimeMillis());
        assertEquals(1, fakeExecutor.numPending());
        assertEquals(2, runnable.mRunCount);
        assertEquals(50, fakeExecutor.advanceClockToNext());
        assertEquals(1, fakeExecutor.runAllReady());
        assertEquals(10100, clock.uptimeMillis());
        assertEquals(0, fakeExecutor.numPending());
        assertEquals(3, runnable.mRunCount);

        // Nothing left to do
        assertEquals(0, fakeExecutor.advanceClockToNext());
        assertEquals(0, fakeExecutor.runAllReady());
        assertEquals(10100, clock.uptimeMillis());
        assertEquals(0, fakeExecutor.numPending());
        assertEquals(3, runnable.mRunCount);
    }

    /**
     * Test execution order.
     */
    @Test
    public void testExecutionOrder() {
        FakeSystemClock clock = new FakeSystemClock();
        FakeExecutor fakeExecutor = new FakeExecutor(clock);
        RunnableImpl runnableA = new RunnableImpl();
        RunnableImpl runnableB = new RunnableImpl();
        RunnableImpl runnableC = new RunnableImpl();
        RunnableImpl runnableD = new RunnableImpl();

        Function4<Integer, Integer, Integer, Integer, Void> checkRunCounts =
                (Integer argA, Integer argB, Integer argC, Integer argD) -> {
                    assertEquals("RunnableA run count wrong", argA.intValue(), runnableA.mRunCount);
                    assertEquals("RunnableB run count wrong", argB.intValue(), runnableB.mRunCount);
                    assertEquals("RunnableC run count wrong", argC.intValue(), runnableC.mRunCount);
                    assertEquals("RunnableD run count wrong", argD.intValue(), runnableD.mRunCount);
                    return null;
                };

        assertEquals(10000, clock.uptimeMillis());
        checkRunCounts.invoke(0, 0, 0, 0);

        fakeExecutor.execute(runnableA);
        fakeExecutor.execute(runnableB);
        fakeExecutor.execute(runnableC);
        fakeExecutor.execute(runnableD);

        fakeExecutor.runNextReady();
        checkRunCounts.invoke(1, 0, 0, 0);
        fakeExecutor.runNextReady();
        checkRunCounts.invoke(1, 1, 0, 0);
        fakeExecutor.runNextReady();
        checkRunCounts.invoke(1, 1, 1, 0);
        fakeExecutor.runNextReady();
        checkRunCounts.invoke(1, 1, 1, 1);

        fakeExecutor.executeDelayed(runnableA, 100);
        fakeExecutor.execute(runnableB);
        fakeExecutor.executeDelayed(runnableC, 50);
        fakeExecutor.execute(runnableD);

        fakeExecutor.advanceClockToNext();
        fakeExecutor.runAllReady();
        checkRunCounts.invoke(1, 2, 1, 2);
        fakeExecutor.advanceClockToNext();
        fakeExecutor.runAllReady();
        checkRunCounts.invoke(1, 2, 2, 2);
        fakeExecutor.advanceClockToNext();
        fakeExecutor.runAllReady();
        checkRunCounts.invoke(2, 2, 2, 2);

        fakeExecutor.execute(runnableA);
        fakeExecutor.executeAtTime(runnableB, 0);  // this is in the past!
        fakeExecutor.executeAtTime(runnableC, 11000);
        fakeExecutor.executeAtTime(runnableD, 10500);

        fakeExecutor.advanceClockToNext();
        fakeExecutor.runAllReady();
        checkRunCounts.invoke(3, 3, 2, 2);
        fakeExecutor.advanceClockToNext();
        fakeExecutor.runAllReady();
        checkRunCounts.invoke(3, 3, 2, 3);
        fakeExecutor.advanceClockToNext();
        fakeExecutor.runAllReady();
        checkRunCounts.invoke(3, 3, 3, 3);
    }

    /**
     * Test removing a single item.
     */
    @Test
    public void testRemoval_single() {
        FakeSystemClock clock = new FakeSystemClock();
        FakeExecutor fakeExecutor = new FakeExecutor(clock);
        RunnableImpl runnable = new RunnableImpl();
        Runnable removeFunction;

        // Nothing to remove.
        assertEquals(0, runnable.mRunCount);
        assertEquals(0, fakeExecutor.numPending());

        // Two pending items that have not yet run.
        // We will try to remove the second item.
        fakeExecutor.executeDelayed(runnable, 100);
        removeFunction = fakeExecutor.executeDelayed(runnable, 200);
        assertEquals(2, fakeExecutor.numPending());
        assertEquals(0, runnable.mRunCount);

        // Remove the item.
        removeFunction.run();
        assertEquals(1, fakeExecutor.numPending());
        assertEquals(0, runnable.mRunCount);

        // One item to run.
        fakeExecutor.advanceClockToLast();
        fakeExecutor.runAllReady();
        assertEquals(0, fakeExecutor.numPending());
        assertEquals(1, runnable.mRunCount);

        // Nothing to remove.
        removeFunction.run();
        fakeExecutor.runAllReady();
        assertEquals(0, fakeExecutor.numPending());
        assertEquals(1, runnable.mRunCount);
    }

    /**
     * Test removing multiple items.
     */
    @Test
    public void testRemoval_multi() {
        FakeSystemClock clock = new FakeSystemClock();
        FakeExecutor fakeExecutor = new FakeExecutor(clock);
        List<Runnable> removeFunctions = new ArrayList<>();
        RunnableImpl runnable = new RunnableImpl();

        // Nothing to remove.
        assertEquals(0, runnable.mRunCount);
        assertEquals(0, fakeExecutor.numPending());

        // Three pending items that have not yet run.
        // We will try to remove the first and third items.
        removeFunctions.add(fakeExecutor.executeDelayed(runnable, 100));
        fakeExecutor.executeDelayed(runnable, 200);
        removeFunctions.add(fakeExecutor.executeDelayed(runnable, 300));
        assertEquals(3, fakeExecutor.numPending());
        assertEquals(0, runnable.mRunCount);

        // Remove the items.
        removeFunctions.forEach(Runnable::run);
        assertEquals(1, fakeExecutor.numPending());
        assertEquals(0, runnable.mRunCount);

        // One item to run.
        fakeExecutor.advanceClockToLast();
        fakeExecutor.runAllReady();
        assertEquals(0, fakeExecutor.numPending());
        assertEquals(1, runnable.mRunCount);

        // Nothing to remove.
        removeFunctions.forEach(Runnable::run);
        assertEquals(0, fakeExecutor.numPending());
        assertEquals(1, runnable.mRunCount);
    }

    @Test
    public void testIsExecuting() {
        FakeSystemClock clock = new FakeSystemClock();
        FakeExecutor fakeExecutor = new FakeExecutor(clock);

        Runnable runnable = () -> assertThat(fakeExecutor.isExecuting()).isTrue();

        assertThat(fakeExecutor.isExecuting()).isFalse();
        fakeExecutor.execute(runnable);
        assertThat(fakeExecutor.isExecuting()).isFalse();
    }

    private static class RunnableImpl implements Runnable {
        int mRunCount;

        @Override
        public void run() {
            mRunCount++;
        }
    }
}
