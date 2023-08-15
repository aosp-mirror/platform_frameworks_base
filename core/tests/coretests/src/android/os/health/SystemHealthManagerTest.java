/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.os.health;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.google.common.truth.Truth.assertThat;

import android.os.ConditionVariable;
import android.os.PowerMonitor;
import android.os.PowerMonitorReadings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SystemHealthManagerTest {
    private List<PowerMonitor> mPowerMonitorInfo;
    private PowerMonitorReadings mReadings;
    private RuntimeException mException;

    @Test
    public void getPowerMonitors() {
        SystemHealthManager shm = getContext().getSystemService(SystemHealthManager.class);
        List<PowerMonitor> powerMonitorInfo = shm.getSupportedPowerMonitors();
        assertThat(powerMonitorInfo).isNotNull();
        if (powerMonitorInfo.isEmpty()) {
            // This device does not support PowerStats HAL
            return;
        }

        PowerMonitor consumerMonitor = null;
        PowerMonitor measurementMonitor = null;
        for (PowerMonitor pmi : powerMonitorInfo) {
            if (pmi.type == PowerMonitor.POWER_MONITOR_TYPE_MEASUREMENT) {
                measurementMonitor = pmi;
            } else {
                consumerMonitor = pmi;
            }
        }

        List<PowerMonitor> selectedMonitors = new ArrayList<>();
        if (consumerMonitor != null) {
            selectedMonitors.add(consumerMonitor);
        }
        if (measurementMonitor != null) {
            selectedMonitors.add(measurementMonitor);
        }

        PowerMonitorReadings readings = shm.getPowerMonitorReadings(selectedMonitors);

        for (PowerMonitor monitor : selectedMonitors) {
            assertThat(readings.getConsumedEnergyUws(monitor)).isAtLeast(0);
            assertThat(readings.getTimestamp(monitor)).isGreaterThan(0);
        }
    }

    @Test
    public void getPowerMonitorsAsync() {
        SystemHealthManager shm = getContext().getSystemService(SystemHealthManager.class);
        ConditionVariable done = new ConditionVariable();
        shm.getSupportedPowerMonitors(null, pms -> {
            mPowerMonitorInfo = pms;
            done.open();
        });
        done.block();
        assertThat(mPowerMonitorInfo).isNotNull();
        if (mPowerMonitorInfo.isEmpty()) {
            // This device does not support PowerStats HAL
            return;
        }

        PowerMonitor consumerMonitor = null;
        PowerMonitor measurementMonitor = null;
        for (PowerMonitor pmi : mPowerMonitorInfo) {
            if (pmi.type == PowerMonitor.POWER_MONITOR_TYPE_MEASUREMENT) {
                measurementMonitor = pmi;
            } else {
                consumerMonitor = pmi;
            }
        }

        List<PowerMonitor> selectedMonitors = new ArrayList<>();
        if (consumerMonitor != null) {
            selectedMonitors.add(consumerMonitor);
        }
        if (measurementMonitor != null) {
            selectedMonitors.add(measurementMonitor);
        }

        done.close();
        shm.getPowerMonitorReadings(selectedMonitors, null,
                readings -> {
                    mReadings = readings;
                    done.open();
                },
                exception -> {
                    mException = exception;
                    done.open();
                }
        );
        done.block();

        assertThat(mException).isNull();

        for (PowerMonitor monitor : selectedMonitors) {
            assertThat(mReadings.getConsumedEnergyUws(monitor)).isAtLeast(0);
            assertThat(mReadings.getTimestamp(monitor)).isGreaterThan(0);
        }
    }
}
