/*
 * Copyright (C) 2010, The Android Open Source Project
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

package com.android.connectivitymanagertest.functional;

import com.android.connectivitymanagertest.ConnectivityManagerStressTestRunner;
import com.android.connectivitymanagertest.ConnectivityManagerTestActivity;
import com.android.connectivitymanagertest.ConnectivityManagerTestRunner;
import com.android.connectivitymanagertest.NetworkState;

import android.R;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.Status;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.provider.Settings;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Test Wi-Fi connection with different configuration
 * To run this tests:
 *     adb shell am instrument -e class
 *          com.android.connectivitymanagertest.functional.WifiConnectionTest
 *          -w com.android.connectivitymanagertest/.ConnectivityManagerTestRunner
 */
public class WifiConnectionTest
    extends ActivityInstrumentationTestCase2<ConnectivityManagerTestActivity> {
    private static final String TAG = "WifiConnectionTest";
    private static final boolean DEBUG = false;
    private List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
    private ConnectivityManagerTestActivity mAct;
    private HashMap<String, DhcpInfo> hm = null;
    private ConnectivityManagerTestRunner mRunner;
    private ContentResolver cr;
    private Set<WifiConfiguration> enabledNetworks = null;
    private WifiManager mWifiManager = null;

    public WifiConnectionTest() {
        super(ConnectivityManagerTestActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        log("before we launch the test activity, we preserve all the configured networks.");
        mRunner = ((ConnectivityManagerTestRunner)getInstrumentation());
        mWifiManager = (WifiManager) mRunner.getContext().getSystemService(Context.WIFI_SERVICE);
        enabledNetworks = getEnabledNetworks(mWifiManager.getConfiguredNetworks());

        mAct = getActivity();
        cr = mRunner.getContext().getContentResolver();
        networks = mAct.loadNetworkConfigurations();
        hm = mAct.getDhcpInfo();
        if (DEBUG) {
            printNetworkConfigurations();
            printDhcpInfo();
        }

        // enable Wifi and verify wpa_supplicant is started
        assertTrue("enable Wifi failed", mAct.enableWifi());
        sleep(2 * ConnectivityManagerTestActivity.SHORT_TIMEOUT,
                "interrupted while waiting for WPA_SUPPLICANT to start");
        WifiInfo mConnection = mAct.mWifiManager.getConnectionInfo();
        assertNotNull(mConnection);
        assertTrue("wpa_supplicant is not started ", mAct.mWifiManager.pingSupplicant());
    }

    private void printNetworkConfigurations() {
        log("==== print network configurations parsed from XML file ====");
        log("number of access points: " + networks.size());
        for (WifiConfiguration config : networks) {
            log(config.toString());
        }
    }

    private void printDhcpInfo() {
        if (hm == null) {
            return;
        } else {
            Set<Entry<String, DhcpInfo>> set = hm.entrySet();
            for (Entry<String, DhcpInfo> me: set) {
               log("SSID: " + me.getKey());
               DhcpInfo dhcp = me.getValue();
               log("IP: " + intToIpString(dhcp.ipAddress));
               log("gateway: " + intToIpString(dhcp.gateway));
               log("Netmask: " + intToIpString(dhcp.netmask));
               log("DNS1: " + intToIpString(dhcp.dns1));
               log("DNS2: " + intToIpString(dhcp.dns2));
            }
        }
    }

    @Override
    public void tearDown() throws Exception {
        log("tear down");
        mAct.removeConfiguredNetworksAndDisableWifi();
        reEnableNetworks(enabledNetworks);
        mWifiManager.saveConfiguration();
        super.tearDown();
    }

    private Set<WifiConfiguration> getEnabledNetworks(List<WifiConfiguration> configuredNetworks) {
        Set<WifiConfiguration> networks = new HashSet<WifiConfiguration>();
        for (WifiConfiguration wifiConfig : configuredNetworks) {
            if (wifiConfig.status == Status.ENABLED || wifiConfig.status == Status.CURRENT) {
                networks.add(wifiConfig);
                log("remembering enabled network " + wifiConfig.SSID +
                        " status is " + wifiConfig.status);
            }
        }
        return networks;
    }

    private void reEnableNetworks(Set<WifiConfiguration> enabledWifiConfig) {
        if (!mWifiManager.isWifiEnabled()) {
            log("reEnableNetworks: enable Wifi");
            mWifiManager.setWifiEnabled(true);
            sleep(ConnectivityManagerTestActivity.SHORT_TIMEOUT,
                    "interruped while waiting for wifi to be enabled");
        }
        for (WifiConfiguration wifiConfig : enabledWifiConfig) {
            log("recover wifi configuration: " + wifiConfig.toString());
            int netId = mWifiManager.addNetwork(wifiConfig);
            if (wifiConfig.status == Status.CURRENT) {
                mWifiManager.enableNetwork(netId, true);
                mWifiManager.reconnect();
                sleep(ConnectivityManagerTestActivity.SHORT_TIMEOUT,
                        String.format("interruped while connecting to %s", wifiConfig.SSID));
                log("re-connecting to network " + wifiConfig.SSID);
            }
        }
        List<WifiConfiguration> wifiConfigurations = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration wifiConfig: wifiConfigurations) {
            if (wifiConfig.status == Status.DISABLED) {
                mWifiManager.enableNetwork(wifiConfig.networkId, false);
            }
        }
    }

    private String intToIpString(int i) {
        return ((i & 0xFF) + "." +
                ((i >> 8) & 0xFF) + "." +
                ((i >> 16) & 0xFF) + "." +
                ((i >> 24) & 0xFF));
    }

    private void sleep(long sometime, String errorMsg) {
        try {
            Thread.sleep(sometime);
        } catch (InterruptedException e) {
            fail(errorMsg);
        }
    }

    private void log(String message) {
        Log.v(TAG, message);
    }
    /**
     * Connect to the provided Wi-Fi network
     * @param config is the network configuration
     * @return true if the connection is successful.
     */
    private void connectToWifi(WifiConfiguration config) {
        // step 1: connect to the test access point
        boolean isStaticIP = false;
        if (hm.containsKey(config.SSID)) {
            DhcpInfo dhcpInfo = hm.get(config.SSID);
            if (dhcpInfo != null) {
                isStaticIP = true;
                // set the system settings:
                Settings.System.putInt(cr,Settings.System.WIFI_USE_STATIC_IP, 1);
                Settings.System.putString(cr, Settings.System.WIFI_STATIC_IP,
                        intToIpString(dhcpInfo.ipAddress));
                Settings.System.putString(cr, Settings.System.WIFI_STATIC_GATEWAY,
                        intToIpString(dhcpInfo.gateway));
                Settings.System.putString(cr, Settings.System.WIFI_STATIC_NETMASK,
                        intToIpString(dhcpInfo.netmask));
                Settings.System.putString(cr, Settings.System.WIFI_STATIC_DNS1,
                        intToIpString(dhcpInfo.dns1));
                Settings.System.putString(cr, Settings.System.WIFI_STATIC_DNS2,
                        intToIpString(dhcpInfo.dns2));
            }
        }

        assertTrue("failed to connect to " + config.SSID,
                mAct.connectToWifiWithConfiguration(config));

        // step 2: verify Wifi state and network state;
        assertTrue(mAct.waitForWifiState(WifiManager.WIFI_STATE_ENABLED,
                ConnectivityManagerTestActivity.SHORT_TIMEOUT));
        // 802.1x requires long time for connection.
        assertTrue(mAct.waitForNetworkState(ConnectivityManager.TYPE_WIFI,
                State.CONNECTED, 2 * ConnectivityManagerTestActivity.LONG_TIMEOUT));

        // step 3: verify the current connected network is the given SSID
        assertNotNull("Wifi connection returns null", mAct.mWifiManager.getConnectionInfo());
        if (DEBUG) {
            log("config.SSID = " + config.SSID);
            log("mAct.mWifiManager.getConnectionInfo.getSSID()" +
                    mAct.mWifiManager.getConnectionInfo().getSSID());
        }
        assertTrue(config.SSID.contains(mAct.mWifiManager.getConnectionInfo().getSSID()));
        if (isStaticIP) {
            Settings.System.putInt(cr, Settings.System.WIFI_USE_STATIC_IP, 0);
            Settings.System.putString(cr, Settings.System.WIFI_STATIC_IP, "");
            Settings.System.putString(cr, Settings.System.WIFI_STATIC_GATEWAY, "");
            Settings.System.putString(cr, Settings.System.WIFI_STATIC_NETMASK, "");
            Settings.System.putString(cr, Settings.System.WIFI_STATIC_DNS1, "");
            Settings.System.putString(cr, Settings.System.WIFI_STATIC_DNS2, "");
        }
    }

    @LargeTest
    public void testWifiConnections() {
        for (int i = 0; i < networks.size(); i++) {
            String ssid = networks.get(i).SSID;
            log("-- START Wi-Fi connection test for SSID: " + ssid + " --");
            connectToWifi(networks.get(i));
            sleep(2 * ConnectivityManagerTestActivity.SHORT_TIMEOUT,
                    String.format("Interrupted while connecting to ", ssid));
            log("-- END Wi-Fi connection test for SSID: " + ssid + " --");
        }
    }
}
