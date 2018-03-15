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
import android.util.Log;

import androidx.test.filters.SmallTest;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Provides test cases for android.os.BatteryStats.
 */
public class BatteryStatsTimeBaseTest extends TestCase {
    private static final String TAG = "BatteryStatsTimeBaseTest";

    static class TestTimeBase extends BatteryStatsImpl.TimeBase {

        public void populate(long uptime, long realtime, boolean running, long pastUptime,
                long uptimeStart, long pastRealtime, long realtimeStart,
                long unpluggedUptime, long unpluggedRealtime) {
            mUptime = uptime;
            mRealtime = realtime;
            mRunning = running;
            mPastUptime = pastUptime;
            mUptimeStart = uptimeStart;
            mPastRealtime = pastRealtime;
            mRealtimeStart = realtimeStart;
            mUnpluggedUptime = unpluggedUptime;
            mUnpluggedRealtime = unpluggedRealtime;
        }

        public void verify(long uptime, long realtime, boolean running, long pastUptime,
                long uptimeStart, long pastRealtime, long realtimeStart,
                long unpluggedUptime, long unpluggedRealtime) {
            Assert.assertEquals(uptime, mUptime);
            Assert.assertEquals(realtime, mRealtime);
            Assert.assertEquals(running, mRunning);
            Assert.assertEquals(pastUptime, mPastUptime);
            Assert.assertEquals(uptimeStart, mUptimeStart);
            Assert.assertEquals(pastRealtime, mPastRealtime);
            Assert.assertEquals(realtimeStart, mRealtimeStart);
            Assert.assertEquals(unpluggedUptime, mUnpluggedUptime);
            Assert.assertEquals(unpluggedRealtime, mUnpluggedRealtime);
        }
    }

    /**
     * Test the observers and the setRunning call.
     */
    @SmallTest
    public void testRunning() throws Exception {
        TestTimeBase tb = new TestTimeBase();

        // Toggle running once, to accumulate past uptime and past realtime
        // so the test values aren't 0.
        tb.setRunning(true, 100, 10000);
        tb.setRunning(false, 200, 11000);
        Assert.assertEquals(100, tb.getUptimeStart());
        Assert.assertEquals(10000, tb.getRealtimeStart());

        // Create some observers
        BatteryStatsImpl.TimeBaseObs observer1 = Mockito.mock(BatteryStatsImpl.TimeBaseObs.class);
        BatteryStatsImpl.TimeBaseObs observer2 = Mockito.mock(BatteryStatsImpl.TimeBaseObs.class);
        BatteryStatsImpl.TimeBaseObs observer3 = Mockito.mock(BatteryStatsImpl.TimeBaseObs.class);

        // Add them
        tb.add(observer1);
        tb.add(observer2);
        tb.add(observer3);
        Assert.assertTrue(tb.hasObserver(observer1));
        Assert.assertTrue(tb.hasObserver(observer2));
        Assert.assertTrue(tb.hasObserver(observer3));

        // Remove one
        tb.remove(observer3);
        Assert.assertTrue(tb.hasObserver(observer1));
        Assert.assertTrue(tb.hasObserver(observer2));
        Assert.assertFalse(tb.hasObserver(observer3));

        // Start running, make sure we get a started call on the two active observers
        // and not the third.
        tb.setRunning(true, 250, 14000);

        Assert.assertTrue(tb.isRunning());

        if (false) {
            Log.d(TAG, "mUptimeStart=" + tb.getUptimeStart()
                    + " mRealtimeStart=" + tb.getRealtimeStart()
                    + " mUptime=" + tb.getUptime(250)
                    + " mRealtime=" + tb.getRealtime(14000)
                    + " isRunning=" + tb.isRunning());
        }

        Assert.assertEquals(250, tb.getUptimeStart());
        Assert.assertEquals(14000, tb.getRealtimeStart());
        Assert.assertEquals(100, tb.getUptime(250));
        Assert.assertEquals(1000, tb.getRealtime(14000));

        Mockito.verify(observer1).onTimeStarted(14000, 100, 1000);
        Mockito.verify(observer1, Mockito.never()).onTimeStopped(-1, -1, -1);
        Mockito.verifyNoMoreInteractions(observer1);
        Mockito.verify(observer2).onTimeStarted(14000, 100, 1000);
        Mockito.verify(observer2, Mockito.never()).onTimeStopped(-1, -1, -1);
        Mockito.verifyNoMoreInteractions(observer2);

        Mockito.reset(observer1);
        Mockito.reset(observer2);
        Mockito.reset(observer3);

        // Advance the "timer" and make sure the getters account for the current time passed in
        Assert.assertEquals(400, tb.getUptime(550));
        Assert.assertEquals(1555, tb.getRealtime(14555));

        // Stop running, make sure we get a stopped call on the two active observers
        // and not the third.
        tb.setRunning(false, 402, 14002);

        Assert.assertFalse(tb.isRunning());

        if (false) {
            Log.d(TAG, "mUptimeStart=" + tb.getUptimeStart()
                    + " mRealtimeStart=" + tb.getRealtimeStart()
                    + " mUptime=" + tb.getUptime(250)
                    + " mRealtime=" + tb.getRealtime(14000)
                    + " isRunning=" + tb.isRunning());
        }

        Assert.assertEquals(252, tb.getUptime(402));
        Assert.assertEquals(1002, tb.getRealtime(14002));

        Mockito.verify(observer1).onTimeStopped(14002, 252, 1002);
        Mockito.verify(observer1, Mockito.never()).onTimeStopped(-1, -1, -1);
        Mockito.verifyNoMoreInteractions(observer1);
        Mockito.verify(observer2).onTimeStopped(14002, 252, 1002);
        Mockito.verify(observer2, Mockito.never()).onTimeStopped(-1, -1, -1);
        Mockito.verifyNoMoreInteractions(observer2);

        // Advance the "timer" and make sure the getters account for the current time passed in
        // is the same as the time when running went to false.
        Assert.assertEquals(252, tb.getUptime(600));
        Assert.assertEquals(1002, tb.getRealtime(17000));
    }

    /**
     * Test that reset while running updates the plugged and unplugged times
     */
    @SmallTest
    public void testResetWhileRunning() throws Exception {
        TestTimeBase tb = new TestTimeBase();
        tb.populate(100, 200, true, 300, 400, 500, 600, 700, 800);

        tb.reset(666, 6666);

        // Not sure if this is a bug: reset while running does not
        // reset mPastUptime, but while it is running it does.
        tb.verify(100, 200, true, 300, 666, 500, 6666, 300, 500);
    }

    /**
     * Test that reset while running updates the plugged and unplugged times
     */
    @SmallTest
    public void testResetWhileNotRunning() throws Exception {
        TestTimeBase tb = new TestTimeBase();
        tb.populate(100, 200, false, 300, 400, 500, 600, 700, 800);

        tb.reset(666, 6666);

        tb.verify(100, 200, false, 0, 400, 0, 600, 700, 800);
    }

    /**
     * Test init
     */
    @SmallTest
    public void testInit() throws Exception {
        TestTimeBase tb = new TestTimeBase();
        tb.populate(100, 200, false, 300, 400, 500, 600, 700, 800);

        tb.init(666, 6666);

        tb.verify(0, 0, false, 0, 666, 0, 6666, 0, 0);
    }

    /**
     * Test writeToParcel and readFromParcel
     */
    @SmallTest
    public void testParcellingWhileRunning() throws Exception {
        TestTimeBase tb1 = new TestTimeBase();

        tb1.populate(100, 200, true, 300, 400, 500, 600, 700, 800);

        Parcel parcel = Parcel.obtain();
        tb1.writeToParcel(parcel, 666, 6666);

        parcel.setDataPosition(0);

        TestTimeBase tb2 = new TestTimeBase();
        tb2.readFromParcel(parcel);

        // Running is not preserved across parceling
        tb2.verify(100, 200, false, 300+666-400, 400, 500+6666-600, 600, 700, 800);
    }

    /**
     * Test writeToParcel and readFromParcel
     */
    @SmallTest
    public void testParcellingWhileNotRunning() throws Exception {
        TestTimeBase tb1 = new TestTimeBase();

        tb1.populate(100, 200, false, 300, 400, 500, 600, 700, 800);

        Parcel parcel = Parcel.obtain();
        tb1.writeToParcel(parcel, 666, 6666);

        parcel.setDataPosition(0);

        TestTimeBase tb2 = new TestTimeBase();
        tb2.readFromParcel(parcel);

        tb2.verify(100, 200, false, 300, 400, 500, 600, 700, 800);
    }

    /**
     * Test writeSummaryToParcel and readSummaryFromParcel
     */
    @SmallTest
    public void testSummary() throws Exception {
        TestTimeBase tb1 = new TestTimeBase();

        tb1.populate(100, 200, true, 300, 400, 500, 600, 700, 800);

        Parcel parcel = Parcel.obtain();
        tb1.writeSummaryToParcel(parcel, 666, 6666);

        parcel.setDataPosition(0);

        TestTimeBase tb2 = new TestTimeBase();

        // readSummaryFromParcel doesn't affect the other fields.
        // Not sure if this is deliberate
        tb2.populate(1, 2, true, 3, 4, 5, 6, 7, 8);

        tb2.readSummaryFromParcel(parcel);

        tb2.verify(666, 6766, true, 3, 4, 5, 6, 7, 8);
    }

    /**
     * Test computeUptime
     */
    @SmallTest
    public void testComputeUptime() throws Exception {
        TestTimeBase tb = new TestTimeBase();

        tb.populate(100, 200, true, 300, 400, 500, 600, 50, 60);

        Assert.assertEquals(100+300+666-400,
                tb.computeUptime(666, BatteryStats.STATS_SINCE_CHARGED));
    }

    /**
     * Test computeUptime
     */
    @SmallTest
    public void testComputeRealtime() throws Exception {
        TestTimeBase tb = new TestTimeBase();

        tb.populate(100, 200, true, 300, 400, 500, 600, 50, 60);

        Assert.assertEquals(200+500+6666-600,
                tb.computeRealtime(6666, BatteryStats.STATS_SINCE_CHARGED));
    }

    /**
     * Test dump
     */
    @SmallTest
    public void testDump() throws Exception {
        TestTimeBase tb = new TestTimeBase();

        tb.populate(100, 200, true, 300, 400, 500, 600, 50, 60);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        tb.dump(pw, "+++++ ");

        pw.close();

        // note the spaces at the ends of the lines which come from formatTimeMs.
        final String CORRECT = "+++++ mRunning=true\n"
                + "+++++ mUptime=0ms \n"
                + "+++++ mRealtime=0ms \n"
                + "+++++ mPastUptime=0ms mUptimeStart=0ms mUnpluggedUptime=0ms \n"
                + "+++++ mPastRealtime=0ms mRealtimeStart=0ms mUnpluggedRealtime=0ms \n";

        Assert.assertEquals(CORRECT, sw.toString());
    }

}

