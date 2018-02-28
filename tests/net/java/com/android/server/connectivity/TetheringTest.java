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

import static android.hardware.usb.UsbManager.USB_CONFIGURED;
import static android.hardware.usb.UsbManager.USB_CONNECTED;
import static android.hardware.usb.UsbManager.USB_FUNCTION_RNDIS;
import static android.net.ConnectivityManager.ACTION_TETHER_STATE_CHANGED;
import static android.net.ConnectivityManager.EXTRA_ACTIVE_LOCAL_ONLY;
import static android.net.ConnectivityManager.EXTRA_ACTIVE_TETHER;
import static android.net.ConnectivityManager.EXTRA_AVAILABLE_TETHER;
import static android.net.ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
import static android.net.ConnectivityManager.TETHERING_WIFI;
import static android.net.ConnectivityManager.TETHERING_USB;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_LOCAL_ONLY;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_TETHERED;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_MODE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.hardware.usb.UsbManager;
import android.net.INetd;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.ip.RouterAdvertisementDaemon;
import android.net.util.InterfaceParams;
import android.net.util.NetworkConstants;
import android.net.util.SharedLog;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.CarrierConfigManager;
import android.test.mock.MockContentResolver;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.StateMachine;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.connectivity.tethering.IControlsTethering;
import com.android.server.connectivity.tethering.IPv6TetheringCoordinator;
import com.android.server.connectivity.tethering.OffloadHardwareInterface;
import com.android.server.connectivity.tethering.TetherInterfaceStateMachine;
import com.android.server.connectivity.tethering.TetheringDependencies;
import com.android.server.connectivity.tethering.UpstreamNetworkMonitor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Vector;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TetheringTest {
    private static final int IFINDEX_OFFSET = 100;

    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};
    private static final String TEST_MOBILE_IFNAME = "test_rmnet_data0";
    private static final String TEST_XLAT_MOBILE_IFNAME = "v4-test_rmnet_data0";
    private static final String TEST_USB_IFNAME = "test_rndis0";
    private static final String TEST_WLAN_IFNAME = "test_wlan0";

    @Mock private ApplicationInfo mApplicationInfo;
    @Mock private Context mContext;
    @Mock private INetworkManagementService mNMService;
    @Mock private INetworkStatsService mStatsService;
    @Mock private INetworkPolicyManager mPolicyManager;
    @Mock private MockableSystemProperties mSystemProperties;
    @Mock private OffloadHardwareInterface mOffloadHardwareInterface;
    @Mock private Resources mResources;
    @Mock private UsbManager mUsbManager;
    @Mock private WifiManager mWifiManager;
    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private UpstreamNetworkMonitor mUpstreamNetworkMonitor;
    @Mock private IPv6TetheringCoordinator mIPv6TetheringCoordinator;
    @Mock private RouterAdvertisementDaemon mRouterAdvertisementDaemon;
    @Mock private INetd mNetd;

    private final MockTetheringDependencies mTetheringDependencies =
            new MockTetheringDependencies();

    // Like so many Android system APIs, these cannot be mocked because it is marked final.
    // We have to use the real versions.
    private final PersistableBundle mCarrierConfig = new PersistableBundle();
    private final TestLooper mLooper = new TestLooper();

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
            if (Context.WIFI_SERVICE.equals(name)) return mWifiManager;
            if (Context.USB_SERVICE.equals(name)) return mUsbManager;
            return super.getSystemService(name);
        }
    }

    public class MockTetheringDependencies extends TetheringDependencies {
        StateMachine upstreamNetworkMonitorMasterSM;
        ArrayList<TetherInterfaceStateMachine> ipv6CoordinatorNotifyList;
        int isTetheringSupportedCalls;

        public void reset() {
            upstreamNetworkMonitorMasterSM = null;
            ipv6CoordinatorNotifyList = null;
            isTetheringSupportedCalls = 0;
        }

        @Override
        public OffloadHardwareInterface getOffloadHardwareInterface(Handler h, SharedLog log) {
            return mOffloadHardwareInterface;
        }

        @Override
        public UpstreamNetworkMonitor getUpstreamNetworkMonitor(Context ctx,
                StateMachine target, SharedLog log, int what) {
            upstreamNetworkMonitorMasterSM = target;
            return mUpstreamNetworkMonitor;
        }

        @Override
        public IPv6TetheringCoordinator getIPv6TetheringCoordinator(
                ArrayList<TetherInterfaceStateMachine> notifyList, SharedLog log) {
            ipv6CoordinatorNotifyList = notifyList;
            return mIPv6TetheringCoordinator;
        }

        @Override
        public RouterAdvertisementDaemon getRouterAdvertisementDaemon(InterfaceParams ifParams) {
            return mRouterAdvertisementDaemon;
        }

        @Override
        public INetd getNetdService() {
            return mNetd;
        }

        @Override
        public InterfaceParams getInterfaceParams(String ifName) {
            final String[] ifaces = new String[] { TEST_USB_IFNAME, TEST_WLAN_IFNAME,
                    TEST_MOBILE_IFNAME };
            final int index = ArrayUtils.indexOf(ifaces, ifName);
            assertTrue("Non-mocked interface: " + ifName, index >= 0);
            return new InterfaceParams(ifName, index + IFINDEX_OFFSET,
                    MacAddress.ALL_ZEROS_ADDRESS);
        }

        @Override
        public boolean isTetheringSupported() {
            isTetheringSupportedCalls++;
            return true;
        }
    }

    private static NetworkState buildMobileUpstreamState(boolean withIPv4, boolean withIPv6,
            boolean with464xlat) {
        final NetworkInfo info = new NetworkInfo(TYPE_MOBILE, 0, null, null);
        info.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_MOBILE_IFNAME);

        if (withIPv4) {
            prop.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0),
                    NetworkUtils.numericToInetAddress("10.0.0.1"), TEST_MOBILE_IFNAME));
        }

        if (withIPv6) {
            prop.addDnsServer(NetworkUtils.numericToInetAddress("2001:db8::2"));
            prop.addLinkAddress(
                    new LinkAddress(NetworkUtils.numericToInetAddress("2001:db8::"),
                            NetworkConstants.RFC7421_PREFIX_LENGTH));
            prop.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0),
                    NetworkUtils.numericToInetAddress("2001:db8::1"), TEST_MOBILE_IFNAME));
        }

        if (with464xlat) {
            final LinkProperties stackedLink = new LinkProperties();
            stackedLink.setInterfaceName(TEST_XLAT_MOBILE_IFNAME);
            stackedLink.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0),
                    NetworkUtils.numericToInetAddress("192.0.0.1"), TEST_XLAT_MOBILE_IFNAME));

            prop.addStackedLink(stackedLink);
        }


        final NetworkCapabilities capabilities = new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);;
        return new NetworkState(info, prop, capabilities, new Network(100), null, "netid");
    }

    private static NetworkState buildMobileIPv4UpstreamState() {
        return buildMobileUpstreamState(true, false, false);
    }

    private static NetworkState buildMobileIPv6UpstreamState() {
        return buildMobileUpstreamState(false, true, false);
    }

    private static NetworkState buildMobileDualStackUpstreamState() {
        return buildMobileUpstreamState(true, true, false);
    }

    private static NetworkState buildMobile464xlatUpstreamState() {
        return buildMobileUpstreamState(false, true, true);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_dhcp_range))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_usb_regexs))
                .thenReturn(new String[] { "test_rndis\\d" });
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_wifi_regexs))
                .thenReturn(new String[]{ "test_wlan\\d" });
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_bluetooth_regexs))
                .thenReturn(new String[0]);
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[0]);
        when(mNMService.listInterfaces())
                .thenReturn(new String[] {
                        TEST_MOBILE_IFNAME, TEST_WLAN_IFNAME, TEST_USB_IFNAME});
        when(mNMService.getInterfaceConfig(anyString()))
                .thenReturn(new InterfaceConfiguration());
        when(mRouterAdvertisementDaemon.start())
                .thenReturn(true);

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
                new IntentFilter(ACTION_TETHER_STATE_CHANGED));
        mTetheringDependencies.reset();
        mTethering = new Tethering(mServiceContext, mNMService, mStatsService, mPolicyManager,
                                   mLooper.getLooper(), mSystemProperties,
                                   mTetheringDependencies);
        verify(mNMService).registerTetheringStatsProvider(any(), anyString());
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
        sendConfigurationChanged();
        assertTrue(mTethering.isTetherProvisioningRequired());
    }

    @Test
    public void toleratesCarrierConfigManagerMissing() {
        setupForRequiredProvisioning();
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(null);
        sendConfigurationChanged();
        // Couldn't get the CarrierConfigManager, but still had a declared provisioning app.
        // We therefore still require provisioning.
        assertTrue(mTethering.isTetherProvisioningRequired());
    }

    @Test
    public void toleratesCarrierConfigMissing() {
        setupForRequiredProvisioning();
        when(mCarrierConfigManager.getConfig()).thenReturn(null);
        sendConfigurationChanged();
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

    private void sendUsbBroadcast(boolean connected, boolean configured, boolean rndisFunction) {
        final Intent intent = new Intent(UsbManager.ACTION_USB_STATE);
        intent.putExtra(USB_CONNECTED, connected);
        intent.putExtra(USB_CONFIGURED, configured);
        intent.putExtra(USB_FUNCTION_RNDIS, rndisFunction);
        mServiceContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendConfigurationChanged() {
        final Intent intent = new Intent(Intent.ACTION_CONFIGURATION_CHANGED);
        mServiceContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void verifyInterfaceServingModeStarted() throws Exception {
        verify(mNMService, times(1)).getInterfaceConfig(TEST_WLAN_IFNAME);
        verify(mNMService, times(1))
                .setInterfaceConfig(eq(TEST_WLAN_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).tetherInterface(TEST_WLAN_IFNAME);
    }

    private void verifyTetheringBroadcast(String ifname, String whichExtra) {
        // Verify that ifname is in the whichExtra array of the tether state changed broadcast.
        final Intent bcast = mIntents.get(0);
        assertEquals(ACTION_TETHER_STATE_CHANGED, bcast.getAction());
        final ArrayList<String> ifnames = bcast.getStringArrayListExtra(whichExtra);
        assertTrue(ifnames.contains(ifname));
        mIntents.remove(bcast);
    }

    public void failingLocalOnlyHotspotLegacyApBroadcast(
            boolean emulateInterfaceStatusChanged) throws Exception {
        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // hotspot mode is to be started.
        if (emulateInterfaceStatusChanged) {
            mTethering.interfaceStatusChanged(TEST_WLAN_IFNAME, true);
        }
        sendWifiApStateChanged(WIFI_AP_STATE_ENABLED);
        mLooper.dispatchAll();

        // If, and only if, Tethering received an interface status changed
        // then it creates a TetherInterfaceStateMachine and sends out a
        // broadcast indicating that the interface is "available".
        if (emulateInterfaceStatusChanged) {
            assertEquals(1, mTetheringDependencies.isTetheringSupportedCalls);
            verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_AVAILABLE_TETHER);
        }
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
    }

    private void prepareUsbTethering(NetworkState upstreamState) {
        when(mUpstreamNetworkMonitor.selectPreferredUpstreamType(any()))
                .thenReturn(upstreamState);

        // Emulate pressing the USB tethering button in Settings UI.
        mTethering.startTethering(TETHERING_USB, null, false);
        mLooper.dispatchAll();
        verify(mUsbManager, times(1)).setCurrentFunctions(UsbManager.FUNCTION_RNDIS);

        mTethering.interfaceStatusChanged(TEST_USB_IFNAME, true);
    }

    @Test
    public void testUsbConfiguredBroadcastStartsTethering() throws Exception {
        NetworkState upstreamState = buildMobileIPv4UpstreamState();
        prepareUsbTethering(upstreamState);

        // This should produce no activity of any kind.
        verifyNoMoreInteractions(mNMService);

        // Pretend we then receive USB configured broadcast.
        sendUsbBroadcast(true, true, true);
        mLooper.dispatchAll();
        // Now we should see the start of tethering mechanics (in this case:
        // tetherMatchingInterfaces() which starts by fetching all interfaces).
        verify(mNMService, times(1)).listInterfaces();

        // UpstreamNetworkMonitor should receive selected upstream
        verify(mUpstreamNetworkMonitor, times(1)).selectPreferredUpstreamType(any());
        verify(mUpstreamNetworkMonitor, times(1)).setCurrentUpstream(upstreamState.network);
    }

    @Test
    public void failingLocalOnlyHotspotLegacyApBroadcastWithIfaceStatusChanged() throws Exception {
        failingLocalOnlyHotspotLegacyApBroadcast(true);
    }

    @Test
    public void failingLocalOnlyHotspotLegacyApBroadcastSansIfaceStatusChanged() throws Exception {
        failingLocalOnlyHotspotLegacyApBroadcast(false);
    }

    public void workingLocalOnlyHotspotEnrichedApBroadcast(
            boolean emulateInterfaceStatusChanged) throws Exception {
        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // hotspot mode is to be started.
        if (emulateInterfaceStatusChanged) {
            mTethering.interfaceStatusChanged(TEST_WLAN_IFNAME, true);
        }
        sendWifiApStateChanged(WIFI_AP_STATE_ENABLED, TEST_WLAN_IFNAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();

        verifyInterfaceServingModeStarted();
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_AVAILABLE_TETHER);
        verify(mNMService, times(1)).setIpForwardingEnabled(true);
        verify(mNMService, times(1)).startTethering(any(String[].class));
        verifyNoMoreInteractions(mNMService);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
        verifyNoMoreInteractions(mWifiManager);
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_ACTIVE_LOCAL_ONLY);
        verify(mUpstreamNetworkMonitor, times(1)).start();
        // TODO: Figure out why this isn't exactly once, for sendTetherStateChangedBroadcast().
        assertTrue(1 <= mTetheringDependencies.isTetheringSupportedCalls);

        // Emulate externally-visible WifiManager effects, when hotspot mode
        // is being torn down.
        sendWifiApStateChanged(WifiManager.WIFI_AP_STATE_DISABLED);
        mTethering.interfaceRemoved(TEST_WLAN_IFNAME);
        mLooper.dispatchAll();

        verify(mNMService, times(1)).untetherInterface(TEST_WLAN_IFNAME);
        // TODO: Why is {g,s}etInterfaceConfig() called more than once?
        verify(mNMService, atLeastOnce()).getInterfaceConfig(TEST_WLAN_IFNAME);
        verify(mNMService, atLeastOnce())
                .setInterfaceConfig(eq(TEST_WLAN_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).stopTethering();
        verify(mNMService, times(1)).setIpForwardingEnabled(false);
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
        // Asking for the last error after the per-interface state machine
        // has been reaped yields an unknown interface error.
        assertEquals(TETHER_ERROR_UNKNOWN_IFACE, mTethering.getLastTetherError(TEST_WLAN_IFNAME));
    }

    /**
     * Send CMD_IPV6_TETHER_UPDATE to TISMs as would be done by IPv6TetheringCoordinator.
     */
    private void sendIPv6TetherUpdates(NetworkState upstreamState) {
        // IPv6TetheringCoordinator must have been notified of downstream
        verify(mIPv6TetheringCoordinator, times(1)).addActiveDownstream(
                argThat(sm -> sm.linkProperties().getInterfaceName().equals(TEST_USB_IFNAME)),
                eq(IControlsTethering.STATE_TETHERED));

        for (TetherInterfaceStateMachine tism :
                mTetheringDependencies.ipv6CoordinatorNotifyList) {
            NetworkState ipv6OnlyState = buildMobileUpstreamState(false, true, false);
            tism.sendMessage(TetherInterfaceStateMachine.CMD_IPV6_TETHER_UPDATE, 0, 0,
                    upstreamState.linkProperties.isIPv6Provisioned()
                            ? ipv6OnlyState.linkProperties
                            : null);
        }
        mLooper.dispatchAll();
    }

    private void runUsbTethering(NetworkState upstreamState) {
        prepareUsbTethering(upstreamState);
        sendUsbBroadcast(true, true, true);
        mLooper.dispatchAll();
    }

    @Test
    public void workingMobileUsbTethering_IPv4() throws Exception {
        NetworkState upstreamState = buildMobileIPv4UpstreamState();
        runUsbTethering(upstreamState);

        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);

        sendIPv6TetherUpdates(upstreamState);
        verify(mRouterAdvertisementDaemon, never()).buildNewRa(any(), notNull());
    }

    @Test
    public void workingMobileUsbTethering_IPv6() throws Exception {
        NetworkState upstreamState = buildMobileIPv6UpstreamState();
        runUsbTethering(upstreamState);

        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);

        sendIPv6TetherUpdates(upstreamState);
        verify(mRouterAdvertisementDaemon, times(1)).buildNewRa(any(), notNull());
        verify(mNetd, times(1)).tetherApplyDnsInterfaces();
    }

    @Test
    public void workingMobileUsbTethering_DualStack() throws Exception {
        NetworkState upstreamState = buildMobileDualStackUpstreamState();
        runUsbTethering(upstreamState);

        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mRouterAdvertisementDaemon, times(1)).start();

        sendIPv6TetherUpdates(upstreamState);
        verify(mRouterAdvertisementDaemon, times(1)).buildNewRa(any(), notNull());
        verify(mNetd, times(1)).tetherApplyDnsInterfaces();
    }

    @Test
    public void workingMobileUsbTethering_MultipleUpstreams() throws Exception {
        NetworkState upstreamState = buildMobile464xlatUpstreamState();
        runUsbTethering(upstreamState);

        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_XLAT_MOBILE_IFNAME);
        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME,
                TEST_XLAT_MOBILE_IFNAME);

        sendIPv6TetherUpdates(upstreamState);
        verify(mRouterAdvertisementDaemon, times(1)).buildNewRa(any(), notNull());
        verify(mNetd, times(1)).tetherApplyDnsInterfaces();
    }

    @Test
    public void workingMobileUsbTethering_v6Then464xlat() throws Exception {
        // Setup IPv6
        NetworkState upstreamState = buildMobileIPv6UpstreamState();
        runUsbTethering(upstreamState);

        // Then 464xlat comes up
        upstreamState = buildMobile464xlatUpstreamState();
        when(mUpstreamNetworkMonitor.selectPreferredUpstreamType(any()))
                .thenReturn(upstreamState);

        // Upstream LinkProperties changed: UpstreamNetworkMonitor sends EVENT_ON_LINKPROPERTIES.
        mTetheringDependencies.upstreamNetworkMonitorMasterSM.sendMessage(
                Tethering.TetherMasterSM.EVENT_UPSTREAM_CALLBACK,
                UpstreamNetworkMonitor.EVENT_ON_LINKPROPERTIES,
                0,
                upstreamState);
        mLooper.dispatchAll();

        // Forwarding is added for 464xlat, and was still added only once for v6
        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_XLAT_MOBILE_IFNAME);
        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME,
                TEST_XLAT_MOBILE_IFNAME);
    }

    @Test
    public void workingLocalOnlyHotspotEnrichedApBroadcastWithIfaceChanged() throws Exception {
        workingLocalOnlyHotspotEnrichedApBroadcast(true);
    }

    @Test
    public void workingLocalOnlyHotspotEnrichedApBroadcastSansIfaceChanged() throws Exception {
        workingLocalOnlyHotspotEnrichedApBroadcast(false);
    }

    // TODO: Test with and without interfaceStatusChanged().
    @Test
    public void failingWifiTetheringLegacyApBroadcast() throws Exception {
        when(mWifiManager.startSoftAp(any(WifiConfiguration.class))).thenReturn(true);

        // Emulate pressing the WiFi tethering button.
        mTethering.startTethering(TETHERING_WIFI, null, false);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).startSoftAp(null);
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // tethering mode is to be started.
        mTethering.interfaceStatusChanged(TEST_WLAN_IFNAME, true);
        sendWifiApStateChanged(WIFI_AP_STATE_ENABLED);
        mLooper.dispatchAll();

        assertEquals(1, mTetheringDependencies.isTetheringSupportedCalls);
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_AVAILABLE_TETHER);
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
    }

    // TODO: Test with and without interfaceStatusChanged().
    @Test
    public void workingWifiTetheringEnrichedApBroadcast() throws Exception {
        when(mWifiManager.startSoftAp(any(WifiConfiguration.class))).thenReturn(true);

        // Emulate pressing the WiFi tethering button.
        mTethering.startTethering(TETHERING_WIFI, null, false);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).startSoftAp(null);
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // tethering mode is to be started.
        mTethering.interfaceStatusChanged(TEST_WLAN_IFNAME, true);
        sendWifiApStateChanged(WIFI_AP_STATE_ENABLED, TEST_WLAN_IFNAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();

        verifyInterfaceServingModeStarted();
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_AVAILABLE_TETHER);
        verify(mNMService, times(1)).setIpForwardingEnabled(true);
        verify(mNMService, times(1)).startTethering(any(String[].class));
        verifyNoMoreInteractions(mNMService);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_TETHERED);
        verifyNoMoreInteractions(mWifiManager);
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_ACTIVE_TETHER);
        verify(mUpstreamNetworkMonitor, times(1)).start();
        // In tethering mode, in the default configuration, an explicit request
        // for a mobile network is also made.
        verify(mUpstreamNetworkMonitor, times(1)).registerMobileNetworkRequest();
        // TODO: Figure out why this isn't exactly once, for sendTetherStateChangedBroadcast().
        assertTrue(1 <= mTetheringDependencies.isTetheringSupportedCalls);

        /////
        // We do not currently emulate any upstream being found.
        //
        // This is why there are no calls to verify mNMService.enableNat() or
        // mNMService.startInterfaceForwarding().
        /////

        // Emulate pressing the WiFi tethering button.
        mTethering.stopTethering(TETHERING_WIFI);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).stopSoftAp();
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, when tethering mode
        // is being torn down.
        sendWifiApStateChanged(WifiManager.WIFI_AP_STATE_DISABLED);
        mTethering.interfaceRemoved(TEST_WLAN_IFNAME);
        mLooper.dispatchAll();

        verify(mNMService, times(1)).untetherInterface(TEST_WLAN_IFNAME);
        // TODO: Why is {g,s}etInterfaceConfig() called more than once?
        verify(mNMService, atLeastOnce()).getInterfaceConfig(TEST_WLAN_IFNAME);
        verify(mNMService, atLeastOnce())
                .setInterfaceConfig(eq(TEST_WLAN_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).stopTethering();
        verify(mNMService, times(1)).setIpForwardingEnabled(false);
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
        // Asking for the last error after the per-interface state machine
        // has been reaped yields an unknown interface error.
        assertEquals(TETHER_ERROR_UNKNOWN_IFACE, mTethering.getLastTetherError(TEST_WLAN_IFNAME));
    }

    // TODO: Test with and without interfaceStatusChanged().
    @Test
    public void failureEnablingIpForwarding() throws Exception {
        when(mWifiManager.startSoftAp(any(WifiConfiguration.class))).thenReturn(true);
        doThrow(new RemoteException()).when(mNMService).setIpForwardingEnabled(true);

        // Emulate pressing the WiFi tethering button.
        mTethering.startTethering(TETHERING_WIFI, null, false);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).startSoftAp(null);
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // tethering mode is to be started.
        mTethering.interfaceStatusChanged(TEST_WLAN_IFNAME, true);
        sendWifiApStateChanged(WIFI_AP_STATE_ENABLED, TEST_WLAN_IFNAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();

        // We verify get/set called twice here: once for setup and once during
        // teardown because all events happen over the course of the single
        // dispatchAll() above.
        verify(mNMService, times(2)).getInterfaceConfig(TEST_WLAN_IFNAME);
        verify(mNMService, times(2))
                .setInterfaceConfig(eq(TEST_WLAN_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).tetherInterface(TEST_WLAN_IFNAME);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_TETHERED);
        // TODO: Figure out why this isn't exactly once, for sendTetherStateChangedBroadcast().
        assertTrue(1 <= mTetheringDependencies.isTetheringSupportedCalls);
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_AVAILABLE_TETHER);
        // This is called, but will throw.
        verify(mNMService, times(1)).setIpForwardingEnabled(true);
        // This never gets called because of the exception thrown above.
        verify(mNMService, times(0)).startTethering(any(String[].class));
        // When the master state machine transitions to an error state it tells
        // downstream interfaces, which causes us to tell Wi-Fi about the error
        // so it can take down AP mode.
        verify(mNMService, times(1)).untetherInterface(TEST_WLAN_IFNAME);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_CONFIGURATION_ERROR);

        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mNMService);
    }

    private void userRestrictionsListenerBehaviour(
        boolean currentDisallow, boolean nextDisallow, String[] activeTetheringIfacesList,
        int expectedInteractionsWithShowNotification) throws  Exception {
        final int userId = 0;
        final Bundle currRestrictions = new Bundle();
        final Bundle newRestrictions = new Bundle();
        Tethering tethering = mock(Tethering.class);
        Tethering.TetheringUserRestrictionListener turl =
                new Tethering.TetheringUserRestrictionListener(tethering);

        currRestrictions.putBoolean(UserManager.DISALLOW_CONFIG_TETHERING, currentDisallow);
        newRestrictions.putBoolean(UserManager.DISALLOW_CONFIG_TETHERING, nextDisallow);
        when(tethering.getTetheredIfaces()).thenReturn(activeTetheringIfacesList);

        turl.onUserRestrictionsChanged(userId, newRestrictions, currRestrictions);

        verify(tethering, times(expectedInteractionsWithShowNotification))
                .showTetheredNotification(anyInt(), eq(false));

        verify(tethering, times(expectedInteractionsWithShowNotification)).untetherAll();
    }

    @Test
    public void testDisallowTetheringWhenNoTetheringInterfaceIsActive() throws Exception {
        final String[] emptyActiveIfacesList = new String[]{};
        final boolean currDisallow = false;
        final boolean nextDisallow = true;
        final int expectedInteractionsWithShowNotification = 0;

        userRestrictionsListenerBehaviour(currDisallow, nextDisallow, emptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }

    @Test
    public void testDisallowTetheringWhenAtLeastOneTetheringInterfaceIsActive() throws Exception {
        final String[] nonEmptyActiveIfacesList = new String[]{TEST_WLAN_IFNAME};
        final boolean currDisallow = false;
        final boolean nextDisallow = true;
        final int expectedInteractionsWithShowNotification = 1;

        userRestrictionsListenerBehaviour(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }

    @Test
    public void testAllowTetheringWhenNoTetheringInterfaceIsActive() throws Exception {
        final String[] nonEmptyActiveIfacesList = new String[]{};
        final boolean currDisallow = true;
        final boolean nextDisallow = false;
        final int expectedInteractionsWithShowNotification = 0;

        userRestrictionsListenerBehaviour(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }

    @Test
    public void testAllowTetheringWhenAtLeastOneTetheringInterfaceIsActive() throws Exception {
        final String[] nonEmptyActiveIfacesList = new String[]{TEST_WLAN_IFNAME};
        final boolean currDisallow = true;
        final boolean nextDisallow = false;
        final int expectedInteractionsWithShowNotification = 0;

        userRestrictionsListenerBehaviour(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }

    @Test
    public void testDisallowTetheringUnchanged() throws Exception {
        final String[] nonEmptyActiveIfacesList = new String[]{TEST_WLAN_IFNAME};
        final int expectedInteractionsWithShowNotification = 0;
        boolean currDisallow = true;
        boolean nextDisallow = true;

        userRestrictionsListenerBehaviour(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);

        currDisallow = false;
        nextDisallow = false;

        userRestrictionsListenerBehaviour(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }


    // TODO: Test that a request for hotspot mode doesn't interfere with an
    // already operating tethering mode interface.
}
