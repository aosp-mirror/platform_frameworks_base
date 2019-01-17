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

package com.android.internal.os;

import android.os.BatteryStats;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import junit.framework.Assert;
import junit.framework.TestCase;

/**
 * Provides test cases for android.os.BatteryStats.
 */
public class BatteryStatsServTest extends TestCase {
    private static final String TAG = "BatteryStatsServTest";

    public static class TestServ extends BatteryStatsImpl.Uid.Pkg.Serv {
        TestServ(MockBatteryStatsImpl bsi) {
            super(bsi);
        }

        void populate() {
            mStartTime = 1010;
            mRunningSince = 2021;
            mRunning = true;
            mStarts = 4042;
            mLaunchedTime = 5053;
            mLaunchedSince = 6064;
            mLaunched = true;
            mLaunches = 8085;
            mLoadedStartTime = 9096;
            mLoadedStarts = 10017;
            mLoadedLaunches = 11118;
            mLastStartTime = 12219;
            mLastStarts = 13310;
            mLastLaunches = 14411;
            mUnpluggedStartTime = 15512;
            mUnpluggedStarts = 16613;
            mUnpluggedLaunches = 17714;
        }

        long getStartTime() {
            return mStartTime;
        }

        long getRunningSince() {
            return mRunningSince;
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
            return mLaunchedTime;
        }

        long getLaunchedSince() {
            return mLaunchedSince;
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

        long getLoadedStartTime() {
            return mLoadedStartTime;
        }

        int getLoadedStarts() {
            return mLoadedStarts;
        }

        int getLoadedLaunches() {
            return mLoadedLaunches;
        }

        long getLastStartTime() {
            return mLastStartTime;
        }

        int getLastStarts() {
            return mLastStarts;
        }

        int getLastLaunches() {
            return mLastLaunches;
        }

        long getUnpluggedStartTime() {
            return mUnpluggedStartTime;
        }

        int getUnpluggedStarts() {
            return mUnpluggedStarts;
        }

        int getUnpluggedLaunches() {
            return mUnpluggedLaunches;
        }
    }

    /**
     * Test that the constructor and detach methods touch the time bast observer list.
     */
    @SmallTest
    public void testConstructAndDetach() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();

        TestServ serv = new TestServ(bsi);
        Assert.assertTrue(bsi.getOnBatteryTimeBase().hasObserver(serv));

        serv.detach();
        Assert.assertFalse(bsi.getOnBatteryTimeBase().hasObserver(serv));
    }

    /**
     * Test OnTimeStarted
     */
    @SmallTest
    public void testOnTimeStarted() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);

        serv.populate();
        serv.setRunning(true);
        serv.onTimeStarted(111111, 20000, 222222);
        Assert.assertEquals(18989, serv.getUnpluggedStartTime());
        Assert.assertEquals(4042, serv.getUnpluggedStarts());
        Assert.assertEquals(8085, serv.getUnpluggedLaunches());

        serv.populate();
        serv.setRunning(false);
        serv.onTimeStarted(111111, 20000, 222222);
        Assert.assertEquals(1010, serv.getUnpluggedStartTime());
        Assert.assertEquals(4042, serv.getUnpluggedStarts());
        Assert.assertEquals(8085, serv.getUnpluggedLaunches());
    }

    /**
     * Test parceling and unparceling.
     */
    @SmallTest
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
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(0, serv.getLastStartTime());
        Assert.assertEquals(0, serv.getLastStarts());
        Assert.assertEquals(0, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test getLaunchTimeToNow()
     */
    @SmallTest
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
    @SmallTest
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
    @SmallTest
    public void testStartLaunchedLockedWhileLaunched() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked() {
                return 777777L;
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
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test startLaunchedLocked while previously launched
     */
    @SmallTest
    public void testStartLaunchedLockedWhileNotLaunched() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked() {
                return 777777L;
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
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test stopLaunchedLocked when not previously launched.
     */
    @SmallTest
    public void testStopLaunchedLockedWhileNotLaunched() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked() {
                return 777777L;
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
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test stopLaunchedLocked when previously launched, with measurable time between
     * start and stop.
     */
    @SmallTest
    public void testStopLaunchedLockedWhileLaunchedNormal() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked() {
                return 777777L;
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
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test stopLaunchedLocked when previously launched, with no measurable time between
     * start and stop.
     */
    @SmallTest
    public void testStopLaunchedLockedWhileLaunchedTooQuick() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked() {
                return 6064L;
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
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertFalse(serv.getLaunched());
        Assert.assertEquals(8085-1, serv.getLaunches()); // <-- changed 
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test startRunningLocked while previously running
     */
    @SmallTest
    public void testStartRunningLockedWhileRunning() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked() {
                return 777777L;
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
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test startRunningLocked while not previously launched
     */
    @SmallTest
    public void testStartRunningLockedWhileNotRunning() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked() {
                return 777777L;
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
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test stopRunningLocked when previously launched, with measurable time between
     * start and stop.
     */
    @SmallTest
    public void testStopRunningLockedWhileRunningNormal() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked() {
                return 777777L;
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
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test stopRunningLocked when previously launched, with measurable time between
     * start and stop.
     */
    @SmallTest
    public void testStopRunningLockedWhileRunningTooQuick() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl() {
            @Override
            public long getBatteryUptimeLocked() {
                return 2021;
            }
        };
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
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test that getBatteryStats returns the BatteryStatsImpl passed in to the contstructor.
     */
    @SmallTest
    public void testGetBatteryStats() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);

        Assert.assertEquals(bsi, serv.getBatteryStats());
    }

    /**
     * Test getLaunches
     */
    @SmallTest
    public void testGetLaunches() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);
        serv.populate();

        Assert.assertEquals(8085, serv.getLaunches(BatteryStats.STATS_SINCE_CHARGED));
        Assert.assertEquals(8085-11118, serv.getLaunches(BatteryStats.STATS_CURRENT));
        Assert.assertEquals(8085-17714, serv.getLaunches(BatteryStats.STATS_SINCE_UNPLUGGED));

        // No change to fields
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test getStartTime while running
     */
    @SmallTest
    public void testGetStartTimeRunning() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);
        serv.populate();
        serv.setRunning(true);

        final long startTimeToNow = 1010 + 20000 - 2021;

        Assert.assertEquals(startTimeToNow,
                serv.getStartTime(20000, BatteryStats.STATS_SINCE_CHARGED));
        Assert.assertEquals(startTimeToNow-9096,
                serv.getStartTime(20000, BatteryStats.STATS_CURRENT));
        Assert.assertEquals(startTimeToNow-15512,
                serv.getStartTime(20000, BatteryStats.STATS_SINCE_UNPLUGGED));

        // No change to fields
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }

    /**
     * Test getStartTime while not running
     */
    @SmallTest
    public void testGetStartTimeNotRunning() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);
        serv.populate();
        serv.setRunning(false);

        final long startTimeToNow = 1010;

        Assert.assertEquals(startTimeToNow,
                serv.getStartTime(20000, BatteryStats.STATS_SINCE_CHARGED));
        Assert.assertEquals(startTimeToNow-9096,
                serv.getStartTime(20000, BatteryStats.STATS_CURRENT));
        Assert.assertEquals(startTimeToNow-15512,
                serv.getStartTime(20000, BatteryStats.STATS_SINCE_UNPLUGGED));

        // No change to fields
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertFalse(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }


    /**
     * Test getStarts
     */
    @SmallTest
    public void testGetStarts() throws Exception  {
        MockBatteryStatsImpl bsi = new MockBatteryStatsImpl();
        TestServ serv = new TestServ(bsi);
        serv.populate();

        Assert.assertEquals(4042, serv.getStarts(BatteryStats.STATS_SINCE_CHARGED));
        Assert.assertEquals(4042-10017, serv.getStarts(BatteryStats.STATS_CURRENT));
        Assert.assertEquals(4042-16613, serv.getStarts(BatteryStats.STATS_SINCE_UNPLUGGED));

        // No change to fields
        Assert.assertEquals(1010, serv.getStartTime());
        Assert.assertEquals(2021, serv.getRunningSince());
        Assert.assertTrue(serv.getRunning());
        Assert.assertEquals(4042, serv.getStarts());
        Assert.assertEquals(5053, serv.getLaunchedTime());
        Assert.assertEquals(6064, serv.getLaunchedSince());
        Assert.assertTrue(serv.getLaunched());
        Assert.assertEquals(8085, serv.getLaunches());
        Assert.assertEquals(9096, serv.getLoadedStartTime());
        Assert.assertEquals(10017, serv.getLoadedStarts());
        Assert.assertEquals(11118, serv.getLoadedLaunches());
        Assert.assertEquals(12219, serv.getLastStartTime());
        Assert.assertEquals(13310, serv.getLastStarts());
        Assert.assertEquals(14411, serv.getLastLaunches());
        Assert.assertEquals(15512, serv.getUnpluggedStartTime());
        Assert.assertEquals(16613, serv.getUnpluggedStarts());
        Assert.assertEquals(17714, serv.getUnpluggedLaunches());
    }
    
}

