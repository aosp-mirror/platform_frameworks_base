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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WifiStatusTrackerTest {
    @Mock Context mContext;
    @Mock WifiManager mWifiManager;
    @Mock NetworkScoreManager mNetworkScoreManager;
    @Mock ConnectivityManager mConnectivityManager;
    @Mock Runnable mCallback;

    private final ArgumentCaptor<ConnectivityManager.NetworkCallback>
            mNetworkCallbackCaptor =
            ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Verify that we only clear the WifiInfo if the primary network was lost.
     */
    @Test
    public void testWifiInfoClearedOnPrimaryNetworkLost() {
        WifiStatusTracker wifiStatusTracker = new WifiStatusTracker(mContext, mWifiManager,
                mNetworkScoreManager, mConnectivityManager, mCallback);
        wifiStatusTracker.setListening(true);

        verify(mConnectivityManager)
                .registerNetworkCallback(any(), mNetworkCallbackCaptor.capture(), any());

        // Trigger a validation callback for the primary Wifi network.
        WifiInfo primaryWifiInfo = Mockito.mock(WifiInfo.class);
        when(primaryWifiInfo.makeCopy(anyLong())).thenReturn(primaryWifiInfo);
        when(primaryWifiInfo.isPrimary()).thenReturn(true);
        int primaryRssi = -55;
        when(primaryWifiInfo.getRssi()).thenReturn(primaryRssi);
        NetworkCapabilities primaryCap = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setTransportInfo(primaryWifiInfo)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);
        mNetworkCallbackCaptor.getValue().onCapabilitiesChanged(primaryNetwork, primaryCap);

        // Verify primary wifi info is the one being used.
        assertThat(wifiStatusTracker.connected).isTrue();
        assertThat(wifiStatusTracker.rssi).isEqualTo(primaryRssi);

        // Trigger a validation callback for the non-primary Wifi network.
        WifiInfo nonPrimaryWifiInfo = Mockito.mock(WifiInfo.class);
        when(nonPrimaryWifiInfo.makeCopy(anyLong())).thenReturn(nonPrimaryWifiInfo);
        when(nonPrimaryWifiInfo.isPrimary()).thenReturn(false);
        int nonPrimaryRssi = -75;
        when(primaryWifiInfo.getRssi()).thenReturn(nonPrimaryRssi);
        NetworkCapabilities nonPrimaryCap = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setTransportInfo(nonPrimaryWifiInfo)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        Network nonPrimaryNetwork = Mockito.mock(Network.class);
        int nonPrimaryNetworkId = 2;
        when(nonPrimaryNetwork.getNetId()).thenReturn(nonPrimaryNetworkId);
        mNetworkCallbackCaptor.getValue().onCapabilitiesChanged(nonPrimaryNetwork, nonPrimaryCap);

        // Verify primary wifi info is still the one being used.
        assertThat(wifiStatusTracker.connected).isTrue();
        assertThat(wifiStatusTracker.rssi).isEqualTo(primaryRssi);

        // Lose the non-primary network.
        mNetworkCallbackCaptor.getValue().onLost(nonPrimaryNetwork);

        // Verify primary wifi info is still the one being used.
        assertThat(wifiStatusTracker.connected).isTrue();
        assertThat(wifiStatusTracker.rssi).isEqualTo(primaryRssi);

        // Lose the primary network.
        mNetworkCallbackCaptor.getValue().onLost(primaryNetwork);

        // Verify we aren't connected anymore.
        assertThat(wifiStatusTracker.connected).isFalse();
    }
}
