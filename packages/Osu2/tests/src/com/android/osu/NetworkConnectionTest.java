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

package com.android.osu;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.IOException;

/**
 * Unit tests for {@link com.android.osu.NetworkConnection}.
 */
@SmallTest
public class NetworkConnectionTest {
    private static final String TEST_SSID = "TEST SSID";
    private static final String TEST_SSID_WITH_QUOTES = "\"" + TEST_SSID + "\"";
    private static final int TEST_NETWORK_ID = 1;

    @Mock Context mContext;
    @Mock Handler mHandler;
    @Mock WifiManager mWifiManager;
    @Mock NetworkConnection.Callbacks mCallbacks;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        when(mContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mWifiManager);
    }

    /**
     * Verify that an IOException will be thrown when failed to add the network.
     *
     * @throws Exception
     */
    @Test(expected = IOException.class)
    public void networkAddFailed() throws Exception {
        when(mWifiManager.addNetwork(any(WifiConfiguration.class))).thenReturn(-1);
        new NetworkConnection(mContext, mHandler, WifiSsid.createFromAsciiEncoded(TEST_SSID),
                null, mCallbacks);
    }

    /**
     * Verify that an IOException will be thrown when failed to enable the network.
     *
     * @throws Exception
     */
    @Test(expected = IOException.class)
    public void networkEnableFailed() throws Exception {
        when(mWifiManager.addNetwork(any(WifiConfiguration.class))).thenReturn(TEST_NETWORK_ID);
        when(mWifiManager.enableNetwork(eq(TEST_NETWORK_ID), eq(true))).thenReturn(false);
        new NetworkConnection(mContext, mHandler, WifiSsid.createFromAsciiEncoded(TEST_SSID),
                null, mCallbacks);
    }

    /**
     * Verify that the connection is established after receiving a
     * WifiManager.NETWORK_STATE_CHANGED_ACTION intent indicating that we are connected.
     *
     * @throws Exception
     */
    @Test
    public void openNetworkConnectionEstablished() throws Exception {
        when(mWifiManager.addNetwork(any(WifiConfiguration.class))).thenReturn(TEST_NETWORK_ID);
        when(mWifiManager.enableNetwork(eq(TEST_NETWORK_ID), eq(true))).thenReturn(true);
        NetworkConnection connection = new NetworkConnection(mContext, mHandler,
                WifiSsid.createFromAsciiEncoded(TEST_SSID), null, mCallbacks);

        // Verify the WifiConfiguration being added.
        ArgumentCaptor<WifiConfiguration> wifiConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiManager).addNetwork(wifiConfig.capture());
        assertEquals(wifiConfig.getValue().SSID, TEST_SSID_WITH_QUOTES);

        // Capture the BroadcastReceiver.
        ArgumentCaptor<BroadcastReceiver> receiver =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiver.capture(), any(), any(), any());

        // Setup intent.
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        NetworkInfo networkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setNetworkId(TEST_NETWORK_ID);
        intent.putExtra(WifiManager.EXTRA_WIFI_INFO, wifiInfo);

        // Send intent to the receiver.
        Network network = new Network(0);
        when(mWifiManager.getCurrentNetwork()).thenReturn(network);
        receiver.getValue().onReceive(mContext, intent);

        // Verify we are connected.
        verify(mCallbacks).onConnected(eq(network));
    }
}
