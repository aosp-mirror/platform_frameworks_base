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

package com.android.server.vcn;

import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_MMS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.NetworkRequest;
import android.net.vcn.VcnConfig;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.os.ParcelUuid;
import android.os.test.TestLooper;
import android.util.ArraySet;

import com.android.server.VcnManagementService.VcnCallback;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.Vcn.VcnGatewayStatusCallback;
import com.android.server.vcn.VcnNetworkProvider.NetworkRequestListener;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

public class VcnTest {
    private static final String PKG_NAME = VcnTest.class.getPackage().getName();
    private static final ParcelUuid TEST_SUB_GROUP = new ParcelUuid(new UUID(0, 0));
    private static final int NETWORK_SCORE = 0;
    private static final int PROVIDER_ID = 5;
    private static final int[][] TEST_CAPS =
            new int[][] {
                new int[] {NET_CAPABILITY_MMS, NET_CAPABILITY_INTERNET},
                new int[] {NET_CAPABILITY_DUN}
            };

    private Context mContext;
    private VcnContext mVcnContext;
    private TelephonySubscriptionSnapshot mSubscriptionSnapshot;
    private VcnNetworkProvider mVcnNetworkProvider;
    private VcnCallback mVcnCallback;
    private Vcn.Dependencies mDeps;

    private ArgumentCaptor<VcnGatewayStatusCallback> mGatewayStatusCallbackCaptor;

    private TestLooper mTestLooper;
    private VcnGatewayConnectionConfig mGatewayConnectionConfig;
    private VcnConfig mConfig;
    private Vcn mVcn;

    @Before
    public void setUp() {
        mContext = mock(Context.class);
        mVcnContext = mock(VcnContext.class);
        mSubscriptionSnapshot = mock(TelephonySubscriptionSnapshot.class);
        mVcnNetworkProvider = mock(VcnNetworkProvider.class);
        mVcnCallback = mock(VcnCallback.class);
        mDeps = mock(Vcn.Dependencies.class);

        mTestLooper = new TestLooper();

        doReturn(PKG_NAME).when(mContext).getOpPackageName();
        doReturn(mContext).when(mVcnContext).getContext();
        doReturn(mTestLooper.getLooper()).when(mVcnContext).getLooper();
        doReturn(mVcnNetworkProvider).when(mVcnContext).getVcnNetworkProvider();

        // Setup VcnGatewayConnection instance generation
        doAnswer((invocation) -> {
            // Mock-within a doAnswer is safe, because it doesn't actually run nested.
            return mock(VcnGatewayConnection.class);
        }).when(mDeps).newVcnGatewayConnection(any(), any(), any(), any(), any());

        mGatewayStatusCallbackCaptor = ArgumentCaptor.forClass(VcnGatewayStatusCallback.class);

        final VcnConfig.Builder configBuilder = new VcnConfig.Builder(mContext);
        for (final int[] caps : TEST_CAPS) {
            configBuilder.addGatewayConnectionConfig(
                    VcnGatewayConnectionConfigTest.buildTestConfigWithExposedCaps(caps));
        }

        mConfig = configBuilder.build();
        mVcn =
                new Vcn(
                        mVcnContext,
                        TEST_SUB_GROUP,
                        mConfig,
                        mSubscriptionSnapshot,
                        mVcnCallback,
                        mDeps);
    }

    private NetworkRequestListener verifyAndGetRequestListener() {
        ArgumentCaptor<NetworkRequestListener> mNetworkRequestListenerCaptor =
                ArgumentCaptor.forClass(NetworkRequestListener.class);
        verify(mVcnNetworkProvider).registerListener(mNetworkRequestListenerCaptor.capture());

        return mNetworkRequestListenerCaptor.getValue();
    }

    private void startVcnGatewayWithCapabilities(
            NetworkRequestListener requestListener, int... netCapabilities) {
        final NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        for (final int netCapability : netCapabilities) {
            requestBuilder.addCapability(netCapability);
        }

        requestListener.onNetworkRequested(requestBuilder.build(), NETWORK_SCORE, PROVIDER_ID);
        mTestLooper.dispatchAll();
    }

    @Test
    public void testSubscriptionSnapshotUpdatesVcnGatewayConnections() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        startVcnGatewayWithCapabilities(requestListener, TEST_CAPS[0]);

        final Set<VcnGatewayConnection> gatewayConnections = mVcn.getVcnGatewayConnections();
        assertFalse(gatewayConnections.isEmpty());

        final TelephonySubscriptionSnapshot updatedSnapshot =
                mock(TelephonySubscriptionSnapshot.class);

        mVcn.updateSubscriptionSnapshot(updatedSnapshot);
        mTestLooper.dispatchAll();

        for (final VcnGatewayConnection gateway : gatewayConnections) {
            verify(gateway).updateSubscriptionSnapshot(eq(updatedSnapshot));
        }
    }

    private void triggerVcnRequestListeners(NetworkRequestListener requestListener) {
        for (final int[] caps : TEST_CAPS) {
            startVcnGatewayWithCapabilities(requestListener, caps);
        }
    }

    public Set<VcnGatewayConnection> startGatewaysAndGetGatewayConnections(
            NetworkRequestListener requestListener) {
        triggerVcnRequestListeners(requestListener);

        final int numExpectedGateways = TEST_CAPS.length;

        final Set<VcnGatewayConnection> gatewayConnections = mVcn.getVcnGatewayConnections();
        assertEquals(numExpectedGateways, gatewayConnections.size());
        verify(mDeps, times(numExpectedGateways))
                .newVcnGatewayConnection(
                        eq(mVcnContext),
                        eq(TEST_SUB_GROUP),
                        eq(mSubscriptionSnapshot),
                        any(),
                        mGatewayStatusCallbackCaptor.capture());

        return gatewayConnections;
    }

    private void verifySafeMode(
            NetworkRequestListener requestListener,
            Set<VcnGatewayConnection> expectedGatewaysTornDown) {
        assertFalse(mVcn.isActive());
        assertTrue(mVcn.getVcnGatewayConnections().isEmpty());
        for (final VcnGatewayConnection gatewayConnection : expectedGatewaysTornDown) {
            verify(gatewayConnection).teardownAsynchronously();
        }
        verify(mVcnNetworkProvider).unregisterListener(requestListener);
        verify(mVcnCallback).onEnteredSafeMode();
    }

    @Test
    public void testGatewayEnteringSafeModeNotifiesVcn() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        final Set<VcnGatewayConnection> gatewayConnections =
                startGatewaysAndGetGatewayConnections(requestListener);

        // Doesn't matter which callback this gets - any Gateway entering Safemode should shut down
        // all Gateways
        final VcnGatewayStatusCallback statusCallback = mGatewayStatusCallbackCaptor.getValue();
        statusCallback.onEnteredSafeMode();
        mTestLooper.dispatchAll();

        verifySafeMode(requestListener, gatewayConnections);
    }

    @Test
    public void testGatewayQuit() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        final Set<VcnGatewayConnection> gatewayConnections =
                new ArraySet<>(startGatewaysAndGetGatewayConnections(requestListener));

        final VcnGatewayStatusCallback statusCallback = mGatewayStatusCallbackCaptor.getValue();
        statusCallback.onQuit();
        mTestLooper.dispatchAll();

        // Verify that the VCN requests the networkRequests be resent
        assertEquals(1, mVcn.getVcnGatewayConnections().size());
        verify(mVcnNetworkProvider).resendAllRequests(requestListener);

        // Verify that the VcnGatewayConnection is restarted
        triggerVcnRequestListeners(requestListener);
        mTestLooper.dispatchAll();
        assertEquals(2, mVcn.getVcnGatewayConnections().size());
        verify(mDeps, times(gatewayConnections.size() + 1))
                .newVcnGatewayConnection(
                        eq(mVcnContext),
                        eq(TEST_SUB_GROUP),
                        eq(mSubscriptionSnapshot),
                        any(),
                        mGatewayStatusCallbackCaptor.capture());
    }

    @Test
    public void testUpdateConfigExitsSafeMode() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();
        final Set<VcnGatewayConnection> gatewayConnections =
                new ArraySet<>(startGatewaysAndGetGatewayConnections(requestListener));

        final VcnGatewayStatusCallback statusCallback = mGatewayStatusCallbackCaptor.getValue();
        statusCallback.onEnteredSafeMode();
        mTestLooper.dispatchAll();
        verifySafeMode(requestListener, gatewayConnections);

        doAnswer(invocation -> {
            final NetworkRequestListener listener = invocation.getArgument(0);
            triggerVcnRequestListeners(listener);
            return null;
        }).when(mVcnNetworkProvider).registerListener(eq(requestListener));

        mVcn.updateConfig(mConfig);
        mTestLooper.dispatchAll();

        // Registered on start, then re-registered with new configs
        verify(mVcnNetworkProvider, times(2)).registerListener(eq(requestListener));
        assertTrue(mVcn.isActive());
        for (final int[] caps : TEST_CAPS) {
            // Expect each gateway connection created on initial startup, and again with new configs
            verify(mDeps, times(2))
                    .newVcnGatewayConnection(
                            eq(mVcnContext),
                            eq(TEST_SUB_GROUP),
                            eq(mSubscriptionSnapshot),
                            argThat(config -> Arrays.equals(caps, config.getExposedCapabilities())),
                            any());
        }
    }
}
