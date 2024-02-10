/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.power.stats;

import android.os.BatteryStats;
import android.os.Parcel;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;

import junit.framework.Assert;

import org.junit.Rule;
import org.junit.Test;

/**
 * Provides test cases for android.os.BatteryStats.
 */
@SmallTest
public class BatteryStatsServTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final String TAG = "BatteryStatsServTest";

    public static class TestServ extends BatteryStatsImpl.Uid.Pkg.Serv {
        TestServ(MockBatteryStatsImpl bsi) {
            super(bsi);
        }

        void populate() {
            mStartTimeMs = 1010;
            mRunningSinceMs = 2021;
            mRunning = true;
            mStarts = 4042;
            mLaunchedTimeMs = 5053;
            mLaunchedSinceMs = 6064;
            mLaunched = true;
            mLaunches = 8085;
        }

        long getStartTime() {
            return mStartTimeMs;
        }

        long getRunningSince() {
            return mRunningSinceMs;
        }

        void setRunning(boolean val) {
            mRunning = val;
        }

        boolean getRunning() {
            return mRunning;
        }

        int getStarts() {
            return mStarts;
        }

        long getLaunchedTime() {
            return mLaunchedTimeMs;
        }

        long getLaunchedSince() {
            return mLaunchedSinceMs;
        }

        void setLaunched(boolean val) {
            mLaunched = val;
        }

        boolean getLaunched() {
            return mLaunched;
        }

        int getLaunches() {
            return mLaunches;
        }
    }

    /**
     * Test that the constructor and detach methods touch the time bast observer list.
     */
    @Test
    public void testConstructAndDetach() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();

        TestServ serv = new TestServ(bsi);
        Assert.assertTrue(bsi.getOnBatteryTimeBase().hasObserver(serv));

        serv.detach();
        Assert.assertFalse(bsi.getOnBatteryTimeBase().hasObserver(serv));
    }

    /**
     * Test parceling and unparceling.
     */
    @Test
    public void testParceling() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ orig = new TestServ(bsi);
        orig.populate();

        Parcel parcel = Parcel.obtain();
        orig.writeToParcelLocked(parcel);

        parcel.setDataPosition(0);

        TestServ serv = new TestServ(bsi);
        serv.readFromParcelLocked(parcel);

        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());

        parcel.recycle();
    }

    /**
     * Test getLaunchTimeToNow()
     */
    @Test
    public void testLaunchTimeToNow() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setLaunched(true);
        Assert.assertEquals(8989, serv.getLaunchTimeToNowLocked(10000));

        serv.populate();
        serv.setLaunched(false);
        Assert.assertEquals(5053, serv.getLaunchTimeToNowLocked(10000));

    }

    /**
     * Test getStartTimeToNow()
     */
    @Test
    public void testStartTimeToNow() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setRunning(true);
        Assert.assertEquals(18989, serv.getStartTimeToNowLocked(20000));

        serv.populate();
        serv.setRunning(false);
        Assert.assertEquals(1010, serv.getStartTimeToNowLocked(20000));
    }

    /**
     * Test startLaunchedLocked while not previously launched
     */
    @Test
    public void testStartLaunchedLockedWhileLaunched() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked(long uptimeMs) {
                return 777777L * 1000; // microseconds
            }
        };

        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setLaunched(true);
        serv.startLaunchedLocked();

        // No changes
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }

    /**
     * Test startLaunchedLocked while previously launched
     */
    @Test
    public void testStartLaunchedLockedWhileNotLaunched() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked(long uptimeMs) {
                return 777777L * 1000; // microseconds
            }
        };

        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setLaunched(false);
        serv.startLaunchedLocked();
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(777777L, serv.getLaunchedSince()); // <-- changed
        Assert.assertTrue(serv.getLaunched()); // <-- changed
        Assert.assertEquals(8086, serv.getLaunches()); // <-- changed
    }

    /**
     * Test stopLaunchedLocked when not previously launched.
     */
    @Test
    public void testStopLaunchedLockedWhileNotLaunched() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked(long uptimeMs) {
                return 777777L * 1000; // microseconds
            }
        };
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setLaunched(false);

        serv.stopLaunchedLocked();

        // No changes
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertFalse(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }

    /**
     * Test stopLaunchedLocked when previously launched, with measurable time between
     * start and stop.
     */
    @Test
    public void testStopLaunchedLockedWhileLaunchedNormal() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked(long uptimeMs) {
                return 777777L * 1000; // microseconds
            }
        };
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setLaunched(true);

        serv.stopLaunchedLocked();

        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(777777L-6064+5053, serv.getLaunchedTime()); // <-- changed
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertFalse(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }

    /**
     * Test stopLaunchedLocked when previously launched, with no measurable time between
     * start and stop.
     */
    @Test
    public void testStopLaunchedLockedWhileLaunchedTooQuick() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setLaunched(true);

        serv.stopLaunchedLocked();

        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertFalse(serv.getLaunched());
        Assert.assertEquals(8085-1, serv.getLaunches()); // <-- changed
    }

    /**
     * Test startRunningLocked while previously running
     */
    @Test
    public void testStartRunningLockedWhileRunning() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked(long uptimeMs) {
                return 777777L * 1000; // microseconds
            }
        };
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setRunning(true);

        serv.startRunningLocked();

        // no change
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }

    /**
     * Test startRunningLocked while not previously launched
     */
    @Test
    public void testStartRunningLockedWhileNotRunning() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked(long uptimeMs) {
                return 777777L * 1000; // microseconds
            }
        };
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setRunning(false);

        serv.startRunningLocked();

        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(777777L, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042+1, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }

    /**
     * Test stopRunningLocked when previously launched, with measurable time between
     * start and stop.
     */
    @Test
    public void testStopRunningLockedWhileRunningNormal() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked(long uptimeMs) {
                return 777777L * 1000; // microseconds
            }
        };
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setRunning(true);

        serv.stopRunningLocked();

        Assert.assertEquals(777777L-2021+1010, serv.getStartTime()); // <-- changed
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertFalse(serv.getRunning()); // <-- changed
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }

    /**
     * Test stopRunningLocked when previously launched, with measurable time between
     * start and stop.
     */
    @Test
    public void testStopRunningLockedWhileRunningTooQuick() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setRunning(true);

        serv.stopRunningLocked();

        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertFalse(serv.getRunning()); // <-- changed
        Assert.assertEquals(4042-1, serv.getStarts()); // <-- changed
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }

    /**
     * Test that getBatteryStats returns the BatteryStatsImpl passed in to the contstructor.
     */
    @Test
    public void testGetBatteryStats() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);

        Assert.assertEquals(bsi, serv.getBatteryStats());
    }

    /**
     * Test getLaunches
     */
    @Test
    public void testGetLaunches() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);
        serv.populate();

        Assert.assertEquals(8085, serv.getLaunches(BatteryStats.STATS_SINCE_CHARGED));

        // No change to fields
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }

    /**
     * Test getStartTime while running
     */
    @Test
    public void testGetStartTimeRunning() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);
        serv.populate();
        serv.setRunning(true);

        final long startTimeToNow = 1010 + 20000 - 2021;

        Assert.assertEquals(startTimeToNow,
                serv.getStartTime(20000, BatteryStats.STATS_SINCE_CHARGED));

        // No change to fields
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }

    /**
     * Test getStartTime while not running
     */
    @Test
    public void testGetStartTimeNotRunning() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);
        serv.populate();
        serv.setRunning(false);

        final long startTimeToNow = 1010;

        Assert.assertEquals(startTimeToNow,
                serv.getStartTime(20000, BatteryStats.STATS_SINCE_CHARGED));

        // No change to fields
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertFalse(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }


    /**
     * Test getStarts
     */
    @Test
    public void testGetStarts() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);
        serv.populate();

        Assert.assertEquals(4042, serv.getStarts(BatteryStats.STATS_SINCE_CHARGED));

        // No change to fields
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
    }
}
