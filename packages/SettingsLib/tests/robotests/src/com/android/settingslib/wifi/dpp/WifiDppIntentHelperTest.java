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

package com.android.settingslib.wifi.dpp;

import static com.android.settingslib.wifi.dpp.WifiDppIntentHelper.EXTRA_WIFI_HIDDEN_SSID;
import static com.android.settingslib.wifi.dpp.WifiDppIntentHelper.EXTRA_WIFI_PRE_SHARED_KEY;
import static com.android.settingslib.wifi.dpp.WifiDppIntentHelper.EXTRA_WIFI_SECURITY;
import static com.android.settingslib.wifi.dpp.WifiDppIntentHelper.EXTRA_WIFI_SSID;
import static com.android.settingslib.wifi.dpp.WifiDppIntentHelper.SECURITY_NO_PASSWORD;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
public class WifiDppIntentHelperTest {
    @Mock
    private WifiManager mWifiManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mWifiManager.getPrivilegedConfiguredNetworks()).thenReturn(new ArrayList<>());
    }

    @Test
    public void setConfiguratorIntentExtra_returnsCorrectValues() {
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.SSID = EXTRA_WIFI_SSID;
        wifiConfiguration.preSharedKey = EXTRA_WIFI_PRE_SHARED_KEY;
        wifiConfiguration.hiddenSSID = true;

        Intent expected = new Intent();
        WifiDppIntentHelper.setConfiguratorIntentExtra(expected, mWifiManager, wifiConfiguration);

        assertThat(expected.getStringExtra(EXTRA_WIFI_SSID)).isEqualTo(EXTRA_WIFI_SSID);
        assertThat(expected.getStringExtra(EXTRA_WIFI_SECURITY)).isEqualTo(SECURITY_NO_PASSWORD);
        assertThat(expected.getStringExtra(EXTRA_WIFI_PRE_SHARED_KEY)).isEqualTo(
                EXTRA_WIFI_PRE_SHARED_KEY);
        assertThat(expected.getBooleanExtra(EXTRA_WIFI_HIDDEN_SSID, false))
                .isEqualTo(true);
    }
}
