/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.vibrator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link VibratorFrameworkStatsLogger}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibratorFrameworkStatsLoggerTest
 */
@Presubmit
public class VibratorFrameworkStatsLoggerTest {

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    private TestLooper mTestLooper;
    private VibratorFrameworkStatsLogger mLogger;

    @Before
    public void setUp() {
        mTestLooper = new TestLooper();
    }

    @Test
    public void writeVibrationReportedAsync_afterMinInterval_writesRightAway() {
        setUpLogger(/* minIntervalMillis= */ 10, /* queueMaxSize= */ 10);

        VibrationStats.StatsInfo firstStats = newEmptyStatsInfo();
        assertFalse(firstStats.isWritten());

        mLogger.writeVibrationReportedAsync(firstStats);
        mTestLooper.dispatchAll();
        assertTrue(firstStats.isWritten());
    }

    @Test
    public void writeVibrationReportedAsync_rightAfterLogging_schedulesToRunAfterRemainingDelay() {
        setUpLogger(/* minIntervalMillis= */ 100, /* queueMaxSize= */ 10);

        VibrationStats.StatsInfo firstStats = newEmptyStatsInfo();
        VibrationStats.StatsInfo secondStats = newEmptyStatsInfo();
        assertFalse(firstStats.isWritten());
        assertFalse(secondStats.isWritten());

        // Write first message at current SystemClock.uptimeMillis
        mLogger.writeVibrationReportedAsync(firstStats);
        mTestLooper.dispatchAll();
        assertTrue(firstStats.isWritten());

        // Second message is not written right away, it needs to wait the configured interval.
        mLogger.writeVibrationReportedAsync(secondStats);
        mTestLooper.dispatchAll();
        assertFalse(secondStats.isWritten());

        // Second message is written after delay passes.
        mTestLooper.moveTimeForward(100);
        mTestLooper.dispatchAll();
        assertTrue(secondStats.isWritten());
    }

    @Test
    public void writeVibrationReportedAsync_tooFast_logsUsingIntervalAndDropsMessagesFromQueue() {
        setUpLogger(/* minIntervalMillis= */ 100, /* queueMaxSize= */ 2);

        VibrationStats.StatsInfo firstStats = newEmptyStatsInfo();
        VibrationStats.StatsInfo secondStats = newEmptyStatsInfo();
        VibrationStats.StatsInfo thirdStats = newEmptyStatsInfo();

        mLogger.writeVibrationReportedAsync(firstStats);
        mLogger.writeVibrationReportedAsync(secondStats);
        mLogger.writeVibrationReportedAsync(thirdStats);

        // Only first message is logged.
        mTestLooper.dispatchAll();
        assertTrue(firstStats.isWritten());
        assertFalse(secondStats.isWritten());
        assertFalse(thirdStats.isWritten());

        // Wait one interval to check only the second one is logged.
        mTestLooper.moveTimeForward(100);
        mTestLooper.dispatchAll();
        assertTrue(secondStats.isWritten());
        assertFalse(thirdStats.isWritten());

        // Wait a long interval to check the third one was dropped and will never be logged.
        mTestLooper.moveTimeForward(1_000);
        mTestLooper.dispatchAll();
        assertFalse(thirdStats.isWritten());
    }

    private void setUpLogger(int minIntervalMillis, int queueMaxSize) {
        mLogger = new VibratorFrameworkStatsLogger(new Handler(mTestLooper.getLooper()),
                minIntervalMillis, queueMaxSize);
    }

    private static VibrationStats.StatsInfo newEmptyStatsInfo() {
        return new VibrationStats.StatsInfo(
                0, 0, 0, Vibration.Status.FINISHED, new VibrationStats(), 0L);
    }
}
