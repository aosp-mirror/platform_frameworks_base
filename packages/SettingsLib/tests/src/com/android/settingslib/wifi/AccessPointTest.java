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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiSsid;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.SpannableString;
import android.text.style.TtsSpan;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AccessPointTest {

    private static final String TEST_SSID = "test_ssid";
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void testSsidIsTelephoneSpan() {
        final Bundle bundle = new Bundle();
        bundle.putString("key_ssid", TEST_SSID);
        final AccessPoint ap = new AccessPoint(InstrumentationRegistry.getTargetContext(), bundle);
        final CharSequence ssid = ap.getSsid();

        assertTrue(ssid instanceof SpannableString);

        TtsSpan[] spans = ((SpannableString) ssid).getSpans(0, TEST_SSID.length(), TtsSpan.class);

        assertEquals(1, spans.length);
        assertEquals(TtsSpan.TYPE_TELEPHONE, spans[0].getType());
    }

    @Test
    public void testCopyAccessPoint_dataShouldMatch() {
        WifiConfiguration configuration = createWifiConfiguration();

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 2, "WIFI", "WIFI_SUBTYPE");
        AccessPoint originalAccessPoint = new AccessPoint(mContext, configuration);
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(configuration.SSID));
        wifiInfo.setBSSID(configuration.BSSID);
        originalAccessPoint.update(configuration, wifiInfo, networkInfo);
        AccessPoint copy = new AccessPoint(mContext, originalAccessPoint);

        assertEquals(originalAccessPoint.getSsid().toString(), copy.getSsid().toString());
        assertEquals(originalAccessPoint.getBssid(), copy.getBssid());
        assertSame(originalAccessPoint.getConfig(), copy.getConfig());
        assertEquals(originalAccessPoint.getSecurity(), copy.getSecurity());
        assertTrue(originalAccessPoint.compareTo(copy) == 0);
    }

    @Test
    public void testThatCopyAccessPoint_scanCacheShouldMatch() {
        Bundle bundle = new Bundle();
        ArrayList<ScanResult> scanResults = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ScanResult scanResult = new ScanResult();
            scanResult.level = i;
            scanResult.BSSID = "bssid-" + i;
            scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
            scanResults.add(scanResult);
        }

        bundle.putParcelableArrayList("key_scanresultcache", scanResults);
        AccessPoint original = new AccessPoint(mContext, bundle);
        assertEquals(4, original.getRssi());
        AccessPoint copy = new AccessPoint(mContext, createWifiConfiguration());
        assertEquals(Integer.MIN_VALUE, copy.getRssi());
        copy.copyFrom(original);
        assertEquals(original.getRssi(), copy.getRssi());
    }

    private WifiConfiguration createWifiConfiguration() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.BSSID = "bssid";
        configuration.SSID = "ssid";
        configuration.networkId = 123;
        return configuration;
    }
}
