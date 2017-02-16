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
import android.os.Debug;
import android.support.test.filters.SmallTest;
import android.util.Log;

import junit.framework.TestCase;

/**
 * Test BatteryStatsImpl Sensor Timers.
 */
public class BatteryStatsSensorTest extends TestCase {

    private static final int UID = 10500;
    private static final int SENSOR_ID = -10000;

    @SmallTest
    public void testSensorStartStop() throws Exception {
        final MockClocks clocks = new MockClocks();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.mForceOnBattery = true;
        clocks.realtime = 100;
        clocks.uptime = 100;
        bi.getOnBatteryTimeBase().setRunning(true, 100, 100);
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
        BatteryStats.Counter sensorBgCounter = bi.getUidStats().get(UID).getSensorStats()
                .get(SENSOR_ID).getSensorBgCount();

        assertEquals(2, sensorTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(300000,
                sensorTimer.getTotalTimeLocked(clocks.realtime, BatteryStats.STATS_SINCE_CHARGED));

        assertEquals(1, sensorBgCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }
}
