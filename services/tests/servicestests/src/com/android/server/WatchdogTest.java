/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.os.Handler;
import android.os.SimpleClock;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.Watchdog.HandlerChecker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.ZoneOffset;

/** Test class for {@link Watchdog}. */
@RunWith(AndroidJUnit4.class)
public class WatchdogTest {
    private static final int TIMEOUT_MS = 10;

    private TestClock mClock;
    private Handler mHandler;
    private HandlerChecker mChecker;

    @Before
    public void setUp() {
        mClock = new TestClock();
        mHandler = mock(Handler.class);
        mChecker =
                new HandlerChecker(mHandler, "monitor thread", new Object(), mClock) {
                    @Override
                    public boolean isHandlerPolling() {
                        return false;
                    }
                };
    }

    @Test
    public void checkerPausedUntilResume() {
        Watchdog.Monitor monitor = mock(Watchdog.Monitor.class);
        mChecker.addMonitorLocked(monitor);

        mChecker.pauseLocked("pausing");
        mChecker.scheduleCheckLocked(TIMEOUT_MS);
        verifyNoMoreInteractions(mHandler);
        assertEquals(Watchdog.COMPLETED, mChecker.getCompletionStateLocked());

        mChecker.resumeLocked("resuming");
        mChecker.scheduleCheckLocked(10);
        assertEquals(Watchdog.WAITING, mChecker.getCompletionStateLocked());
    }

    @Test
    public void checkerPausedUntilDeadline() {
        Watchdog.Monitor monitor = mock(Watchdog.Monitor.class);
        mChecker.addMonitorLocked(monitor);

        mChecker.pauseForLocked(10, "pausing");
        mChecker.scheduleCheckLocked(TIMEOUT_MS);
        verifyNoMoreInteractions(mHandler);
        assertEquals(Watchdog.COMPLETED, mChecker.getCompletionStateLocked());

        mClock.advanceBy(5);
        verifyNoMoreInteractions(mHandler);
        assertEquals(Watchdog.COMPLETED, mChecker.getCompletionStateLocked());

        // Above the 10s timeout. Watchdog should not be paused anymore.
        mClock.advanceBy(6);
        mChecker.scheduleCheckLocked(TIMEOUT_MS);
        assertEquals(Watchdog.WAITING, mChecker.getCompletionStateLocked());
    }

    @Test
    public void checkerPausedDuringScheduledRun() {
        Watchdog.Monitor monitor = mock(Watchdog.Monitor.class);
        mChecker.addMonitorLocked(monitor);

        mChecker.scheduleCheckLocked(TIMEOUT_MS);
        mClock.advanceBy(5);
        mChecker.pauseForLocked(10, "pausing");
        verifyNoMoreInteractions(mHandler);
        assertEquals(Watchdog.COMPLETED, mChecker.getCompletionStateLocked());

        // Above the 10s timeout. Watchdog should not be paused anymore.
        mClock.advanceBy(11);
        mChecker.scheduleCheckLocked(TIMEOUT_MS);
        assertEquals(Watchdog.WAITING, mChecker.getCompletionStateLocked());
    }

    @Test
    public void blockedThread() {
        mChecker.scheduleCheckLocked(TIMEOUT_MS);
        assertEquals(mChecker.getCompletionStateLocked(), Watchdog.WAITING);

        mClock.advanceBy(6);
        assertEquals(Watchdog.WAITED_UNTIL_PRE_WATCHDOG, mChecker.getCompletionStateLocked());

        // Above the 10s timeout.
        mClock.advanceBy(6);
        assertEquals(Watchdog.OVERDUE, mChecker.getCompletionStateLocked());
    }

    @Test
    public void checkNothingBlocked() {
        Watchdog.Monitor monitor = mock(Watchdog.Monitor.class);
        mChecker.addMonitorLocked(monitor);

        mChecker.scheduleCheckLocked(TIMEOUT_MS);
        // scheduleCheckLocked calls #postAtFrontOfQueue which will call mChecker.run().
        mChecker.run();
        assertEquals(Watchdog.COMPLETED, mChecker.getCompletionStateLocked());
        verify(monitor).monitor();
    }

    private static class TestClock extends SimpleClock {
        long mNowMillis = 1;

        TestClock() {
            super(ZoneOffset.UTC);
        }

        @Override
        public long millis() {
            return mNowMillis;
        }

        public void advanceBy(long millis) {
            mNowMillis += millis;
        }
    }
}
