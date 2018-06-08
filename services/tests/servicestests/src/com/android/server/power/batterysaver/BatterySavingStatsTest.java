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
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.power.batterysaver.BatterySavingStats.BatterySaverState;
import com.android.server.power.batterysaver.BatterySavingStats.DozeState;
import com.android.server.power.batterysaver.BatterySavingStats.InteractiveState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

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
            super(new Object(), mMetricsLogger);
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
            dump(new PrintWriter(out), ""); // Just make sure it won't crash.
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

    private boolean sendTronEvents;

    @Test
    public void testAll_withTron() {
        sendTronEvents = true;
        checkAll();
    }

    @Test
    public void testAll_noTron() {
        sendTronEvents = false;
        checkAll();
    }

    private void checkAll() {
        final BatterySavingStatsTestable target = new BatterySavingStatsTestable();
        target.setSendTronLog(sendTronEvents);

        target.assertDumpable();

        target.advanceClock(1);
        target.drainBattery(200);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(4);
        target.drainBattery(100);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(2);
        target.drainBattery(500);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(4);
        target.drainBattery(100);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(2);
        target.drainBattery(500);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(3);
        target.drainBattery(100);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.LIGHT);

        target.advanceClock(5);
        target.drainBattery(100);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.DEEP);

        target.advanceClock(1);
        target.drainBattery(200);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(1);
        target.drainBattery(300);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(3);
        target.drainBattery(500);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(3);
        target.drainBattery(500);

        target.startCharging();

        target.advanceClock(5);
        target.drainBattery(1000);

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        target.advanceClock(5);
        target.drainBattery(100);

        target.startCharging();

        target.assertDumpable();

        assertEquals(
                "BS=0,I=0,D=0:{4m,1000,15000.00uA/H,1500.00%}\n" +
                "BS=1,I=0,D=0:{0m,0,0.00uA/H,0.00%}\n" +
                "BS=0,I=1,D=0:{14m,800,3428.57uA/H,342.86%}\n" +
                "BS=1,I=1,D=0:{9m,900,6000.00uA/H,600.00%}\n" +
                "BS=0,I=0,D=1:{5m,100,1200.00uA/H,120.00%}\n" +
                "BS=1,I=0,D=1:{0m,0,0.00uA/H,0.00%}\n" +
                "BS=0,I=1,D=1:{0m,0,0.00uA/H,0.00%}\n" +
                "BS=1,I=1,D=1:{0m,0,0.00uA/H,0.00%}\n" +
                "BS=0,I=0,D=2:{1m,200,12000.00uA/H,1200.00%}\n" +
                "BS=1,I=0,D=2:{0m,0,0.00uA/H,0.00%}\n" +
                "BS=0,I=1,D=2:{0m,0,0.00uA/H,0.00%}\n" +
                "BS=1,I=1,D=2:{0m,0,0.00uA/H,0.00%}",
                target.toDebugString());
    }

    private void assertLog(boolean batterySaver, boolean interactive, long deltaTimeMs,
            int deltaBatteryLevelUa, int deltaBatteryLevelPercent) {
        if (sendTronEvents) {
            ArgumentCaptor<LogMaker> ac = ArgumentCaptor.forClass(LogMaker.class);
            verify(mMetricsLogger, times(1)).write(ac.capture());

            LogMaker lm = ac.getValue();
            assertEquals(MetricsEvent.BATTERY_SAVER, lm.getCategory());
            assertEquals(batterySaver ? 1 : 0,
                    lm.getTaggedData(MetricsEvent.RESERVED_FOR_LOGBUILDER_SUBTYPE));
            assertEquals(interactive ? 1 : 0, lm.getTaggedData(MetricsEvent.FIELD_INTERACTIVE));
            assertEquals(deltaTimeMs, lm.getTaggedData(MetricsEvent.FIELD_DURATION_MILLIS));

            assertEquals(deltaBatteryLevelUa,
                    (int) lm.getTaggedData(MetricsEvent.FIELD_START_BATTERY_UA)
                            - (int) lm.getTaggedData(MetricsEvent.FIELD_END_BATTERY_UA));
            assertEquals(deltaBatteryLevelPercent,
                    (int) lm.getTaggedData(MetricsEvent.FIELD_START_BATTERY_PERCENT)
                            - (int) lm.getTaggedData(MetricsEvent.FIELD_END_BATTERY_PERCENT));
        } else {
            verify(mMetricsLogger, times(0)).write(any(LogMaker.class));
        }
    }


    @Test
    public void testMetricsLogger_withTron() {
        sendTronEvents = true;
        checkMetricsLogger();
    }

    @Test
    public void testMetricsLogger_noTron() {
        sendTronEvents = false;
        checkMetricsLogger();
    }

    private void checkMetricsLogger() {
        final BatterySavingStatsTestable target = new BatterySavingStatsTestable();
        target.setSendTronLog(sendTronEvents);

        target.advanceClock(1);
        target.drainBattery(1000);

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        verify(mMetricsLogger, times(0)).count(anyString(), anyInt());

        target.advanceClock(1);
        target.drainBattery(2000);

        reset(mMetricsLogger);
        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING);

        assertLog(false, true, 60_000, 2000, 200);

        target.advanceClock(1);
        target.drainBattery(2000);

        reset(mMetricsLogger);
        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.DEEP);

        target.advanceClock(1);
        target.drainBattery(2000);

        verify(mMetricsLogger, times(0)).count(anyString(), anyInt());

        target.transitionState(
                BatterySaverState.OFF,
                InteractiveState.NON_INTERACTIVE,
                DozeState.LIGHT);

        target.advanceClock(1);
        target.drainBattery(2000);

        verify(mMetricsLogger, times(0)).count(anyString(), anyInt());

        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.INTERACTIVE,
                DozeState.NOT_DOZING);

        assertLog(false, false, 60_000 * 3, 2000 * 3, 200 * 3);

        target.advanceClock(10);
        target.drainBattery(10000);

        reset(mMetricsLogger);
        target.startCharging();

        assertLog(true, true, 60_000 * 10, 10000, 1000);

        target.advanceClock(1);
        target.drainBattery(2000);

        reset(mMetricsLogger);
        target.transitionState(
                BatterySaverState.ON,
                InteractiveState.NON_INTERACTIVE,
                DozeState.NOT_DOZING);

        verify(mMetricsLogger, times(0)).count(anyString(), anyInt());

        target.advanceClock(1);
        target.drainBattery(2000);

        target.startCharging();

        assertLog(true, false, 60_000, 2000, 200);
    }
}
