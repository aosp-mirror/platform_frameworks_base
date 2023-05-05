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

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class WifiStatusTrackerTest {
    @Mock Context mContext;
    @Mock WifiManager mWifiManager;
    @Mock NetworkScoreManager mNetworkScoreManager;
    @Mock ConnectivityManager mConnectivityManager;
    @Mock Runnable mCallback;

    private WifiStatusTracker mWifiStatusTracker;

    private final ArgumentCaptor<ConnectivityManager.NetworkCallback>
            mNetworkCallbackCaptor =
            ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);

    private final ArgumentCaptor<ConnectivityManager.NetworkCallback>
            mDefaultNetworkCallbackCaptor =
            ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mWifiStatusTracker = new WifiStatusTracker(
                mContext,
                mWifiManager,
                mNetworkScoreManager,
                mConnectivityManager,
                mCallback);
        mWifiStatusTracker.setListening(true);

        verify(mConnectivityManager)
                .registerNetworkCallback(any(), mNetworkCallbackCaptor.capture(), any());
        verify(mConnectivityManager)
                .registerDefaultNetworkCallback(mDefaultNetworkCallbackCaptor.capture(), any());
    }

    /**
     * Verify that we only clear the WifiInfo if the primary network was lost.
     */
    @Test
    public void testWifiInfoClearedOnPrimaryNetworkLost() {
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
        assertThat(mWifiStatusTracker.connected).isTrue();
        assertThat(mWifiStatusTracker.rssi).isEqualTo(primaryRssi);

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
        assertThat(mWifiStatusTracker.connected).isTrue();
        assertThat(mWifiStatusTracker.rssi).isEqualTo(primaryRssi);

        // Lose the non-primary network.
        mNetworkCallbackCaptor.getValue().onLost(nonPrimaryNetwork);

        // Verify primary wifi info is still the one being used.
        assertThat(mWifiStatusTracker.connected).isTrue();
        assertThat(mWifiStatusTracker.rssi).isEqualTo(primaryRssi);

        // Lose the primary network.
        mNetworkCallbackCaptor.getValue().onLost(primaryNetwork);

        // Verify we aren't connected anymore.
        assertThat(mWifiStatusTracker.connected).isFalse();
    }

    @Test
    public void isCarrierMerged_typicalWifi_false() {
        WifiInfo primaryWifiInfo = Mockito.mock(WifiInfo.class);
        when(primaryWifiInfo.isPrimary()).thenReturn(true);

        NetworkCapabilities primaryCap = Mockito.mock(NetworkCapabilities.class);
        when(primaryCap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true);
        when(primaryCap.getTransportInfo()).thenReturn(primaryWifiInfo);

        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);

        mNetworkCallbackCaptor.getValue().onCapabilitiesChanged(primaryNetwork, primaryCap);

        assertThat(mWifiStatusTracker.isCarrierMerged).isFalse();
    }

    @Test
    public void isCarrierMerged_typicalCellular_false() {
        NetworkCapabilities primaryCap = Mockito.mock(NetworkCapabilities.class);
        when(primaryCap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(true);

        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);

        mNetworkCallbackCaptor.getValue().onCapabilitiesChanged(primaryNetwork, primaryCap);

        assertThat(mWifiStatusTracker.isCarrierMerged).isFalse();
    }

    @Test
    public void isCarrierMerged_cellularCarrierMergedWifi_true() {
        WifiInfo primaryWifiInfo = Mockito.mock(WifiInfo.class);
        when(primaryWifiInfo.isPrimary()).thenReturn(true);
        when(primaryWifiInfo.isCarrierMerged()).thenReturn(true);

        NetworkCapabilities primaryCap = Mockito.mock(NetworkCapabilities.class);
        when(primaryCap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(true);
        when(primaryCap.getTransportInfo()).thenReturn(primaryWifiInfo);

        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);

        mNetworkCallbackCaptor.getValue().onCapabilitiesChanged(primaryNetwork, primaryCap);

        assertThat(mWifiStatusTracker.isCarrierMerged).isTrue();
    }

    /** Test for b/225902574. */
    @Test
    public void isCarrierMerged_cellularWithUnderlyingCarrierMergedWifi_true() {
        WifiInfo underlyingCarrierMergedInfo = Mockito.mock(WifiInfo.class);
        when(underlyingCarrierMergedInfo.isPrimary()).thenReturn(true);
        when(underlyingCarrierMergedInfo.isCarrierMerged()).thenReturn(true);

        NetworkCapabilities underlyingNetworkCapabilities = Mockito.mock(NetworkCapabilities.class);
        when(underlyingNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(true);
        when(underlyingNetworkCapabilities.getTransportInfo())
                .thenReturn(underlyingCarrierMergedInfo);

        Network underlyingNetwork = Mockito.mock(Network.class);
        when(mConnectivityManager.getNetworkCapabilities(underlyingNetwork))
                .thenReturn(underlyingNetworkCapabilities);

        NetworkCapabilities mainCapabilities = Mockito.mock(NetworkCapabilities.class);
        when(mainCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(true);
        when(mainCapabilities.getTransportInfo()).thenReturn(null);
        when(mainCapabilities.getUnderlyingNetworks())
                .thenReturn(Arrays.asList(underlyingNetwork));

        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);

        mNetworkCallbackCaptor.getValue().onCapabilitiesChanged(primaryNetwork, mainCapabilities);

        assertThat(mWifiStatusTracker.isCarrierMerged).isTrue();
    }

    @Test
    public void isDefaultNetwork_typicalWifi_true() {
        WifiInfo primaryWifiInfo = Mockito.mock(WifiInfo.class);
        when(primaryWifiInfo.isPrimary()).thenReturn(true);

        NetworkCapabilities primaryCap = Mockito.mock(NetworkCapabilities.class);
        when(primaryCap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true);
        when(primaryCap.getTransportInfo()).thenReturn(primaryWifiInfo);

        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);

        mDefaultNetworkCallbackCaptor.getValue().onCapabilitiesChanged(primaryNetwork, primaryCap);

        assertThat(mWifiStatusTracker.isDefaultNetwork).isTrue();
    }

    @Test
    public void isDefaultNetwork_typicalCellular_false() {
        NetworkCapabilities primaryCap = Mockito.mock(NetworkCapabilities.class);
        when(primaryCap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(true);

        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);

        mDefaultNetworkCallbackCaptor.getValue().onCapabilitiesChanged(primaryNetwork, primaryCap);

        assertThat(mWifiStatusTracker.isDefaultNetwork).isFalse();
    }

    @Test
    public void isDefaultNetwork_cellularCarrierMergedWifi_true() {
        WifiInfo primaryWifiInfo = Mockito.mock(WifiInfo.class);
        when(primaryWifiInfo.isPrimary()).thenReturn(true);
        when(primaryWifiInfo.isCarrierMerged()).thenReturn(true);

        NetworkCapabilities primaryCap = Mockito.mock(NetworkCapabilities.class);
        when(primaryCap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(true);
        when(primaryCap.getTransportInfo()).thenReturn(primaryWifiInfo);

        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);

        mDefaultNetworkCallbackCaptor.getValue().onCapabilitiesChanged(primaryNetwork, primaryCap);

        assertThat(mWifiStatusTracker.isDefaultNetwork).isTrue();
    }

    /** Test for b/225902574. */
    @Test
    public void isDefaultNetwork_cellularWithUnderlyingCarrierMergedWifi_true() {
        WifiInfo underlyingCarrierMergedInfo = Mockito.mock(WifiInfo.class);
        when(underlyingCarrierMergedInfo.isPrimary()).thenReturn(true);
        when(underlyingCarrierMergedInfo.isCarrierMerged()).thenReturn(true);

        NetworkCapabilities underlyingNetworkCapabilities = Mockito.mock(NetworkCapabilities.class);
        when(underlyingNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(true);
        when(underlyingNetworkCapabilities.getTransportInfo())
                .thenReturn(underlyingCarrierMergedInfo);

        Network underlyingNetwork = Mockito.mock(Network.class);
        when(mConnectivityManager.getNetworkCapabilities(underlyingNetwork))
                .thenReturn(underlyingNetworkCapabilities);

        NetworkCapabilities mainCapabilities = Mockito.mock(NetworkCapabilities.class);
        when(mainCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(true);
        when(mainCapabilities.getTransportInfo()).thenReturn(null);
        when(mainCapabilities.getUnderlyingNetworks())
                .thenReturn(Arrays.asList(underlyingNetwork));

        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);

        mDefaultNetworkCallbackCaptor.getValue()
                .onCapabilitiesChanged(primaryNetwork, mainCapabilities);

        assertThat(mWifiStatusTracker.isDefaultNetwork).isTrue();
    }

    /** Regression test for b/280169520. */
    @Test
    public void networkCallbackNullCapabilities_noCrash() {
        Network primaryNetwork = Mockito.mock(Network.class);

        // WHEN the network capabilities are null
        mNetworkCallbackCaptor.getValue().onCapabilitiesChanged(
                primaryNetwork, /* networkCapabilities= */ null);

        // THEN there's no crash (no assert needed)
    }
}
