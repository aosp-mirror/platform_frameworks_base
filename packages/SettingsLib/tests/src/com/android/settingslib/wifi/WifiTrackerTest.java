/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.settingslib.wifi;


import static org.junit.Assert.assertTrue;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class WifiTrackerTest {

    @Test
    public void testAccessPointListenerSetWhenLookingUpUsingScanResults() {
        ScanResult scanResult = new ScanResult();
        scanResult.level = 123;
        scanResult.BSSID = "bssid-" + 111;
        scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
        scanResult.capabilities = "";

        WifiTracker tracker = new WifiTracker(
                InstrumentationRegistry.getTargetContext(), null, true, true);

        AccessPoint result = tracker.getCachedOrCreate(scanResult, new ArrayList<AccessPoint>());
        assertTrue(result.mAccessPointListener != null);
    }

    @Test
    public void testAccessPointListenerSetWhenLookingUpUsingWifiConfiguration() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = "test123";
        configuration.BSSID="bssid";
        configuration.networkId = 123;
        configuration.allowedKeyManagement = new BitSet();
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);

        WifiTracker tracker = new WifiTracker(
                InstrumentationRegistry.getTargetContext(), null, true, true);

        AccessPoint result = tracker.getCachedOrCreate(configuration, new ArrayList<AccessPoint>());
        assertTrue(result.mAccessPointListener != null);
    }
}
