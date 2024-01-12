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

import static android.net.IpSecManager.DIRECTION_FWD;
import static android.net.IpSecManager.DIRECTION_IN;
import static android.net.IpSecManager.DIRECTION_OUT;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.ipsec.ike.exceptions.IkeProtocolException.ERROR_TYPE_AUTHENTICATION_FAILED;
import static android.net.ipsec.ike.exceptions.IkeProtocolException.ERROR_TYPE_TEMPORARY_FAILURE;
import static android.net.vcn.VcnGatewayConnectionConfigTest.MIN_UDP_PORT_4500_NAT_TIMEOUT;
import static android.net.vcn.VcnManager.VCN_ERROR_CODE_CONFIG_ERROR;
import static android.net.vcn.VcnManager.VCN_ERROR_CODE_INTERNAL_ERROR;
import static android.net.vcn.VcnManager.VCN_ERROR_CODE_NETWORK_ERROR;

import static com.android.server.vcn.VcnGatewayConnection.VcnChildSessionConfiguration;
import static com.android.server.vcn.VcnGatewayConnection.VcnIkeSession;
import static com.android.server.vcn.VcnGatewayConnection.VcnNetworkAgent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static java.util.Collections.singletonList;

import android.net.ConnectivityDiagnosticsManager.DataStallReport;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.IkeSessionConnectionInfo;
import android.net.ipsec.ike.TunnelModeChildSessionParams;
import android.net.ipsec.ike.exceptions.IkeException;
import android.net.ipsec.ike.exceptions.IkeInternalException;
import android.net.ipsec.ike.exceptions.IkeProtocolException;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.net.vcn.VcnManager.VcnErrorCode;
import android.net.vcn.VcnTransportInfo;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.vcn.VcnGatewayConnection.VcnChildSessionCallback;
import com.android.server.vcn.VcnGatewayConnection.VcnChildSessionConfiguration;
import com.android.server.vcn.VcnGatewayConnection.VcnIkeSession;
import com.android.server.vcn.VcnGatewayConnection.VcnNetworkAgent;
import com.android.server.vcn.routeselection.UnderlyingNetworkRecord;
import com.android.server.vcn.util.MtuUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Tests for VcnGatewayConnection.ConnectedState */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionConnectedStateTest extends VcnGatewayConnectionTestBase {
    private static final int PARALLEL_SA_COUNT = 4;

    private VcnIkeSession mIkeSession;
    private VcnNetworkAgent mNetworkAgent;
    private Network mVcnNetwork;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        mNetworkAgent = mock(VcnNetworkAgent.class);
        doReturn(mNetworkAgent)
                .when(mDeps)
                .newNetworkAgent(any(), any(), any(), any(), any(), any(), any(), any(), any());

        mVcnNetwork = mock(Network.class);
        doReturn(mVcnNetwork).when(mNetworkAgent).getNetwork();

        mGatewayConnection.setUnderlyingNetwork(TEST_UNDERLYING_NETWORK_RECORD_1);

        mIkeSession = mGatewayConnection.buildIkeSession(TEST_UNDERLYING_NETWORK_RECORD_1.network);
        mGatewayConnection.setIkeSession(mIkeSession);

        mGatewayConnection.transitionTo(mGatewayConnection.mConnectedState);
        mTestLooper.dispatchAll();
    }

    @Test
    public void testEnterStateCreatesNewIkeSession() throws Exception {
        verify(mDeps).newIkeSession(any(), any(), any(), any(), any());
    }

    @Test
    public void testEnterStateDoesNotCancelSafeModeAlarm() {
        verifySafeModeTimeoutAlarmAndGetCallback(false /* expectCanceled */);
    }

    @Test
    public void testNullNetworkDoesNotTriggerDisconnect() throws Exception {
        doReturn(false).when(mDeps).isAirplaneModeOn(any());

        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(null);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
        verify(mIkeSession, never()).close();
        verifyDisconnectRequestAlarmAndGetCallback(false /* expectCanceled */);
    }

    @Test
    public void testNullNetworkAirplaneModeDisconnects() throws Exception {
        doReturn(true).when(mDeps).isAirplaneModeOn(any());

        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(null);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        verify(mIkeSession).kill();
    }

    @Test
    public void testNewNetworkTriggersMigration() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_2);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
        verify(mIkeSession, never()).close();
        verify(mIkeSession).setNetwork(TEST_UNDERLYING_NETWORK_RECORD_2.network);
    }

    @Test
    public void testSameNetworkDoesNotTriggerMigration() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_1);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
    }

    private void verifyDataStallTriggersMigration(
            UnderlyingNetworkRecord networkRecord,
            Network networkWithDataStall,
            boolean expectMobilityUpdate)
            throws Exception {
        mGatewayConnection.setUnderlyingNetwork(networkRecord);
        triggerChildOpened();
        mTestLooper.dispatchAll();

        final DataStallReport report =
                new DataStallReport(
                        networkWithDataStall,
                        1234 /* reportTimestamp */,
                        1 /* detectionMethod */,
                        new LinkProperties(),
                        new NetworkCapabilities(),
                        new PersistableBundle());

        mGatewayConnection.getConnectivityDiagnosticsCallback().onDataStallSuspected(report);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());

        if (expectMobilityUpdate) {
            verify(mIkeSession).setNetwork(networkRecord.network);
        } else {
            verify(mIkeSession, never()).setNetwork(any(Network.class));
        }
    }

    @Test
    public void testDataStallTriggersMigration() throws Exception {
        verifyDataStallTriggersMigration(
                TEST_UNDERLYING_NETWORK_RECORD_1, mVcnNetwork, true /* expectMobilityUpdate */);
    }

    @Test
    public void testDataStallWontTriggerMigrationWhenOnOtherNetwork() throws Exception {
        verifyDataStallTriggersMigration(
                TEST_UNDERLYING_NETWORK_RECORD_1,
                mock(Network.class),
                false /* expectMobilityUpdate */);
    }

    @Test
    public void testDataStallWontTriggerMigrationWhenUnderlyingNetworkLost() throws Exception {
        verifyDataStallTriggersMigration(
                null /* networkRecord */, mock(Network.class), false /* expectMobilityUpdate */);
    }

    private void verifyVcnTransformsApplied(
            VcnGatewayConnection vcnGatewayConnection, boolean expectForwardTransform)
            throws Exception {
        verifyVcnTransformsApplied(
                vcnGatewayConnection,
                expectForwardTransform,
                Collections.singletonList(getChildSessionCallback()));
    }

    private void verifyVcnTransformsApplied(
            VcnGatewayConnection vcnGatewayConnection,
            boolean expectForwardTransform,
            List<VcnChildSessionCallback> callbacks)
            throws Exception {
        for (int direction : new int[] {DIRECTION_IN, DIRECTION_OUT}) {
            for (VcnChildSessionCallback cb : callbacks) {
                cb.onIpSecTransformCreated(makeDummyIpSecTransform(), direction);
            }
            mTestLooper.dispatchAll();

            verify(mIpSecSvc, times(callbacks.size()))
                    .applyTunnelModeTransform(
                            eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), eq(direction), anyInt(), any());
        }

        verify(mIpSecSvc, expectForwardTransform ? times(callbacks.size()) : never())
                .applyTunnelModeTransform(
                        eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), eq(DIRECTION_FWD), anyInt(), any());

        assertEquals(vcnGatewayConnection.mConnectedState, vcnGatewayConnection.getCurrentState());
    }

    @Test
    public void testCreatedTransformsAreApplied() throws Exception {
        verifyVcnTransformsApplied(mGatewayConnection, false /* expectForwardTransform */);
        verify(mUnderlyingNetworkController).updateInboundTransform(any(), any());
    }

    @Test
    public void testCreatedTransformsAreAppliedWithDun() throws Exception {
        VcnGatewayConnectionConfig gatewayConfig =
                VcnGatewayConnectionConfigTest.buildTestConfigWithExposedCaps(
                        NET_CAPABILITY_INTERNET, NET_CAPABILITY_DUN);
        VcnGatewayConnection gatewayConnection =
                new VcnGatewayConnection(
                        mVcnContext,
                        TEST_SUB_GRP,
                        TEST_SUBSCRIPTION_SNAPSHOT,
                        gatewayConfig,
                        mGatewayStatusCallback,
                        true /* isMobileDataEnabled */,
                        mDeps);
        gatewayConnection.setUnderlyingNetwork(TEST_UNDERLYING_NETWORK_RECORD_1);
        final VcnIkeSession session =
                gatewayConnection.buildIkeSession(TEST_UNDERLYING_NETWORK_RECORD_1.network);
        gatewayConnection.setIkeSession(session);
        gatewayConnection.transitionTo(gatewayConnection.mConnectedState);
        mTestLooper.dispatchAll();

        verifyVcnTransformsApplied(gatewayConnection, true /* expectForwardTransform */);
    }

    @Test
    public void testMigration() throws Exception {
        triggerChildOpened();
        mTestLooper.dispatchAll();
        assertEquals(mIkeConnectionInfo, mGatewayConnection.getIkeConnectionInfo());

        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_2);

        final IkeSessionConnectionInfo newIkeConnectionInfo =
                new IkeSessionConnectionInfo(
                        TEST_ADDR_V4, TEST_ADDR_V4_2, TEST_UNDERLYING_NETWORK_RECORD_2.network);
        getIkeSessionCallback().onIkeSessionConnectionInfoChanged(newIkeConnectionInfo);
        getChildSessionCallback()
                .onIpSecTransformsMigrated(makeDummyIpSecTransform(), makeDummyIpSecTransform());
        mTestLooper.dispatchAll();

        assertEquals(newIkeConnectionInfo, mGatewayConnection.getIkeConnectionInfo());

        verify(mIpSecSvc, times(2))
                .setNetworkForTunnelInterface(
                        eq(TEST_IPSEC_TUNNEL_RESOURCE_ID),
                        eq(TEST_UNDERLYING_NETWORK_RECORD_2.network),
                        any());

        for (int direction : new int[] {DIRECTION_IN, DIRECTION_OUT}) {
            verify(mIpSecSvc)
                    .applyTunnelModeTransform(
                            eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), eq(direction), anyInt(), any());
        }

        verify(mUnderlyingNetworkController).updateInboundTransform(any(), any());

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());

        final List<ChildSaProposal> saProposals =
                mConfig.getTunnelConnectionParams()
                        .getTunnelModeChildSessionParams()
                        .getSaProposals();
        final int expectedMtu =
                MtuUtils.getMtu(
                        saProposals,
                        mConfig.getMaxMtu(),
                        TEST_UNDERLYING_NETWORK_RECORD_2.linkProperties.getMtu(),
                        true /* isIpv4 */);
        verify(mNetworkAgent).sendLinkProperties(
                argThat(lp -> expectedMtu == lp.getMtu()
                        && TEST_TCP_BUFFER_SIZES_2.equals(lp.getTcpBufferSizes())));
        verify(mNetworkAgent)
                .setUnderlyingNetworks(eq(singletonList(TEST_UNDERLYING_NETWORK_RECORD_2.network)));

        // Verify revalidation is triggered on VCN network
        verify(mConnMgr).reportNetworkConnectivity(eq(mNetworkAgent.getNetwork()), eq(false));
    }

    @Test
    public void testMigrationHandleFailure() throws Exception {
        triggerChildOpened();
        mTestLooper.dispatchAll();
        assertEquals(mIkeConnectionInfo, mGatewayConnection.getIkeConnectionInfo());

        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_2);

        final IkeSessionConnectionInfo newIkeConnectionInfo =
                new IkeSessionConnectionInfo(
                        TEST_ADDR_V4, TEST_ADDR_V4_2, TEST_UNDERLYING_NETWORK_RECORD_2.network);
        getIkeSessionCallback().onIkeSessionConnectionInfoChanged(newIkeConnectionInfo);
        getChildSessionCallback()
                .onIpSecTransformsMigrated(makeDummyIpSecTransform(), makeDummyIpSecTransform());

        doThrow(new IllegalArgumentException("testMigrationHandleFailure"))
                .when(mIpSecSvc)
                .setNetworkForTunnelInterface(anyInt(), any(), any());

        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        verify(mIkeSession).close();
    }

    private void triggerChildOpened() {
        triggerChildOpened(Collections.singletonList(TEST_INTERNAL_ADDR), TEST_DNS_ADDR);
    }

    private void triggerChildOpened(List<LinkAddress> internalAddresses, InetAddress dnsAddress) {
        final VcnChildSessionConfiguration mMockChildSessionConfig =
                mock(VcnChildSessionConfiguration.class);
        doReturn(internalAddresses).when(mMockChildSessionConfig).getInternalAddresses();
        doReturn(Collections.singletonList(dnsAddress))
                .when(mMockChildSessionConfig)
                .getInternalDnsServers();

        getIkeSessionCallback().onOpened(mIkeSessionConfiguration);
        getChildSessionCallback().onOpened(mMockChildSessionConfig);
    }

    private void triggerValidation(int status) {
        final ArgumentCaptor<Consumer<Integer>> validationCallbackCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(mDeps)
                .newNetworkAgent(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        validationCallbackCaptor.capture());

        validationCallbackCaptor.getValue().accept(status);
    }

    @Test
    public void testChildOpenedRegistersNetwork() throws Exception {
        // Verify scheduled but not canceled when entering ConnectedState
        verifySafeModeTimeoutAlarmAndGetCallback(false /* expectCanceled */);
        triggerChildOpened();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
        assertEquals(mIkeConnectionInfo, mGatewayConnection.getIkeConnectionInfo());

        final ArgumentCaptor<LinkProperties> lpCaptor =
                ArgumentCaptor.forClass(LinkProperties.class);
        final ArgumentCaptor<NetworkCapabilities> ncCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        verify(mDeps)
                .newNetworkAgent(
                        eq(mVcnContext),
                        any(String.class),
                        ncCaptor.capture(),
                        lpCaptor.capture(),
                        any(),
                        // Subtype integer/name and extras do not have getters; cannot be tested.
                        argThat(nac -> nac.getLegacyType() == ConnectivityManager.TYPE_MOBILE
                                && nac.getLegacyTypeName().equals(
                                        VcnGatewayConnection.NETWORK_INFO_NETWORK_TYPE_STRING)),
                        any(),
                        any(),
                        any());
        verify(mNetworkAgent).register();
        verify(mNetworkAgent).markConnected();

        verify(mIpSecSvc)
                .addAddressToTunnelInterface(
                        eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), eq(TEST_INTERNAL_ADDR), any());

        final LinkProperties lp = lpCaptor.getValue();
        assertEquals(Collections.singletonList(TEST_INTERNAL_ADDR), lp.getLinkAddresses());
        assertEquals(Collections.singletonList(TEST_DNS_ADDR), lp.getDnsServers());
        assertEquals(TEST_TCP_BUFFER_SIZES_1, lp.getTcpBufferSizes());

        final NetworkCapabilities nc = ncCaptor.getValue();
        assertTrue(nc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(nc.hasTransport(TRANSPORT_WIFI));
        assertEquals(List.of(TEST_UNDERLYING_NETWORK_RECORD_1.network), nc.getUnderlyingNetworks());
        for (int cap : mConfig.getAllExposedCapabilities()) {
            assertTrue(nc.hasCapability(cap));
        }

        assertTrue(nc.getTransportInfo() instanceof VcnTransportInfo);
        final VcnTransportInfo vcnTransportInfo = (VcnTransportInfo) nc.getTransportInfo();
        assertEquals(
                MIN_UDP_PORT_4500_NAT_TIMEOUT,
                vcnTransportInfo.getMinUdpPort4500NatTimeoutSeconds());

        // Now that Vcn Network is up, notify it as validated and verify the SafeMode alarm is
        // canceled
        triggerValidation(NetworkAgent.VALIDATION_STATUS_VALID);
        verify(mSafeModeTimeoutAlarm).cancel();
        assertFalse(mGatewayConnection.isInSafeMode());
        verifySafeModeStateAndCallbackFired(1 /* invocationCount */, false /* isInSafeMode */);
    }

    private List<VcnChildSessionCallback> openChildAndVerifyParallelSasRequested()
            throws Exception {
        doReturn(PARALLEL_SA_COUNT)
                .when(mDeps)
                .getParallelTunnelCount(eq(TEST_SUBSCRIPTION_SNAPSHOT), eq(TEST_SUB_GRP));

        // Verify scheduled but not canceled when entering ConnectedState
        verifySafeModeTimeoutAlarmAndGetCallback(false /* expectCanceled */);
        triggerChildOpened();
        mTestLooper.dispatchAll();

        // Verify new child sessions requested
        final ArgumentCaptor<VcnChildSessionCallback> captor =
                ArgumentCaptor.forClass(VcnChildSessionCallback.class);
        verify(mIkeSession, times(PARALLEL_SA_COUNT - 1))
                .openChildSession(any(TunnelModeChildSessionParams.class), captor.capture());

        return captor.getAllValues();
    }

    private List<VcnChildSessionCallback> verifyChildOpenedRequestsAndAppliesParallelSas()
            throws Exception {
        List<VcnChildSessionCallback> callbacks = openChildAndVerifyParallelSasRequested();

        verifyVcnTransformsApplied(mGatewayConnection, false, callbacks);

        // Mock IKE calling of onOpened()
        for (VcnChildSessionCallback cb : callbacks) {
            cb.onOpened(mock(VcnChildSessionConfiguration.class));
        }
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
        return callbacks;
    }

    @Test
    public void testChildOpenedWithParallelSas() throws Exception {
        verifyChildOpenedRequestsAndAppliesParallelSas();
    }

    @Test
    public void testOpportunisticSa_ignoresPreOpenFailures() throws Exception {
        List<VcnChildSessionCallback> callbacks = openChildAndVerifyParallelSasRequested();

        for (VcnChildSessionCallback cb : callbacks) {
            cb.onClosed();
            cb.onClosedExceptionally(mock(IkeException.class));
        }
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
        assertEquals(mIkeConnectionInfo, mGatewayConnection.getIkeConnectionInfo());
    }

    private void verifyPostOpenFailuresCloseSession(boolean shouldCloseWithException)
            throws Exception {
        List<VcnChildSessionCallback> callbacks = verifyChildOpenedRequestsAndAppliesParallelSas();

        for (VcnChildSessionCallback cb : callbacks) {
            if (shouldCloseWithException) {
                cb.onClosed();
            } else {
                cb.onClosedExceptionally(mock(IkeException.class));
            }
        }
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        verify(mIkeSession).close();
    }

    @Test
    public void testOpportunisticSa_handlesPostOpenFailures_onClosed() throws Exception {
        verifyPostOpenFailuresCloseSession(false /* shouldCloseWithException */);
    }

    @Test
    public void testOpportunisticSa_handlesPostOpenFailures_onClosedExceptionally()
            throws Exception {
        verifyPostOpenFailuresCloseSession(true /* shouldCloseWithException */);
    }

    @Test
    public void testInternalAndDnsAddressesChanged() throws Exception {
        final List<LinkAddress> startingInternalAddrs =
                Arrays.asList(new LinkAddress[] {TEST_INTERNAL_ADDR, TEST_INTERNAL_ADDR_2});
        triggerChildOpened(startingInternalAddrs, TEST_DNS_ADDR);
        mTestLooper.dispatchAll();

        for (LinkAddress addr : startingInternalAddrs) {
            verify(mIpSecSvc)
                    .addAddressToTunnelInterface(
                            eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), eq(addr), any());
        }

        verify(mDeps)
                .newNetworkAgent(
                        any(),
                        any(),
                        any(),
                        argThat(
                                lp ->
                                        startingInternalAddrs.equals(lp.getLinkAddresses())
                                                && Collections.singletonList(TEST_DNS_ADDR)
                                                        .equals(lp.getDnsServers())),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());

        // Trigger another connection event, and verify that the addresses change
        final List<LinkAddress> newInternalAddrs =
                Arrays.asList(new LinkAddress[] {TEST_INTERNAL_ADDR_2, TEST_INTERNAL_ADDR_3});
        triggerChildOpened(newInternalAddrs, TEST_DNS_ADDR_2);
        mTestLooper.dispatchAll();

        // Verify addresses on tunnel network added/removed
        for (LinkAddress addr : newInternalAddrs) {
            verify(mIpSecSvc)
                    .addAddressToTunnelInterface(
                            eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), eq(addr), any());
        }
        verify(mIpSecSvc)
                .removeAddressFromTunnelInterface(
                        eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), eq(TEST_INTERNAL_ADDR), any());

        verify(mNetworkAgent).sendLinkProperties(argThat(
                lp -> newInternalAddrs.equals(lp.getLinkAddresses())
                        && Collections.singletonList(TEST_DNS_ADDR_2).equals(lp.getDnsServers())));

        // Verify that IpSecTunnelInterface only created once
        verify(mIpSecSvc).createTunnelInterface(any(), any(), any(), any(), any());
        verifyNoMoreInteractions(mIpSecSvc);
    }

    @Test
    public void testSuccessfulConnectionExitsSafeMode() throws Exception {
        verifySafeModeTimeoutNotifiesCallbackAndUnregistersNetworkAgent(
                mGatewayConnection.mConnectedState);

        assertTrue(mGatewayConnection.isInSafeMode());
        assertFalse(mGatewayConnection.isQuitting());

        triggerChildOpened();
        mTestLooper.dispatchAll();

        triggerValidation(NetworkAgent.VALIDATION_STATUS_VALID);

        verifySafeModeStateAndCallbackFired(2 /* invocationCount */, false /* isInSafeMode */);
        assertFalse(mGatewayConnection.isInSafeMode());
    }

    @Test
    public void testSubsequentFailedValidationTriggersSafeMode() throws Exception {
        triggerChildOpened();
        mTestLooper.dispatchAll();

        triggerValidation(NetworkAgent.VALIDATION_STATUS_VALID);
        verifySafeModeStateAndCallbackFired(1 /* invocationCount */, false /* isInSafeMode */);

        // Trigger a failed validation, and the subsequent safemode timeout.
        triggerValidation(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        mTestLooper.dispatchAll();

        verify(mConnMgr)
                .reportNetworkConnectivity(eq(TEST_UNDERLYING_NETWORK_RECORD_1.network), eq(false));

        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mDeps, times(2))
                .newWakeupMessage(
                        eq(mVcnContext),
                        any(),
                        eq(VcnGatewayConnection.SAFEMODE_TIMEOUT_ALARM),
                        runnableCaptor.capture());
        runnableCaptor.getValue().run();
        mTestLooper.dispatchAll();

        verifySafeModeStateAndCallbackFired(2 /* invocationCount */, true /* isInSafeMode */);
    }

    private void verifySetSafeModeAlarm(
            boolean safeModeEnabledByCaller,
            boolean safeModeConfigFlagEnabled,
            boolean expectingSafeModeEnabled)
            throws Exception {
        final VcnGatewayConnectionConfig config =
                VcnGatewayConnectionConfigTest.newTestBuilderMinimal()
                        .setSafeModeEnabled(safeModeEnabledByCaller)
                        .build();
        final VcnGatewayConnection.Dependencies deps =
                mock(VcnGatewayConnection.Dependencies.class);
        setUpWakeupMessage(
                mSafeModeTimeoutAlarm, VcnGatewayConnection.SAFEMODE_TIMEOUT_ALARM, deps);
        doReturn(safeModeConfigFlagEnabled).when(mFeatureFlags).safeModeConfig();

        final VcnGatewayConnection connection =
                new VcnGatewayConnection(
                        mVcnContext,
                        TEST_SUB_GRP,
                        TEST_SUBSCRIPTION_SNAPSHOT,
                        config,
                        mGatewayStatusCallback,
                        true /* isMobileDataEnabled */,
                        deps);

        connection.setSafeModeAlarm();

        final int expectedCallCnt = expectingSafeModeEnabled ? 1 : 0;
        verify(deps, times(expectedCallCnt))
                .newWakeupMessage(
                        eq(mVcnContext),
                        any(),
                        eq(VcnGatewayConnection.SAFEMODE_TIMEOUT_ALARM),
                        any());
    }

    @Test
    public void testSafeModeEnabled_configFlagEnabled() throws Exception {
        verifySetSafeModeAlarm(
                true /* safeModeEnabledByCaller */,
                true /* safeModeConfigFlagEnabled */,
                true /* expectingSafeModeEnabled */);
    }

    @Test
    public void testSafeModeEnabled_configFlagDisabled() throws Exception {
        verifySetSafeModeAlarm(
                true /* safeModeEnabledByCaller */,
                false /* safeModeConfigFlagEnabled */,
                true /* expectingSafeModeEnabled */);
    }

    @Test
    public void testSafeModeDisabled_configFlagEnabled() throws Exception {
        verifySetSafeModeAlarm(
                false /* safeModeEnabledByCaller */,
                true /* safeModeConfigFlagEnabled */,
                false /* expectingSafeModeEnabled */);
    }

    @Test
    public void testSafeModeDisabled_configFlagDisabled() throws Exception {
        verifySetSafeModeAlarm(
                false /* safeModeEnabledByCaller */,
                false /* safeModeConfigFlagEnabled */,
                true /* expectingSafeModeEnabled */);
    }

    private Consumer<VcnNetworkAgent> setupNetworkAndGetUnwantedCallback() {
        triggerChildOpened();
        mTestLooper.dispatchAll();

        final ArgumentCaptor<Consumer<VcnNetworkAgent>> unwantedCallbackCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(mDeps)
                .newNetworkAgent(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        unwantedCallbackCaptor.capture(),
                        any());

        return unwantedCallbackCaptor.getValue();
    }

    @Test
    public void testUnwantedNetworkAgentTriggersTeardown() throws Exception {
        final Consumer<VcnNetworkAgent> unwantedCallback = setupNetworkAndGetUnwantedCallback();

        unwantedCallback.accept(mNetworkAgent);
        mTestLooper.dispatchAll();

        assertTrue(mGatewayConnection.isQuitting());
        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
    }

    @Test
    public void testUnwantedNetworkAgentWithDisconnectedNetworkAgent() throws Exception {
        final Consumer<VcnNetworkAgent> unwantedCallback = setupNetworkAndGetUnwantedCallback();

        mGatewayConnection.setNetworkAgent(null);
        unwantedCallback.accept(mNetworkAgent);
        mTestLooper.dispatchAll();

        // Verify that the call was ignored; the state machine is still running, and the state has
        // not changed.
        assertFalse(mGatewayConnection.isQuitting());
        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
    }

    @Test
    public void testUnwantedNetworkAgentWithNewNetworkAgent() throws Exception {
        final Consumer<VcnNetworkAgent> unwantedCallback = setupNetworkAndGetUnwantedCallback();
        final VcnNetworkAgent testAgent = mock(VcnNetworkAgent.class);

        mGatewayConnection.setNetworkAgent(testAgent);
        unwantedCallback.accept(mNetworkAgent);
        mTestLooper.dispatchAll();

        assertFalse(mGatewayConnection.isQuitting());
        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
        assertEquals(testAgent, mGatewayConnection.getNetworkAgent());
    }

    @Test
    public void testChildSessionClosedTriggersDisconnect() throws Exception {
        // Verify scheduled but not canceled when entering ConnectedState
        verifySafeModeTimeoutAlarmAndGetCallback(false /* expectCanceled */);

        getChildSessionCallback().onClosed();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        verifyTeardownTimeoutAlarmAndGetCallback(false /* expectCanceled */);

        // Since network never validated, verify mSafeModeTimeoutAlarm not canceled
        verifyNoMoreInteractions(mSafeModeTimeoutAlarm);

        // The child session was closed without exception, so verify that the GatewayStatusCallback
        // was not notified
        verifyNoMoreInteractions(mGatewayStatusCallback);
    }

    @Test
    public void testChildSessionClosedExceptionallyNotifiesGatewayStatusCallback()
            throws Exception {
        final IkeInternalException exception = new IkeInternalException(mock(IOException.class));
        getChildSessionCallback().onClosedExceptionally(exception);
        mTestLooper.dispatchAll();

        verify(mGatewayStatusCallback)
                .onGatewayConnectionError(
                        eq(mConfig.getGatewayConnectionName()),
                        eq(VCN_ERROR_CODE_INTERNAL_ERROR),
                        any(),
                        any());
    }

    @Test
    public void testIkeSessionClosedTriggersDisconnect() throws Exception {
        // Verify scheduled but not canceled when entering ConnectedState
        verifySafeModeTimeoutAlarmAndGetCallback(false /* expectCanceled */);

        getIkeSessionCallback().onClosed();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mRetryTimeoutState, mGatewayConnection.getCurrentState());
        verify(mIkeSession).close();

        // Since network never validated, verify mSafeModeTimeoutAlarm not canceled
        verifyNoMoreInteractions(mSafeModeTimeoutAlarm);

        // IkeSession closed with no error, so verify that the GatewayStatusCallback was not
        // notified
        verifyNoMoreInteractions(mGatewayStatusCallback);
    }

    private void verifyIkeSessionClosedExceptionalltyNotifiesStatusCallback(
            IkeException cause, @VcnErrorCode int expectedErrorType) {
        getIkeSessionCallback().onClosedExceptionally(cause);
        mTestLooper.dispatchAll();

        verify(mIkeSession).close();

        verify(mGatewayStatusCallback)
                .onGatewayConnectionError(
                        eq(mConfig.getGatewayConnectionName()),
                        eq(expectedErrorType),
                        any(),
                        any());
    }

    private static IkeProtocolException buildMockIkeProtocolException(int errorCode) {
        final IkeProtocolException exception = mock(IkeProtocolException.class);
        when(exception.getErrorType()).thenReturn(errorCode);
        return exception;
    }

    @Test
    public void testIkeSessionClosedExceptionallyAuthenticationFailure() throws Exception {
        verifyIkeSessionClosedExceptionalltyNotifiesStatusCallback(
                buildMockIkeProtocolException(ERROR_TYPE_AUTHENTICATION_FAILED),
                VCN_ERROR_CODE_CONFIG_ERROR);
    }

    @Test
    public void testIkeSessionClosedExceptionallyDnsFailure() throws Exception {
        verifyIkeSessionClosedExceptionalltyNotifiesStatusCallback(
                new IkeInternalException(new UnknownHostException()), VCN_ERROR_CODE_NETWORK_ERROR);
    }

    @Test
    public void testIkeSessionClosedExceptionallyInternalFailure() throws Exception {
        verifyIkeSessionClosedExceptionalltyNotifiesStatusCallback(
                buildMockIkeProtocolException(ERROR_TYPE_TEMPORARY_FAILURE),
                VCN_ERROR_CODE_INTERNAL_ERROR);
    }

    @Test
    public void testTeardown() throws Exception {
        mGatewayConnection.teardownAsynchronously();
        mTestLooper.dispatchAll();

        // Verify that sending a non-quitting disconnect request does not unset the isQuitting flag
        mGatewayConnection.sendDisconnectRequestedAndAcquireWakelock("TEST", false);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        assertTrue(mGatewayConnection.isQuitting());
    }

    @Test
    public void testNonTeardownDisconnectRequest() throws Exception {
        mGatewayConnection.sendDisconnectRequestedAndAcquireWakelock("TEST", false);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        assertFalse(mGatewayConnection.isQuitting());
    }
}
