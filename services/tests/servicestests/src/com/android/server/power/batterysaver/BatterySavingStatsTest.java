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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.metrics.LogMaker;
import android.util.IndentingPrintWriter;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.MetricsLogger;
import com.android.server.power.batterysaver.BatterySavingStats.BatterySaverState;
import com.android.server.power.batterysaver.BatterySavingStats.DozeState;
import com.android.server.power.batterysaver.BatterySavingStats.InteractiveState;
import com.android.server.power.batterysaver.BatterySavingStats.PlugState;

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

        private int mBatteryLevel = 1_000_000_000;

        private BatterySavingStatsTestable() {
            super(new Object());
        }

        @Override
        long injectCurrentTime() {
            return mTime;
        }

        @Override
        int injectBatteryLevel() {
            return mBatteryLevel;
        }

        @Override
        int injectBatteryPercent() {
            return mBatteryLevel / 10;
        }

        void assertDumpable() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            dump(new IndentingPrintWriter(new PrintWriter(out))); // Just make sure it won't crash.
        }

        void advanceClock(int minutes) {
            mTime += 60_000 * minutes;
        }

        void drainBattery(int value) {
            mBatteryLevel -= value;
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

    public MetricsLogger mMetricsLogger = mock(MetricsLogger.class);

    @Test
    public void testAll() {
        checkAll();
    }

    private void checkAll() {
        final BatterySavingStatsTestable target = new BatterySavingStatsTestable();

        target.assertDumpable();

        target.advanceClock(1);
        target.drainBattery(200);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        target.advanceClock(4);
        target.drainBattery(100);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        target.advanceClock(2);
        target.drainBattery(500);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        target.advanceClock(4);
        target.drainBattery(100);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        target.advanceClock(2);
        target.drainBattery(500);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        target.advanceClock(3);
        target.drainBattery(100);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.LIGHT,
                PlugState.UNPLUGGED);

        target.advanceClock(5);
        target.drainBattery(100);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.DEEP,
                PlugState.UNPLUGGED);

        target.advanceClock(1);
        target.drainBattery(200);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        target.advanceClock(1);
        target.drainBattery(300);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        target.advanceClock(3);
        target.drainBattery(500);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        target.advanceClock(3);
        target.drainBattery(500);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.PLUGGED);

        target.advanceClock(5);
        target.drainBattery(1000);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        target.advanceClock(5);
        target.drainBattery(100);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.PLUGGED);

        target.assertDumpable();

        assertEquals(
                "BS=0,I=0,D=0,P=0:{4m,1000,15000.00uA/H,1500.00%}\n"
                        + "BS=1,I=0,D=0,P=0:{0m,0,0.00uA/H,0.00%}\n"
                        + "BS=0,I=1,D=0,P=0:{14m,800,3428.57uA/H,342.86%}\n"
                        + "BS=1,I=1,D=0,P=0:{9m,900,6000.00uA/H,600.00%}\n"
                        + "BS=0,I=0,D=1,P=0:{5m,100,1200.00uA/H,120.00%}\n"
                        + "BS=1,I=0,D=1,P=0:{0m,0,0.00uA/H,0.00%}\n"
                        + "BS=0,I=1,D=1,P=0:{0m,0,0.00uA/H,0.00%}\n"
                        + "BS=1,I=1,D=1,P=0:{0m,0,0.00uA/H,0.00%}\n"
                        + "BS=0,I=0,D=2,P=0:{1m,200,12000.00uA/H,1200.00%}\n"
                        + "BS=1,I=0,D=2,P=0:{0m,0,0.00uA/H,0.00%}\n"
                        + "BS=0,I=1,D=2,P=0:{0m,0,0.00uA/H,0.00%}\n"
                        + "BS=1,I=1,D=2,P=0:{0m,0,0.00uA/H,0.00%}\n"
                        + "BS=1,I=1,D=0,P=1:{5m,1000,12000.00uA/H,1200.00%}",
                target.toDebugString());
    }

    private void assertLog() {
        verify(mMetricsLogger, times(0)).write(any(LogMaker.class));
    }

    @Test
    public void testMetricsLogger() {
        final BatterySavingStatsTestable target = new BatterySavingStatsTestable();

        target.advanceClock(1);
        target.drainBattery(1000);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        verify(mMetricsLogger, times(0)).count(anyString(), anyInt());

        target.advanceClock(1);
        target.drainBattery(2000);

        reset(mMetricsLogger);
        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        assertLog();

        target.advanceClock(1);
        target.drainBattery(2000);

        reset(mMetricsLogger);
        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.DEEP,
                PlugState.UNPLUGGED);

        target.advanceClock(1);
        target.drainBattery(2000);

        verify(mMetricsLogger, times(0)).count(anyString(), anyInt());

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.LIGHT,
                PlugState.UNPLUGGED);

        target.advanceClock(1);
        target.drainBattery(2000);

        verify(mMetricsLogger, times(0)).count(anyString(), anyInt());

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        assertLog();

        target.advanceClock(10);
        target.drainBattery(10000);

        reset(mMetricsLogger);
        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.PLUGGED);

        assertLog();

        target.advanceClock(1);
        target.drainBattery(2000);

        reset(mMetricsLogger);
        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.UNPLUGGED);

        verify(mMetricsLogger, times(0)).count(anyString(), anyInt());

        target.advanceClock(1);
        target.drainBattery(2000);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING,
                PlugState.PLUGGED);

        assertLog();
    }
}
