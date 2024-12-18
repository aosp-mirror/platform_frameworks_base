/*
 * Copyright (C) 2020 The Android Open Source Project
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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RepeatableExecutorTest extends SysuiTestCase {

    private static final int DELAY = 100;

    private FakeSystemClock mFakeClock;
    private FakeExecutor mFakeExecutor;
    private RepeatableExecutor mExecutor;
    private CountingTask mCountingTask;

    @Before
    public void setUp() throws Exception {
        mFakeClock = new FakeSystemClock();
        mFakeExecutor = new FakeExecutor(mFakeClock);
        mCountingTask = new CountingTask();
        mExecutor = new RepeatableExecutorImpl(mFakeExecutor);
    }

    /**
     * Test FakeExecutor that receives non-delayed items to execute.
     */
    @Test
    public void testExecute() {
        mExecutor.execute(mCountingTask);
        mFakeExecutor.runAllReady();
        assertThat(mCountingTask.getCount()).isEqualTo(1);
    }

    @Test
    public void testRepeats() {
        // GIVEN that a command is queued to repeat
        mExecutor.executeRepeatedly(mCountingTask, DELAY, DELAY);
        // WHEN The clock advances and the task is run
        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runAllReady();
        // THEN another task is queued
        assertThat(mCountingTask.getCount()).isEqualTo(1);
        assertThat(mFakeExecutor.numPending()).isEqualTo(1);
    }

    @Test
    public void testNoExecutionBeforeStartDelay() {
        // WHEN a command is queued with a start delay
        mExecutor.executeRepeatedly(mCountingTask, 2 * DELAY, DELAY);
        mFakeExecutor.runAllReady();
        // THEN then it doesn't run immediately
        assertThat(mCountingTask.getCount()).isEqualTo(0);
        assertThat(mFakeExecutor.numPending()).isEqualTo(1);
    }

    @Test
    public void testExecuteAfterStartDelay() {
        // GIVEN that a command is queued to repeat with a longer start delay
        mExecutor.executeRepeatedly(mCountingTask, 2 * DELAY, DELAY);
        // WHEN the clock advances the start delay
        mFakeClock.advanceTime(2 * DELAY);
        mFakeExecutor.runAllReady();
        // THEN the command has run and another task is queued
        assertThat(mCountingTask.getCount()).isEqualTo(1);
        assertThat(mFakeExecutor.numPending()).isEqualTo(1);
    }

    @Test
    public void testExecuteWithZeroStartDelay() {
        // WHEN a command is queued with no start delay
        mExecutor.executeRepeatedly(mCountingTask, 0L, DELAY);
        mFakeExecutor.runAllReady();
        // THEN the command has run and another task is queued
        assertThat(mCountingTask.getCount()).isEqualTo(1);
        assertThat(mFakeExecutor.numPending()).isEqualTo(1);
    }

    @Test
    public void testAdvanceTimeTwice() {
        // GIVEN that a command is queued to repeat
        mExecutor.executeRepeatedly(mCountingTask, DELAY, DELAY);
        // WHEN the clock advances the time DELAY twice
        mFakeClock.advanceTime(DELAY);
        mFakeExecutor.runAllReady();
        mFakeClock.advanceTime(DELAY);
        mFakeExecutor.runAllReady();
        // THEN the command has run twice and another task is queued
        assertThat(mCountingTask.getCount()).isEqualTo(2);
        assertThat(mFakeExecutor.numPending()).isEqualTo(1);
    }

    @Test
    public void testCancel() {
        // GIVEN that a scheduled command has been cancelled
        Runnable cancel = mExecutor.executeRepeatedly(mCountingTask, DELAY, DELAY);
        cancel.run();
        // WHEN the clock advances the time DELAY
        mFakeClock.advanceTime(DELAY);
        mFakeExecutor.runAllReady();
        // THEN the comamnd has not run and no further tasks are queued
        assertThat(mCountingTask.getCount()).isEqualTo(0);
        assertThat(mFakeExecutor.numPending()).isEqualTo(0);
    }

    @Test
    public void testCancelAfterStart() {
        // GIVEN that a command has reapeated a few times
        Runnable cancel = mExecutor.executeRepeatedly(mCountingTask, DELAY, DELAY);
        mFakeClock.advanceTime(DELAY);
        mFakeExecutor.runAllReady();
        // WHEN cancelled and time advances
        cancel.run();
        // THEN the command has only run the first time
        assertThat(mCountingTask.getCount()).isEqualTo(1);
        assertThat(mFakeExecutor.numPending()).isEqualTo(0);
    }

    /**
     * Runnable used for testing that counts the number of times run() is invoked.
     */
    private static class CountingTask implements Runnable {

        private int mRunCount;

        @Override
        public void run() {
            mRunCount++;
        }

        /** Gets the run count. */
        public int getCount() {
            return mRunCount;
        }
    }
}
