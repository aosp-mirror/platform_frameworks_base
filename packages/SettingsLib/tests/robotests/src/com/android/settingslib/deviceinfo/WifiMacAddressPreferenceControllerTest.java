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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.R;
import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.List;

@SuppressLint("HardwareIds")
@RunWith(RobolectricTestRunner.class)
public class WifiMacAddressPreferenceControllerTest {
    @Mock
    private Lifecycle mLifecycle;
    @Mock
    private PreferenceScreen mScreen;
    @Mock
    private WifiManager mWifiManager;
    @Mock
    private WifiInfo mWifiInfo;

    private AbstractWifiMacAddressPreferenceController mController;
    private Context mContext;
    private Preference mPreference;

    private static final String TEST_MAC_ADDRESS = "00:11:22:33:44:55";

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = spy(RuntimeEnvironment.application);
        mPreference = new Preference(mContext);

        doReturn(mPreference).when(mScreen)
                .findPreference(AbstractWifiMacAddressPreferenceController.KEY_WIFI_MAC_ADDRESS);
        doReturn(mWifiManager).when(mContext).getSystemService(WifiManager.class);
        doReturn(mWifiInfo).when(mWifiManager).getConnectionInfo();

        mController = new ConcreteWifiMacAddressPreferenceController(mContext, mLifecycle);
    }

    @Test
    public void testHasIntentFilters() {
        final List<String> expectedIntents = Arrays.asList(
                ConnectivityManager.CONNECTIVITY_ACTION,
                WifiManager.LINK_CONFIGURATION_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION);


        assertWithMessage("Intent filter should contain expected intents")
                .that(mController.getConnectivityIntents())
                .asList().containsAllIn(expectedIntents);
    }

    @Test
    public void updateConnectivity_null_setMacUnavailable() {
        doReturn(null).when(mWifiManager).getFactoryMacAddresses();
        mController.displayPreference(mScreen);
        assertThat(mPreference.getSummary())
                .isEqualTo(mContext.getString(R.string.status_unavailable));
    }

    @Test
    public void updateConnectivity_validMac_setValidMac() {
        final String[] macAddresses = new String[]{TEST_MAC_ADDRESS};
        doReturn(macAddresses).when(mWifiManager).getFactoryMacAddresses();
        mController.displayPreference(mScreen);
        assertThat(mPreference.getSummary()).isEqualTo(TEST_MAC_ADDRESS);




    }

    private static class ConcreteWifiMacAddressPreferenceController
            extends AbstractWifiMacAddressPreferenceController {

        private ConcreteWifiMacAddressPreferenceController(Context context,
                Lifecycle lifecycle) {
            super(context, lifecycle);
        }
    }
}
