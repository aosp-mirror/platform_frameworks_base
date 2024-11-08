/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vcn;

import static android.net.IpSecManager.IpSecTunnelInterface;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.vcn.VcnGatewayConnectionConfig.VCN_GATEWAY_OPTION_ENABLE_DATA_STALL_RECOVERY_WITH_MOBILITY;

import static com.android.server.vcn.VcnGatewayConnection.DUMMY_ADDR;
import static com.android.server.vcn.VcnGatewayConnection.SAFEMODE_TIMEOUT_SECONDS;
import static com.android.server.vcn.VcnGatewayConnection.VcnChildSessionConfiguration;
import static com.android.server.vcn.VcnGatewayConnection.VcnIkeSession;
import static com.android.server.vcn.VcnGatewayConnection.VcnNetworkAgent;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.ConnectivityDiagnosticsManager.ConnectivityDiagnosticsCallback;
import android.net.IpSecManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.net.vcn.VcnManager;
import android.net.vcn.VcnTransportInfo;
import android.net.wifi.WifiInfo;
import android.os.ParcelUuid;
import android.os.Process;
import android.telephony.SubscriptionInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.routeselection.UnderlyingNetworkRecord;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Tests for TelephonySubscriptionTracker */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionTest extends VcnGatewayConnectionTestBase {
    private static final int TEST_UID = Process.myUid() + 1;

    private static final String LOOPBACK_IFACE = "lo";

    private static final ParcelUuid TEST_PARCEL_UUID = new ParcelUuid(UUID.randomUUID());
    private static final int TEST_SIM_SLOT_INDEX = 1;
    private static final int TEST_SUBSCRIPTION_ID_1 = 2;
    private static final SubscriptionInfo TEST_SUBINFO_1 = mock(SubscriptionInfo.class);
    private static final int TEST_SUBSCRIPTION_ID_2 = 3;
    private static final SubscriptionInfo TEST_SUBINFO_2 = mock(SubscriptionInfo.class);
    private static final Map<Integer, ParcelUuid> TEST_SUBID_TO_GROUP_MAP;
    private static final String TEST_TCP_BUFFER_SIZES = "1,2,3,4,5,6";
    private static final int TEST_MTU = 1300;
    private static final int TEST_MTU_DELTA = 64;
    private static final List<LinkAddress> TEST_INTERNAL_ADDRESSES =
            Arrays.asList(new LinkAddress(DUMMY_ADDR, 16));
    private static final List<InetAddress> TEST_DNS_ADDRESSES = Arrays.asList(DUMMY_ADDR);

    private static final int TEST_UPSTREAM_BANDWIDTH = 1234;
    private static final int TEST_DOWNSTREAM_BANDWIDTH = 2345;

    static {
        final Map<Integer, ParcelUuid> subIdToGroupMap = new HashMap<>();
        subIdToGroupMap.put(TEST_SUBSCRIPTION_ID_1, TEST_PARCEL_UUID);
        subIdToGroupMap.put(TEST_SUBSCRIPTION_ID_2, TEST_PARCEL_UUID);
        TEST_SUBID_TO_GROUP_MAP = Collections.unmodifiableMap(subIdToGroupMap);
    }

    private WifiInfo mWifiInfo;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        mWifiInfo = mock(WifiInfo.class);
        doReturn(mWifiInfo).when(mWifiInfo).makeCopy(anyLong());
    }

    private void verifyBuildNetworkCapabilitiesCommon(
            int transportType, boolean isMobileDataEnabled) {
        final NetworkCapabilities.Builder capBuilder = new NetworkCapabilities.Builder();
        capBuilder.addTransportType(transportType);
        capBuilder.addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);
        capBuilder.addCapability(NET_CAPABILITY_NOT_METERED);
        capBuilder.addCapability(NET_CAPABILITY_NOT_ROAMING);

        if (transportType == TRANSPORT_WIFI) {
            capBuilder.setTransportInfo(mWifiInfo);
            capBuilder.setOwnerUid(TEST_UID);
        } else if (transportType == TRANSPORT_CELLULAR) {
            capBuilder.setNetworkSpecifier(
                    new TelephonyNetworkSpecifier(TEST_SUBSCRIPTION_ID_1));
        }
        capBuilder.setLinkUpstreamBandwidthKbps(TEST_UPSTREAM_BANDWIDTH);
        capBuilder.setLinkDownstreamBandwidthKbps(TEST_DOWNSTREAM_BANDWIDTH);
        capBuilder.setAdministratorUids(new int[] {TEST_UID});
        final Network underlyingNetwork = mock(Network.class, CALLS_REAL_METHODS);
        UnderlyingNetworkRecord record =
                getTestNetworkRecord(
                        underlyingNetwork, capBuilder.build(), new LinkProperties(), false);
        final NetworkCapabilities vcnCaps =
                VcnGatewayConnection.buildNetworkCapabilities(
                        VcnGatewayConnectionConfigTest.buildTestConfig(),
                        record,
                        isMobileDataEnabled);

        assertTrue(vcnCaps.hasTransport(TRANSPORT_CELLULAR));
        assertTrue(vcnCaps.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertTrue(vcnCaps.hasCapability(NET_CAPABILITY_NOT_ROAMING));
        assertTrue(vcnCaps.getUnderlyingNetworks().equals(List.of(underlyingNetwork)));

        for (int cap : VcnGatewayConnectionConfigTest.EXPOSED_CAPS) {
            if (cap == NET_CAPABILITY_INTERNET || cap == NET_CAPABILITY_DUN) {
                assertEquals(isMobileDataEnabled, vcnCaps.hasCapability(cap));
            } else {
                assertTrue(vcnCaps.hasCapability(cap));
            }
        }

        assertArrayEquals(new int[] {Process.myUid(), TEST_UID}, vcnCaps.getAdministratorUids());
        assertTrue(vcnCaps.getTransportInfo() instanceof VcnTransportInfo);
        assertEquals(TEST_UPSTREAM_BANDWIDTH, vcnCaps.getLinkUpstreamBandwidthKbps());
        assertEquals(TEST_DOWNSTREAM_BANDWIDTH, vcnCaps.getLinkDownstreamBandwidthKbps());

        final VcnTransportInfo info = (VcnTransportInfo) vcnCaps.getTransportInfo();
        if (transportType == TRANSPORT_WIFI) {
            assertEquals(mWifiInfo, info.getWifiInfo());
        } else if (transportType == TRANSPORT_CELLULAR) {
            assertEquals(TEST_SUBSCRIPTION_ID_1, info.getSubId());
        }
    }

    @Test
    public void testBuildNetworkCapabilitiesUnderlyingWifi() throws Exception {
        verifyBuildNetworkCapabilitiesCommon(TRANSPORT_WIFI, true /* isMobileDataEnabled */);
    }

    @Test
    public void testBuildNetworkCapabilitiesUnderlyingCell() throws Exception {
        verifyBuildNetworkCapabilitiesCommon(TRANSPORT_CELLULAR, true /* isMobileDataEnabled */);
    }

    @Test
    public void testBuildNetworkCapabilitiesMobileDataDisabled() throws Exception {
        verifyBuildNetworkCapabilitiesCommon(TRANSPORT_CELLULAR, false /* isMobileDataEnabled */);
    }

    @Test
    public void testBuildLinkProperties() throws Exception {
        final IpSecTunnelInterface tunnelIface =
                mContext.getSystemService(IpSecManager.class)
                        .createIpSecTunnelInterface(
                                DUMMY_ADDR, DUMMY_ADDR, TEST_UNDERLYING_NETWORK_RECORD_1.network);

        final LinkProperties underlyingLp = new LinkProperties();
        underlyingLp.setInterfaceName(LOOPBACK_IFACE);
        underlyingLp.setTcpBufferSizes(TEST_TCP_BUFFER_SIZES);
        doReturn(TEST_MTU).when(mDeps).getUnderlyingIfaceMtu(LOOPBACK_IFACE);

        final VcnChildSessionConfiguration childSessionConfig =
                mock(VcnChildSessionConfiguration.class);
        doReturn(TEST_INTERNAL_ADDRESSES).when(childSessionConfig).getInternalAddresses();
        doReturn(TEST_DNS_ADDRESSES).when(childSessionConfig).getInternalDnsServers();

        UnderlyingNetworkRecord record =
                getTestNetworkRecord(
                        mock(Network.class, CALLS_REAL_METHODS),
                        new NetworkCapabilities.Builder().build(),
                        underlyingLp,
                        false);

        final LinkProperties vcnLp1 =
                mGatewayConnection.buildConnectedLinkProperties(
                        VcnGatewayConnectionConfigTest.buildTestConfig(),
                        tunnelIface,
                        childSessionConfig,
                        record,
                        mIkeConnectionInfo);

        verify(mDeps).getUnderlyingIfaceMtu(LOOPBACK_IFACE);

        // Instead of having to recalculate the final MTU (after accounting for IPsec overhead),
        // calculate another set of Link Properties with a lower MTU, and calculate the delta.
        doReturn(TEST_MTU - TEST_MTU_DELTA).when(mDeps).getUnderlyingIfaceMtu(LOOPBACK_IFACE);

        final LinkProperties vcnLp2 =
                mGatewayConnection.buildConnectedLinkProperties(
                        VcnGatewayConnectionConfigTest.buildTestConfig(),
                        tunnelIface,
                        childSessionConfig,
                        record,
                        mIkeConnectionInfo);

        verify(mDeps, times(2)).getUnderlyingIfaceMtu(LOOPBACK_IFACE);

        assertEquals(tunnelIface.getInterfaceName(), vcnLp1.getInterfaceName());
        assertEquals(TEST_INTERNAL_ADDRESSES, vcnLp1.getLinkAddresses());
        assertEquals(TEST_DNS_ADDRESSES, vcnLp1.getDnsServers());
        assertEquals(TEST_TCP_BUFFER_SIZES, vcnLp1.getTcpBufferSizes());
        assertEquals(TEST_MTU_DELTA, vcnLp1.getMtu() - vcnLp2.getMtu());
    }

    @Test
    public void testSubscriptionSnapshotUpdateNotifiesUnderlyingNetworkController() {
        verifyWakeLockSetUp();

        final TelephonySubscriptionSnapshot updatedSnapshot =
                mock(TelephonySubscriptionSnapshot.class);
        mGatewayConnection.updateSubscriptionSnapshot(updatedSnapshot);

        verify(mUnderlyingNetworkController).updateSubscriptionSnapshot(eq(updatedSnapshot));
        verifyWakeLockAcquired();

        mTestLooper.dispatchAll();

        verifyWakeLockReleased();
    }

    @Test
    public void testNonNullUnderlyingNetworkRecordUpdateCancelsAlarm() {
        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(null);

        verifyDisconnectRequestAlarmAndGetCallback(false /* expectCanceled */);

        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_1);

        verify(mDisconnectRequestAlarm).cancel();
    }

    @Test
    public void testQuittingCleansUpPersistentState() {
        final VcnIkeSession vcnIkeSession = mock(VcnIkeSession.class);
        final VcnNetworkAgent vcnNetworkAgent = mock(VcnNetworkAgent.class);

        mGatewayConnection.setIkeSession(vcnIkeSession);
        mGatewayConnection.setNetworkAgent(vcnNetworkAgent);

        mGatewayConnection.quitNow();
        mTestLooper.dispatchAll();

        assertNull(mGatewayConnection.getIkeSession());
        verify(vcnIkeSession).kill();
        assertNull(mGatewayConnection.getNetworkAgent());
        verify(vcnNetworkAgent).unregister();

        verifyWakeLockReleased();

        verify(mConnDiagMgr)
                .unregisterConnectivityDiagnosticsCallback(
                        mGatewayConnection.getConnectivityDiagnosticsCallback());
    }

    private VcnGatewayConnection buildConnectionWithDataStallHandling(
            boolean datatStallHandlingEnabled) throws Exception {
        Set<Integer> options =
                datatStallHandlingEnabled
                        ? Collections.singleton(
                                VCN_GATEWAY_OPTION_ENABLE_DATA_STALL_RECOVERY_WITH_MOBILITY)
                        : Collections.emptySet();
        final VcnGatewayConnectionConfig gatewayConfig =
                VcnGatewayConnectionConfigTest.buildTestConfigWithGatewayOptions(options);
        final VcnGatewayConnection gatewayConnection =
                new VcnGatewayConnection(
                        mVcnContext,
                        TEST_SUB_GRP,
                        TEST_SUBSCRIPTION_SNAPSHOT,
                        gatewayConfig,
                        mGatewayStatusCallback,
                        true /* isMobileDataEnabled */,
                        mDeps);
        return gatewayConnection;
    }

    @Test
    public void testDataStallHandlingEnabled() throws Exception {
        final VcnGatewayConnection gatewayConnection =
                buildConnectionWithDataStallHandling(true /* datatStallHandlingEnabled */);

        final ArgumentCaptor<NetworkRequest> networkRequestCaptor =
                ArgumentCaptor.forClass(NetworkRequest.class);
        verify(mConnDiagMgr)
                .registerConnectivityDiagnosticsCallback(
                        networkRequestCaptor.capture(),
                        any(Executor.class),
                        eq(gatewayConnection.getConnectivityDiagnosticsCallback()));

        final NetworkRequest nr = networkRequestCaptor.getValue();
        final NetworkRequest expected =
                new NetworkRequest.Builder().addTransportType(TRANSPORT_CELLULAR).build();
        assertEquals(expected, nr);
    }

    @Test
    public void testDataStallHandlingDisabled() throws Exception {
        buildConnectionWithDataStallHandling(false /* datatStallHandlingEnabled */);

        verify(mConnDiagMgr, never())
                .registerConnectivityDiagnosticsCallback(
                        any(NetworkRequest.class),
                        any(Executor.class),
                        any(ConnectivityDiagnosticsCallback.class));
    }

    private void verifyGetSafeModeTimeoutMs(
            boolean isInTestMode,
            PersistableBundleWrapper carrierConfig,
            long expectedTimeoutMs)
            throws Exception {
        doReturn(isInTestMode).when(mVcnContext).isInTestMode();

        final TelephonySubscriptionSnapshot snapshot = mock(TelephonySubscriptionSnapshot.class);
        doReturn(carrierConfig).when(snapshot).getCarrierConfigForSubGrp(TEST_SUB_GRP);

        final long result =
                VcnGatewayConnection.getSafeModeTimeoutMs(mVcnContext, snapshot, TEST_SUB_GRP);

        assertEquals(expectedTimeoutMs, result);
    }

    @Test
    public void testGetSafeModeTimeoutMs() throws Exception {
        final int carrierConfigTimeoutSeconds = 20;
        final PersistableBundleWrapper carrierConfig = mock(PersistableBundleWrapper.class);
        doReturn(carrierConfigTimeoutSeconds)
                .when(carrierConfig)
                .getInt(eq(VcnManager.VCN_SAFE_MODE_TIMEOUT_SECONDS_KEY), anyInt());

        verifyGetSafeModeTimeoutMs(
                false /* isInTestMode */,
                carrierConfig,
                TimeUnit.SECONDS.toMillis(carrierConfigTimeoutSeconds));
    }

    @Test
    public void testGetSafeModeTimeoutMs_carrierConfigNull() throws Exception {
        verifyGetSafeModeTimeoutMs(
                false /* isInTestMode */,
                null /* carrierConfig */,
                TimeUnit.SECONDS.toMillis(SAFEMODE_TIMEOUT_SECONDS));
    }

    @Test
    public void testGetSafeModeTimeoutMs_configTimeoutOverrideTestModeDefault() throws Exception {
        final int carrierConfigTimeoutSeconds = 20;
        final PersistableBundleWrapper carrierConfig = mock(PersistableBundleWrapper.class);
        doReturn(carrierConfigTimeoutSeconds)
                .when(carrierConfig)
                .getInt(eq(VcnManager.VCN_SAFE_MODE_TIMEOUT_SECONDS_KEY), anyInt());

        verifyGetSafeModeTimeoutMs(
                true /* isInTestMode */,
                carrierConfig,
                TimeUnit.SECONDS.toMillis(carrierConfigTimeoutSeconds));
    }
}
