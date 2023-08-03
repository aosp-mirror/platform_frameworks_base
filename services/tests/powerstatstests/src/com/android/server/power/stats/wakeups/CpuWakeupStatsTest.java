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

package com.android.server.power.stats.wakeups;

import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_ALARM;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_CELLULAR_DATA;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_SENSOR;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_UNKNOWN;
import static android.os.BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_WIFI;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Handler;
import android.util.IntArray;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.frameworks.powerstatstests.R;
import com.android.server.power.stats.wakeups.CpuWakeupStats.Wakeup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@RunWith(AndroidJUnit4.class)
public class CpuWakeupStatsTest {
    private static final String KERNEL_REASON_ALARM_IRQ = "120 test.alarm.device";
    private static final String KERNEL_REASON_WIFI_IRQ = "130 test.wifi.device";
    private static final String KERNEL_REASON_SOUND_TRIGGER_IRQ = "129 test.sound_trigger.device";
    private static final String KERNEL_REASON_SENSOR_IRQ = "15 test.sensor.device";
    private static final String KERNEL_REASON_CELLULAR_DATA_IRQ = "18 test.cellular_data.device";
    private static final String KERNEL_REASON_UNKNOWN_IRQ = "140 test.unknown.device";
    private static final String KERNEL_REASON_UNKNOWN_FORMAT = "free-form-reason test.alarm.device";
    private static final String KERNEL_REASON_ALARM_ABNORMAL = "-1 test.alarm.device";
    private static final String KERNEL_REASON_ABORT = "Abort: due to test.alarm.device";

    private static final int TEST_UID_1 = 13239823;
    private static final int TEST_UID_2 = 25268423;
    private static final int TEST_UID_3 = 92261423;
    private static final int TEST_UID_4 = 56926423;
    private static final int TEST_UID_5 = 76421423;

    private static final int TEST_PROC_STATE_1 = 72331;
    private static final int TEST_PROC_STATE_2 = 792351;
    private static final int TEST_PROC_STATE_3 = 138831;
    private static final int TEST_PROC_STATE_4 = 23231;
    private static final int TEST_PROC_STATE_5 = 42;

    private static final Context sContext = InstrumentationRegistry.getTargetContext();
    private final Handler mHandler = Mockito.mock(Handler.class);
    private final ThreadLocalRandom mRandom = ThreadLocalRandom.current();

    private void populateDefaultProcStates(CpuWakeupStats obj) {
        obj.mUidProcStates.put(TEST_UID_1, TEST_PROC_STATE_1);
        obj.mUidProcStates.put(TEST_UID_2, TEST_PROC_STATE_2);
        obj.mUidProcStates.put(TEST_UID_3, TEST_PROC_STATE_3);
        obj.mUidProcStates.put(TEST_UID_4, TEST_PROC_STATE_4);
        obj.mUidProcStates.put(TEST_UID_5, TEST_PROC_STATE_5);
    }

    @Test
    public void removesOldWakeups() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long retention = obj.mConfig.WAKEUP_STATS_RETENTION_MS;

        final Set<Long> timestamps = new HashSet<>();
        final long firstWakeup = 453192;

        // Reasons do not matter for this test, as long as they map to known subsystems
        obj.noteWakeupTimeAndReason(firstWakeup, 32, KERNEL_REASON_ALARM_IRQ);
        timestamps.add(firstWakeup);
        for (int i = 1; i < 1000; i++) {
            final long delta = mRandom.nextLong(retention);
            if (timestamps.add(firstWakeup + delta)) {
                obj.noteWakeupTimeAndReason(firstWakeup + delta, i, KERNEL_REASON_SENSOR_IRQ);
            }
        }
        assertThat(obj.mWakeupEvents.size()).isEqualTo(timestamps.size());

        obj.noteWakeupTimeAndReason(firstWakeup + retention, 231, KERNEL_REASON_WIFI_IRQ);
        assertThat(obj.mWakeupEvents.size()).isEqualTo(timestamps.size());

        for (int i = 0; i < 100; i++) {
            final long now = mRandom.nextLong(retention + 1, 100 * retention);
            obj.noteWakeupTimeAndReason(now, i, KERNEL_REASON_SOUND_TRIGGER_IRQ);
            assertThat(obj.mWakeupEvents.lastIndexOnOrBefore(now - retention)).isLessThan(0);
        }
    }

    @Test
    public void irqAttributionAllCombinations() {
        final int[] subsystems = new int[] {
                CPU_WAKEUP_SUBSYSTEM_ALARM,
                CPU_WAKEUP_SUBSYSTEM_WIFI,
                CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER,
                CPU_WAKEUP_SUBSYSTEM_SENSOR,
                CPU_WAKEUP_SUBSYSTEM_CELLULAR_DATA,
        };

        final String[] kernelReasons = new String[] {
                KERNEL_REASON_ALARM_IRQ,
                KERNEL_REASON_WIFI_IRQ,
                KERNEL_REASON_SOUND_TRIGGER_IRQ,
                KERNEL_REASON_SENSOR_IRQ,
                KERNEL_REASON_CELLULAR_DATA_IRQ,
        };

        final int[] uids = new int[] {
                TEST_UID_2, TEST_UID_3, TEST_UID_4, TEST_UID_1, TEST_UID_5
        };

        final int[] procStates = new int[] {
                TEST_PROC_STATE_2,
                TEST_PROC_STATE_3,
                TEST_PROC_STATE_4,
                TEST_PROC_STATE_1,
                TEST_PROC_STATE_5
        };

        final int total = subsystems.length;
        assertThat(kernelReasons.length).isEqualTo(total);
        assertThat(uids.length).isEqualTo(total);
        assertThat(procStates.length).isEqualTo(total);

        for (int mask = 1; mask < (1 << total); mask++) {
            final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3,
                    mHandler);
            populateDefaultProcStates(obj);

            final long wakeupTime = mRandom.nextLong(123456);

            String combinedKernelReason = null;
            for (int i = 0; i < total; i++) {
                if ((mask & (1 << i)) != 0) {
                    combinedKernelReason = (combinedKernelReason == null)
                            ? kernelReasons[i]
                            : String.join(":", combinedKernelReason, kernelReasons[i]);
                }

                obj.noteWakingActivity(subsystems[i], wakeupTime + 2, uids[i]);
            }
            obj.noteWakeupTimeAndReason(wakeupTime, 1, combinedKernelReason);

            assertThat(obj.mWakeupAttribution.size()).isEqualTo(1);

            final SparseArray<SparseIntArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
            assertThat(attribution.size()).isEqualTo(Integer.bitCount(mask));

            for (int i = 0; i < total; i++) {
                if ((mask & (1 << i)) == 0) {
                    assertThat(attribution.contains(subsystems[i])).isFalse();
                    continue;
                }
                assertThat(attribution.contains(subsystems[i])).isTrue();
                assertThat(attribution.get(subsystems[i]).get(uids[i])).isEqualTo(procStates[i]);

                for (int j = 0; j < total; j++) {
                    if (i == j) {
                        continue;
                    }
                    assertThat(attribution.get(subsystems[i]).indexOfKey(uids[j])).isLessThan(0);
                }
            }
        }
    }

    @Test
    public void alarmIrqAttributionSolo() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 12423121;

        populateDefaultProcStates(obj);

        obj.noteWakeupTimeAndReason(wakeupTime, 1, KERNEL_REASON_ALARM_IRQ);

        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM,
                wakeupTime - obj.mConfig.WAKEUP_MATCHING_WINDOW_MS - 1, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM,
                wakeupTime + obj.mConfig.WAKEUP_MATCHING_WINDOW_MS + 1, TEST_UID_2);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3, TEST_UID_5);

        final SparseArray<SparseIntArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution).isNotNull();
        assertThat(attribution.size()).isEqualTo(1);
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_ALARM)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).indexOfKey(TEST_UID_1)).isLessThan(
                0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).indexOfKey(TEST_UID_2)).isLessThan(
                0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_3)).isEqualTo(
                TEST_PROC_STATE_3);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).indexOfKey(TEST_UID_4)).isLessThan(
                0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_5)).isEqualTo(
                TEST_PROC_STATE_5);
    }

    @Test
    public void soundTriggerIrqAttributionSolo() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 1247121;

        populateDefaultProcStates(obj);

        obj.noteWakeupTimeAndReason(wakeupTime, 1, KERNEL_REASON_SOUND_TRIGGER_IRQ);

        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER,
                wakeupTime - obj.mConfig.WAKEUP_MATCHING_WINDOW_MS - 1, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER,
                wakeupTime + obj.mConfig.WAKEUP_MATCHING_WINDOW_MS + 1, TEST_UID_2);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER, wakeupTime + 5, TEST_UID_3,
                TEST_UID_5);

        final SparseArray<SparseIntArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution).isNotNull();
        assertThat(attribution.size()).isEqualTo(1);
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER).indexOfKey(
                TEST_UID_1)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER).indexOfKey(
                TEST_UID_2)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER).get(TEST_UID_3)).isEqualTo(
                TEST_PROC_STATE_3);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER).indexOfKey(
                TEST_UID_4)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER).get(TEST_UID_5)).isEqualTo(
                TEST_PROC_STATE_5);
    }

    @Test
    public void wifiIrqAttributionSolo() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 12423121;

        populateDefaultProcStates(obj);

        obj.noteWakeupTimeAndReason(wakeupTime, 1, KERNEL_REASON_WIFI_IRQ);

        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI,
                wakeupTime - obj.mConfig.WAKEUP_MATCHING_WINDOW_MS - 1, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI,
                wakeupTime + obj.mConfig.WAKEUP_MATCHING_WINDOW_MS + 1, TEST_UID_2);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime + 3, TEST_UID_4, TEST_UID_5);

        final SparseArray<SparseIntArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution).isNotNull();
        assertThat(attribution.size()).isEqualTo(1);
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_WIFI)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).indexOfKey(TEST_UID_1)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).indexOfKey(TEST_UID_2)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).indexOfKey(TEST_UID_3)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_4)).isEqualTo(
                TEST_PROC_STATE_4);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_5)).isEqualTo(
                TEST_PROC_STATE_5);
    }

    @Test
    public void alarmAndWifiIrqAttribution() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 92123210;

        populateDefaultProcStates(obj);

        obj.noteWakeupTimeAndReason(wakeupTime, 4,
                KERNEL_REASON_WIFI_IRQ + ":" + KERNEL_REASON_ALARM_IRQ);

        // Alarm activity
        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM,
                wakeupTime - obj.mConfig.WAKEUP_MATCHING_WINDOW_MS - 1, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM,
                wakeupTime + obj.mConfig.WAKEUP_MATCHING_WINDOW_MS + 1, TEST_UID_2);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime - 3, TEST_UID_4,
                TEST_UID_5);

        // Wifi activity
        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI,
                wakeupTime - obj.mConfig.WAKEUP_MATCHING_WINDOW_MS - 1, TEST_UID_4);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI,
                wakeupTime + obj.mConfig.WAKEUP_MATCHING_WINDOW_MS + 1, TEST_UID_3);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime + 2, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime - 1, TEST_UID_2,
                TEST_UID_5);

        final SparseArray<SparseIntArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution).isNotNull();
        assertThat(attribution.size()).isEqualTo(2);

        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_ALARM)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).indexOfKey(TEST_UID_1)).isLessThan(
                0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).indexOfKey(TEST_UID_2)).isLessThan(
                0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_3)).isEqualTo(
                TEST_PROC_STATE_3);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_4)).isEqualTo(
                TEST_PROC_STATE_4);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_5)).isEqualTo(
                TEST_PROC_STATE_5);

        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_WIFI)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_1)).isEqualTo(
                TEST_PROC_STATE_1);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_2)).isEqualTo(
                TEST_PROC_STATE_2);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).indexOfKey(TEST_UID_3)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).indexOfKey(TEST_UID_4)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_5)).isEqualTo(
                TEST_PROC_STATE_5);
    }

    @Test
    public void unknownIrqSoloIgnored() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 92123410;

        obj.noteWakeupTimeAndReason(wakeupTime, 24, KERNEL_REASON_UNKNOWN_IRQ);

        assertThat(obj.mWakeupEvents.size()).isEqualTo(0);

        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime - 3, TEST_UID_4,
                TEST_UID_5);

        // Any nearby activity should not end up in the attribution map.
        assertThat(obj.mWakeupAttribution.size()).isEqualTo(0);
    }

    @Test
    public void unknownAndWifiAttribution() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 92123410;

        populateDefaultProcStates(obj);

        obj.noteWakeupTimeAndReason(wakeupTime, 24,
                KERNEL_REASON_UNKNOWN_IRQ + ":" + KERNEL_REASON_WIFI_IRQ);

        // Wifi activity
        // Outside the window, so should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI,
                wakeupTime - obj.mConfig.WAKEUP_MATCHING_WINDOW_MS - 1, TEST_UID_4);
        // Should be attributed
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime + 2, TEST_UID_1);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime - 1, TEST_UID_3,
                TEST_UID_5);

        // Unrelated, should be ignored.
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3);

        final SparseArray<SparseIntArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution).isNotNull();
        assertThat(attribution.size()).isEqualTo(2);
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_WIFI)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_1)).isEqualTo(
                TEST_PROC_STATE_1);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).indexOfKey(TEST_UID_2)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_3)).isEqualTo(
                TEST_PROC_STATE_3);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).indexOfKey(TEST_UID_4)).isLessThan(0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_WIFI).get(TEST_UID_5)).isEqualTo(
                TEST_PROC_STATE_5);
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_UNKNOWN)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_UNKNOWN)).isNull();
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_ALARM)).isFalse();
    }

    @Test
    public void unknownFormatWakeupIgnored() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        final long wakeupTime = 72123210;

        obj.noteWakeupTimeAndReason(wakeupTime, 34, KERNEL_REASON_UNKNOWN_FORMAT);

        // Should be ignored as this type of wakeup is not known.
        assertThat(obj.mWakeupEvents.size()).isEqualTo(0);

        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime - 3, TEST_UID_4);

        // Any nearby activity should not end up in the attribution map.
        assertThat(obj.mWakeupAttribution.size()).isEqualTo(0);
    }

    @Test
    public void abnormalAlarmAttribution() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);
        populateDefaultProcStates(obj);

        long wakeupTime = 970934;
        obj.noteWakeupTimeAndReason(wakeupTime, 34, KERNEL_REASON_ALARM_ABNORMAL);

        assertThat(obj.mWakeupEvents.size()).isEqualTo(1);
        assertThat(obj.mWakeupEvents.valueAt(0).mType).isEqualTo(Wakeup.TYPE_ABNORMAL);

        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime + 5, TEST_UID_3);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, wakeupTime - 3, TEST_UID_4);

        final SparseArray<SparseIntArray> attribution = obj.mWakeupAttribution.get(wakeupTime);
        assertThat(attribution.size()).isEqualTo(1);
        assertThat(attribution.contains(CPU_WAKEUP_SUBSYSTEM_ALARM)).isTrue();
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).indexOfKey(TEST_UID_1)).isLessThan(
                0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).indexOfKey(TEST_UID_2)).isLessThan(
                0);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_3)).isEqualTo(
                TEST_PROC_STATE_3);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).get(TEST_UID_4)).isEqualTo(
                TEST_PROC_STATE_4);
        assertThat(attribution.get(CPU_WAKEUP_SUBSYSTEM_ALARM).indexOfKey(TEST_UID_5)).isLessThan(
                0);
    }

    @Test
    public void abortIgnored() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);

        long wakeupTime = 883124;
        obj.noteWakeupTimeAndReason(wakeupTime, 3, KERNEL_REASON_ABORT);

        // Should be ignored as this type of wakeup is unsupported.
        assertThat(obj.mWakeupEvents.size()).isEqualTo(0);

        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime + 2, TEST_UID_1, TEST_UID_4);
        obj.noteWakingActivity(CPU_WAKEUP_SUBSYSTEM_WIFI, wakeupTime - 5, TEST_UID_3);

        // Any nearby activity should not end up in the attribution map.
        assertThat(obj.mWakeupAttribution.size()).isEqualTo(0);
    }

    @Test
    public void uidProcStateBookkeeping() {
        final CpuWakeupStats obj = new CpuWakeupStats(sContext, R.xml.irq_device_map_3, mHandler);

        assertThat(obj.mUidProcStates.size()).isEqualTo(0);

        final IntArray uids = new IntArray(87);
        for (int i = 0; i < 87; i++) {
            final int uid = mRandom.nextInt(1 << 20);
            if (uids.indexOf(uid) < 0) {
                uids.add(uid);
            }
        }

        for (int i = 0; i < uids.size(); i++) {
            final int uid = uids.get(i);
            for (int j = 0; j < 43; j++) {
                final int procState = mRandom.nextInt(1 << 15);
                obj.noteUidProcessState(uid, procState);
                assertThat(obj.mUidProcStates.get(uid)).isEqualTo(procState);
            }
            assertThat(obj.mUidProcStates.size()).isEqualTo(i + 1);
        }

        for (int i = 0; i < uids.size(); i++) {
            obj.onUidRemoved(uids.get(i));
            assertThat(obj.mUidProcStates.indexOfKey(uids.get(i))).isLessThan(0);
        }

        assertThat(obj.mUidProcStates.size()).isEqualTo(0);

        obj.onUidRemoved(213);
        assertThat(obj.mUidProcStates.size()).isEqualTo(0);
    }
}
