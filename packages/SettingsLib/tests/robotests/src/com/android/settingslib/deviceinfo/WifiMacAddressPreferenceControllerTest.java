/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import static com.google.common.truth.Truth.assertWithMessage;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;

import com.android.settingslib.R;
import com.android.settingslib.SettingsLibRobolectricTestRunner;
import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

@SuppressLint("HardwareIds")
@RunWith(SettingsLibRobolectricTestRunner.class)
public class WifiMacAddressPreferenceControllerTest {
    @Mock
    private Context mContext;
    @Mock
    private Lifecycle mLifecycle;
    @Mock
    private PreferenceScreen mScreen;
    @Mock
    private Preference mPreference;

    private static final String TEST_MAC_ADDRESS = "00:11:22:33:44:55";

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mPreference).when(mScreen)
                .findPreference(AbstractWifiMacAddressPreferenceController.KEY_WIFI_MAC_ADDRESS);
    }

    @Test
    public void testHasIntentFilters() {
        final AbstractWifiMacAddressPreferenceController wifiMacAddressPreferenceController =
                new ConcreteWifiMacAddressPreferenceController(mContext, mLifecycle);
        final List<String> expectedIntents = Arrays.asList(
                ConnectivityManager.CONNECTIVITY_ACTION,
                WifiManager.LINK_CONFIGURATION_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION);


        assertWithMessage("Intent filter should contain expected intents")
                .that(wifiMacAddressPreferenceController.getConnectivityIntents())
                .asList().containsAllIn(expectedIntents);
    }

    @Test
    public void testWifiMacAddress() {
        final WifiManager wifiManager = mock(WifiManager.class);
        final WifiInfo wifiInfo = mock(WifiInfo.class);

        doReturn(null).when(wifiManager).getConnectionInfo();
        doReturn(wifiManager).when(mContext).getSystemService(WifiManager.class);

        final AbstractWifiMacAddressPreferenceController wifiMacAddressPreferenceController =
                new ConcreteWifiMacAddressPreferenceController(mContext, mLifecycle);

        wifiMacAddressPreferenceController.displayPreference(mScreen);
        verify(mPreference).setSummary(R.string.status_unavailable);

        doReturn(wifiInfo).when(wifiManager).getConnectionInfo();
        doReturn(TEST_MAC_ADDRESS).when(wifiInfo).getMacAddress();
        wifiMacAddressPreferenceController.displayPreference(mScreen);
        verify(mPreference).setSummary(TEST_MAC_ADDRESS);

        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED, 1);
        wifiMacAddressPreferenceController.displayPreference(mScreen);
        verify(mPreference, times(2)).setSummary(TEST_MAC_ADDRESS);

        doReturn(WifiInfo.DEFAULT_MAC_ADDRESS).when(wifiInfo).getMacAddress();
        wifiMacAddressPreferenceController.displayPreference(mScreen);
        verify(mPreference).setSummary(R.string.wifi_status_mac_randomized);
    }

    private static class ConcreteWifiMacAddressPreferenceController
            extends AbstractWifiMacAddressPreferenceController {

        public ConcreteWifiMacAddressPreferenceController(Context context,
                Lifecycle lifecycle) {
            super(context, lifecycle);
        }
    }
}
