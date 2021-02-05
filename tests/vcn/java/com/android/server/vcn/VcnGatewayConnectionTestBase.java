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

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.content.Context;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.os.ParcelUuid;
import android.os.test.TestLooper;

import com.android.server.IpSecService;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.Vcn.VcnGatewayStatusCallback;

import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.UUID;

public class VcnGatewayConnectionTestBase {
    protected static final ParcelUuid TEST_SUB_GRP = new ParcelUuid(UUID.randomUUID());
    protected static final int TEST_IPSEC_SPI_VALUE = 0x1234;
    protected static final int TEST_IPSEC_SPI_RESOURCE_ID = 1;
    protected static final int TEST_IPSEC_TRANSFORM_RESOURCE_ID = 2;
    protected static final int TEST_IPSEC_TUNNEL_RESOURCE_ID = 3;
    protected static final int TEST_SUB_ID = 5;
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

    @NonNull protected final IpSecService mIpSecSvc;

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

        mIpSecSvc = mock(IpSecService.class);
        setupIpSecManager(mContext, mIpSecSvc);

        doReturn(mContext).when(mVcnContext).getContext();
        doReturn(mTestLooper.getLooper()).when(mVcnContext).getLooper();
        doReturn(mVcnNetworkProvider).when(mVcnContext).getVcnNetworkProvider();

        doReturn(mUnderlyingNetworkTracker)
                .when(mDeps)
                .newUnderlyingNetworkTracker(any(), any(), any(), any(), any());
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

    protected ChildSessionCallback getChildSessionCallback() {
        ArgumentCaptor<ChildSessionCallback> captor =
                ArgumentCaptor.forClass(ChildSessionCallback.class);
        verify(mDeps).newIkeSession(any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }
}
