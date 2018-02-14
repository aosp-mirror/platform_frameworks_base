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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;

import com.android.settingslib.SettingsLibRobolectricTestRunner;
import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class IpAddressPreferenceControllerTest {
    @Mock
    private Context mContext;
    @Mock
    private Lifecycle mLifecycle;
    @Mock
    private PreferenceScreen mScreen;
    @Mock
    private Preference mPreference;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mPreference).when(mScreen)
                .findPreference(AbstractIpAddressPreferenceController.KEY_IP_ADDRESS);
    }

    @Test
    public void testHasIntentFilters() {
        final AbstractIpAddressPreferenceController ipAddressPreferenceController =
                new ConcreteIpAddressPreferenceController(mContext, mLifecycle);
        final List<String> expectedIntents = Arrays.asList(
                ConnectivityManager.CONNECTIVITY_ACTION,
                WifiManager.LINK_CONFIGURATION_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION);


        assertWithMessage("Intent filter should contain expected intents")
                .that(ipAddressPreferenceController.getConnectivityIntents())
                .asList().containsAllIn(expectedIntents);
    }

    private static class ConcreteIpAddressPreferenceController extends
            AbstractIpAddressPreferenceController {

        public ConcreteIpAddressPreferenceController(Context context,
                Lifecycle lifecycle) {
            super(context, lifecycle);
        }
    }
}
