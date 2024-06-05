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

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import static com.android.server.vcn.VcnGatewayConnection.VcnIkeSession;
import static com.android.server.vcn.VcnGatewayConnection.VcnNetworkAgent;
import static com.android.server.vcn.VcnTestUtils.setupIpSecManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityDiagnosticsManager;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionConfiguration;
import android.net.ipsec.ike.IkeSessionConnectionInfo;
import android.net.vcn.FeatureFlags;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.test.TestLooper;
import android.telephony.SubscriptionInfo;

import com.android.internal.util.State;
import com.android.internal.util.WakeupMessage;
import com.android.server.IpSecService;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.Vcn.VcnGatewayStatusCallback;
import com.android.server.vcn.VcnGatewayConnection.VcnChildSessionCallback;
import com.android.server.vcn.VcnGatewayConnection.VcnIkeSession;
import com.android.server.vcn.VcnGatewayConnection.VcnNetworkAgent;
import com.android.server.vcn.VcnGatewayConnection.VcnWakeLock;
import com.android.server.vcn.routeselection.UnderlyingNetworkController;
import com.android.server.vcn.routeselection.UnderlyingNetworkRecord;

import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class VcnGatewayConnectionTestBase {
    protected static final ParcelUuid TEST_SUB_GRP = new ParcelUuid(UUID.randomUUID());
    protected static final SubscriptionInfo TEST_SUB_INFO = mock(SubscriptionInfo.class);

    static {
        doReturn(TEST_SUB_GRP).when(TEST_SUB_INFO).getGroupUuid();
    }

    protected static final InetAddress TEST_ADDR = InetAddresses.parseNumericAddress("2001:db8::1");
    protected static final InetAddress TEST_ADDR_2 =
            InetAddresses.parseNumericAddress("2001:db8::2");
    protected static final InetAddress TEST_ADDR_V4 =
            InetAddresses.parseNumericAddress("192.0.2.1");
    protected static final InetAddress TEST_ADDR_V4_2 =
            InetAddresses.parseNumericAddress("192.0.2.2");
    protected static final InetAddress TEST_DNS_ADDR =
            InetAddresses.parseNumericAddress("2001:DB8:0:1::");
    protected static final InetAddress TEST_DNS_ADDR_2 =
            InetAddresses.parseNumericAddress("2001:DB8:0:2::");
    protected static final LinkAddress TEST_INTERNAL_ADDR =
            new LinkAddress(InetAddresses.parseNumericAddress("2001:DB8:1:1::"), 64);
    protected static final LinkAddress TEST_INTERNAL_ADDR_2 =
            new LinkAddress(InetAddresses.parseNumericAddress("2001:DB8:1:2::"), 64);
    protected static final LinkAddress TEST_INTERNAL_ADDR_3 =
            new LinkAddress(InetAddresses.parseNumericAddress("2001:DB8:1:3::"), 64);

    protected static final int TEST_IPSEC_SPI_VALUE = 0x1234;
    protected static final int TEST_IPSEC_SPI_RESOURCE_ID = 1;
    protected static final int TEST_IPSEC_TRANSFORM_RESOURCE_ID = 2;
    protected static final int TEST_IPSEC_TUNNEL_RESOURCE_ID = 3;
    protected static final int TEST_SUB_ID = 5;
    protected static final long ELAPSED_REAL_TIME = 123456789L;
    protected static final String TEST_IPSEC_TUNNEL_IFACE = "IPSEC_IFACE";

    protected static UnderlyingNetworkRecord getTestNetworkRecord(
            Network network,
            NetworkCapabilities networkCapabilities,
            LinkProperties linkProperties,
            boolean isBlocked) {
        return new UnderlyingNetworkRecord(network, networkCapabilities, linkProperties, isBlocked);
    }

    protected static final String TEST_TCP_BUFFER_SIZES_1 = "1,2,3,4";
    protected static final UnderlyingNetworkRecord TEST_UNDERLYING_NETWORK_RECORD_1 =
            getTestNetworkRecord(
                    mock(Network.class, CALLS_REAL_METHODS),
                    new NetworkCapabilities.Builder()
                            .addTransportType(TRANSPORT_CELLULAR)
                            .setNetworkSpecifier(new TelephonyNetworkSpecifier(TEST_SUB_ID))
                            .build(),
                    new LinkProperties(),
                    false /* blocked */);

    static {
        TEST_UNDERLYING_NETWORK_RECORD_1.linkProperties.setMtu(1500);
        TEST_UNDERLYING_NETWORK_RECORD_1.linkProperties.setTcpBufferSizes(TEST_TCP_BUFFER_SIZES_1);
    }

    protected static final String TEST_TCP_BUFFER_SIZES_2 = "2,3,4,5";
    protected static final UnderlyingNetworkRecord TEST_UNDERLYING_NETWORK_RECORD_2 =
            getTestNetworkRecord(
                    mock(Network.class, CALLS_REAL_METHODS),
                    new NetworkCapabilities(),
                    new LinkProperties(),
                    false /* blocked */);

    static {
        TEST_UNDERLYING_NETWORK_RECORD_2.linkProperties.setMtu(1460);
        TEST_UNDERLYING_NETWORK_RECORD_2.linkProperties.setTcpBufferSizes(TEST_TCP_BUFFER_SIZES_2);
    }

    protected static final TelephonySubscriptionSnapshot TEST_SUBSCRIPTION_SNAPSHOT =
            new TelephonySubscriptionSnapshot(
                    TEST_SUB_ID,
                    Collections.singletonMap(TEST_SUB_ID, TEST_SUB_INFO),
                    Collections.EMPTY_MAP,
                    Collections.EMPTY_MAP);

    @NonNull protected final Context mContext;
    @NonNull protected final TestLooper mTestLooper;
    @NonNull protected final VcnNetworkProvider mVcnNetworkProvider;
    @NonNull protected final FeatureFlags mFeatureFlags;
    @NonNull protected final VcnContext mVcnContext;
    @NonNull protected final VcnGatewayConnectionConfig mConfig;
    @NonNull protected final VcnGatewayStatusCallback mGatewayStatusCallback;
    @NonNull protected final VcnGatewayConnection.Dependencies mDeps;
    @NonNull protected final UnderlyingNetworkController mUnderlyingNetworkController;
    @NonNull protected final VcnWakeLock mWakeLock;
    @NonNull protected final WakeupMessage mTeardownTimeoutAlarm;
    @NonNull protected final WakeupMessage mDisconnectRequestAlarm;
    @NonNull protected final WakeupMessage mRetryTimeoutAlarm;
    @NonNull protected final WakeupMessage mSafeModeTimeoutAlarm;

    @NonNull protected final IpSecService mIpSecSvc;
    @NonNull protected final ConnectivityManager mConnMgr;
    @NonNull protected final ConnectivityDiagnosticsManager mConnDiagMgr;

    @NonNull protected final IkeSessionConnectionInfo mIkeConnectionInfo;
    @NonNull protected final IkeSessionConfiguration mIkeSessionConfiguration;

    protected VcnIkeSession mMockIkeSession;
    protected VcnGatewayConnection mGatewayConnection;

    public VcnGatewayConnectionTestBase() {
        mContext = mock(Context.class);
        mTestLooper = new TestLooper();
        mVcnNetworkProvider = mock(VcnNetworkProvider.class);
        mFeatureFlags = mock(FeatureFlags.class);
        mVcnContext = mock(VcnContext.class);
        mConfig = VcnGatewayConnectionConfigTest.buildTestConfig();
        mGatewayStatusCallback = mock(VcnGatewayStatusCallback.class);
        mDeps = mock(VcnGatewayConnection.Dependencies.class);
        mUnderlyingNetworkController = mock(UnderlyingNetworkController.class);
        mWakeLock = mock(VcnWakeLock.class);
        mTeardownTimeoutAlarm = mock(WakeupMessage.class);
        mDisconnectRequestAlarm = mock(WakeupMessage.class);
        mRetryTimeoutAlarm = mock(WakeupMessage.class);
        mSafeModeTimeoutAlarm = mock(WakeupMessage.class);

        mIpSecSvc = mock(IpSecService.class);
        setupIpSecManager(mContext, mIpSecSvc);

        mConnMgr = mock(ConnectivityManager.class);
        VcnTestUtils.setupSystemService(
                mContext, mConnMgr, Context.CONNECTIVITY_SERVICE, ConnectivityManager.class);

        mConnDiagMgr = mock(ConnectivityDiagnosticsManager.class);
        VcnTestUtils.setupSystemService(
                mContext,
                mConnDiagMgr,
                Context.CONNECTIVITY_DIAGNOSTICS_SERVICE,
                ConnectivityDiagnosticsManager.class);

        mIkeConnectionInfo =
                new IkeSessionConnectionInfo(TEST_ADDR, TEST_ADDR_2, mock(Network.class));
        mIkeSessionConfiguration = new IkeSessionConfiguration.Builder(mIkeConnectionInfo).build();

        doReturn(mContext).when(mVcnContext).getContext();
        doReturn(mTestLooper.getLooper()).when(mVcnContext).getLooper();
        doReturn(mVcnNetworkProvider).when(mVcnContext).getVcnNetworkProvider();
        doReturn(mFeatureFlags).when(mVcnContext).getFeatureFlags();
        doReturn(true).when(mVcnContext).isFlagSafeModeTimeoutConfigEnabled();
        doReturn(true).when(mVcnContext).isFlagIpSecTransformStateEnabled();
        doReturn(true).when(mVcnContext).isFlagNetworkMetricMonitorEnabled();

        doReturn(mUnderlyingNetworkController)
                .when(mDeps)
                .newUnderlyingNetworkController(any(), any(), any(), any(), any());
        doReturn(mWakeLock)
                .when(mDeps)
                .newWakeLock(eq(mContext), eq(PowerManager.PARTIAL_WAKE_LOCK), any());
        doReturn(1)
                .when(mDeps)
                .getParallelTunnelCount(eq(TEST_SUBSCRIPTION_SNAPSHOT), eq(TEST_SUB_GRP));

        setUpWakeupMessage(mTeardownTimeoutAlarm, VcnGatewayConnection.TEARDOWN_TIMEOUT_ALARM);
        setUpWakeupMessage(mDisconnectRequestAlarm, VcnGatewayConnection.DISCONNECT_REQUEST_ALARM);
        setUpWakeupMessage(mRetryTimeoutAlarm, VcnGatewayConnection.RETRY_TIMEOUT_ALARM);
        setUpWakeupMessage(mSafeModeTimeoutAlarm, VcnGatewayConnection.SAFEMODE_TIMEOUT_ALARM);

        doReturn(ELAPSED_REAL_TIME).when(mDeps).getElapsedRealTime();
    }

    protected void setUpWakeupMessage(
            @NonNull WakeupMessage msg,
            @NonNull String cmdName,
            VcnGatewayConnection.Dependencies deps) {
        doReturn(msg).when(deps).newWakeupMessage(eq(mVcnContext), any(), eq(cmdName), any());
    }

    private void setUpWakeupMessage(@NonNull WakeupMessage msg, @NonNull String cmdName) {
        setUpWakeupMessage(msg, cmdName, mDeps);
    }

    @Before
    public void setUp() throws Exception {
        IpSecTunnelInterfaceResponse resp =
                new IpSecTunnelInterfaceResponse(
                        IpSecManager.Status.OK,
                        TEST_IPSEC_TUNNEL_RESOURCE_ID,
                        TEST_IPSEC_TUNNEL_IFACE);
        doReturn(resp).when(mIpSecSvc).createTunnelInterface(any(), any(), any(), any(), any());

        mMockIkeSession = mock(VcnIkeSession.class);
        doReturn(mMockIkeSession).when(mDeps).newIkeSession(any(), any(), any(), any(), any());

        mGatewayConnection =
                new VcnGatewayConnection(
                        mVcnContext,
                        TEST_SUB_GRP,
                        TEST_SUBSCRIPTION_SNAPSHOT,
                        mConfig,
                        mGatewayStatusCallback,
                        true /* isMobileDataEnabled */,
                        mDeps);
    }

    protected IpSecTransform makeDummyIpSecTransform() throws Exception {
        return new IpSecTransform(mContext, new IpSecConfig());
    }

    protected IkeSessionCallback getIkeSessionCallback() {
        ArgumentCaptor<IkeSessionCallback> captor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);
        verify(mDeps).newIkeSession(any(), any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    protected VcnChildSessionCallback getChildSessionCallback() {
        ArgumentCaptor<ChildSessionCallback> captor =
                ArgumentCaptor.forClass(ChildSessionCallback.class);
        verify(mDeps, atLeastOnce()).newIkeSession(any(), any(), any(), any(), captor.capture());
        return (VcnChildSessionCallback) captor.getValue();
    }

    protected void verifyWakeLockSetUp() {
        verify(mDeps).newWakeLock(eq(mContext), eq(PowerManager.PARTIAL_WAKE_LOCK), any());
        verifyNoMoreInteractions(mWakeLock);
    }

    protected void verifyWakeLockAcquired() {
        verify(mWakeLock).acquire();
        verifyNoMoreInteractions(mWakeLock);
    }

    protected void verifyWakeLockReleased() {
        verify(mWakeLock).release();
        verifyNoMoreInteractions(mWakeLock);
    }

    private Runnable verifyWakeupMessageSetUpAndGetCallback(
            @NonNull String tag,
            @NonNull WakeupMessage msg,
            long delayInMillis,
            boolean expectCanceled) {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mDeps).newWakeupMessage(eq(mVcnContext), any(), eq(tag), runnableCaptor.capture());

        verify(mDeps, atLeastOnce()).getElapsedRealTime();
        verify(msg).schedule(ELAPSED_REAL_TIME + delayInMillis);
        verify(msg, expectCanceled ? times(1) : never()).cancel();

        return runnableCaptor.getValue();
    }

    protected Runnable verifyTeardownTimeoutAlarmAndGetCallback(boolean expectCanceled) {
        return verifyWakeupMessageSetUpAndGetCallback(
                VcnGatewayConnection.TEARDOWN_TIMEOUT_ALARM,
                mTeardownTimeoutAlarm,
                TimeUnit.SECONDS.toMillis(VcnGatewayConnection.TEARDOWN_TIMEOUT_SECONDS),
                expectCanceled);
    }

    protected Runnable verifyDisconnectRequestAlarmAndGetCallback(boolean expectCanceled) {
        return verifyWakeupMessageSetUpAndGetCallback(
                VcnGatewayConnection.DISCONNECT_REQUEST_ALARM,
                mDisconnectRequestAlarm,
                TimeUnit.SECONDS.toMillis(
                        VcnGatewayConnection.NETWORK_LOSS_DISCONNECT_TIMEOUT_SECONDS),
                expectCanceled);
    }

    protected Runnable verifyRetryTimeoutAlarmAndGetCallback(
            long delayInMillis, boolean expectCanceled) {
        return verifyWakeupMessageSetUpAndGetCallback(
                VcnGatewayConnection.RETRY_TIMEOUT_ALARM,
                mRetryTimeoutAlarm,
                delayInMillis,
                expectCanceled);
    }

    protected Runnable verifySafeModeTimeoutAlarmAndGetCallback(boolean expectCanceled) {
        return verifyWakeupMessageSetUpAndGetCallback(
                VcnGatewayConnection.SAFEMODE_TIMEOUT_ALARM,
                mSafeModeTimeoutAlarm,
                TimeUnit.SECONDS.toMillis(VcnGatewayConnection.SAFEMODE_TIMEOUT_SECONDS),
                expectCanceled);
    }

    protected void verifySafeModeStateAndCallbackFired(int invocationCount, boolean isInSafeMode) {
        verify(mGatewayStatusCallback, times(invocationCount)).onSafeModeStatusChanged();
        assertEquals(isInSafeMode, mGatewayConnection.isInSafeMode());
    }

    protected void verifySafeModeTimeoutNotifiesCallbackAndUnregistersNetworkAgent(
            @NonNull State expectedState) {
        // Set a VcnNetworkAgent, and expect it to be unregistered and cleared
        final VcnNetworkAgent mockNetworkAgent = mock(VcnNetworkAgent.class);
        mGatewayConnection.setNetworkAgent(mockNetworkAgent);

        // SafeMode timer starts when VcnGatewayConnection exits DisconnectedState (the initial
        // state)
        final Runnable delayedEvent =
                verifySafeModeTimeoutAlarmAndGetCallback(false /* expectCanceled */);
        delayedEvent.run();
        mTestLooper.dispatchAll();

        assertEquals(expectedState, mGatewayConnection.getCurrentState());
        verifySafeModeStateAndCallbackFired(1, true);

        verify(mockNetworkAgent).unregister();
        assertNull(mGatewayConnection.getNetworkAgent());
    }
}
