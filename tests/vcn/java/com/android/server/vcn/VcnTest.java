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

import static org.junit.Assert.assertFalse;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.NetworkRequest;
import android.net.vcn.VcnConfig;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.os.ParcelUuid;
import android.os.test.TestLooper;

import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnNetworkProvider.NetworkRequestListener;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.UUID;

public class VcnTest {
    private static final String PKG_NAME = VcnTest.class.getPackage().getName();
    private static final ParcelUuid TEST_SUB_GROUP = new ParcelUuid(new UUID(0, 0));
    private static final int NETWORK_SCORE = 0;
    private static final int PROVIDER_ID = 5;

    private Context mContext;
    private VcnContext mVcnContext;
    private TelephonySubscriptionSnapshot mSubscriptionSnapshot;
    private VcnNetworkProvider mVcnNetworkProvider;
    private Vcn.Dependencies mDeps;

    private TestLooper mTestLooper;
    private VcnConfig mConfig;
    private Vcn mVcn;

    @Before
    public void setUp() {
        mContext = mock(Context.class);
        mVcnContext = mock(VcnContext.class);
        mSubscriptionSnapshot = mock(TelephonySubscriptionSnapshot.class);
        mVcnNetworkProvider = mock(VcnNetworkProvider.class);
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
        }).when(mDeps).newVcnGatewayConnection(any(), any(), any(), any());

        mConfig =
                new VcnConfig.Builder(mContext)
                        .addGatewayConnectionConfig(
                                VcnGatewayConnectionConfigTest.buildTestConfig())
                        .build();

        mVcn = new Vcn(mVcnContext, TEST_SUB_GROUP, mConfig, mSubscriptionSnapshot, mDeps);
    }

    private NetworkRequestListener verifyAndGetRequestListener() {
        ArgumentCaptor<NetworkRequestListener> mNetworkRequestListenerCaptor =
                ArgumentCaptor.forClass(NetworkRequestListener.class);
        verify(mVcnNetworkProvider).registerListener(mNetworkRequestListenerCaptor.capture());

        return mNetworkRequestListenerCaptor.getValue();
    }

    private NetworkRequest getNetworkRequestWithCapabilities(int[] networkCapabilities) {
        final NetworkRequest.Builder builder = new NetworkRequest.Builder();
        for (final int netCapability : networkCapabilities) {
            builder.addCapability(netCapability);
        }
        return builder.build();
    }

    @Test
    public void testSubscriptionSnapshotUpdatesVcnGatewayConnections() {
        final NetworkRequestListener requestListener = verifyAndGetRequestListener();

        requestListener.onNetworkRequested(
                getNetworkRequestWithCapabilities(VcnGatewayConnectionConfigTest.EXPOSED_CAPS),
                NETWORK_SCORE,
                PROVIDER_ID);
        mTestLooper.dispatchAll();

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
}
