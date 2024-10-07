/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.connectivity;

import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.vcn.VcnTransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.settingslib.mobile.TelephonyIcons;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Collections;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper
public class NetworkControllerWifiTest extends NetworkControllerBaseTest {
    // These match the constants in WifiManager and need to be kept up to date.
    private static final int MIN_RSSI = -100;
    private static final int MAX_RSSI = -55;
    private WifiInfo mWifiInfo = mock(WifiInfo.class);
    private VcnTransportInfo mVcnTransportInfo = mock(VcnTransportInfo.class);

    @Before
    public void setUp() throws Exception {
        super.setUp();
        allowTestableLooperAsMainThread();
        when(mWifiInfo.makeCopy(anyLong())).thenReturn(mWifiInfo);
        when(mWifiInfo.isPrimary()).thenReturn(true);
    }

    @Test
    public void testWifiIcon() {
        String testSsid = "Test SSID";
        setWifiEnabled(true);
        verifyLastWifiIcon(false, WifiIcons.WIFI_NO_NETWORK);

        setWifiState(true, testSsid);
        setWifiLevel(0);

        // Connected, but still not validated - does not show
        verifyLastWifiIcon(false, WifiIcons.WIFI_SIGNAL_STRENGTH[0][0]);

        for (int testLevel = 0; testLevel < WifiIcons.WIFI_LEVEL_COUNT; testLevel++) {
            setWifiLevel(testLevel);

            setConnectivityViaCallbackInNetworkController(
                    NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
            verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[1][testLevel]);
            setConnectivityViaCallbackInNetworkController(
                    NetworkCapabilities.TRANSPORT_WIFI, false, true, mWifiInfo);
            // Icon does not show if not validated
            verifyLastWifiIcon(false, WifiIcons.WIFI_SIGNAL_STRENGTH[0][testLevel]);
        }
    }

    @Test
    public void testQsWifiIcon() {
        String testSsid = "Test SSID";

        setWifiEnabled(false);
        verifyLastQsWifiIcon(false, false, WifiIcons.QS_WIFI_NO_NETWORK, null);

        setWifiEnabled(true);
        verifyLastQsWifiIcon(true, false, WifiIcons.QS_WIFI_NO_NETWORK, null);

        setWifiState(true, testSsid);
        for (int testLevel = 0; testLevel < WifiIcons.WIFI_LEVEL_COUNT; testLevel++) {
            setWifiLevel(testLevel);
            setConnectivityViaCallbackInNetworkController(
                    NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
            setConnectivityViaDefaultCallbackInWifiTracker(
                    NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
            verifyLastQsWifiIcon(true, true, WifiIcons.QS_WIFI_SIGNAL_STRENGTH[1][testLevel],
                    testSsid);
            setConnectivityViaCallbackInNetworkController(
                    NetworkCapabilities.TRANSPORT_WIFI, false, true, mWifiInfo);
            verifyLastQsWifiIcon(true, true, WifiIcons.QS_WIFI_SIGNAL_STRENGTH[0][testLevel],
                    testSsid);
        }
    }

    @Test
    public void testQsDataDirection() {
        // Setup normal connection
        String testSsid = "Test SSID";
        int testLevel = 2;
        setWifiEnabled(true);
        setWifiState(true, testSsid);
        setWifiLevel(testLevel);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
        setConnectivityViaDefaultCallbackInWifiTracker(
                NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
        verifyLastQsWifiIcon(true, true,
                WifiIcons.QS_WIFI_SIGNAL_STRENGTH[1][testLevel], testSsid);

        // Set to different activity state first to ensure a callback happens.
        setWifiActivity(WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN);

        setWifiActivity(WifiManager.TrafficStateCallback.DATA_ACTIVITY_NONE);
        verifyLastQsDataDirection(false, false);
        setWifiActivity(WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN);
        verifyLastQsDataDirection(true, false);
        setWifiActivity(WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT);
        verifyLastQsDataDirection(false, true);
        setWifiActivity(WifiManager.TrafficStateCallback.DATA_ACTIVITY_INOUT);
        verifyLastQsDataDirection(true, true);
    }

    @Test
    public void testRoamingIconDuringWifi() {
        // Setup normal connection
        String testSsid = "Test SSID";
        int testLevel = 2;
        setWifiEnabled(true);
        setWifiState(true, testSsid);
        setWifiLevel(testLevel);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
        verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[1][testLevel]);

        setupDefaultSignal();
        setGsmRoaming(true);
        // Still be on wifi though.
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_CELLULAR, false, false, null);
        verifyLastMobileDataIndicators(true, DEFAULT_LEVEL, 0, true);
    }

    @Test
    public void testWifiIconInvalidatedViaCallback() {
        // Setup normal connection
        String testSsid = "Test SSID";
        int testLevel = 2;
        setWifiEnabled(true);
        setWifiState(true, testSsid);
        setWifiLevel(testLevel);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
        verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[1][testLevel]);

        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_WIFI, false, true, mWifiInfo);
        verifyLastWifiIcon(false, WifiIcons.WIFI_SIGNAL_STRENGTH[0][testLevel]);
    }

    @Test
    public void testWifiIconDisconnectedViaCallback() {
        // Setup normal connection
        String testSsid = "Test SSID";
        int testLevel = 2;
        setWifiEnabled(true);
        setWifiState(true, testSsid);
        setWifiLevel(testLevel);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
        verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[1][testLevel]);

        setWifiState(false, testSsid);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_WIFI, false, false, mWifiInfo);
        verifyLastWifiIcon(false, WifiIcons.WIFI_NO_NETWORK);
    }

    @Test
    public void testVpnWithUnderlyingWifi() {
        String testSsid = "Test SSID";
        int testLevel = 2;
        setWifiEnabled(true);
        verifyLastWifiIcon(false, WifiIcons.WIFI_NO_NETWORK);

        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_VPN, false, true, mWifiInfo);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_VPN, true, true, mWifiInfo);
        verifyLastWifiIcon(false, WifiIcons.WIFI_NO_NETWORK);

        // Mock calling setUnderlyingNetworks.
        setWifiState(true, testSsid);
        setWifiLevel(testLevel);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_WIFI, true, true, mWifiInfo);
        verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[1][testLevel]);
    }

    @Test
    public void testFetchInitialData() {
        mNetworkController.mWifiSignalController.fetchInitialState();
        Mockito.verify(mMockWm).getWifiState();
        Mockito.verify(mMockCm).getNetworkInfo(ConnectivityManager.TYPE_WIFI);
    }

    @Test
    public void testFetchInitialData_correctValues() {
        String testSsid = "TEST";

        when(mMockWm.getWifiState()).thenReturn(WifiManager.WIFI_STATE_ENABLED);
        NetworkInfo networkInfo = mock(NetworkInfo.class);
        when(networkInfo.isConnected()).thenReturn(true);
        when(mMockCm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)).thenReturn(networkInfo);
        WifiInfo wifiInfo = mock(WifiInfo.class);
        when(wifiInfo.getSSID()).thenReturn(testSsid);
        when(mMockWm.getConnectionInfo()).thenReturn(wifiInfo);

        mNetworkController.mWifiSignalController.fetchInitialState();

        assertTrue(mNetworkController.mWifiSignalController.mCurrentState.enabled);
        assertTrue(mNetworkController.mWifiSignalController.mCurrentState.connected);
        assertEquals(testSsid, mNetworkController.mWifiSignalController.mCurrentState.ssid);
    }

    private Network newWifiNetwork(WifiInfo wifiInfo) {
        final Network network = mock(Network.class);
        final NetworkCapabilities wifiCaps =
                new NetworkCapabilities.Builder()
                        .addTransportType(TRANSPORT_WIFI)
                        .setTransportInfo(wifiInfo)
                        .build();
        when(mMockCm.getNetworkCapabilities(network)).thenReturn(wifiCaps);
        return network;
    }

    @Test
    public void testVcnWithUnderlyingWifi() {
        String testSsid = "Test VCN SSID";
        setWifiEnabled(true);
        verifyLastWifiIcon(false, WifiIcons.WIFI_NO_NETWORK);

        mNetworkController.setNoNetworksAvailable(false);
        setWifiStateForVcn(true, testSsid);
        setWifiLevelForVcn(0);
        verifyLastMobileDataIndicatorsForVcn(true, 0, TelephonyIcons.ICON_CWF, false);

        mNetworkController.setNoNetworksAvailable(true);
        for (int testLevel = 0; testLevel < WifiIcons.WIFI_LEVEL_COUNT; testLevel++) {
            setWifiLevelForVcn(testLevel);

            setConnectivityViaCallbackInNetworkControllerForVcn(
                    NetworkCapabilities.TRANSPORT_CELLULAR,
                    true,
                    true,
                    mVcnTransportInfo,
                    newWifiNetwork(mWifiInfo));
            verifyLastMobileDataIndicatorsForVcn(true, testLevel, TelephonyIcons.ICON_CWF, true);

            setConnectivityViaCallbackInNetworkControllerForVcn(
                    NetworkCapabilities.TRANSPORT_CELLULAR,
                    false,
                    true,
                    mVcnTransportInfo,
                    newWifiNetwork(mWifiInfo));
            verifyLastMobileDataIndicatorsForVcn(true, testLevel, TelephonyIcons.ICON_CWF, false);
        }
    }

    /** Test for b/225902574. */
    @Test
    public void vcnOnlyOnUnderlyingNetwork() {
        setWifiEnabled(true);

        // Set up a carrier merged network...
        WifiInfo underlyingCarrierMergedInfo = Mockito.mock(WifiInfo.class);
        when(underlyingCarrierMergedInfo.isCarrierMerged()).thenReturn(true);
        when(underlyingCarrierMergedInfo.isPrimary()).thenReturn(true);
        int zeroLevel = 0;
        when(underlyingCarrierMergedInfo.getRssi()).thenReturn(calculateRssiForLevel(zeroLevel));

        NetworkCapabilities underlyingNetworkCapabilities = Mockito.mock(NetworkCapabilities.class);
        when(underlyingNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(true);
        when(underlyingNetworkCapabilities.getTransportInfo())
                .thenReturn(underlyingCarrierMergedInfo);

        Network underlyingNetwork = Mockito.mock(Network.class);
        when(mMockCm.getNetworkCapabilities(underlyingNetwork))
                .thenReturn(underlyingNetworkCapabilities);

        NetworkCapabilities.Builder mainCapabilitiesBuilder = new NetworkCapabilities.Builder();
        mainCapabilitiesBuilder.addTransportType(TRANSPORT_CELLULAR);
        mainCapabilitiesBuilder.setTransportInfo(null);
        // And make the carrier merged network the underlying network, *not* the main network.
        mainCapabilitiesBuilder.setUnderlyingNetworks(Collections.singletonList(underlyingNetwork));

        Network primaryNetwork = Mockito.mock(Network.class);
        int primaryNetworkId = 1;
        when(primaryNetwork.getNetId()).thenReturn(primaryNetworkId);

        // WHEN this primary network with underlying carrier merged information is sent
        setConnectivityViaDefaultAndNormalCallbackInWifiTracker(
                primaryNetwork, mainCapabilitiesBuilder.build());

        // THEN we see the mobile data indicators for carrier merged
        verifyLastMobileDataIndicatorsForVcn(
                /* visible= */ true,
                /* level= */ zeroLevel,
                TelephonyIcons.ICON_CWF,
                /* inet= */ false);

        // For each level...
        for (int testLevel = 0; testLevel < WifiIcons.WIFI_LEVEL_COUNT; testLevel++) {
            int rssi = calculateRssiForLevel(testLevel);
            when(underlyingCarrierMergedInfo.getRssi()).thenReturn(rssi);
            // WHEN the new level is sent to the callbacks
            setConnectivityViaDefaultAndNormalCallbackInWifiTracker(
                    primaryNetwork, mainCapabilitiesBuilder.build());

            // WHEN the network is validated
            mainCapabilitiesBuilder.addCapability(NET_CAPABILITY_VALIDATED);
            setConnectivityViaCallbackInNetworkController(
                    primaryNetwork, mainCapabilitiesBuilder.build());

            // THEN we see the mobile data indicators with inet=true (no exclamation mark)
            verifyLastMobileDataIndicatorsForVcn(
                    /* visible= */ true,
                    testLevel,
                    TelephonyIcons.ICON_CWF,
                    /* inet= */ true);

            // WHEN the network is not validated
            mainCapabilitiesBuilder.removeCapability(NET_CAPABILITY_VALIDATED);
            setConnectivityViaCallbackInNetworkController(
                    primaryNetwork, mainCapabilitiesBuilder.build());

            // THEN we see the mobile data indicators with inet=false (exclamation mark)
            verifyLastMobileDataIndicatorsForVcn(
                    /* visible= */ true,
                    testLevel,
                    TelephonyIcons.ICON_CWF,
                    /* inet= */ false);
        }
    }

    @Test
    public void testDisableWiFiWithVcnWithUnderlyingWifi() {
        String testSsid = "Test VCN SSID";
        setWifiEnabled(true);
        verifyLastWifiIcon(false, WifiIcons.WIFI_NO_NETWORK);

        mNetworkController.setNoNetworksAvailable(false);
        setWifiStateForVcn(true, testSsid);
        setWifiLevelForVcn(1);
        verifyLastMobileDataIndicatorsForVcn(true, 1, TelephonyIcons.ICON_CWF, false);

        setWifiEnabled(false);
        verifyLastMobileDataIndicatorsForVcn(false, 1, 0, false);
    }

    protected void setWifiActivity(int activity) {
        // TODO: Not this, because this variable probably isn't sticking around.
        mNetworkController.mWifiSignalController.setActivity(activity);
    }

    protected void setWifiLevel(int level) {
        when(mWifiInfo.getRssi()).thenReturn(calculateRssiForLevel(level));
        setConnectivityViaCallbackInWifiTracker(
                NetworkCapabilities.TRANSPORT_WIFI, false, true, mWifiInfo);
    }

    protected void setWifiEnabled(boolean enabled) {
        when(mMockWm.getWifiState()).thenReturn(
                enabled ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED);
        mNetworkController.onReceive(mContext, new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));
    }

    protected void setWifiState(boolean connected, String ssid) {
        when(mWifiInfo.getSSID()).thenReturn(ssid);
        setConnectivityViaCallbackInWifiTracker(
                NetworkCapabilities.TRANSPORT_WIFI, false, connected, mWifiInfo);
    }

    protected void setWifiLevelForVcn(int level) {
        when(mWifiInfo.getRssi()).thenReturn(calculateRssiForLevel(level));
        when(mWifiInfo.isCarrierMerged()).thenReturn(true);
        when(mWifiInfo.getSubscriptionId()).thenReturn(1);
        setConnectivityViaCallbackInWifiTrackerForVcn(
                NetworkCapabilities.TRANSPORT_CELLULAR,
                false,
                true,
                mVcnTransportInfo,
                newWifiNetwork(mWifiInfo));
    }

    private int calculateRssiForLevel(int level) {
        float amountPerLevel = (MAX_RSSI - MIN_RSSI) / (WifiIcons.WIFI_LEVEL_COUNT - 1);
        int rssi = (int) (MIN_RSSI + level * amountPerLevel);
        // Put RSSI in the middle of the range.
        rssi += amountPerLevel / 2;
        return rssi;
    }

    protected void setWifiStateForVcn(boolean connected, String ssid) {
        when(mWifiInfo.getSSID()).thenReturn(ssid);
        when(mWifiInfo.isCarrierMerged()).thenReturn(true);
        when(mWifiInfo.getSubscriptionId()).thenReturn(1);
        setConnectivityViaCallbackInWifiTrackerForVcn(
                NetworkCapabilities.TRANSPORT_CELLULAR,
                false,
                connected,
                mVcnTransportInfo,
                newWifiNetwork(mWifiInfo));
    }

    protected void verifyLastQsDataDirection(boolean in, boolean out) {
        ArgumentCaptor<WifiIndicators> indicatorsArg =
                ArgumentCaptor.forClass(WifiIndicators.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setWifiIndicators(
                indicatorsArg.capture());
        WifiIndicators expected = indicatorsArg.getValue();
        assertEquals("WiFi data in, in quick settings", in, expected.activityIn);
        assertEquals("WiFi data out, in quick settings", out, expected.activityOut);
    }

    protected void verifyLastQsWifiIcon(boolean enabled, boolean connected, int icon,
            String description) {
        ArgumentCaptor<WifiIndicators> indicatorsArg =
                ArgumentCaptor.forClass(WifiIndicators.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setWifiIndicators(
                indicatorsArg.capture());
        WifiIndicators expected = indicatorsArg.getValue();
        assertEquals("WiFi enabled, in quick settings", enabled, expected.enabled);
        assertEquals("WiFI desc (ssid), in quick settings", description, expected.description);
        if (enabled && connected) {
            assertEquals("WiFi connected, in quick settings", connected, expected.qsIcon.visible);
            assertEquals("WiFi signal, in quick settings", icon, expected.qsIcon.icon);
        } else {
            assertEquals("WiFi is not default", null, expected.qsIcon);
        }
    }

    protected void verifyLastWifiIcon(boolean visible, int icon) {
        ArgumentCaptor<WifiIndicators> indicatorsArg =
                ArgumentCaptor.forClass(WifiIndicators.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setWifiIndicators(
                indicatorsArg.capture());
        WifiIndicators expected = indicatorsArg.getValue();
        assertEquals("WiFi visible, in status bar", visible, expected.statusIcon.visible);
        assertEquals("WiFi signal, in status bar", icon, expected.statusIcon.icon);
    }
}
