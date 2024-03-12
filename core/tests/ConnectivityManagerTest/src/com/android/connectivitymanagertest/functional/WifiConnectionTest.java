/*
 * Copyright (C) 2010, The Android Open Source Project
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

package com.android.connectivitymanagertest.functional;

import android.net.wifi.WifiConfiguration;
import android.os.SystemClock;

import androidx.test.filters.LargeTest;

import com.android.connectivitymanagertest.ConnectivityManagerTestBase;
import com.android.connectivitymanagertest.WifiConfigurationHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

/**
 * Test Wi-Fi connection with different configuration
 * To run this tests:
 *     adb shell am instrument \
 *         -e class com.android.connectivitymanagertest.functional.WifiConnectionTest \
 *         -w com.android.connectivitymanagertest/.ConnectivityManagerTestRunner
 */
public class WifiConnectionTest extends ConnectivityManagerTestBase {
    private static final String WIFI_CONFIG_FILE = "/data/wifi_configs.json";
    private static final long PAUSE_DURATION_MS = 60 * 1000;

    public WifiConnectionTest() {
        super(WifiConnectionTest.class.getSimpleName());
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        assertTrue("Failed to enable wifi", enableWifi());
    }

    @Override
    public void tearDown() throws Exception {
        removeConfiguredNetworksAndDisableWifi();
        super.tearDown();
    }

    @LargeTest
    public void testWifiConnections() {
        List<WifiConfiguration> wifiConfigs = loadConfigurations();

        printWifiConfigurations(wifiConfigs);

        assertFalse("No configurations to test against", wifiConfigs.isEmpty());

        boolean shouldPause = false;
        for (WifiConfiguration config : wifiConfigs) {
            if (shouldPause) {
                logv("Pausing for %d seconds", PAUSE_DURATION_MS / 1000);
                SystemClock.sleep(PAUSE_DURATION_MS);
            }
            logv("Start wifi connection test to: %s", config.SSID);
            connectToWifi(config);

            // verify that connection actually works
            assertTrue("No connectivity at end of test", checkNetworkConnectivity());

            // Disconnect and remove the network
            assertTrue("Unable to remove network", disconnectAP());
            logv("End wifi connection test to: %s", config.SSID);

            shouldPause = true;
        }
    }

    /**
     * Load the configuration file from the root of the data partition
     */
    private List<WifiConfiguration> loadConfigurations() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(new File(WIFI_CONFIG_FILE)));
            StringBuffer jsonBuffer = new StringBuffer();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuffer.append(line);
            }
            return WifiConfigurationHelper.parseJson(jsonBuffer.toString());
        } catch (IllegalArgumentException | IOException e) {
            throw new AssertionError("Error parsing file", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Print the wifi configurations to test against.
     */
    private void printWifiConfigurations(List<WifiConfiguration> wifiConfigs) {
        logv("Wifi configurations to be tested");
        for (WifiConfiguration config : wifiConfigs) {
            logv(config.toString());
        }
    }
}
