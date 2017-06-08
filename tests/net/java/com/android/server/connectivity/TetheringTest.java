/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.net.wifi.WifiManager.IFACE_IP_MODE_LOCAL_ONLY;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_TETHERED;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_MODE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.NetworkRequest;
import android.net.util.SharedLog;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.CarrierConfigManager;
import android.test.mock.MockContentResolver;

import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.connectivity.tethering.OffloadHardwareInterface;
import com.android.server.connectivity.tethering.TetheringDependencies;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Vector;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TetheringTest {
    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};

    @Mock private ApplicationInfo mApplicationInfo;
    @Mock private Context mContext;
    @Mock private ConnectivityManager mConnectivityManager;
    @Mock private INetworkManagementService mNMService;
    @Mock private INetworkStatsService mStatsService;
    @Mock private INetworkPolicyManager mPolicyManager;
    @Mock private MockableSystemProperties mSystemProperties;
    @Mock private OffloadHardwareInterface mOffloadHardwareInterface;
    @Mock private Resources mResources;
    @Mock private TetheringDependencies mTetheringDependencies;
    @Mock private UsbManager mUsbManager;
    @Mock private WifiManager mWifiManager;
    @Mock private CarrierConfigManager mCarrierConfigManager;

    // Like so many Android system APIs, these cannot be mocked because it is marked final.
    // We have to use the real versions.
    private final PersistableBundle mCarrierConfig = new PersistableBundle();
    private final TestLooper mLooper = new TestLooper();
    private final String mTestIfname = "test_wlan0";

    private Vector<Intent> mIntents;
    private BroadcastInterceptingContext mServiceContext;
    private MockContentResolver mContentResolver;
    private BroadcastReceiver mBroadcastReceiver;
    private Tethering mTethering;

    private class MockContext extends BroadcastInterceptingContext {
        MockContext(Context base) {
            super(base);
        }

        @Override
        public ApplicationInfo getApplicationInfo() { return mApplicationInfo; }

        @Override
        public ContentResolver getContentResolver() { return mContentResolver; }

        @Override
        public String getPackageName() { return "TetheringTest"; }

        @Override
        public Resources getResources() { return mResources; }

        @Override
        public Object getSystemService(String name) {
            if (Context.CONNECTIVITY_SERVICE.equals(name)) return mConnectivityManager;
            if (Context.WIFI_SERVICE.equals(name)) return mWifiManager;
            return super.getSystemService(name);
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_dhcp_range))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_usb_regexs))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_wifi_regexs))
                .thenReturn(new String[]{ "test_wlan\\d" });
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_bluetooth_regexs))
                .thenReturn(new String[0]);
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[0]);
        when(mNMService.listInterfaces())
                .thenReturn(new String[]{ "test_rmnet_data0", mTestIfname });
        when(mNMService.getInterfaceConfig(anyString()))
                .thenReturn(new InterfaceConfiguration());

        mServiceContext = new MockContext(mContext);
        mContentResolver = new MockContentResolver(mServiceContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        mIntents = new Vector<>();
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mIntents.addElement(intent);
            }
        };
        mServiceContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(ConnectivityManager.ACTION_TETHER_STATE_CHANGED));
        when(mTetheringDependencies.getOffloadHardwareInterface(
                any(Handler.class), any(SharedLog.class))).thenReturn(mOffloadHardwareInterface);
        mTethering = new Tethering(mServiceContext, mNMService, mStatsService, mPolicyManager,
                                   mLooper.getLooper(), mSystemProperties,
                                   mTetheringDependencies);
    }

    @After
    public void tearDown() {
        mServiceContext.unregisterReceiver(mBroadcastReceiver);
    }

    private void setupForRequiredProvisioning() {
        // Produce some acceptable looking provision app setting if requested.
        when(mResources.getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app))
                .thenReturn(PROVISIONING_APP_NAME);
        // Don't disable tethering provisioning unless requested.
        when(mSystemProperties.getBoolean(eq(Tethering.DISABLE_PROVISIONING_SYSPROP_KEY),
                                          anyBoolean())).thenReturn(false);
        // Act like the CarrierConfigManager is present and ready unless told otherwise.
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(mCarrierConfigManager);
        when(mCarrierConfigManager.getConfig()).thenReturn(mCarrierConfig);
        mCarrierConfig.putBoolean(CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL, true);
    }

    @Test
    public void canRequireProvisioning() {
        setupForRequiredProvisioning();
        assertTrue(mTethering.isTetherProvisioningRequired());
    }

    @Test
    public void toleratesCarrierConfigManagerMissing() {
        setupForRequiredProvisioning();
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(null);
        // Couldn't get the CarrierConfigManager, but still had a declared provisioning app.
        // We therefore still require provisioning.
        assertTrue(mTethering.isTetherProvisioningRequired());
    }

    @Test
    public void toleratesCarrierConfigMissing() {
        setupForRequiredProvisioning();
        when(mCarrierConfigManager.getConfig()).thenReturn(null);
        // We still have a provisioning app configured, so still require provisioning.
        assertTrue(mTethering.isTetherProvisioningRequired());
    }

    @Test
    public void provisioningNotRequiredWhenAppNotFound() {
        setupForRequiredProvisioning();
        when(mResources.getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app))
                .thenReturn(null);
        assertTrue(!mTethering.isTetherProvisioningRequired());
        when(mResources.getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app))
                .thenReturn(new String[] {"malformedApp"});
        assertTrue(!mTethering.isTetherProvisioningRequired());
    }

    private void sendWifiApStateChanged(int state) {
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.putExtra(EXTRA_WIFI_AP_STATE, state);
        mServiceContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendWifiApStateChanged(int state, String ifname, int ipmode) {
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.putExtra(EXTRA_WIFI_AP_STATE, state);
        intent.putExtra(EXTRA_WIFI_AP_INTERFACE_NAME, ifname);
        intent.putExtra(EXTRA_WIFI_AP_MODE, ipmode);
        mServiceContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void verifyInterfaceServingModeStarted(boolean ifnameKnown) throws Exception {
        if (!ifnameKnown) {
            verify(mNMService, times(1)).listInterfaces();
        }
        verify(mNMService, times(1)).getInterfaceConfig(mTestIfname);
        verify(mNMService, times(1))
                .setInterfaceConfig(eq(mTestIfname), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).tetherInterface(mTestIfname);
    }

    private void verifyTetheringBroadcast(String ifname, String whichExtra) {
        // Verify that ifname is in the whichExtra array of the tether state changed broadcast.
        final Intent bcast = mIntents.get(0);
        assertEquals(ConnectivityManager.ACTION_TETHER_STATE_CHANGED, bcast.getAction());
        final ArrayList<String> ifnames = bcast.getStringArrayListExtra(whichExtra);
        assertTrue(ifnames.contains(ifname));
        mIntents.remove(bcast);
    }

    public void workingLocalOnlyHotspot(boolean enrichedApBroadcast) throws Exception {
        when(mConnectivityManager.isTetheringSupported()).thenReturn(true);

        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // hotspot mode is to be started.
        mTethering.interfaceStatusChanged(mTestIfname, true);
        if (enrichedApBroadcast) {
            sendWifiApStateChanged(WIFI_AP_STATE_ENABLED, mTestIfname, IFACE_IP_MODE_LOCAL_ONLY);
        } else {
            sendWifiApStateChanged(WIFI_AP_STATE_ENABLED);
        }
        mLooper.dispatchAll();

        verifyInterfaceServingModeStarted(enrichedApBroadcast);
        verifyTetheringBroadcast(mTestIfname, ConnectivityManager.EXTRA_AVAILABLE_TETHER);
        verify(mNMService, times(1)).setIpForwardingEnabled(true);
        verify(mNMService, times(1)).startTethering(any(String[].class));
        verifyNoMoreInteractions(mNMService);
        verify(mWifiManager).updateInterfaceIpState(
                mTestIfname, WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
        verifyNoMoreInteractions(mWifiManager);
        verifyTetheringBroadcast(mTestIfname, ConnectivityManager.EXTRA_ACTIVE_LOCAL_ONLY);
        // UpstreamNetworkMonitor will be started, and will register two callbacks:
        // a "listen all" and a "track default".
        verify(mConnectivityManager, times(1)).registerNetworkCallback(
                any(NetworkRequest.class), any(NetworkCallback.class), any(Handler.class));
        verify(mConnectivityManager, times(1)).registerDefaultNetworkCallback(
                any(NetworkCallback.class), any(Handler.class));
        // TODO: Figure out why this isn't exactly once, for sendTetherStateChangedBroadcast().
        verify(mConnectivityManager, atLeastOnce()).isTetheringSupported();
        verifyNoMoreInteractions(mConnectivityManager);

        // Emulate externally-visible WifiManager effects, when hotspot mode
        // is being torn down.
        sendWifiApStateChanged(WifiManager.WIFI_AP_STATE_DISABLED);
        mTethering.interfaceRemoved(mTestIfname);
        mLooper.dispatchAll();

        verify(mNMService, times(1)).untetherInterface(mTestIfname);
        // TODO: Why is {g,s}etInterfaceConfig() called more than once?
        verify(mNMService, atLeastOnce()).getInterfaceConfig(mTestIfname);
        verify(mNMService, atLeastOnce())
                .setInterfaceConfig(eq(mTestIfname), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).stopTethering();
        verify(mNMService, times(1)).setIpForwardingEnabled(false);
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
        // Asking for the last error after the per-interface state machine
        // has been reaped yields an unknown interface error.
        assertEquals(ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE,
                mTethering.getLastTetherError(mTestIfname));
    }

    @Test
    public void workingLocalOnlyHotspotLegacyApBroadcast() throws Exception {
        workingLocalOnlyHotspot(false);
    }

    @Test
    public void workingLocalOnlyHotspotEnrichedApBroadcast() throws Exception {
        workingLocalOnlyHotspot(true);
    }

    public void workingWifiTethering(boolean enrichedApBroadcast) throws Exception {
        when(mConnectivityManager.isTetheringSupported()).thenReturn(true);
        when(mWifiManager.startSoftAp(any(WifiConfiguration.class))).thenReturn(true);

        // Emulate pressing the WiFi tethering button.
        mTethering.startTethering(ConnectivityManager.TETHERING_WIFI, null, false);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).startSoftAp(null);
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mConnectivityManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // tethering mode is to be started.
        mTethering.interfaceStatusChanged(mTestIfname, true);
        if (enrichedApBroadcast) {
            sendWifiApStateChanged(WIFI_AP_STATE_ENABLED, mTestIfname, IFACE_IP_MODE_TETHERED);
        } else {
            sendWifiApStateChanged(WIFI_AP_STATE_ENABLED);
        }
        mLooper.dispatchAll();

        verifyInterfaceServingModeStarted(enrichedApBroadcast);
        verifyTetheringBroadcast(mTestIfname, ConnectivityManager.EXTRA_AVAILABLE_TETHER);
        verify(mNMService, times(1)).setIpForwardingEnabled(true);
        verify(mNMService, times(1)).startTethering(any(String[].class));
        verifyNoMoreInteractions(mNMService);
        verify(mWifiManager).updateInterfaceIpState(
                mTestIfname, WifiManager.IFACE_IP_MODE_TETHERED);
        verifyNoMoreInteractions(mWifiManager);
        verifyTetheringBroadcast(mTestIfname, ConnectivityManager.EXTRA_ACTIVE_TETHER);
        // UpstreamNetworkMonitor will be started, and will register two callbacks:
        // a "listen all" and a "track default".
        verify(mConnectivityManager, times(1)).registerNetworkCallback(
                any(NetworkRequest.class), any(NetworkCallback.class), any(Handler.class));
        verify(mConnectivityManager, times(1)).registerDefaultNetworkCallback(
                any(NetworkCallback.class), any(Handler.class));
        // In tethering mode, in the default configuration, an explicit request
        // for a mobile network is also made.
        verify(mConnectivityManager, times(1)).requestNetwork(
                any(NetworkRequest.class), any(NetworkCallback.class), eq(0), anyInt(),
                any(Handler.class));
        // TODO: Figure out why this isn't exactly once, for sendTetherStateChangedBroadcast().
        verify(mConnectivityManager, atLeastOnce()).isTetheringSupported();
        verifyNoMoreInteractions(mConnectivityManager);

        /////
        // We do not currently emulate any upstream being found.
        //
        // This is why there are no calls to verify mNMService.enableNat() or
        // mNMService.startInterfaceForwarding().
        /////

        // Emulate pressing the WiFi tethering button.
        mTethering.stopTethering(ConnectivityManager.TETHERING_WIFI);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).stopSoftAp();
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mConnectivityManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, when tethering mode
        // is being torn down.
        sendWifiApStateChanged(WifiManager.WIFI_AP_STATE_DISABLED);
        mTethering.interfaceRemoved(mTestIfname);
        mLooper.dispatchAll();

        verify(mNMService, times(1)).untetherInterface(mTestIfname);
        // TODO: Why is {g,s}etInterfaceConfig() called more than once?
        verify(mNMService, atLeastOnce()).getInterfaceConfig(mTestIfname);
        verify(mNMService, atLeastOnce())
                .setInterfaceConfig(eq(mTestIfname), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).stopTethering();
        verify(mNMService, times(1)).setIpForwardingEnabled(false);
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
        // Asking for the last error after the per-interface state machine
        // has been reaped yields an unknown interface error.
        assertEquals(ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE,
                mTethering.getLastTetherError(mTestIfname));
    }

    @Test
    public void workingWifiTetheringLegacyApBroadcast() throws Exception {
        workingWifiTethering(false);
    }

    @Test
    public void workingWifiTetheringEnrichedApBroadcast() throws Exception {
        workingWifiTethering(true);
    }

    @Test
    public void failureEnablingIpForwarding() throws Exception {
        when(mConnectivityManager.isTetheringSupported()).thenReturn(true);
        when(mWifiManager.startSoftAp(any(WifiConfiguration.class))).thenReturn(true);
        doThrow(new RemoteException()).when(mNMService).setIpForwardingEnabled(true);

        // Emulate pressing the WiFi tethering button.
        mTethering.startTethering(ConnectivityManager.TETHERING_WIFI, null, false);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).startSoftAp(null);
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mConnectivityManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // tethering mode is to be started.
        mTethering.interfaceStatusChanged(mTestIfname, true);
        sendWifiApStateChanged(WifiManager.WIFI_AP_STATE_ENABLED);
        mLooper.dispatchAll();

        // Activity caused by test_wlan0 becoming available.
        verify(mNMService, times(1)).listInterfaces();
        // We verify get/set called twice here: once for setup and once during
        // teardown because all events happen over the course of the single
        // dispatchAll() above.
        verify(mNMService, times(2)).getInterfaceConfig(mTestIfname);
        verify(mNMService, times(2))
                .setInterfaceConfig(eq(mTestIfname), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).tetherInterface(mTestIfname);
        verify(mWifiManager).updateInterfaceIpState(
                mTestIfname, WifiManager.IFACE_IP_MODE_TETHERED);
        verify(mConnectivityManager, atLeastOnce()).isTetheringSupported();
        verifyTetheringBroadcast(mTestIfname, ConnectivityManager.EXTRA_AVAILABLE_TETHER);
        // This is called, but will throw.
        verify(mNMService, times(1)).setIpForwardingEnabled(true);
        // This never gets called because of the exception thrown above.
        verify(mNMService, times(0)).startTethering(any(String[].class));
        // When the master state machine transitions to an error state it tells
        // downstream interfaces, which causes us to tell Wi-Fi about the error
        // so it can take down AP mode.
        verify(mNMService, times(1)).untetherInterface(mTestIfname);
        verify(mWifiManager).updateInterfaceIpState(
                mTestIfname, WifiManager.IFACE_IP_MODE_CONFIGURATION_ERROR);

        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mConnectivityManager);
        verifyNoMoreInteractions(mNMService);
    }

    // TODO: Test that a request for hotspot mode doesn't interfere with an
    // already operating tethering mode interface.
}
