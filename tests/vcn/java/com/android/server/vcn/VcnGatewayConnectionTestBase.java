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

import static com.android.server.vcn.UnderlyingNetworkTracker.UnderlyingNetworkRecord;
import static com.android.server.vcn.VcnGatewayConnection.VcnIkeSession;
import static com.android.server.vcn.VcnTestUtils.setupIpSecManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.test.TestLooper;

import com.android.internal.util.State;
import com.android.internal.util.WakeupMessage;
import com.android.server.IpSecService;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.Vcn.VcnGatewayStatusCallback;
import com.android.server.vcn.VcnGatewayConnection.VcnChildSessionCallback;
import com.android.server.vcn.VcnGatewayConnection.VcnWakeLock;

import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class VcnGatewayConnectionTestBase {
    protected static final ParcelUuid TEST_SUB_GRP = new ParcelUuid(UUID.randomUUID());
    protected static final InetAddress TEST_DNS_ADDR =
            InetAddresses.parseNumericAddress("2001:DB8:0:1::");
    protected static final LinkAddress TEST_INTERNAL_ADDR =
            new LinkAddress(InetAddresses.parseNumericAddress("2001:DB8:0:2::"), 64);

    protected static final int TEST_IPSEC_SPI_VALUE = 0x1234;
    protected static final int TEST_IPSEC_SPI_RESOURCE_ID = 1;
    protected static final int TEST_IPSEC_TRANSFORM_RESOURCE_ID = 2;
    protected static final int TEST_IPSEC_TUNNEL_RESOURCE_ID = 3;
    protected static final int TEST_SUB_ID = 5;
    protected static final long ELAPSED_REAL_TIME = 123456789L;
    protected static final String TEST_IPSEC_TUNNEL_IFACE = "IPSEC_IFACE";
    protected static final UnderlyingNetworkRecord TEST_UNDERLYING_NETWORK_RECORD_1 =
            new UnderlyingNetworkRecord(
                    new Network(0),
                    new NetworkCapabilities(),
                    new LinkProperties(),
                    false /* blocked */);
    protected static final UnderlyingNetworkRecord TEST_UNDERLYING_NETWORK_RECORD_2 =
            new UnderlyingNetworkRecord(
                    new Network(1),
                    new NetworkCapabilities(),
                    new LinkProperties(),
                    false /* blocked */);

    protected static final TelephonySubscriptionSnapshot TEST_SUBSCRIPTION_SNAPSHOT =
            new TelephonySubscriptionSnapshot(
                    Collections.singletonMap(TEST_SUB_ID, TEST_SUB_GRP), Collections.EMPTY_MAP);

    @NonNull protected final Context mContext;
    @NonNull protected final TestLooper mTestLooper;
    @NonNull protected final VcnNetworkProvider mVcnNetworkProvider;
    @NonNull protected final VcnContext mVcnContext;
    @NonNull protected final VcnGatewayConnectionConfig mConfig;
    @NonNull protected final VcnGatewayStatusCallback mGatewayStatusCallback;
    @NonNull protected final VcnGatewayConnection.Dependencies mDeps;
    @NonNull protected final UnderlyingNetworkTracker mUnderlyingNetworkTracker;
    @NonNull protected final VcnWakeLock mWakeLock;
    @NonNull protected final WakeupMessage mTeardownTimeoutAlarm;
    @NonNull protected final WakeupMessage mDisconnectRequestAlarm;
    @NonNull protected final WakeupMessage mRetryTimeoutAlarm;
    @NonNull protected final WakeupMessage mSafeModeTimeoutAlarm;

    @NonNull protected final IpSecService mIpSecSvc;
    @NonNull protected final ConnectivityManager mConnMgr;

    protected VcnIkeSession mMockIkeSession;
    protected VcnGatewayConnection mGatewayConnection;

    public VcnGatewayConnectionTestBase() {
        mContext = mock(Context.class);
        mTestLooper = new TestLooper();
        mVcnNetworkProvider = mock(VcnNetworkProvider.class);
        mVcnContext = mock(VcnContext.class);
        mConfig = VcnGatewayConnectionConfigTest.buildTestConfig();
        mGatewayStatusCallback = mock(VcnGatewayStatusCallback.class);
        mDeps = mock(VcnGatewayConnection.Dependencies.class);
        mUnderlyingNetworkTracker = mock(UnderlyingNetworkTracker.class);
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

        doReturn(mContext).when(mVcnContext).getContext();
        doReturn(mTestLooper.getLooper()).when(mVcnContext).getLooper();
        doReturn(mVcnNetworkProvider).when(mVcnContext).getVcnNetworkProvider();

        doReturn(mUnderlyingNetworkTracker)
                .when(mDeps)
                .newUnderlyingNetworkTracker(any(), any(), any(), any(), any());
        doReturn(mWakeLock)
                .when(mDeps)
                .newWakeLock(eq(mContext), eq(PowerManager.PARTIAL_WAKE_LOCK), any());

        setUpWakeupMessage(mTeardownTimeoutAlarm, VcnGatewayConnection.TEARDOWN_TIMEOUT_ALARM);
        setUpWakeupMessage(mDisconnectRequestAlarm, VcnGatewayConnection.DISCONNECT_REQUEST_ALARM);
        setUpWakeupMessage(mRetryTimeoutAlarm, VcnGatewayConnection.RETRY_TIMEOUT_ALARM);
        setUpWakeupMessage(mSafeModeTimeoutAlarm, VcnGatewayConnection.SAFEMODE_TIMEOUT_ALARM);

        doReturn(ELAPSED_REAL_TIME).when(mDeps).getElapsedRealTime();
    }

    private void setUpWakeupMessage(@NonNull WakeupMessage msg, @NonNull String cmdName) {
        doReturn(msg).when(mDeps).newWakeupMessage(eq(mVcnContext), any(), eq(cmdName), any());
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
        verify(mDeps).newIkeSession(any(), any(), any(), any(), captor.capture());
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

    protected void verifySafeModeTimeoutNotifiesCallbackAndUnregistersNetworkAgent(
            @NonNull State expectedState) {
        // Set a NetworkAgent, and expect it to be unregistered and cleared
        final NetworkAgent mockNetworkAgent = mock(NetworkAgent.class);
        mGatewayConnection.setNetworkAgent(mockNetworkAgent);

        // SafeMode timer starts when VcnGatewayConnection exits DisconnectedState (the initial
        // state)
        final Runnable delayedEvent =
                verifySafeModeTimeoutAlarmAndGetCallback(false /* expectCanceled */);
        delayedEvent.run();
        mTestLooper.dispatchAll();

        verify(mGatewayStatusCallback).onSafeModeStatusChanged();
        assertEquals(expectedState, mGatewayConnection.getCurrentState());
        assertTrue(mGatewayConnection.isInSafeMode());

        verify(mockNetworkAgent).unregister();
        assertNull(mGatewayConnection.getNetworkAgent());
    }
}
