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

package com.android.server.connectivity.tethering;

import static android.hardware.usb.UsbManager.USB_CONFIGURED;
import static android.hardware.usb.UsbManager.USB_CONNECTED;
import static android.hardware.usb.UsbManager.USB_FUNCTION_RNDIS;
import static android.net.ConnectivityManager.ACTION_TETHER_STATE_CHANGED;
import static android.net.ConnectivityManager.EXTRA_ACTIVE_LOCAL_ONLY;
import static android.net.ConnectivityManager.EXTRA_ACTIVE_TETHER;
import static android.net.ConnectivityManager.EXTRA_AVAILABLE_TETHER;
import static android.net.ConnectivityManager.TETHERING_USB;
import static android.net.ConnectivityManager.TETHERING_WIFI;
import static android.net.ConnectivityManager.TETHER_ERROR_NO_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
import static android.net.ConnectivityManager.TYPE_WIFI_P2P;
import static android.net.RouteInfo.RTN_UNICAST;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_MODE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_LOCAL_ONLY;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_TETHERED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.provider.Settings.Global.TETHER_ENABLE_LEGACY_DHCP_SERVER;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
import android.net.ITetheringEventCallback;
import android.net.InetAddresses;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.TetherStatesParcel;
import android.net.TetheringConfigurationParcel;
import android.net.dhcp.DhcpServerCallbacks;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServer;
import android.net.ip.IpServer;
import android.net.ip.RouterAdvertisementDaemon;
import android.net.util.InterfaceParams;
import android.net.util.NetworkConstants;
import android.net.util.SharedLog;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.StateMachine;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Vector;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TetheringTest {
    private static final int IFINDEX_OFFSET = 100;

    private static final String TEST_MOBILE_IFNAME = "test_rmnet_data0";
    private static final String TEST_XLAT_MOBILE_IFNAME = "v4-test_rmnet_data0";
    private static final String TEST_USB_IFNAME = "test_rndis0";
    private static final String TEST_WLAN_IFNAME = "test_wlan0";
    private static final String TEST_P2P_IFNAME = "test_p2p-p2p0-0";

    private static final int DHCPSERVER_START_TIMEOUT_MS = 1000;

    @Mock private ApplicationInfo mApplicationInfo;
    @Mock private Context mContext;
    @Mock private INetworkManagementService mNMService;
    @Mock private INetworkStatsService mStatsService;
    @Mock private INetworkPolicyManager mPolicyManager;
    @Mock private OffloadHardwareInterface mOffloadHardwareInterface;
    @Mock private Resources mResources;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private UsbManager mUsbManager;
    @Mock private WifiManager mWifiManager;
    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private UpstreamNetworkMonitor mUpstreamNetworkMonitor;
    @Mock private IPv6TetheringCoordinator mIPv6TetheringCoordinator;
    @Mock private RouterAdvertisementDaemon mRouterAdvertisementDaemon;
    @Mock private IDhcpServer mDhcpServer;
    @Mock private INetd mNetd;
    @Mock private UserManager mUserManager;
    @Mock private NetworkRequest mNetworkRequest;

    private final MockIpServerDependencies mIpServerDependencies =
            spy(new MockIpServerDependencies());
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
    private PhoneStateListener mPhoneStateListener;

    private class TestContext extends BroadcastInterceptingContext {
        TestContext(Context base) {
            super(base);
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            return mApplicationInfo;
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        public String getPackageName() {
            return "TetheringTest";
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.WIFI_SERVICE.equals(name)) return mWifiManager;
            if (Context.USB_SERVICE.equals(name)) return mUsbManager;
            if (Context.TELEPHONY_SERVICE.equals(name)) return mTelephonyManager;
            if (Context.USER_SERVICE.equals(name)) return mUserManager;
            return super.getSystemService(name);
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            if (TelephonyManager.class.equals(serviceClass)) return Context.TELEPHONY_SERVICE;
            return super.getSystemServiceName(serviceClass);
        }
    }

    public class MockIpServerDependencies extends IpServer.Dependencies {
        @Override
        public RouterAdvertisementDaemon getRouterAdvertisementDaemon(
                InterfaceParams ifParams) {
            return mRouterAdvertisementDaemon;
        }

        @Override
        public InterfaceParams getInterfaceParams(String ifName) {
            assertTrue("Non-mocked interface " + ifName,
                    ifName.equals(TEST_USB_IFNAME)
                            || ifName.equals(TEST_WLAN_IFNAME)
                            || ifName.equals(TEST_MOBILE_IFNAME)
                            || ifName.equals(TEST_P2P_IFNAME));
            final String[] ifaces = new String[] {
                    TEST_USB_IFNAME, TEST_WLAN_IFNAME, TEST_MOBILE_IFNAME, TEST_P2P_IFNAME};
            return new InterfaceParams(ifName, ArrayUtils.indexOf(ifaces, ifName) + IFINDEX_OFFSET,
                    MacAddress.ALL_ZEROS_ADDRESS);
        }

        @Override
        public INetd getNetdService() {
            return mNetd;
        }

        @Override
        public void makeDhcpServer(String ifName, DhcpServingParamsParcel params,
                DhcpServerCallbacks cb) {
            new Thread(() -> {
                try {
                    cb.onDhcpServerCreated(STATUS_SUCCESS, mDhcpServer);
                } catch (RemoteException e) {
                    fail(e.getMessage());
                }
            }).run();
        }
    }

    private class MockTetheringConfiguration extends TetheringConfiguration {
        MockTetheringConfiguration(Context ctx, SharedLog log, int id) {
            super(ctx, log, id);
        }

        @Override
        protected Resources getResourcesForSubIdWrapper(Context ctx, int subId) {
            return mResources;
        }
    }

    public class MockTetheringDependencies extends TetheringDependencies {
        StateMachine mUpstreamNetworkMonitorMasterSM;
        ArrayList<IpServer> mIpv6CoordinatorNotifyList;
        int mIsTetheringSupportedCalls;

        public void reset() {
            mUpstreamNetworkMonitorMasterSM = null;
            mIpv6CoordinatorNotifyList = null;
            mIsTetheringSupportedCalls = 0;
        }

        @Override
        public OffloadHardwareInterface getOffloadHardwareInterface(Handler h, SharedLog log) {
            return mOffloadHardwareInterface;
        }

        @Override
        public UpstreamNetworkMonitor getUpstreamNetworkMonitor(Context ctx,
                StateMachine target, SharedLog log, int what) {
            mUpstreamNetworkMonitorMasterSM = target;
            return mUpstreamNetworkMonitor;
        }

        @Override
        public IPv6TetheringCoordinator getIPv6TetheringCoordinator(
                ArrayList<IpServer> notifyList, SharedLog log) {
            mIpv6CoordinatorNotifyList = notifyList;
            return mIPv6TetheringCoordinator;
        }

        @Override
        public IpServer.Dependencies getIpServerDependencies() {
            return mIpServerDependencies;
        }

        @Override
        public NetworkRequest getDefaultNetworkRequest() {
            return mNetworkRequest;
        }

        @Override
        public boolean isTetheringSupported() {
            mIsTetheringSupportedCalls++;
            return true;
        }

        @Override
        public TetheringConfiguration generateTetheringConfiguration(Context ctx, SharedLog log,
                int subId) {
            return new MockTetheringConfiguration(ctx, log, subId);
        }

        @Override
        public INetworkManagementService getINetworkManagementService() {
            return mNMService;
        }

        @Override
        public INetworkStatsService getINetworkStatsService() {
            return mStatsService;
        }

        @Override
        public INetworkPolicyManager getINetworkPolicyManager() {
            return mPolicyManager;
        }

        @Override
        public INetd getINetd(Context context) {
            return mNetd;
        }

        @Override
        public Looper getTetheringLooper() {
            return mLooper.getLooper();
        }

        @Override
        public Context getContext() {
            return mServiceContext;
        }
    }

    private static UpstreamNetworkState buildMobileUpstreamState(boolean withIPv4,
            boolean withIPv6, boolean with464xlat) {
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_MOBILE_IFNAME);

        if (withIPv4) {
            prop.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0),
                    InetAddresses.parseNumericAddress("10.0.0.1"),
                    TEST_MOBILE_IFNAME, RTN_UNICAST));
        }

        if (withIPv6) {
            prop.addDnsServer(InetAddresses.parseNumericAddress("2001:db8::2"));
            prop.addLinkAddress(
                    new LinkAddress(InetAddresses.parseNumericAddress("2001:db8::"),
                            NetworkConstants.RFC7421_PREFIX_LENGTH));
            prop.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0),
                    InetAddresses.parseNumericAddress("2001:db8::1"),
                    TEST_MOBILE_IFNAME, RTN_UNICAST));
        }

        if (with464xlat) {
            final LinkProperties stackedLink = new LinkProperties();
            stackedLink.setInterfaceName(TEST_XLAT_MOBILE_IFNAME);
            stackedLink.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0),
                    InetAddresses.parseNumericAddress("192.0.0.1"),
                    TEST_XLAT_MOBILE_IFNAME, RTN_UNICAST));

            prop.addStackedLink(stackedLink);
        }


        final NetworkCapabilities capabilities = new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        return new UpstreamNetworkState(prop, capabilities, new Network(100));
    }

    private static UpstreamNetworkState buildMobileIPv4UpstreamState() {
        return buildMobileUpstreamState(true, false, false);
    }

    private static UpstreamNetworkState buildMobileIPv6UpstreamState() {
        return buildMobileUpstreamState(false, true, false);
    }

    private static UpstreamNetworkState buildMobileDualStackUpstreamState() {
        return buildMobileUpstreamState(true, true, false);
    }

    private static UpstreamNetworkState buildMobile464xlatUpstreamState() {
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
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_wifi_p2p_regexs))
                .thenReturn(new String[]{ "test_p2p-p2p\\d-.*" });
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_bluetooth_regexs))
                .thenReturn(new String[0]);
        when(mResources.getIntArray(com.android.internal.R.array.config_tether_upstream_types))
                .thenReturn(new int[0]);
        when(mResources.getBoolean(com.android.internal.R.bool.config_tether_upstream_automatic))
                .thenReturn(false);
        when(mNMService.listInterfaces())
                .thenReturn(new String[] {
                        TEST_MOBILE_IFNAME, TEST_WLAN_IFNAME, TEST_USB_IFNAME, TEST_P2P_IFNAME});
        when(mNMService.getInterfaceConfig(anyString()))
                .thenReturn(new InterfaceConfiguration());
        when(mRouterAdvertisementDaemon.start())
                .thenReturn(true);

        mServiceContext = new TestContext(mContext);
        mContentResolver = new MockContentResolver(mServiceContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        Settings.Global.putInt(mContentResolver, TETHER_ENABLE_LEGACY_DHCP_SERVER, 0);
        mIntents = new Vector<>();
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mIntents.addElement(intent);
            }
        };
        mServiceContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(ACTION_TETHER_STATE_CHANGED));
        mTethering = makeTethering();
        verify(mNMService).registerTetheringStatsProvider(any(), anyString());
        verify(mNetd).registerUnsolicitedEventListener(any());
        final ArgumentCaptor<PhoneStateListener> phoneListenerCaptor =
                ArgumentCaptor.forClass(PhoneStateListener.class);
        verify(mTelephonyManager).listen(phoneListenerCaptor.capture(),
                eq(PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE));
        mPhoneStateListener = phoneListenerCaptor.getValue();
    }

    private Tethering makeTethering() {
        mTetheringDependencies.reset();
        return new Tethering(mTetheringDependencies);
    }

    @After
    public void tearDown() {
        mServiceContext.unregisterReceiver(mBroadcastReceiver);
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

    private static final String[] P2P_RECEIVER_PERMISSIONS_FOR_BROADCAST = {
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_WIFI_STATE
    };

    private void sendWifiP2pConnectionChanged(
            boolean isGroupFormed, boolean isGroupOwner, String ifname) {
        WifiP2pInfo p2pInfo = new WifiP2pInfo();
        p2pInfo.groupFormed = isGroupFormed;
        p2pInfo.isGroupOwner = isGroupOwner;

        NetworkInfo networkInfo = new NetworkInfo(TYPE_WIFI_P2P, 0, null, null);

        WifiP2pGroup group = new WifiP2pGroup();
        group.setIsGroupOwner(isGroupOwner);
        group.setInterface(ifname);

        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, p2pInfo);
        intent.putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, networkInfo);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, group);
        mServiceContext.sendBroadcastAsUserMultiplePermissions(intent, UserHandle.ALL,
                P2P_RECEIVER_PERMISSIONS_FOR_BROADCAST);
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

    private void verifyInterfaceServingModeStarted(String ifname) throws Exception {
        verify(mNMService, times(1)).getInterfaceConfig(ifname);
        verify(mNMService, times(1))
                .setInterfaceConfig(eq(ifname), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).tetherInterface(ifname);
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

        // If, and only if, Tethering received an interface status changed then
        // it creates a IpServer and sends out a broadcast indicating that the
        // interface is "available".
        if (emulateInterfaceStatusChanged) {
            assertEquals(1, mTetheringDependencies.mIsTetheringSupportedCalls);
            verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_AVAILABLE_TETHER);
            verify(mWifiManager).updateInterfaceIpState(
                    TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        }
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
    }

    private void prepareUsbTethering(UpstreamNetworkState upstreamState) {
        when(mUpstreamNetworkMonitor.getCurrentPreferredUpstream()).thenReturn(upstreamState);
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
        UpstreamNetworkState upstreamState = buildMobileIPv4UpstreamState();
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

        verifyInterfaceServingModeStarted(TEST_WLAN_IFNAME);
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_AVAILABLE_TETHER);
        verify(mNMService, times(1)).setIpForwardingEnabled(true);
        verify(mNMService, times(1)).startTethering(any(String[].class));
        verifyNoMoreInteractions(mNMService);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
        verifyNoMoreInteractions(mWifiManager);
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_ACTIVE_LOCAL_ONLY);
        verify(mUpstreamNetworkMonitor, times(1)).startObserveAllNetworks();
        // This will be called twice, one is on entering IpServer.STATE_AVAILABLE,
        // and another one is on IpServer.STATE_TETHERED/IpServer.STATE_LOCAL_ONLY.
        assertEquals(2, mTetheringDependencies.mIsTetheringSupportedCalls);

        // Emulate externally-visible WifiManager effects, when hotspot mode
        // is being torn down.
        sendWifiApStateChanged(WifiManager.WIFI_AP_STATE_DISABLED);
        mTethering.interfaceRemoved(TEST_WLAN_IFNAME);
        mLooper.dispatchAll();

        verify(mNMService, times(1)).untetherInterface(TEST_WLAN_IFNAME);
        // {g,s}etInterfaceConfig() called twice for enabling and disabling IPv4.
        verify(mNMService, times(2)).getInterfaceConfig(TEST_WLAN_IFNAME);
        verify(mNMService, times(2))
                .setInterfaceConfig(eq(TEST_WLAN_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).stopTethering();
        verify(mNMService, times(1)).setIpForwardingEnabled(false);
        verify(mWifiManager, times(3)).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
        // Asking for the last error after the per-interface state machine
        // has been reaped yields an unknown interface error.
        assertEquals(TETHER_ERROR_UNKNOWN_IFACE, mTethering.getLastTetherError(TEST_WLAN_IFNAME));
    }

    /**
     * Send CMD_IPV6_TETHER_UPDATE to IpServers as would be done by IPv6TetheringCoordinator.
     */
    private void sendIPv6TetherUpdates(UpstreamNetworkState upstreamState) {
        // IPv6TetheringCoordinator must have been notified of downstream
        verify(mIPv6TetheringCoordinator, times(1)).addActiveDownstream(
                argThat(sm -> sm.linkProperties().getInterfaceName().equals(TEST_USB_IFNAME)),
                eq(IpServer.STATE_TETHERED));

        for (IpServer ipSrv : mTetheringDependencies.mIpv6CoordinatorNotifyList) {
            UpstreamNetworkState ipv6OnlyState = buildMobileUpstreamState(false, true, false);
            ipSrv.sendMessage(IpServer.CMD_IPV6_TETHER_UPDATE, 0, 0,
                    upstreamState.linkProperties.isIpv6Provisioned()
                            ? ipv6OnlyState.linkProperties
                            : null);
        }
        mLooper.dispatchAll();
    }

    private void runUsbTethering(UpstreamNetworkState upstreamState) {
        prepareUsbTethering(upstreamState);
        sendUsbBroadcast(true, true, true);
        mLooper.dispatchAll();
    }

    @Test
    public void workingMobileUsbTethering_IPv4() throws Exception {
        UpstreamNetworkState upstreamState = buildMobileIPv4UpstreamState();
        runUsbTethering(upstreamState);

        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);

        sendIPv6TetherUpdates(upstreamState);
        verify(mRouterAdvertisementDaemon, never()).buildNewRa(any(), notNull());
        verify(mDhcpServer, timeout(DHCPSERVER_START_TIMEOUT_MS).times(1)).start(any());
    }

    @Test
    public void workingMobileUsbTethering_IPv4LegacyDhcp() {
        Settings.Global.putInt(mContentResolver, TETHER_ENABLE_LEGACY_DHCP_SERVER, 1);
        sendConfigurationChanged();
        final UpstreamNetworkState upstreamState = buildMobileIPv4UpstreamState();
        runUsbTethering(upstreamState);
        sendIPv6TetherUpdates(upstreamState);

        verify(mIpServerDependencies, never()).makeDhcpServer(any(), any(), any());
    }

    @Test
    public void workingMobileUsbTethering_IPv6() throws Exception {
        UpstreamNetworkState upstreamState = buildMobileIPv6UpstreamState();
        runUsbTethering(upstreamState);

        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);

        sendIPv6TetherUpdates(upstreamState);
        verify(mRouterAdvertisementDaemon, times(1)).buildNewRa(any(), notNull());
        verify(mNetd, times(1)).tetherApplyDnsInterfaces();
    }

    @Test
    public void workingMobileUsbTethering_DualStack() throws Exception {
        UpstreamNetworkState upstreamState = buildMobileDualStackUpstreamState();
        runUsbTethering(upstreamState);

        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mRouterAdvertisementDaemon, times(1)).start();
        verify(mDhcpServer, timeout(DHCPSERVER_START_TIMEOUT_MS).times(1)).start(any());

        sendIPv6TetherUpdates(upstreamState);
        verify(mRouterAdvertisementDaemon, times(1)).buildNewRa(any(), notNull());
        verify(mNetd, times(1)).tetherApplyDnsInterfaces();
    }

    @Test
    public void workingMobileUsbTethering_MultipleUpstreams() throws Exception {
        UpstreamNetworkState upstreamState = buildMobile464xlatUpstreamState();
        runUsbTethering(upstreamState);

        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_XLAT_MOBILE_IFNAME);
        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mDhcpServer, timeout(DHCPSERVER_START_TIMEOUT_MS).times(1)).start(any());
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
        UpstreamNetworkState upstreamState = buildMobileIPv6UpstreamState();
        runUsbTethering(upstreamState);

        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mDhcpServer, timeout(DHCPSERVER_START_TIMEOUT_MS).times(1)).start(any());
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);

        // Then 464xlat comes up
        upstreamState = buildMobile464xlatUpstreamState();
        when(mUpstreamNetworkMonitor.selectPreferredUpstreamType(any()))
                .thenReturn(upstreamState);

        // Upstream LinkProperties changed: UpstreamNetworkMonitor sends EVENT_ON_LINKPROPERTIES.
        mTetheringDependencies.mUpstreamNetworkMonitorMasterSM.sendMessage(
                Tethering.TetherMasterSM.EVENT_UPSTREAM_CALLBACK,
                UpstreamNetworkMonitor.EVENT_ON_LINKPROPERTIES,
                0,
                upstreamState);
        mLooper.dispatchAll();

        // Forwarding is added for 464xlat
        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_XLAT_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME,
                TEST_XLAT_MOBILE_IFNAME);
        // Forwarding was not re-added for v6 (still times(1))
        verify(mNMService, times(1)).enableNat(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        verify(mNMService, times(1)).startInterfaceForwarding(TEST_USB_IFNAME, TEST_MOBILE_IFNAME);
        // DHCP not restarted on downstream (still times(1))
        verify(mDhcpServer, timeout(DHCPSERVER_START_TIMEOUT_MS).times(1)).start(any());
    }

    @Test
    public void configTetherUpstreamAutomaticIgnoresConfigTetherUpstreamTypes() throws Exception {
        when(mResources.getBoolean(com.android.internal.R.bool.config_tether_upstream_automatic))
                .thenReturn(true);
        sendConfigurationChanged();

        // Setup IPv6
        final UpstreamNetworkState upstreamState = buildMobileIPv6UpstreamState();
        runUsbTethering(upstreamState);

        // UpstreamNetworkMonitor should choose upstream automatically
        // (in this specific case: choose the default network).
        verify(mUpstreamNetworkMonitor, times(1)).getCurrentPreferredUpstream();
        verify(mUpstreamNetworkMonitor, never()).selectPreferredUpstreamType(any());

        verify(mUpstreamNetworkMonitor, times(1)).setCurrentUpstream(upstreamState.network);
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
        when(mWifiManager.startTetheredHotspot(any(SoftApConfiguration.class))).thenReturn(true);

        // Emulate pressing the WiFi tethering button.
        mTethering.startTethering(TETHERING_WIFI, null, false);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).startTetheredHotspot(null);
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // tethering mode is to be started.
        mTethering.interfaceStatusChanged(TEST_WLAN_IFNAME, true);
        sendWifiApStateChanged(WIFI_AP_STATE_ENABLED);
        mLooper.dispatchAll();

        assertEquals(1, mTetheringDependencies.mIsTetheringSupportedCalls);
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_AVAILABLE_TETHER);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
    }

    // TODO: Test with and without interfaceStatusChanged().
    @Test
    public void workingWifiTetheringEnrichedApBroadcast() throws Exception {
        when(mWifiManager.startTetheredHotspot(any(SoftApConfiguration.class))).thenReturn(true);

        // Emulate pressing the WiFi tethering button.
        mTethering.startTethering(TETHERING_WIFI, null, false);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).startTetheredHotspot(null);
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // tethering mode is to be started.
        mTethering.interfaceStatusChanged(TEST_WLAN_IFNAME, true);
        sendWifiApStateChanged(WIFI_AP_STATE_ENABLED, TEST_WLAN_IFNAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();

        verifyInterfaceServingModeStarted(TEST_WLAN_IFNAME);
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_AVAILABLE_TETHER);
        verify(mNMService, times(1)).setIpForwardingEnabled(true);
        verify(mNMService, times(1)).startTethering(any(String[].class));
        verifyNoMoreInteractions(mNMService);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_TETHERED);
        verifyNoMoreInteractions(mWifiManager);
        verifyTetheringBroadcast(TEST_WLAN_IFNAME, EXTRA_ACTIVE_TETHER);
        verify(mUpstreamNetworkMonitor, times(1)).startObserveAllNetworks();
        // In tethering mode, in the default configuration, an explicit request
        // for a mobile network is also made.
        verify(mUpstreamNetworkMonitor, times(1)).registerMobileNetworkRequest();
        // This will be called twice, one is on entering IpServer.STATE_AVAILABLE,
        // and another one is on IpServer.STATE_TETHERED/IpServer.STATE_LOCAL_ONLY.
        assertEquals(2, mTetheringDependencies.mIsTetheringSupportedCalls);

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
        // {g,s}etInterfaceConfig() called twice for enabling and disabling IPv4.
        verify(mNMService, atLeastOnce()).getInterfaceConfig(TEST_WLAN_IFNAME);
        verify(mNMService, atLeastOnce())
                .setInterfaceConfig(eq(TEST_WLAN_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).stopTethering();
        verify(mNMService, times(1)).setIpForwardingEnabled(false);
        verify(mWifiManager, times(3)).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        verifyNoMoreInteractions(mNMService);
        verifyNoMoreInteractions(mWifiManager);
        // Asking for the last error after the per-interface state machine
        // has been reaped yields an unknown interface error.
        assertEquals(TETHER_ERROR_UNKNOWN_IFACE, mTethering.getLastTetherError(TEST_WLAN_IFNAME));
    }

    // TODO: Test with and without interfaceStatusChanged().
    @Test
    public void failureEnablingIpForwarding() throws Exception {
        when(mWifiManager.startTetheredHotspot(any(SoftApConfiguration.class))).thenReturn(true);
        doThrow(new RemoteException()).when(mNMService).setIpForwardingEnabled(true);

        // Emulate pressing the WiFi tethering button.
        mTethering.startTethering(TETHERING_WIFI, null, false);
        mLooper.dispatchAll();
        verify(mWifiManager, times(1)).startTetheredHotspot(null);
        verifyNoMoreInteractions(mWifiManager);
        verifyNoMoreInteractions(mNMService);

        // Emulate externally-visible WifiManager effects, causing the
        // per-interface state machine to start up, and telling us that
        // tethering mode is to be started.
        mTethering.interfaceStatusChanged(TEST_WLAN_IFNAME, true);
        sendWifiApStateChanged(WIFI_AP_STATE_ENABLED, TEST_WLAN_IFNAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();

        // We verify get/set called thrice here: twice for setup (on NMService) and once during
        // teardown (on Netd) because all events happen over the course of the single
        // dispatchAll() above. Note that once the IpServer IPv4 address config
        // code is refactored the two calls during shutdown will revert to one.
        verify(mNMService, times(2)).getInterfaceConfig(TEST_WLAN_IFNAME);
        verify(mNMService, times(2))
                .setInterfaceConfig(eq(TEST_WLAN_IFNAME), any(InterfaceConfiguration.class));
        verify(mNetd, times(1)).interfaceSetCfg(argThat(p -> TEST_WLAN_IFNAME.equals(p.ifName)));
        verify(mNMService, times(1)).tetherInterface(TEST_WLAN_IFNAME);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        verify(mWifiManager).updateInterfaceIpState(
                TEST_WLAN_IFNAME, WifiManager.IFACE_IP_MODE_TETHERED);
        // There are 3 state change event:
        // AVAILABLE -> STATE_TETHERED -> STATE_AVAILABLE.
        assertEquals(3, mTetheringDependencies.mIsTetheringSupportedCalls);
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

    private void runUserRestrictionsChange(
            boolean currentDisallow, boolean nextDisallow, String[] activeTetheringIfacesList,
            int expectedInteractionsWithShowNotification) throws  Exception {
        final Bundle newRestrictions = new Bundle();
        newRestrictions.putBoolean(UserManager.DISALLOW_CONFIG_TETHERING, nextDisallow);
        final Tethering mockTethering = mock(Tethering.class);
        when(mockTethering.getTetheredIfaces()).thenReturn(activeTetheringIfacesList);
        when(mUserManager.getUserRestrictions()).thenReturn(newRestrictions);

        final Tethering.UserRestrictionActionListener ural =
                new Tethering.UserRestrictionActionListener(mUserManager, mockTethering);
        ural.mDisallowTethering = currentDisallow;

        ural.onUserRestrictionsChanged();

        verify(mockTethering, times(expectedInteractionsWithShowNotification))
                .showTetheredNotification(anyInt(), eq(false));

        verify(mockTethering, times(expectedInteractionsWithShowNotification))
                .untetherAll();
    }

    @Test
    public void testDisallowTetheringWhenNoTetheringInterfaceIsActive() throws Exception {
        final String[] emptyActiveIfacesList = new String[]{};
        final boolean currDisallow = false;
        final boolean nextDisallow = true;
        final int expectedInteractionsWithShowNotification = 0;

        runUserRestrictionsChange(currDisallow, nextDisallow, emptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }

    @Test
    public void testDisallowTetheringWhenAtLeastOneTetheringInterfaceIsActive() throws Exception {
        final String[] nonEmptyActiveIfacesList = new String[]{TEST_WLAN_IFNAME};
        final boolean currDisallow = false;
        final boolean nextDisallow = true;
        final int expectedInteractionsWithShowNotification = 1;

        runUserRestrictionsChange(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }

    @Test
    public void testAllowTetheringWhenNoTetheringInterfaceIsActive() throws Exception {
        final String[] nonEmptyActiveIfacesList = new String[]{};
        final boolean currDisallow = true;
        final boolean nextDisallow = false;
        final int expectedInteractionsWithShowNotification = 0;

        runUserRestrictionsChange(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }

    @Test
    public void testAllowTetheringWhenAtLeastOneTetheringInterfaceIsActive() throws Exception {
        final String[] nonEmptyActiveIfacesList = new String[]{TEST_WLAN_IFNAME};
        final boolean currDisallow = true;
        final boolean nextDisallow = false;
        final int expectedInteractionsWithShowNotification = 0;

        runUserRestrictionsChange(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }

    @Test
    public void testDisallowTetheringUnchanged() throws Exception {
        final String[] nonEmptyActiveIfacesList = new String[]{TEST_WLAN_IFNAME};
        final int expectedInteractionsWithShowNotification = 0;
        boolean currDisallow = true;
        boolean nextDisallow = true;

        runUserRestrictionsChange(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);

        currDisallow = false;
        nextDisallow = false;

        runUserRestrictionsChange(currDisallow, nextDisallow, nonEmptyActiveIfacesList,
                expectedInteractionsWithShowNotification);
    }

    private class TestTetheringEventCallback extends ITetheringEventCallback.Stub {
        private final ArrayList<Network> mActualUpstreams = new ArrayList<>();
        private final ArrayList<TetheringConfigurationParcel> mTetheringConfigs =
                new ArrayList<>();
        private final ArrayList<TetherStatesParcel> mTetherStates = new ArrayList<>();

        // This function will remove the recorded callbacks, so it must be called once for
        // each callback. If this is called after multiple callback, the order matters.
        // onCallbackCreated counts as the first call to expectUpstreamChanged with
        // @see onCallbackCreated.
        public void expectUpstreamChanged(Network... networks) {
            if (networks == null) {
                assertNoUpstreamChangeCallback();
                return;
            }

            final ArrayList<Network> expectedUpstreams =
                    new ArrayList<Network>(Arrays.asList(networks));
            for (Network upstream : expectedUpstreams) {
                // throws OOB if no expectations
                assertEquals(mActualUpstreams.remove(0), upstream);
            }
            assertNoUpstreamChangeCallback();
        }

        // This function will remove the recorded callbacks, so it must be called once
        // for each callback. If this is called after multiple callback, the order matters.
        // onCallbackCreated counts as the first call to onConfigurationChanged with
        // @see onCallbackCreated.
        public void expectConfigurationChanged(TetheringConfigurationParcel... tetherConfigs) {
            final ArrayList<TetheringConfigurationParcel> expectedTetherConfig =
                    new ArrayList<TetheringConfigurationParcel>(Arrays.asList(tetherConfigs));
            for (TetheringConfigurationParcel config : expectedTetherConfig) {
                // throws OOB if no expectations
                final TetheringConfigurationParcel actualConfig = mTetheringConfigs.remove(0);
                assertTetherConfigParcelEqual(actualConfig, config);
            }
            assertNoConfigChangeCallback();
        }

        public TetherStatesParcel pollTetherStatesChanged() {
            assertStateChangeCallback();
            return mTetherStates.remove(0);
        }

        @Override
        public void onUpstreamChanged(Network network) {
            mActualUpstreams.add(network);
        }

        @Override
        public void onConfigurationChanged(TetheringConfigurationParcel config) {
            mTetheringConfigs.add(config);
        }

        @Override
        public void onTetherStatesChanged(TetherStatesParcel states) {
            mTetherStates.add(states);
        }

        @Override
        public void onCallbackStarted(Network network, TetheringConfigurationParcel config,
                TetherStatesParcel states) {
            mActualUpstreams.add(network);
            mTetheringConfigs.add(config);
            mTetherStates.add(states);
        }

        @Override
        public void onCallbackStopped(int errorCode) { }

        public void assertNoUpstreamChangeCallback() {
            assertTrue(mActualUpstreams.isEmpty());
        }

        public void assertNoConfigChangeCallback() {
            assertTrue(mTetheringConfigs.isEmpty());
        }

        public void assertNoStateChangeCallback() {
            assertTrue(mTetherStates.isEmpty());
        }

        public void assertStateChangeCallback() {
            assertFalse(mTetherStates.isEmpty());
        }

        public void assertNoCallback() {
            assertNoUpstreamChangeCallback();
            assertNoConfigChangeCallback();
            assertNoStateChangeCallback();
        }

        private void assertTetherConfigParcelEqual(@NonNull TetheringConfigurationParcel actual,
                @NonNull TetheringConfigurationParcel expect) {
            assertEquals(actual.subId, expect.subId);
            assertArrayEquals(actual.tetherableUsbRegexs, expect.tetherableUsbRegexs);
            assertArrayEquals(actual.tetherableWifiRegexs, expect.tetherableWifiRegexs);
            assertArrayEquals(actual.tetherableBluetoothRegexs, expect.tetherableBluetoothRegexs);
            assertEquals(actual.isDunRequired, expect.isDunRequired);
            assertEquals(actual.chooseUpstreamAutomatically, expect.chooseUpstreamAutomatically);
            assertArrayEquals(actual.preferredUpstreamIfaceTypes,
                    expect.preferredUpstreamIfaceTypes);
            assertArrayEquals(actual.legacyDhcpRanges, expect.legacyDhcpRanges);
            assertArrayEquals(actual.defaultIPv4DNS, expect.defaultIPv4DNS);
            assertEquals(actual.enableLegacyDhcpServer, expect.enableLegacyDhcpServer);
            assertArrayEquals(actual.provisioningApp, expect.provisioningApp);
            assertEquals(actual.provisioningAppNoUi, expect.provisioningAppNoUi);
            assertEquals(actual.provisioningCheckPeriod, expect.provisioningCheckPeriod);
        }
    }

    @Test
    public void testRegisterTetheringEventCallback() throws Exception {
        TestTetheringEventCallback callback = new TestTetheringEventCallback();
        TestTetheringEventCallback callback2 = new TestTetheringEventCallback();

        // 1. Register one callback before running any tethering.
        mTethering.registerTetheringEventCallback(callback);
        mLooper.dispatchAll();
        callback.expectUpstreamChanged(new Network[] {null});
        callback.expectConfigurationChanged(
                mTethering.getTetheringConfiguration().toStableParcelable());
        TetherStatesParcel tetherState = callback.pollTetherStatesChanged();
        assertEquals(tetherState, null);
        // 2. Enable wifi tethering.
        UpstreamNetworkState upstreamState = buildMobileDualStackUpstreamState();
        when(mUpstreamNetworkMonitor.getCurrentPreferredUpstream()).thenReturn(upstreamState);
        when(mUpstreamNetworkMonitor.selectPreferredUpstreamType(any()))
                .thenReturn(upstreamState);
        when(mWifiManager.startTetheredHotspot(any(SoftApConfiguration.class))).thenReturn(true);
        mTethering.interfaceStatusChanged(TEST_WLAN_IFNAME, true);
        mLooper.dispatchAll();
        tetherState = callback.pollTetherStatesChanged();
        assertArrayEquals(tetherState.availableList, new String[] {TEST_WLAN_IFNAME});

        mTethering.startTethering(TETHERING_WIFI, null, false);
        sendWifiApStateChanged(WIFI_AP_STATE_ENABLED, TEST_WLAN_IFNAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();
        tetherState = callback.pollTetherStatesChanged();
        assertArrayEquals(tetherState.tetheredList, new String[] {TEST_WLAN_IFNAME});
        callback.expectUpstreamChanged(upstreamState.network);

        // 3. Register second callback.
        mTethering.registerTetheringEventCallback(callback2);
        mLooper.dispatchAll();
        callback2.expectUpstreamChanged(upstreamState.network);
        callback2.expectConfigurationChanged(
                mTethering.getTetheringConfiguration().toStableParcelable());
        tetherState = callback2.pollTetherStatesChanged();
        assertEquals(tetherState.tetheredList, new String[] {TEST_WLAN_IFNAME});

        // 4. Unregister first callback and disable wifi tethering
        mTethering.unregisterTetheringEventCallback(callback);
        mLooper.dispatchAll();
        mTethering.stopTethering(TETHERING_WIFI);
        sendWifiApStateChanged(WifiManager.WIFI_AP_STATE_DISABLED);
        mLooper.dispatchAll();
        tetherState = callback2.pollTetherStatesChanged();
        assertArrayEquals(tetherState.availableList, new String[] {TEST_WLAN_IFNAME});
        mLooper.dispatchAll();
        callback2.expectUpstreamChanged(new Network[] {null});
        callback.assertNoCallback();
    }

    @Test
    public void testMultiSimAware() throws Exception {
        final TetheringConfiguration initailConfig = mTethering.getTetheringConfiguration();
        assertEquals(INVALID_SUBSCRIPTION_ID, initailConfig.activeDataSubId);

        final int fakeSubId = 1234;
        mPhoneStateListener.onActiveDataSubscriptionIdChanged(fakeSubId);
        final TetheringConfiguration newConfig = mTethering.getTetheringConfiguration();
        assertEquals(fakeSubId, newConfig.activeDataSubId);
    }

    private void workingWifiP2pGroupOwner(
            boolean emulateInterfaceStatusChanged) throws Exception {
        if (emulateInterfaceStatusChanged) {
            mTethering.interfaceStatusChanged(TEST_P2P_IFNAME, true);
        }
        sendWifiP2pConnectionChanged(true, true, TEST_P2P_IFNAME);
        mLooper.dispatchAll();

        verifyInterfaceServingModeStarted(TEST_P2P_IFNAME);
        verifyTetheringBroadcast(TEST_P2P_IFNAME, EXTRA_AVAILABLE_TETHER);
        verify(mNMService, times(1)).setIpForwardingEnabled(true);
        verify(mNMService, times(1)).startTethering(any(String[].class));
        verifyNoMoreInteractions(mNMService);
        verifyTetheringBroadcast(TEST_P2P_IFNAME, EXTRA_ACTIVE_LOCAL_ONLY);
        verify(mUpstreamNetworkMonitor, times(1)).startObserveAllNetworks();
        // This will be called twice, one is on entering IpServer.STATE_AVAILABLE,
        // and another one is on IpServer.STATE_TETHERED/IpServer.STATE_LOCAL_ONLY.
        assertEquals(2, mTetheringDependencies.mIsTetheringSupportedCalls);

        assertEquals(TETHER_ERROR_NO_ERROR, mTethering.getLastTetherError(TEST_P2P_IFNAME));

        // Emulate externally-visible WifiP2pManager effects, when wifi p2p group
        // is being removed.
        sendWifiP2pConnectionChanged(false, true, TEST_P2P_IFNAME);
        mTethering.interfaceRemoved(TEST_P2P_IFNAME);
        mLooper.dispatchAll();

        verify(mNMService, times(1)).untetherInterface(TEST_P2P_IFNAME);
        // {g,s}etInterfaceConfig() called twice for enabling and disabling IPv4.
        verify(mNMService, times(2)).getInterfaceConfig(TEST_P2P_IFNAME);
        verify(mNMService, times(2))
                .setInterfaceConfig(eq(TEST_P2P_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, times(1)).stopTethering();
        verify(mNMService, times(1)).setIpForwardingEnabled(false);
        verify(mUpstreamNetworkMonitor, never()).getCurrentPreferredUpstream();
        verify(mUpstreamNetworkMonitor, never()).selectPreferredUpstreamType(any());
        verifyNoMoreInteractions(mNMService);
        // Asking for the last error after the per-interface state machine
        // has been reaped yields an unknown interface error.
        assertEquals(TETHER_ERROR_UNKNOWN_IFACE, mTethering.getLastTetherError(TEST_P2P_IFNAME));
    }

    private void workingWifiP2pGroupClient(
            boolean emulateInterfaceStatusChanged) throws Exception {
        if (emulateInterfaceStatusChanged) {
            mTethering.interfaceStatusChanged(TEST_P2P_IFNAME, true);
        }
        sendWifiP2pConnectionChanged(true, false, TEST_P2P_IFNAME);
        mLooper.dispatchAll();

        verify(mNMService, never()).getInterfaceConfig(TEST_P2P_IFNAME);
        verify(mNMService, never())
                .setInterfaceConfig(eq(TEST_P2P_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, never()).tetherInterface(TEST_P2P_IFNAME);
        verify(mNMService, never()).setIpForwardingEnabled(true);
        verify(mNMService, never()).startTethering(any(String[].class));

        // Emulate externally-visible WifiP2pManager effects, when wifi p2p group
        // is being removed.
        sendWifiP2pConnectionChanged(false, false, TEST_P2P_IFNAME);
        mTethering.interfaceRemoved(TEST_P2P_IFNAME);
        mLooper.dispatchAll();

        verify(mNMService, never()).untetherInterface(TEST_P2P_IFNAME);
        verify(mNMService, never()).getInterfaceConfig(TEST_P2P_IFNAME);
        verify(mNMService, never())
                .setInterfaceConfig(eq(TEST_P2P_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, never()).stopTethering();
        verify(mNMService, never()).setIpForwardingEnabled(false);
        verifyNoMoreInteractions(mNMService);
        // Asking for the last error after the per-interface state machine
        // has been reaped yields an unknown interface error.
        assertEquals(TETHER_ERROR_UNKNOWN_IFACE, mTethering.getLastTetherError(TEST_P2P_IFNAME));
    }

    @Test
    public void workingWifiP2pGroupOwnerWithIfaceChanged() throws Exception {
        workingWifiP2pGroupOwner(true);
    }

    @Test
    public void workingWifiP2pGroupOwnerSansIfaceChanged() throws Exception {
        workingWifiP2pGroupOwner(false);
    }

    private void workingWifiP2pGroupOwnerLegacyMode(
            boolean emulateInterfaceStatusChanged) throws Exception {
        // change to legacy mode and update tethering information by chaning SIM
        when(mResources.getStringArray(com.android.internal.R.array.config_tether_wifi_p2p_regexs))
                .thenReturn(new String[]{});
        final int fakeSubId = 1234;
        mPhoneStateListener.onActiveDataSubscriptionIdChanged(fakeSubId);

        if (emulateInterfaceStatusChanged) {
            mTethering.interfaceStatusChanged(TEST_P2P_IFNAME, true);
        }
        sendWifiP2pConnectionChanged(true, true, TEST_P2P_IFNAME);
        mLooper.dispatchAll();

        verify(mNMService, never()).getInterfaceConfig(TEST_P2P_IFNAME);
        verify(mNMService, never())
                .setInterfaceConfig(eq(TEST_P2P_IFNAME), any(InterfaceConfiguration.class));
        verify(mNMService, never()).tetherInterface(TEST_P2P_IFNAME);
        verify(mNMService, never()).setIpForwardingEnabled(true);
        verify(mNMService, never()).startTethering(any(String[].class));
        assertEquals(TETHER_ERROR_UNKNOWN_IFACE, mTethering.getLastTetherError(TEST_P2P_IFNAME));
    }
    @Test
    public void workingWifiP2pGroupOwnerLegacyModeWithIfaceChanged() throws Exception {
        workingWifiP2pGroupOwnerLegacyMode(true);
    }

    @Test
    public void workingWifiP2pGroupOwnerLegacyModeSansIfaceChanged() throws Exception {
        workingWifiP2pGroupOwnerLegacyMode(false);
    }

    @Test
    public void workingWifiP2pGroupClientWithIfaceChanged() throws Exception {
        workingWifiP2pGroupClient(true);
    }

    @Test
    public void workingWifiP2pGroupClientSansIfaceChanged() throws Exception {
        workingWifiP2pGroupClient(false);
    }

    // TODO: Test that a request for hotspot mode doesn't interfere with an
    // already operating tethering mode interface.
}
