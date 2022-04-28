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

package com.android.settingslib.wifi;

import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.UserHandle;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WifiStateWorkerTest {

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Spy
    Context mContext = ApplicationProvider.getApplicationContext();
    @Mock
    WifiManager mWifiManager;

    WifiStateWorker mWifiStateWorker;

    @Before
    public void setUp() {
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_ENABLED);
        WifiStateWorker.sWifiManager = mWifiManager;

        mWifiStateWorker = WifiStateWorker.getInstance(mContext);
    }

    @Test
    public void getInstance_diffContext_getSameInstance() {
        Context context = mContext.createContextAsUser(UserHandle.ALL, 0 /* flags */);
        WifiStateWorker instance = WifiStateWorker.getInstance(context);

        assertThat(mContext).isNotEqualTo(context);
        assertThat(mWifiStateWorker).isEqualTo(instance);
    }

    @Test
    public void getWifiState_wifiStateDisabling_returnWifiStateDisabling() {
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_DISABLING);
        WifiStateWorker.refresh();

        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_DISABLING);
    }

    @Test
    public void getWifiState_wifiStateDisabled_returnWifiStateDisabled() {
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_DISABLED);
        WifiStateWorker.refresh();

        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_DISABLED);
    }

    @Test
    public void getWifiState_wifiStateEnabling_returnWifiStateEnabling() {
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_ENABLING);
        WifiStateWorker.refresh();

        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_ENABLING);
    }

    @Test
    public void getWifiState_wifiStateEnabled_returnWifiStateEnabled() {
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_ENABLED);
        WifiStateWorker.refresh();

        assertThat(mWifiStateWorker.getWifiState()).isEqualTo(WIFI_STATE_ENABLED);
    }

    @Test
    public void isWifiEnabled_wifiStateDisabling_returnFalse() {
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_DISABLING);
        WifiStateWorker.refresh();

        assertThat(mWifiStateWorker.isWifiEnabled()).isFalse();
    }

    @Test
    public void isWifiEnabled_wifiStateDisabled_returnFalse() {
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_DISABLED);
        WifiStateWorker.refresh();

        assertThat(mWifiStateWorker.isWifiEnabled()).isFalse();
    }

    @Test
    public void isWifiEnabled_wifiStateEnabling_returnFalse() {
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_ENABLING);
        WifiStateWorker.refresh();

        assertThat(mWifiStateWorker.isWifiEnabled()).isFalse();
    }

    @Test
    public void isWifiEnabled_wifiStateEnabled_returnTrue() {
        when(mWifiManager.getWifiState()).thenReturn(WIFI_STATE_ENABLED);
        WifiStateWorker.refresh();

        assertThat(mWifiStateWorker.isWifiEnabled()).isTrue();
    }
}
