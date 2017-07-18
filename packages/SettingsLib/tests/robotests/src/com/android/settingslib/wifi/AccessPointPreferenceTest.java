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
package com.android.settingslib.wifi;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import com.android.settingslib.SettingLibRobolectricTestRunner;
import com.android.settingslib.TestConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(SettingLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class AccessPointPreferenceTest {

    private Context mContext = RuntimeEnvironment.application;

    @Test
    public void generatePreferenceKey_shouldReturnSsidPlusSecurity() {
        String ssid = "ssid";
        int security = AccessPoint.SECURITY_WEP;
        String expectedKey = ssid + ',' + security;

        TestAccessPointBuilder builder = new TestAccessPointBuilder(mContext);
        builder.setSsid(ssid).setSecurity(security);

        assertThat(AccessPointPreference.generatePreferenceKey(builder.build()))
                .isEqualTo(expectedKey);
    }

    @Test
    public void generatePreferenceKey_shouldReturnBssidPlusSecurity() {
        String bssid = "bssid";
        int security = AccessPoint.SECURITY_WEP;
        String expectedKey = bssid + ',' + security;

        TestAccessPointBuilder builder = new TestAccessPointBuilder(mContext);
        builder.setBssid(bssid).setSecurity(security);

        assertThat(AccessPointPreference.generatePreferenceKey(builder.build()))
                .isEqualTo(expectedKey);
    }

    @Test
    public void refresh_openNetwork_updateContentDescription() {
        final String ssid = "ssid";
        final String summary = "connected";
        final int security = AccessPoint.SECURITY_WEP;
        final AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setSsid(ssid)
                .setSecurity(security)
                .build();
        final AccessPointPreference pref = mock(AccessPointPreference.class);
        when(pref.getTitle()).thenReturn(ssid);
        when(pref.getSummary()).thenReturn(summary);

        assertThat(AccessPointPreference.buildContentDescription(
                RuntimeEnvironment.application, pref, ap))
                .isEqualTo("ssid,connected,Wifi signal full.,Secure network");
    }
}
