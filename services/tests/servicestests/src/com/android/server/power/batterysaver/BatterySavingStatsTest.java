/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.power.batterysaver;

import static org.junit.Assert.assertEquals;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.power.batterysaver.BatterySavingStats.BatterySaverState;
import com.android.server.power.batterysaver.BatterySavingStats.DozeState;
import com.android.server.power.batterysaver.BatterySavingStats.InteractiveState;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

/**
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/power/batterysaver/BatterySavingStatsTest.java
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatterySavingStatsTest {
    private class BatterySavingStatsTestable extends BatterySavingStats {
        private long mTime = 1_000_000; // Some random starting time.

        private int mBatteryLevel = 100;

        @Override
        long injectCurrentTime() {
            return mTime;
        }

        @Override
        int injectBatteryLevel() {
            return mBatteryLevel;
        }

        void assertDumpable() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            dump(new PrintWriter(out), ""); // Just make sure it won't crash.
        }

        void advanceClock(int minutes) {
            mTime += 60_000 * minutes;
        }

        void drainBattery(int percent) {
            mBatteryLevel -= percent;
            if (mBatteryLevel < 0) {
                mBatteryLevel = 0;
            }
        }

        String toDebugString() {
            final StringBuilder sb = new StringBuilder();
            String sep = "";
            for (int i = 0; i < mStats.size(); i++) {
                sb.append(sep);
                sb.append(stateToString(mStats.keyAt(i)));
                sb.append(":");
                sb.append(mStats.valueAt(i).toStringForTest());
                sep = "\n";
            }
            return sb.toString();
        }
    }

    @Test
    public void testAll() {
        final BatterySavingStatsTestable target = new BatterySavingStatsTestable();

        target.assertDumpable();

        target.advanceClock(1);
        target.drainBattery(2);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(4);
        target.drainBattery(1);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(2);
        target.drainBattery(5);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(4);
        target.drainBattery(1);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(2);
        target.drainBattery(5);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(3);
        target.drainBattery(1);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.LIGHT);

        target.advanceClock(5);
        target.drainBattery(1);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.DEEP);

        target.advanceClock(1);
        target.drainBattery(2);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(1);
        target.drainBattery(3);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(3);
        target.drainBattery(5);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(3);
        target.drainBattery(5);

        target.startCharging();

        target.advanceClock(5);
        target.drainBattery(10);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(5);
        target.drainBattery(1);

        target.startCharging();

        target.assertDumpable();

        assertEquals(
                "BS=0,I=0,D=0:{4m,10,150.00}\n" +
                "BS=1,I=0,D=0:{0m,0,0.00}\n" +
                "BS=0,I=1,D=0:{14m,8,34.29}\n" +
                "BS=1,I=1,D=0:{9m,9,60.00}\n" +
                "BS=0,I=0,D=1:{5m,1,12.00}\n" +
                "BS=1,I=0,D=1:{0m,0,0.00}\n" +
                "BS=0,I=1,D=1:{0m,0,0.00}\n" +
                "BS=1,I=1,D=1:{0m,0,0.00}\n" +
                "BS=0,I=0,D=2:{1m,2,120.00}\n" +
                "BS=1,I=0,D=2:{0m,0,0.00}\n" +
                "BS=0,I=1,D=2:{0m,0,0.00}\n" +
                "BS=1,I=1,D=2:{0m,0,0.00}",
                target.toDebugString());
    }
}
