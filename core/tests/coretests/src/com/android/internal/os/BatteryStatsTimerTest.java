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
import android.support.test.filters.SmallTest;
import android.util.StringBuilderPrinter;

import junit.framework.Assert;
import junit.framework.TestCase;

import com.android.internal.os.BatteryStatsImpl.Clocks;
import com.android.internal.os.BatteryStatsImpl.TimeBase;
import com.android.internal.os.BatteryStatsImpl.Timer;

/**
 * Provides test cases for android.os.BatteryStats.
 */
public class BatteryStatsTimerTest extends TestCase {
    private static final String TAG = "BatteryStatsTest";

    class TestTimer extends Timer {
        long nextComputeRunTime;
        long lastComputeRunTimeRealtime;

        int nextComputeCurrentCount;

        TestTimer(Clocks clocks, int type, TimeBase timeBase, Parcel in) {
            super(clocks, type, timeBase, in);
        }

        TestTimer(Clocks clocks, int type, TimeBase timeBase) {
            super(clocks, type, timeBase);
        }

        protected long computeRunTimeLocked(long curBatteryRealtime) {
            lastComputeRunTimeRealtime = curBatteryRealtime;
            return nextComputeRunTime;
        }

        protected int computeCurrentCountLocked() {
            return nextComputeCurrentCount;
        }

        public int getCount() {
            return mCount;
        }

        public void setCount(int val) {
            mCount = val;
        }

        public int getLoadedCount() {
            return mLoadedCount;
        }

        public void setLoadedCount(int val) {
            mLoadedCount = val;
        }

        public int getLastCount() {
            return mLastCount;
        }

        public void setLastCount(int val) {
            mLastCount = val;
        }

        public int getUnpluggedCount() {
            return mUnpluggedCount;
        }

        public void setUnpluggedCount(int val) {
            mUnpluggedCount = val;
        }

        public long getTotalTime() {
            return mTotalTime;
        }

        public void setTotalTime(long val) {
            mTotalTime = val;
        }

        public long getLoadedTime() {
            return mLoadedTime;
        }

        public void setLoadedTime(long val) {
            mLoadedTime = val;
        }

        public long getLastTime() {
            return mLastTime;
        }

        public void setLastTime(long val) {
            mLastTime = val;
        }

        public long getUnpluggedTime() {
            return mUnpluggedTime;
        }

        public void setUnpluggedTime(long val) {
            mUnpluggedTime = val;
        }

        public long getTimeBeforeMark() {
            return mTimeBeforeMark;
        }

        public void setTimeBeforeMark(long val) {
            mTimeBeforeMark = val;
        }
    }

    /**
     * Tests that the flow through TimeBase.setRunning propagates through
     * to the timer.
     */
    @SmallTest
    public void testRunning() throws Exception {
        TimeBase timeBase = new TimeBase();
        MockClocks clocks = new MockClocks();

        TestTimer timer = new TestTimer(clocks, 0, timeBase);
        timer.nextComputeCurrentCount = 3000;

        // Test that starting the timer counts the unplugged time and counters
        timer.nextComputeRunTime = 4;
        timer.onTimeStarted(10, 20, 50);
        Assert.assertEquals(50, timer.lastComputeRunTimeRealtime);
        Assert.assertEquals(4, timer.getUnpluggedTime());
        Assert.assertEquals(3000, timer.getUnpluggedCount());

        // Test that stopping the timer updates mTotalTime and mCount
        timer.nextComputeRunTime = 17;
        timer.onTimeStopped(100, 130, 170);
        Assert.assertEquals(17, timer.getTotalTime());
        Assert.assertEquals(3000, timer.getCount());
    }

    /**
     * Tests that the parcel can be parceled and unparceled without losing anything.
     */
    @SmallTest
    public void testParceling() throws Exception {
        TimeBase timeBase = new TimeBase();
        MockClocks clocks = new MockClocks();

        // Test write then read
        TestTimer timer1 = new TestTimer(clocks, 0, timeBase);
        timer1.setCount(1);
        timer1.setLoadedCount(3);
        timer1.setLastCount(4);
        timer1.setUnpluggedCount(5);
        timer1.setTotalTime(9223372036854775807L);
        timer1.setLoadedTime(9223372036854775806L);
        timer1.setLastTime(9223372036854775805L);
        timer1.setUnpluggedTime(9223372036854775804L);
        timer1.setTimeBeforeMark(9223372036854775803L);
        timer1.nextComputeRunTime = 201;
        timer1.nextComputeCurrentCount = 2;

        Parcel parcel = Parcel.obtain();
        Timer.writeTimerToParcel(parcel, timer1, 77);

        parcel.setDataPosition(0);
        Assert.assertTrue("parcel null object", parcel.readInt() != 0);

        TestTimer timer2 = new TestTimer(clocks, 0, timeBase, parcel);
        Assert.assertEquals(2, timer2.getCount()); // from computeTotalCountLocked()
        Assert.assertEquals(3, timer2.getLoadedCount());
        Assert.assertEquals(0, timer2.getLastCount()); // NOT saved
        Assert.assertEquals(5, timer2.getUnpluggedCount());
        Assert.assertEquals(201, timer2.getTotalTime()); // from computeRunTimeLocked()
        Assert.assertEquals(9223372036854775806L, timer2.getLoadedTime());
        Assert.assertEquals(0, timer2.getLastTime()); // NOT saved
        Assert.assertEquals(9223372036854775804L, timer2.getUnpluggedTime());
        Assert.assertEquals(9223372036854775803L, timer2.getTimeBeforeMark());

        parcel.recycle();
    }

    /**
     * Tests that the parcel can be parceled and unparceled without losing anything.
     */
    @SmallTest
    public void testParcelingNull() throws Exception {
        // Test writing null
        Parcel parcel = Parcel.obtain();
        Timer.writeTimerToParcel(parcel, null, 88);

        parcel.setDataPosition(0);
        Assert.assertEquals(0, parcel.readInt());

        parcel.recycle();
    }

    /**
     * Tests that reset() clears the correct times.
     */
    @SmallTest
    public void testResetNoDetach() throws Exception {
        TimeBase timeBase = new TimeBase();
        MockClocks clocks = new MockClocks();

        TestTimer timer = new TestTimer(clocks, 0, timeBase);
        timer.setCount(1);
        timer.setLoadedCount(2);
        timer.setLastCount(3);
        timer.setUnpluggedCount(4);
        timer.setTotalTime(9223372036854775807L);
        timer.setLoadedTime(9223372036854775806L);
        timer.setLastTime(9223372036854775805L);
        timer.setUnpluggedTime(9223372036854775804L);
        timer.setTimeBeforeMark(9223372036854775803L);

        timer.reset(false);

        Assert.assertEquals(0, timer.getCount());
        Assert.assertEquals(0, timer.getLoadedCount());
        Assert.assertEquals(0, timer.getLastCount());
        Assert.assertEquals(4, timer.getUnpluggedCount());
        Assert.assertEquals(0, timer.getTotalTime());
        Assert.assertEquals(0, timer.getLoadedTime());
        Assert.assertEquals(0, timer.getLastTime());
        Assert.assertEquals(9223372036854775804L, timer.getUnpluggedTime());
        Assert.assertEquals(0, timer.getTimeBeforeMark());

        // reset(false) shouldn't remove it from the list
        Assert.assertEquals(true, timeBase.hasObserver(timer));
    }

    /**
     * Tests that reset() clears the correct times.
     */
    @SmallTest
    public void testResetDetach() throws Exception {
        TimeBase timeBase = new TimeBase();
        MockClocks clocks = new MockClocks();

        TestTimer timer = new TestTimer(clocks, 0, timeBase);
        timer.setCount(1);
        timer.setLoadedCount(2);
        timer.setLastCount(3);
        timer.setUnpluggedCount(4);
        timer.setTotalTime(9223372036854775807L);
        timer.setLoadedTime(9223372036854775806L);
        timer.setLastTime(9223372036854775805L);
        timer.setUnpluggedTime(9223372036854775804L);
        timer.setTimeBeforeMark(9223372036854775803L);

        timer.reset(true);

        Assert.assertEquals(0, timer.getCount());
        Assert.assertEquals(0, timer.getLoadedCount());
        Assert.assertEquals(0, timer.getLastCount());
        Assert.assertEquals(4, timer.getUnpluggedCount());
        Assert.assertEquals(0, timer.getTotalTime());
        Assert.assertEquals(0, timer.getLoadedTime());
        Assert.assertEquals(0, timer.getLastTime());
        Assert.assertEquals(9223372036854775804L, timer.getUnpluggedTime());
        Assert.assertEquals(0, timer.getTimeBeforeMark());

        // reset(true) should remove it from the list
        Assert.assertEquals(false, timeBase.hasObserver(timer));
    }

    /**
     * Tests reading and writing the summary to a parcel
     */
    @SmallTest
    public void testSummaryParceling() throws Exception {
        TimeBase timeBase = new TimeBase();
        timeBase.setRunning(true, 10, 20);
        timeBase.setRunning(false, 45, 60);
        Assert.assertEquals(40, timeBase.getRealtime(200));
        // the past uptime is 35 and the past runtime is 40

        MockClocks clocks = new MockClocks();

        TestTimer timer1 = new TestTimer(clocks, 0, timeBase);
        timer1.setCount(1);
        timer1.setLoadedCount(2);
        timer1.setLastCount(3);
        timer1.setUnpluggedCount(4);
        timer1.setTotalTime(9223372036854775807L);
        timer1.setLoadedTime(9223372036854775806L);
        timer1.setLastTime(9223372036854775805L);
        timer1.setUnpluggedTime(9223372036854775804L);
        timer1.setTimeBeforeMark(9223372036854775803L);

        Parcel parcel = Parcel.obtain();
        timer1.nextComputeRunTime = 9223372036854775800L;
        timer1.nextComputeCurrentCount = 1;
        timer1.writeSummaryFromParcelLocked(parcel, 201);
        Assert.assertEquals(40, timer1.lastComputeRunTimeRealtime);

        TestTimer timer2 = new TestTimer(clocks, 0, timeBase);

        // Make sure that all the values get touched
        timer2.setCount(666);
        timer2.setLoadedCount(666);
        timer2.setLastCount(666);
        timer2.setUnpluggedCount(666);
        timer2.setTotalTime(666);
        timer2.setLoadedTime(666);
        timer2.setLastTime(666);
        timer2.setUnpluggedTime(666);
        timer2.setTimeBeforeMark(666);

        parcel.setDataPosition(0);

        parcel.setDataPosition(0);
        timer2.readSummaryFromParcelLocked(parcel);

        Assert.assertEquals(1, timer2.getCount());
        Assert.assertEquals(1, timer2.getLoadedCount());
        Assert.assertEquals(0, timer2.getLastCount());
        Assert.assertEquals(1, timer2.getUnpluggedCount());
        Assert.assertEquals(9223372036854775800L, timer2.getTotalTime());
        Assert.assertEquals(9223372036854775800L, timer2.getLoadedTime());
        Assert.assertEquals(0, timer2.getLastTime());
        Assert.assertEquals(9223372036854775800L, timer2.getUnpluggedTime());
        Assert.assertEquals(9223372036854775800L, timer2.getTimeBeforeMark());

        parcel.recycle();
    }

    /**
     * Tests getTotalTimeLocked
     */
    @SmallTest
    public void testGetTotalTimeLocked() throws Exception {
        TimeBase timeBase = new TimeBase();
        timeBase.setRunning(true, 10, 20);
        timeBase.setRunning(false, 45, 60);
        Assert.assertEquals(40, timeBase.getRealtime(200));

        MockClocks clocks = new MockClocks();

        TestTimer timer = new TestTimer(clocks, 0, timeBase);
        timer.setCount(1);
        timer.setLoadedCount(2);
        timer.setLastCount(3);
        timer.setUnpluggedCount(4);
        timer.setTotalTime(100);
        timer.setLoadedTime(200);
        timer.setLastTime(300);
        timer.setUnpluggedTime(400);
        timer.setTimeBeforeMark(500);

        timer.nextComputeRunTime = 10000;

        // Timer.getTotalTimeLocked(STATS_SINCE_CHARGED)
        timer.lastComputeRunTimeRealtime = -1;
        Assert.assertEquals(10000,
                timer.getTotalTimeLocked(66, BatteryStats.STATS_SINCE_CHARGED));
        Assert.assertEquals(40, timer.lastComputeRunTimeRealtime);

        // Timer.getTotalTimeLocked(STATS_CURRENT)
        timer.lastComputeRunTimeRealtime = -1;
        Assert.assertEquals(9800, timer.getTotalTimeLocked(66, BatteryStats.STATS_CURRENT));
        Assert.assertEquals(40, timer.lastComputeRunTimeRealtime);

        // Timer.getTotalTimeLocked(STATS_SINCE_UNPLUGGED)
        timer.lastComputeRunTimeRealtime = -1;
        Assert.assertEquals(9600, timer.getTotalTimeLocked(66, BatteryStats.STATS_SINCE_UNPLUGGED));
        Assert.assertEquals(40, timer.lastComputeRunTimeRealtime);
    }

    /**
     * Tests getCountLocked
     */
    @SmallTest
    public void testGetCountLocked() throws Exception {
        TimeBase timeBase = new TimeBase();
        timeBase.setRunning(true, 10, 20);
        timeBase.setRunning(false, 45, 60);
        Assert.assertEquals(40, timeBase.getRealtime(200));

        MockClocks clocks = new MockClocks();

        TestTimer timer = new TestTimer(clocks, 0, timeBase);
        timer.setCount(1);
        timer.setLoadedCount(2);
        timer.setLastCount(3);
        timer.setUnpluggedCount(4);
        timer.setTotalTime(100);
        timer.setLoadedTime(200);
        timer.setLastTime(300);
        timer.setUnpluggedTime(400);
        timer.setTimeBeforeMark(500);

        // Timer.getCountLocked(STATS_SINCE_CHARGED)
        timer.nextComputeCurrentCount = 10000;
        Assert.assertEquals(10000, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Timer.getCountLocked(STATS_CURRENT)
        timer.nextComputeCurrentCount = 10000;
        Assert.assertEquals(9998, timer.getCountLocked(BatteryStats.STATS_CURRENT));

        // Timer.getCountLocked(STATS_SINCE_UNPLUGGED)
        timer.nextComputeCurrentCount = 10000;
        Assert.assertEquals(9996, timer.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));
    }

    /**
     * Tests getTimeSinceMarkLocked
     */
    @SmallTest
    public void testGetTimeSinceMarked() throws Exception {
        TimeBase timeBase = new TimeBase();
        timeBase.setRunning(true, 10, 20);
        timeBase.setRunning(false, 45, 60);
        Assert.assertEquals(40, timeBase.getRealtime(200));

        MockClocks clocks = new MockClocks();

        TestTimer timer = new TestTimer(clocks, 0, timeBase);
        timer.setCount(1);
        timer.setLoadedCount(2);
        timer.setLastCount(3);
        timer.setUnpluggedCount(4);
        timer.setTotalTime(100);
        timer.setLoadedTime(200);
        timer.setLastTime(300);
        timer.setUnpluggedTime(400);
        timer.setTimeBeforeMark(500);

        timer.nextComputeRunTime = 10000;
        Assert.assertEquals(9500, timer.getTimeSinceMarkLocked(666));
    }

    /**
     * Tests logState
     */
    @SmallTest
    public void testLogState() throws Exception {
        TimeBase timeBase = new TimeBase();
        MockClocks clocks = new MockClocks();

        TestTimer timer = new TestTimer(clocks, 0, timeBase);
        timer.setTotalTime(100);
        timer.setLoadedTime(200);
        timer.setLastTime(300);
        timer.setUnpluggedTime(400);
        timer.setTimeBeforeMark(500);
        timer.setCount(1);
        timer.setLoadedCount(2);
        timer.setLastCount(3);
        timer.setUnpluggedCount(4);
        timer.setTotalTime(9223372036854775807L);
        timer.setLoadedTime(9223372036854775806L);
        timer.setLastTime(9223372036854775805L);
        timer.setUnpluggedTime(9223372036854775804L);
        timer.setTimeBeforeMark(9223372036854775803L);

        StringBuilder sb = new StringBuilder();
        StringBuilderPrinter pw = new StringBuilderPrinter(sb);

        timer.logState(pw, "  ");

        Assert.assertEquals(
                      "  mCount=1 mLoadedCount=2 mLastCount=3 mUnpluggedCount=4\n"
                    + "  mTotalTime=9223372036854775807 mLoadedTime=9223372036854775806\n"
                    + "  mLastTime=9223372036854775805 mUnpluggedTime=9223372036854775804\n",
                sb.toString());
    }
}

