/*
 * Copyright 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.display;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.hardware.display.AmbientBrightnessDayStats;
import android.os.SystemClock;
import android.os.UserManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AmbientBrightnessStatsTrackerTest {

    private TestInjector mTestInjector;

    @Before
    public void setUp() {
        mTestInjector = new TestInjector();
    }

    @Test
    public void testBrightnessStatsTrackerOverSingleDay() {
        AmbientBrightnessStatsTracker statsTracker = getTestStatsTracker();
        ArrayList<AmbientBrightnessDayStats> userStats;
        float[] expectedStats;
        // Test case where no user data
        userStats = statsTracker.getUserStats(0);
        assertNull(userStats);
        // Test after adding some user data
        statsTracker.start();
        statsTracker.add(0, 0);
        mTestInjector.incrementTime(1000);
        statsTracker.stop();
        userStats = statsTracker.getUserStats(0);
        assertEquals(1, userStats.size());
        assertEquals(mTestInjector.getLocalDate(), userStats.get(0).getLocalDate());
        expectedStats = getEmptyStatsArray();
        expectedStats[0] = 1;
        assertArrayEquals(expectedStats, userStats.get(0).getStats(), 0);
        // Test after adding some more user data
        statsTracker.start();
        statsTracker.add(0, 0.05f);
        mTestInjector.incrementTime(1000);
        statsTracker.add(0, 0.2f);
        mTestInjector.incrementTime(1500);
        statsTracker.add(0, 50000);
        mTestInjector.incrementTime(2500);
        statsTracker.stop();
        userStats = statsTracker.getUserStats(0);
        assertEquals(1, userStats.size());
        assertEquals(mTestInjector.getLocalDate(), userStats.get(0).getLocalDate());
        expectedStats = getEmptyStatsArray();
        expectedStats[0] = 2;
        expectedStats[1] = 1.5f;
        expectedStats[11] = 2.5f;
        assertArrayEquals(expectedStats, userStats.get(0).getStats(), 0);
    }

    @Test
    public void testBrightnessStatsTrackerOverMultipleDays() {
        AmbientBrightnessStatsTracker statsTracker = getTestStatsTracker();
        ArrayList<AmbientBrightnessDayStats> userStats;
        float[] expectedStats;
        // Add data for day 1
        statsTracker.start();
        statsTracker.add(0, 0.05f);
        mTestInjector.incrementTime(1000);
        statsTracker.add(0, 0.2f);
        mTestInjector.incrementTime(1500);
        statsTracker.add(0, 1);
        mTestInjector.incrementTime(2500);
        statsTracker.stop();
        // Add data for day 2
        mTestInjector.incrementDate(1);
        statsTracker.start();
        statsTracker.add(0, 0);
        mTestInjector.incrementTime(3500);
        statsTracker.add(0, 5);
        mTestInjector.incrementTime(5000);
        statsTracker.stop();
        // Test that the data is tracked as expected
        userStats = statsTracker.getUserStats(0);
        assertEquals(2, userStats.size());
        assertEquals(mTestInjector.getLocalDate().minusDays(1), userStats.get(0).getLocalDate());
        expectedStats = getEmptyStatsArray();
        expectedStats[0] = 1;
        expectedStats[1] = 1.5f;
        expectedStats[3] = 2.5f;
        assertArrayEquals(expectedStats, userStats.get(0).getStats(), 0);
        assertEquals(mTestInjector.getLocalDate(), userStats.get(1).getLocalDate());
        expectedStats = getEmptyStatsArray();
        expectedStats[0] = 3.5f;
        expectedStats[4] = 5;
        assertArrayEquals(expectedStats, userStats.get(1).getStats(), 0);
    }

    @Test
    public void testBrightnessStatsTrackerOverMultipleUsers() {
        AmbientBrightnessStatsTracker statsTracker = getTestStatsTracker();
        ArrayList<AmbientBrightnessDayStats> userStats;
        float[] expectedStats;
        // Add data for user 1
        statsTracker.start();
        statsTracker.add(0, 0.05f);
        mTestInjector.incrementTime(1000);
        statsTracker.add(0, 0.2f);
        mTestInjector.incrementTime(1500);
        statsTracker.add(0, 1);
        mTestInjector.incrementTime(2500);
        statsTracker.stop();
        // Add data for user 2
        mTestInjector.incrementDate(1);
        statsTracker.start();
        statsTracker.add(1, 0);
        mTestInjector.incrementTime(3500);
        statsTracker.add(1, 5);
        mTestInjector.incrementTime(5000);
        statsTracker.stop();
        // Test that the data is tracked as expected
        userStats = statsTracker.getUserStats(0);
        assertEquals(1, userStats.size());
        assertEquals(mTestInjector.getLocalDate().minusDays(1), userStats.get(0).getLocalDate());
        expectedStats = getEmptyStatsArray();
        expectedStats[0] = 1;
        expectedStats[1] = 1.5f;
        expectedStats[3] = 2.5f;
        assertArrayEquals(expectedStats, userStats.get(0).getStats(), 0);
        userStats = statsTracker.getUserStats(1);
        assertEquals(1, userStats.size());
        assertEquals(mTestInjector.getLocalDate(), userStats.get(0).getLocalDate());
        expectedStats = getEmptyStatsArray();
        expectedStats[0] = 3.5f;
        expectedStats[4] = 5;
        assertArrayEquals(expectedStats, userStats.get(0).getStats(), 0);
    }

    @Test
    public void testBrightnessStatsTrackerOverMaxDays() {
        AmbientBrightnessStatsTracker statsTracker = getTestStatsTracker();
        ArrayList<AmbientBrightnessDayStats> userStats;
        // Add 10 extra days of data over the buffer limit
        for (int i = 0; i < AmbientBrightnessStatsTracker.MAX_DAYS_TO_TRACK + 10; i++) {
            mTestInjector.incrementDate(1);
            statsTracker.start();
            statsTracker.add(0, 10);
            mTestInjector.incrementTime(1000);
            statsTracker.add(0, 20);
            mTestInjector.incrementTime(1000);
            statsTracker.stop();
        }
        // Assert that we are only tracking last "MAX_DAYS_TO_TRACK"
        userStats = statsTracker.getUserStats(0);
        assertEquals(AmbientBrightnessStatsTracker.MAX_DAYS_TO_TRACK, userStats.size());
        LocalDate runningDate = mTestInjector.getLocalDate();
        for (int i = AmbientBrightnessStatsTracker.MAX_DAYS_TO_TRACK - 1; i >= 0; i--) {
            assertEquals(runningDate, userStats.get(i).getLocalDate());
            runningDate = runningDate.minusDays(1);
        }
    }

    @Test
    public void testReadAmbientBrightnessStats() throws IOException {
        AmbientBrightnessStatsTracker statsTracker = getTestStatsTracker();
        LocalDate date = mTestInjector.getLocalDate();
        ArrayList<AmbientBrightnessDayStats> userStats;
        String statsFile =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\r\n"
                        + "<ambient-brightness-stats>\r\n"
                        // Old stats that shouldn't be read
                        + "<ambient-brightness-day-stats user=\"10\" local-date=\""
                        + date.minusDays(AmbientBrightnessStatsTracker.MAX_DAYS_TO_TRACK)
                        + "\" bucket-boundaries=\"0.0,1.0,3.0,10.0,30.0,100.0,300.0,1000.0,"
                        + "3000.0,10000.0\" bucket-stats=\"1.088,0.0,0.726,0.0,25.868,0.0,0.0,"
                        + "0.0,0.0,0.0\" />\r\n"
                        // Valid stats that should get read
                        + "<ambient-brightness-day-stats user=\"10\" local-date=\""
                        + date.minusDays(1)
                        + "\" bucket-boundaries=\"0.0,1.0,3.0,10.0,30.0,100.0,300.0,1000.0,"
                        + "3000.0,10000.0\" bucket-stats=\"1.088,0.0,0.726,0.0,25.868,0.0,0.0,"
                        + "0.0,0.0,0.0\" />\r\n"
                        // Valid stats that should get read
                        + "<ambient-brightness-day-stats user=\"10\" local-date=\"" + date
                        + "\" bucket-boundaries=\"0.0,1.0,3.0,10.0,30.0,100.0,300.0,1000.0,"
                        + "3000.0,10000.0\" bucket-stats=\"0.0,0.0,0.0,0.0,4.482,0.0,0.0,0.0,0.0,"
                        + "0.0\" />\r\n"
                        + "</ambient-brightness-stats>";
        statsTracker.readStats(getInputStream(statsFile));
        userStats = statsTracker.getUserStats(0);
        assertEquals(2, userStats.size());
        assertEquals(new AmbientBrightnessDayStats(date.minusDays(1),
                new float[]{0, 1, 3, 10, 30, 100, 300, 1000, 3000, 10000},
                new float[]{1.088f, 0, 0.726f, 0, 25.868f, 0, 0, 0, 0, 0}), userStats.get(0));
        assertEquals(new AmbientBrightnessDayStats(date,
                new float[]{0, 1, 3, 10, 30, 100, 300, 1000, 3000, 10000},
                new float[]{0, 0, 0, 0, 4.482f, 0, 0, 0, 0, 0}), userStats.get(1));
    }

    @Test
    public void testFailedReadAmbientBrightnessStatsWithException() {
        AmbientBrightnessStatsTracker statsTracker = getTestStatsTracker();
        LocalDate date = mTestInjector.getLocalDate();
        String statsFile;
        // Test with parse error
        statsFile =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\r\n"
                        + "<ambient-brightness-stats>\r\n"
                        // Incorrect since bucket boundaries not parsable
                        + "<ambient-brightness-day-stats user=\"10\" local-date=\"" + date
                        + "\" bucket-boundaries=\"asdf,1.0,3.0,10.0,30.0,100.0,300.0,1000.0,"
                        + "3000.0,10000.0\" bucket-stats=\"1.088,0.0,0.726,0.0,25.868,0.0,0.0,"
                        + "0.0,0.0,0.0\" />\r\n"
                        + "</ambient-brightness-stats>";
        try {
            statsTracker.readStats(getInputStream(statsFile));
        } catch (IOException e) {
            // Expected
        }
        assertNull(statsTracker.getUserStats(0));
        // Test with incorrect data (bucket boundaries length not equal to stats length)
        statsFile =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\r\n"
                        + "<ambient-brightness-stats>\r\n"
                        // Correct data
                        + "<ambient-brightness-day-stats user=\"10\" local-date=\""
                        + date.minusDays(1)
                        + "\" bucket-boundaries=\"0.0,1.0,3.0,10.0,30.0,100.0,300.0,1000.0,"
                        + "3000.0,10000.0\" bucket-stats=\"0.0,0.0,0.0,0.0,4.482,0.0,0.0,0.0,0.0,"
                        + "0.0\" />\r\n"
                        // Incorrect data
                        + "<ambient-brightness-day-stats user=\"10\" local-date=\"" + date
                        + "\" bucket-boundaries=\"0.0,1.0,3.0,10.0,30.0,100.0,1000.0,"
                        + "3000.0,10000.0\" bucket-stats=\"1.088,0.0,0.726,0.0,25.868,0.0,0.0,"
                        + "0.0,0.0,0.0\" />\r\n"
                        + "</ambient-brightness-stats>";
        try {
            statsTracker.readStats(getInputStream(statsFile));
        } catch (Exception e) {
            // Expected
        }
        assertNull(statsTracker.getUserStats(0));
        // Test with missing attribute
        statsFile =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\r\n"
                        + "<ambient-brightness-stats>\r\n"
                        + "<ambientBrightnessDayStats user=\"10\" local-date=\"" + date
                        + "\" bucket-boundaries=\"0.0,1.0,3.0,10.0,30.0,100.0,300.0,1000.0,"
                        + "3000.0,10000.0\" />\r\n"
                        + "</ambient-brightness-stats>";
        try {
            statsTracker.readStats(getInputStream(statsFile));
        } catch (Exception e) {
            // Expected
        }
        assertNull(statsTracker.getUserStats(0));
    }

    @Test
    public void testWriteThenReadAmbientBrightnessStats() throws IOException {
        AmbientBrightnessStatsTracker statsTracker = getTestStatsTracker();
        ArrayList<AmbientBrightnessDayStats> userStats;
        float[] expectedStats;
        // Generate some placeholder data
        // Data: very old which should not be read
        statsTracker.start();
        statsTracker.add(0, 0.05f);
        mTestInjector.incrementTime(1000);
        statsTracker.add(0, 0.2f);
        mTestInjector.incrementTime(1500);
        statsTracker.add(0, 1);
        mTestInjector.incrementTime(2500);
        statsTracker.stop();
        // Data: day 1 user 1
        mTestInjector.incrementDate(AmbientBrightnessStatsTracker.MAX_DAYS_TO_TRACK - 1);
        statsTracker.start();
        statsTracker.add(0, 0.05f);
        mTestInjector.incrementTime(1000);
        statsTracker.add(0, 0.2f);
        mTestInjector.incrementTime(1500);
        statsTracker.add(0, 1);
        mTestInjector.incrementTime(2500);
        statsTracker.stop();
        // Data: day 1 user 2
        statsTracker.start();
        statsTracker.add(1, 0);
        mTestInjector.incrementTime(3500);
        statsTracker.add(1, 5);
        mTestInjector.incrementTime(5000);
        statsTracker.stop();
        // Data: day 2 user 1
        mTestInjector.incrementDate(1);
        statsTracker.start();
        statsTracker.add(0, 0);
        mTestInjector.incrementTime(3500);
        statsTracker.add(0, 50000);
        mTestInjector.incrementTime(5000);
        statsTracker.stop();
        // Write them
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        statsTracker.writeStats(baos);
        baos.flush();
        // Read them back and assert that it's the same
        ByteArrayInputStream input = new ByteArrayInputStream(baos.toByteArray());
        AmbientBrightnessStatsTracker newStatsTracker = getTestStatsTracker();
        newStatsTracker.readStats(input);
        userStats = newStatsTracker.getUserStats(0);
        assertEquals(2, userStats.size());
        // Check day 1 user 1
        assertEquals(mTestInjector.getLocalDate().minusDays(1), userStats.get(0).getLocalDate());
        expectedStats = getEmptyStatsArray();
        expectedStats[0] = 1;
        expectedStats[1] = 1.5f;
        expectedStats[3] = 2.5f;
        assertArrayEquals(expectedStats, userStats.get(0).getStats(), 0);
        // Check day 2 user 1
        assertEquals(mTestInjector.getLocalDate(), userStats.get(1).getLocalDate());
        expectedStats = getEmptyStatsArray();
        expectedStats[0] = 3.5f;
        expectedStats[11] = 5;
        assertArrayEquals(expectedStats, userStats.get(1).getStats(), 0);
        userStats = newStatsTracker.getUserStats(1);
        assertEquals(1, userStats.size());
        // Check day 1 user 2
        assertEquals(mTestInjector.getLocalDate().minusDays(1), userStats.get(0).getLocalDate());
        expectedStats = getEmptyStatsArray();
        expectedStats[0] = 3.5f;
        expectedStats[4] = 5;
        assertArrayEquals(expectedStats, userStats.get(0).getStats(), 0);
    }

    @Test
    public void testTimer() {
        AmbientBrightnessStatsTracker.Timer timer = new AmbientBrightnessStatsTracker.Timer(
                () -> mTestInjector.elapsedRealtimeMillis());
        assertEquals(0, timer.totalDurationSec(), 0);
        mTestInjector.incrementTime(1000);
        assertEquals(0, timer.totalDurationSec(), 0);
        assertFalse(timer.isRunning());
        // Start timer
        timer.start();
        assertTrue(timer.isRunning());
        assertEquals(0, timer.totalDurationSec(), 0);
        mTestInjector.incrementTime(1000);
        assertTrue(timer.isRunning());
        assertEquals(1, timer.totalDurationSec(), 0);
        // Reset timer
        timer.reset();
        assertEquals(0, timer.totalDurationSec(), 0);
        assertFalse(timer.isRunning());
        // Start again
        timer.start();
        assertTrue(timer.isRunning());
        assertEquals(0, timer.totalDurationSec(), 0);
        mTestInjector.incrementTime(2000);
        assertTrue(timer.isRunning());
        assertEquals(2, timer.totalDurationSec(), 0);
        // Reset again
        timer.reset();
        assertEquals(0, timer.totalDurationSec(), 0);
        assertFalse(timer.isRunning());
    }

    private class TestInjector extends AmbientBrightnessStatsTracker.Injector {

        private long mElapsedRealtimeMillis = SystemClock.elapsedRealtime();
        private LocalDate mLocalDate = LocalDate.now();

        public void incrementTime(long timeMillis) {
            mElapsedRealtimeMillis += timeMillis;
        }

        public void incrementDate(int numDays) {
            mLocalDate = mLocalDate.plusDays(numDays);
        }

        @Override
        public long elapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        @Override
        public int getUserSerialNumber(UserManager userManager, int userId) {
            return userId + 10;
        }

        @Override
        public int getUserId(UserManager userManager, int userSerialNumber) {
            return userSerialNumber - 10;
        }

        @Override
        public LocalDate getLocalDate() {
            return mLocalDate;
        }
    }

    private AmbientBrightnessStatsTracker getTestStatsTracker() {
        return new AmbientBrightnessStatsTracker(
                InstrumentationRegistry.getContext().getSystemService(UserManager.class),
                mTestInjector);
    }

    private float[] getEmptyStatsArray() {
        return new float[AmbientBrightnessStatsTracker.BUCKET_BOUNDARIES_FOR_NEW_STATS.length];
    }

    private InputStream getInputStream(String data) {
        return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
    }
}
