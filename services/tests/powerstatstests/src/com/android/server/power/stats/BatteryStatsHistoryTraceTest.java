/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.ParcelFileDescriptor;
import android.perftests.utils.TraceMarkParser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@LargeTest
@android.platform.test.annotations.DisabledOnRavenwood(reason = "Atrace event test")
public class BatteryStatsHistoryTraceTest {
    private static final String ATRACE_START = "atrace --async_start -b 1024 -c ss";
    private static final String ATRACE_STOP = "atrace --async_stop";
    private static final String ATRACE_DUMP = "atrace --async_dump";

    @Before
    public void before() throws Exception {
        runShellCommand(ATRACE_START);
    }

    @After
    public void after() throws Exception {
        runShellCommand(ATRACE_STOP);
    }

    @Test
    public void dumpsys() throws Exception {
        runShellCommand("dumpsys batterystats --history");

        Set<String> slices = readAtraceSlices();
        assertThat(slices).contains("BatteryStatsHistory.copy");
        assertThat(slices).contains("BatteryStatsHistory.iterate");
    }

    @Test
    public void getBatteryUsageStats() throws Exception {
        BatteryStatsManager batteryStatsManager =
                getInstrumentation().getTargetContext().getSystemService(BatteryStatsManager.class);
        BatteryUsageStatsQuery query = new BatteryUsageStatsQuery.Builder()
                .includeBatteryHistory().build();
        BatteryUsageStats batteryUsageStats = batteryStatsManager.getBatteryUsageStats(query);
        assertThat(batteryUsageStats).isNotNull();

        Set<String> slices = readAtraceSlices();
        assertThat(slices).contains("BatteryStatsHistory.copy");
        assertThat(slices).contains("BatteryStatsHistory.iterate");
        assertThat(slices).contains("BatteryStatsHistory.writeToParcel");
    }

    private String runShellCommand(String cmd) throws Exception {
        return UiDevice.getInstance(getInstrumentation()).executeShellCommand(cmd);
    }

    private Set<String> readAtraceSlices() throws Exception {
        Set<String> keys = new HashSet<>();

        TraceMarkParser parser = new TraceMarkParser(
                line -> line.name.startsWith("BatteryStatsHistory."));
        ParcelFileDescriptor pfd =
                getInstrumentation().getUiAutomation().executeShellCommand(ATRACE_DUMP);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.visit(line);
            }
        }
        parser.forAllSlices((key, slices) -> keys.add(key));
        return keys;
    }
}
