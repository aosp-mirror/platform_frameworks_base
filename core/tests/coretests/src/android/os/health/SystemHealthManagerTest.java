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

import android.os.PowerMonitor;
import android.os.PowerMonitorReadings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SystemHealthManagerTest {

    @Test
    public void getPowerMonitors() {
        SystemHealthManager shm = getContext().getSystemService(SystemHealthManager.class);
        PowerMonitor[] powerMonitorInfo = shm.getSupportedPowerMonitors();
        assertThat(powerMonitorInfo).isNotNull();
        if (powerMonitorInfo.length == 0) {
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

        List<PowerMonitor> pmis = new ArrayList<>();
        if (consumerMonitor != null) {
            pmis.add(consumerMonitor);
        }
        if (measurementMonitor != null) {
            pmis.add(measurementMonitor);
        }

        PowerMonitor[] selectedMonitors = pmis.toArray(new PowerMonitor[0]);
        PowerMonitorReadings readings = shm.getPowerMonitorReadings(selectedMonitors);

        for (PowerMonitor monitor : selectedMonitors) {
            assertThat(readings.getConsumedEnergyUws(monitor)).isAtLeast(0);
            assertThat(readings.getTimestampMs(monitor)).isGreaterThan(0);
        }
    }
}
