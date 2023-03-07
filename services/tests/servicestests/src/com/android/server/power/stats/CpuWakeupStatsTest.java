/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_ALARM;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_UNKNOWN;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_WIFI;

import static com.android.server.power.stats.CpuWakeupStats.WAKEUP_REASON_HALF_WINDOW_MS;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Handler;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.frameworks.servicestests.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@RunWith(AndroidJUnit4.class)
public class CpuWakeupStatsTest {
    private static final String KERNEL_REASON_ALARM_IRQ = "120 test.alarm.device";
    private static final String KERNEL_REASON_WIFI_IRQ = "120 test.wifi.device";
    private static final String KERNEL_REASON_UNKNOWN_IRQ = "140 test.unknown.device";
    private static final String KERNEL_REASON_UNKNOWN = "free-form-reason test.alarm.device";
    private static final String KERNEL_REASON_UNSUPPORTED = "-1 test.alarm.device";
    private static final String KERNEL_REASON_ABORT = "Abort: due to test.alarm.device";

    private static final int TEST_UID_1 = 13239823;
    private static final int TEST_UID_2 = 25268423;
    private static final int TEST_UID_3 = 92261423;
    private static final int TEST_UID_4 = 56926423;
    private static final int TEST_UID_5 = 76421423;

    private static final Context sContext = InstrumentationRegistry.getTargetContext();
    private final Handler mHandler = Mockito.mock(Handler.class);
    private final ThreadLocalRandom mRandom = ThreadLocalRandom.current();

    @Test
    public void removesOldWakeups() {
        // The xml resource doesn't matter for this test.
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_1, mHandler);
        final long retention = obj.mConfig.WAKEUP_STATS_RETENTION_MS;

        final Set<Long> timestamps = new HashSet<>();
        final long firstWakeup = 453192;

        obj.noteWakeupTimeAndReason(firstWakeup, 32, KERNEL_REASON_UNKNOWN_IRQ);
        timestamps.add(firstWakeup);
        for (int i = 1; i < 1000; i++) {
            final long delta = mRandom.nextLong(retention);
            if (timestamps.add(firstWakeup + delta)) {
                obj.noteWakeupTimeAndReason(firstWakeup + delta, i, KERNEL_REASON_UNKNOWN_IRQ);
            }
        }
        assertThat(obj.mWakeupEvents.size()).isEqualTo(timestamps.size());

        obj.noteWakeupTimeAndReason(firstWakeup + retention + 1242, 231,
                KERNEL_REASON_UNKNOWN_IRQ);
        assertThat(obj.mWakeupEvents.size()).isEqualTo(timestamps.size());

        for (int i = 0; i < 100; i++) {
            final long now = mRandom.nextLong(retention + 1, 100 * retention);
            obj.noteWakeupTimeAndReason(now, i, KERNEL_REASON_UNKNOWN_IRQ);
            assertThat(obj.mWakeupEvents.closestIndexOnOrBefore(now - retention)).isLessThan(0);
        }
    }

    @Test
    public void alarmIrqAttributionSolo() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 12423121;

        obj.noteWakeupTimeAndReason(wakeupTime, 1, KERNEL_REASON_ALARM_IRQ);

        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM,
                wakeupTime - WAKEUP_REASON_HALF_WINDOW_MS - 1, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM,
                wakeupTime + WAKEUP_REASON_HALF_WINDOW_MS + 1, TEST_UID_2);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3, TEST_UID_5);

        final SparseArray<SparseBooleanArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution).isNotNull();
        assertThat(attribution.size()).isEqualTo(1);
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_ALARM)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_1)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_2)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_3)).isEqualTo(true);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_4)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_5)).isEqualTo(true);
    }

    @Test
    public void wifiIrqAttributionSolo() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 12423121;

        obj.noteWakeupTimeAndReason(wakeupTime, 1, KERNEL_REASON_WIFI_IRQ);

        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI,
                wakeupTime - WAKEUP_REASON_HALF_WINDOW_MS - 1, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI,
                wakeupTime + WAKEUP_REASON_HALF_WINDOW_MS + 1, TEST_UID_2);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime + 3, TEST_UID_4, TEST_UID_5);

        final SparseArray<SparseBooleanArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution).isNotNull();
        assertThat(attribution.size()).isEqualTo(1);
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_WIFI)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_1)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_2)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_3)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_4)).isEqualTo(true);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_5)).isEqualTo(true);
    }

    @Test
    public void alarmAndWifiIrqAttribution() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 92123210;

        obj.noteWakeupTimeAndReason(wakeupTime, 4,
                KERNEL_REASON_WIFI_IRQ + ":" + KERNEL_REASON_ALARM_IRQ);

        // Alarm activity
        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM,
                wakeupTime - WAKEUP_REASON_HALF_WINDOW_MS - 1, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM,
                wakeupTime + WAKEUP_REASON_HALF_WINDOW_MS + 1, TEST_UID_2);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime - 3, TEST_UID_4,
                TEST_UID_5);

        // Wifi activity
        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI,
                wakeupTime - WAKEUP_REASON_HALF_WINDOW_MS - 1, TEST_UID_4);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI,
                wakeupTime + WAKEUP_REASON_HALF_WINDOW_MS + 1, TEST_UID_3);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime + 2, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime - 1, TEST_UID_2,
                TEST_UID_5);

        final SparseArray<SparseBooleanArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution).isNotNull();
        assertThat(attribution.size()).isEqualTo(2);

        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_ALARM)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_1)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_2)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_3)).isEqualTo(true);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_4)).isEqualTo(true);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_5)).isEqualTo(true);

        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_WIFI)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_1)).isEqualTo(true);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_2)).isEqualTo(true);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_3)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_4)).isEqualTo(false);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_5)).isEqualTo(true);
    }

    @Test
    public void unknownIrqAttribution() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 92123410;

        obj.noteWakeupTimeAndReason(wakeupTime, 24, KERNEL_REASON_UNKNOWN_IRQ);

        assertThat(obj.mWakeupEvents.size()).isEqualTo(1);

        // Unrelated subsystems, should not be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime - 3, TEST_UID_4,
                TEST_UID_5);

        final SparseArray<SparseBooleanArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution).isNotNull();
        assertThat(attribution.size()).isEqualTo(1);
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_UNKNOWN)).isTrue();
        final SparseBooleanArray uids = attribution.get(CPU_WAKEUP_SUBSYSTEM_UNKNOWN);
        assertThat(uids == null || uids.size() == 0).isTrue();
    }

    @Test
    public void unknownWakeupIgnored() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 72123210;

        obj.noteWakeupTimeAndReason(wakeupTime, 34, KERNEL_REASON_UNKNOWN);

        // Should be ignored as this type of wakeup is not known.
        assertThat(obj.mWakeupEvents.size()).isEqualTo(0);

        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime - 3, TEST_UID_4);

        // Any nearby activity should not end up in the attribution map.
        assertThat(obj.mWakeupAttribution.size()).isEqualTo(0);
    }

    @Test
    public void unsupportedWakeupIgnored() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);

        long wakeupTime = 970934;
        obj.noteWakeupTimeAndReason(wakeupTime, 34, KERNEL_REASON_UNSUPPORTED);

        // Should be ignored as this type of wakeup is unsupported.
        assertThat(obj.mWakeupEvents.size()).isEqualTo(0);

        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime - 3, TEST_UID_4);

        // Any nearby activity should not end up in the attribution map.
        assertThat(obj.mWakeupAttribution.size()).isEqualTo(0);

        wakeupTime = 883124;
        obj.noteWakeupTimeAndReason(wakeupTime, 3, KERNEL_REASON_ABORT);

        // Should be ignored as this type of wakeup is unsupported.
        assertThat(obj.mWakeupEvents.size()).isEqualTo(0);

        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime + 2, TEST_UID_1, TEST_UID_4);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime - 5, TEST_UID_3);

        // Any nearby activity should not end up in the attribution map.
        assertThat(obj.mWakeupAttribution.size()).isEqualTo(0);
    }
}
