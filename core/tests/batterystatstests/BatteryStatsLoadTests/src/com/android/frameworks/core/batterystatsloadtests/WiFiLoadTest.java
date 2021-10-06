/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.frameworks.core.batterystatsloadtests;

import android.os.BatteryConsumer;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class WiFiLoadTest {
    private static final String TAG = "WiFiLoadTest";
    private static final String DOWNLOAD_TEST_URL =
            "https://i.ytimg.com/vi/l5mE3Tpjejs/maxresdefault.jpg";

    private static final int TIMEOUT_MILLIS = 60 * 60 * 1000;
    private static final float BATTERY_DRAIN_THRESHOLD_PCT = 0.99f;

    @Rule
    public PowerMetricsCollector mPowerMetricsCollector = new PowerMetricsCollector(TAG,
            BATTERY_DRAIN_THRESHOLD_PCT, TIMEOUT_MILLIS);

    @Rule
    public ConnectivitySetupRule mConnectivitySetupRule =
            new ConnectivitySetupRule(/* WiFi enabled */true);

    @Test
    public void test() throws IOException {
        long totalBytesRead = 0;
        URL url = new URL(DOWNLOAD_TEST_URL);
        byte[] buffer = new byte[131072];  // Large buffer to minimize CPU usage

        while (mPowerMetricsCollector.checkpoint()) {
            try (InputStream inputStream = url.openStream()) {
                while (true) {
                    int count = inputStream.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    totalBytesRead += count;
                }
            }
        }

        mPowerMetricsCollector.reportMetrics();

        mPowerMetricsCollector.report(
                "WiFi running time: " + (long) mPowerMetricsCollector.getTimeMetric(
                        BatteryConsumer.POWER_COMPONENT_WIFI).value);

        mPowerMetricsCollector.report("Total bytes read over WiFi: " + totalBytesRead);

        mPowerMetricsCollector.reportMetricAsPercentageOfDrainedPower(
                        BatteryConsumer.POWER_COMPONENT_WIFI);
    }
}
