/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.os;

import android.app.ActivityManager;
import android.os.BatteryStats;
import android.view.Display;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

/**
 * Test BatteryStatsImpl Sensor Timers.
 */
public class BatteryStatsSensorTest extends TestCase {

    private static final int UID = 10500;
    private static final int SENSOR_ID = -10000;

    @SmallTest
    public void testSensorStartStop() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.mForceOnBattery = true;
        clocks.realtime = 100;
        clocks.uptime = 100;
        bi.getOnBatteryTimeBase().setRunning(true, 100_000, 100_000);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        bi.noteStartSensorLocked(UID, SENSOR_ID);
        clocks.realtime = 200;
        clocks.uptime = 200;
        bi.noteStopSensorLocked(UID, SENSOR_ID);

        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_RECEIVER);
        bi.noteStartSensorLocked(UID, SENSOR_ID);
        bi.noteStartSensorLocked(UID, SENSOR_ID);
        clocks.realtime = 400;
        clocks.uptime = 400;
        bi.noteStopSensorLocked(UID, SENSOR_ID);
        bi.noteStopSensorLocked(UID, SENSOR_ID);

        BatteryStats.Timer sensorTimer = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        BatteryStats.Timer sensorBgTimer = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorBackgroundTime();

        assertEquals(2, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(300_000, sensorTimer.getTotalTimeLocked(
                clocks.realtime * 1000, BatteryStats.STATS_SINCE_CHARGED));

        assertEquals(1, sensorBgTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(200_000, sensorBgTimer.getTotalTimeLocked(
                clocks.realtime * 1000, BatteryStats.STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testCountingWhileOffBattery() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long curr = 0; // realtime in us

        // Plugged-in (battery=off, sensor=off)
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(false, Display.STATE_ON, curr, curr);


        // Start sensor (battery=off, sensor=on)
        curr = 1000 * (clocks.realtime = clocks.uptime = 200);
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        // Test situation
        curr = 1000 * (clocks.realtime = clocks.uptime = 215);
        BatteryStats.Timer sensorTimer = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        assertEquals(0,
                sensorTimer.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(0, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Stop sensor (battery=off, sensor=off)
        curr = 1000 * (clocks.realtime = clocks.uptime = 550);
        bi.noteStopSensorLocked(UID, SENSOR_ID);

        // Test situation
        curr = 1000 * (clocks.realtime = clocks.uptime = 678);
        sensorTimer = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        assertEquals(0,
                sensorTimer.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(0, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testCountingWhileOnBattery() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long curr = 0; // realtime in us

        // Unplugged (battery=on, sensor=off)
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(true, Display.STATE_ON, curr, curr);

        // Start sensor (battery=on, sensor=on)
        curr = 1000 * (clocks.realtime = clocks.uptime = 200);
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        // Test situation
        curr = 1000 * (clocks.realtime = clocks.uptime = 215);
        BatteryStats.Timer sensorTimer = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        assertEquals((215-200)*1000,
                sensorTimer.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Stop sensor (battery=on, sensor=off)
        curr = 1000 * (clocks.realtime = clocks.uptime = 550);
        bi.noteStopSensorLocked(UID, SENSOR_ID);

        // Test situation
        curr = 1000 * (clocks.realtime = clocks.uptime = 678);
        sensorTimer = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        assertEquals((550-200)*1000,
                sensorTimer.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testBatteryStatusOnToOff() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long curr = 0; // realtime in us

        // On battery (battery=on, sensor=off)
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(true, Display.STATE_ON, curr, curr);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);

        // Start sensor (battery=on, sensor=on)
        curr = 1000 * (clocks.realtime = clocks.uptime = 202);
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        // Off battery (battery=off, sensor=on)
        curr = 1000 * (clocks.realtime = clocks.uptime = 305);
        bi.updateTimeBasesLocked(false, Display.STATE_ON, curr, curr);

        // Stop sensor while off battery (battery=off, sensor=off)
        curr = 1000 * (clocks.realtime = clocks.uptime = 409);
        bi.noteStopSensorLocked(UID, SENSOR_ID);

        // Start sensor while off battery (battery=off, sensor=on)
        curr = 1000 * (clocks.realtime = clocks.uptime = 519);
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        // Test while still running (but off battery)
        curr = 1000 * (clocks.realtime = clocks.uptime = 657);
        BatteryStats.Timer sensorTimer = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        assertEquals(1, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals((305-202)*1000,
                sensorTimer.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));

        // Now stop running (still off battery) (battery=off, sensor=off)
        curr = 1000 * (clocks.realtime = clocks.uptime = 693);
        bi.noteStopSensorLocked(UID, SENSOR_ID);

        sensorTimer = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        assertEquals(1, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals((305-202)*1000,
                sensorTimer.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testBatteryStatusOffToOn() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long curr = 0; // realtime in us

        // Plugged-in (battery=off, sensor=off)
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(false, Display.STATE_ON, curr, curr);

        // Start sensor (battery=off, sensor=on)
        curr = 1000 * (clocks.realtime = clocks.uptime = 200);
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        // Test situation
        curr = 1000 * (clocks.realtime = clocks.uptime = 215);
        BatteryStats.Timer sensorTimer = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        // Time was entirely off battery, so time=0.
        assertEquals(0,
                sensorTimer.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        // Acquired off battery, so count=0.
        assertEquals(0, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Unplug (battery=on, sensor=on)
        curr = 1000 * (clocks.realtime = clocks.uptime = 305);
        bi.updateTimeBasesLocked(true, Display.STATE_ON, curr, curr);

        //Test situation
        curr = 1000 * (clocks.realtime = clocks.uptime = 410);
        sensorTimer = bi.getUidStats().get(UID).getSensorStats().get(SENSOR_ID).getSensorTime();
        // Part of the time it was on battery.
        assertEquals((410-305)*1000,
                sensorTimer.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        // Only ever acquired off battery, so count=0.
        assertEquals(0, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Stop sensor (battery=on, sensor=off)
        curr = 1000 * (clocks.realtime = clocks.uptime = 550);
        bi.noteStopSensorLocked(UID, SENSOR_ID);

        // Test situation
        curr = 1000 * (clocks.realtime = clocks.uptime = 678);
        sensorTimer = bi.getUidStats().get(UID).getSensorStats().get(SENSOR_ID).getSensorTime();
        // Part of the time it was on battery.
        assertEquals((550-305)*1000,
                sensorTimer.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        // Only ever acquired off battery, so count=0.
        assertEquals(0, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testPooledBackgroundUsage() throws Exception {
        final int UID_2 = 20000; // second uid for testing pool usage
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.mForceOnBattery = true;
        long curr = 0; // realtime in us
        // Entire test is on-battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 1000);
        bi.updateTimeBasesLocked(true, Display.STATE_ON, curr, curr);

        // See below for a diagram of events.

        // UID in foreground
        curr = 1000 * (clocks.realtime = clocks.uptime = 2002);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);

        // UID starts the sensor (foreground)
        curr = 1000 * (clocks.realtime = clocks.uptime = 3004);
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        // UID_2 in background
        curr = 1000 * (clocks.realtime = clocks.uptime = 4008);
        bi.noteUidProcessStateLocked(UID_2, ActivityManager.PROCESS_STATE_RECEIVER); // background

        // UID_2 starts the sensor (background)
        curr = 1000 * (clocks.realtime = clocks.uptime = 5016);
        bi.noteStartSensorLocked(UID_2, SENSOR_ID);

        // UID enters background
        curr = 1000 * (clocks.realtime = clocks.uptime = 6032);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        // UID enters background again (from a different background state)
        curr = 1000 * (clocks.realtime = clocks.uptime = 7004);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_LAST_ACTIVITY);

        // UID_2 stops the sensor (background), then starts it again, then stops again
        curr = 1000 * (clocks.realtime = clocks.uptime = 8064);
        bi.noteStopSensorLocked(UID_2, SENSOR_ID);
        curr = 1000 * (clocks.realtime = clocks.uptime = 9128);
        bi.noteStartSensorLocked(UID_2, SENSOR_ID);
        curr = 1000 * (clocks.realtime = clocks.uptime = 10256);
        bi.noteStopSensorLocked(UID_2, SENSOR_ID);

        // UID re-enters foreground
        curr = 1000 * (clocks.realtime = clocks.uptime = 11512);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);

        // UID starts the sensor a second time (foreground)
        curr = 1000 * (clocks.realtime = clocks.uptime = 12000);
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        // UID re-enters background
        curr = 1000 * (clocks.realtime = clocks.uptime = 13002);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        // UID stops the sensor completely (background)
        curr = 1000 * (clocks.realtime = clocks.uptime = 14004);
        bi.noteStopSensorLocked(UID, SENSOR_ID);
        curr = 1000 * (clocks.realtime = clocks.uptime = 14024);
        bi.noteStopSensorLocked(UID, SENSOR_ID);

        // UID starts the sensor anew (background)
        curr = 1000 * (clocks.realtime = clocks.uptime = 15010);
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        // UID stops the sensor (background)
        curr = 1000 * (clocks.realtime = clocks.uptime = 16020);
        bi.noteStopSensorLocked(UID, SENSOR_ID);

//      Summary
//        UID
//        foreground: 2002---6032,              11512---13002
//        background:        6032---------------11512,  13002--------------------------
//        sensor running: 3004-----------------------------14024, 15010-16020
//
//        UID2
//        foreground:
//        background:       4008-------------------------------------------------------
//        sensor running:    5016--8064, 9128-10256

        BatteryStats.Timer timer1 = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        BatteryStats.Timer bgTimer1 = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorBackgroundTime();

        BatteryStats.Timer timer2 = bi.getUidStats().get(UID_2).getSensorStats()
                .get(SENSOR_ID).getSensorTime();
        BatteryStats.Timer bgTimer2 = bi.getUidStats().get(UID_2).getSensorStats()
                .get(SENSOR_ID).getSensorBackgroundTime();

        // Expected values
        long expActualTime1 = (14024 - 3004) + (16020 - 15010);
        long expBgTime1 = (11512 - 6032) + (14024 - 13002) + (16020 - 15010);

        long expActualTime2 = (8064 - 5016) + (10256 - 9128);
        long expBgTime2 = (8064 - 5016) + (10256 - 9128);

        long expBlamedTime1 = (5016 - 3004) + (8064 - 5016)/2 + (9128 - 8064) + (10256 - 9128)/2
                + (14024 - 10256) + (16020 - 15010);
        long expBlamedTime2 = (8064 - 5016)/2 + (10256 - 9128)/2;

        // Test: UID - blamed time
        assertEquals(expBlamedTime1 * 1000,
                timer1.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        // Test: UID - actual time
        assertEquals(expActualTime1 * 1000,
                timer1.getTotalDurationMsLocked(clocks.realtime) * 1000 );
        // Test: UID - background time
        // bg timer ignores pools, so both totalTime and totalDuration should give the same result
        assertEquals(expBgTime1 * 1000,
                bgTimer1.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(expBgTime1 * 1000,
                bgTimer1.getTotalDurationMsLocked(clocks.realtime) * 1000 );
        // Test: UID - count
        assertEquals(2, timer1.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        // Test: UID - background count
        assertEquals(1, bgTimer1.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Test: UID_2 - blamed time
        assertEquals(expBlamedTime2 * 1000,
                timer2.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        // Test: UID_2 - actual time
        assertEquals(expActualTime2 * 1000,
                timer2.getTotalDurationMsLocked(clocks.realtime) * 1000);
        // Test: UID_2 - background time
        // bg timer ignores pools, so both totalTime and totalDuration should give the same result
        assertEquals(expBgTime2 * 1000,
                bgTimer2.getTotalTimeLocked(curr, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(expBgTime2 * 1000,
                bgTimer2.getTotalDurationMsLocked(clocks.realtime) * 1000 );
        // Test: UID_2 - count
        assertEquals(2, timer2.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        // Test: UID_2 - background count
        assertEquals(2, bgTimer2.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testSensorReset() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.mForceOnBattery = true;
        clocks.realtime = 100;
        clocks.uptime = 100;
        bi.getOnBatteryTimeBase().setRunning(true, 100_000, 100_000);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_RECEIVER);

        clocks.realtime += 100;
        clocks.uptime += 100;

        bi.noteStartSensorLocked(UID, SENSOR_ID);

        clocks.realtime += 100;
        clocks.uptime += 100;

        // The sensor is started and the timer has been created.
        final BatteryStats.Uid uid = bi.getUidStats().get(UID);
        assertNotNull(uid);

        BatteryStats.Uid.Sensor sensor = uid.getSensorStats().get(SENSOR_ID);
        assertNotNull(sensor);
        assertNotNull(sensor.getSensorTime());
        assertNotNull(sensor.getSensorBackgroundTime());

        // Reset the stats. Since the sensor is still running, we should still see the timer
        bi.getUidStatsLocked(UID).reset(clocks.uptime * 1000, clocks.realtime * 1000, 0);

        sensor = uid.getSensorStats().get(SENSOR_ID);
        assertNotNull(sensor);
        assertNotNull(sensor.getSensorTime());
        assertNotNull(sensor.getSensorBackgroundTime());

        bi.noteStopSensorLocked(UID, SENSOR_ID);

        // Now the sensor timer has stopped so this reset should also take out the sensor.
        bi.getUidStatsLocked(UID).reset(clocks.uptime * 1000, clocks.realtime * 1000, 0);

        sensor = uid.getSensorStats().get(SENSOR_ID);
        assertNull(sensor);
    }

    @SmallTest
    public void testSensorResetTimes() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        final int which = BatteryStats.STATS_SINCE_CHARGED;
        bi.mForceOnBattery = true;
        clocks.realtime = 100; // in ms
        clocks.uptime = 100; // in ms

        // TimeBases are on for some time.
        BatteryStatsImpl.TimeBase timeBase = bi.getOnBatteryTimeBase();
        BatteryStatsImpl.TimeBase bgTimeBase = bi.getOnBatteryBackgroundTimeBase(UID);
        timeBase.setRunning(true, clocks.uptime * 1000, clocks.realtime * 1000);
        bgTimeBase.setRunning(true, clocks.uptime * 1000, clocks.realtime * 1000);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        clocks.realtime += 100;
        clocks.uptime += 100;

        // TimeBases are turned off
        timeBase.setRunning(false, clocks.uptime * 1000, clocks.realtime * 1000);
        bgTimeBase.setRunning(false, clocks.uptime * 1000, clocks.realtime * 1000);

        clocks.realtime += 100;
        clocks.uptime += 100;

        // Timer is turned on
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        clocks.realtime += 100;
        clocks.uptime += 100;

        // Timebase was off so times are all 0.
        BatteryStats.Uid.Sensor sensor = bi.getUidStats().get(UID).getSensorStats().get(SENSOR_ID);
        BatteryStats.Timer timer = sensor.getSensorTime();
        BatteryStats.Timer bgTimer = sensor.getSensorBackgroundTime();
        assertEquals(0, timer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(0, timer.getTotalDurationMsLocked(clocks.realtime));
        assertEquals(0, bgTimer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(0, bgTimer.getTotalDurationMsLocked(clocks.realtime));

        clocks.realtime += 100;
        clocks.uptime += 100;

        // Reset the stats. Since the sensor is still running, we should still see the timer
        // but still with 0 times.
        bi.getUidStatsLocked(UID).reset(clocks.uptime * 1000, clocks.realtime * 1000, 0);
        assertEquals(0, timer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(0, timer.getTotalDurationMsLocked(clocks.realtime));
        assertEquals(0, bgTimer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(0, bgTimer.getTotalDurationMsLocked(clocks.realtime));

        clocks.realtime += 100;
        clocks.uptime += 100;

        // Now stop the timer. The times should still be 0.
        bi.noteStopSensorLocked(UID, SENSOR_ID);
        assertEquals(0, timer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(0, timer.getTotalDurationMsLocked(clocks.realtime));
        assertEquals(0, bgTimer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(0, bgTimer.getTotalDurationMsLocked(clocks.realtime));

        // Now repeat with the TimeBases turned on the entire time.
        timeBase.setRunning(true, clocks.uptime * 1000, clocks.realtime * 1000);
        bgTimeBase.setRunning(true, clocks.uptime * 1000, clocks.realtime * 1000);
        clocks.realtime += 100;
        clocks.uptime += 100;

        // Timer is turned on
        bi.noteStartSensorLocked(UID, SENSOR_ID);

        clocks.realtime += 111;
        clocks.uptime += 111;

        // Timebase and timer was on so times have increased.
        assertEquals(111_000, timer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(111, timer.getTotalDurationMsLocked(clocks.realtime));
        assertEquals(111_000, bgTimer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(111, bgTimer.getTotalDurationMsLocked(clocks.realtime));

        clocks.realtime += 100;
        clocks.uptime += 100;

        // Reset the stats. Since the sensor is still running, we should still see the timer
        // but with 0 times.
        bi.getUidStatsLocked(UID).reset(clocks.uptime * 1000, clocks.realtime * 1000, 0);
        assertEquals(0, timer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(0, timer.getTotalDurationMsLocked(clocks.realtime));
        assertEquals(0, bgTimer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(0, bgTimer.getTotalDurationMsLocked(clocks.realtime));

        clocks.realtime += 112;
        clocks.uptime += 112;

        // Now stop the timer. The times should have increased since the timebase was on.
        bi.noteStopSensorLocked(UID, SENSOR_ID);
        assertEquals(112_000, timer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(112, timer.getTotalDurationMsLocked(clocks.realtime));
        assertEquals(112_000, bgTimer.getTotalTimeLocked(1000*clocks.realtime, which));
        assertEquals(112, bgTimer.getTotalDurationMsLocked(clocks.realtime));
    }
}
