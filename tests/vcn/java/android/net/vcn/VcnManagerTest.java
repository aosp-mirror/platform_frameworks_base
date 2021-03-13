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

package android.net.vcn;

import static android.net.vcn.VcnManager.VCN_STATUS_CODE_ACTIVE;

import static androidx.test.InstrumentationRegistry.getContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.vcn.VcnManager.VcnStatusCallback;
import android.net.vcn.VcnManager.VcnStatusCallbackBinder;
import android.net.vcn.VcnManager.VcnUnderlyingNetworkPolicyListener;
import android.os.ParcelUuid;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.Executor;

public class VcnManagerTest {
    private static final ParcelUuid SUB_GROUP = new ParcelUuid(new UUID(0, 0));
    private static final String GATEWAY_CONNECTION_NAME = "gatewayConnectionName";
    private static final Executor INLINE_EXECUTOR = Runnable::run;

    private IVcnManagementService mMockVcnManagementService;
    private VcnUnderlyingNetworkPolicyListener mMockPolicyListener;
    private VcnStatusCallback mMockStatusCallback;

    private Context mContext;
    private VcnManager mVcnManager;

    @Before
    public void setUp() {
        mMockVcnManagementService = mock(IVcnManagementService.class);
        mMockPolicyListener = mock(VcnUnderlyingNetworkPolicyListener.class);
        mMockStatusCallback = mock(VcnStatusCallback.class);

        mContext = getContext();
        mVcnManager = new VcnManager(mContext, mMockVcnManagementService);
    }

    @Test
    public void testAddVcnUnderlyingNetworkPolicyListener() throws Exception {
        mVcnManager.addVcnUnderlyingNetworkPolicyListener(INLINE_EXECUTOR, mMockPolicyListener);

        ArgumentCaptor<IVcnUnderlyingNetworkPolicyListener> captor =
                ArgumentCaptor.forClass(IVcnUnderlyingNetworkPolicyListener.class);
        verify(mMockVcnManagementService).addVcnUnderlyingNetworkPolicyListener(captor.capture());

        assertTrue(VcnManager.getAllPolicyListeners().containsKey(mMockPolicyListener));

        IVcnUnderlyingNetworkPolicyListener listenerWrapper = captor.getValue();
        listenerWrapper.onPolicyChanged();
        verify(mMockPolicyListener).onPolicyChanged();
    }

    @Test
    public void testRemoveVcnUnderlyingNetworkPolicyListener() throws Exception {
        mVcnManager.addVcnUnderlyingNetworkPolicyListener(INLINE_EXECUTOR, mMockPolicyListener);

        mVcnManager.removeVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        assertFalse(VcnManager.getAllPolicyListeners().containsKey(mMockPolicyListener));
        verify(mMockVcnManagementService)
                .addVcnUnderlyingNetworkPolicyListener(
                        any(IVcnUnderlyingNetworkPolicyListener.class));
    }

    @Test
    public void testRemoveVcnUnderlyingNetworkPolicyListenerUnknownListener() throws Exception {
        mVcnManager.removeVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        assertFalse(VcnManager.getAllPolicyListeners().containsKey(mMockPolicyListener));
        verify(mMockVcnManagementService, never())
                .addVcnUnderlyingNetworkPolicyListener(
                        any(IVcnUnderlyingNetworkPolicyListener.class));
    }

    @Test(expected = NullPointerException.class)
    public void testAddVcnUnderlyingNetworkPolicyListenerNullExecutor() throws Exception {
        mVcnManager.addVcnUnderlyingNetworkPolicyListener(null, mMockPolicyListener);
    }

    @Test(expected = NullPointerException.class)
    public void testAddVcnUnderlyingNetworkPolicyListenerNullListener() throws Exception {
        mVcnManager.addVcnUnderlyingNetworkPolicyListener(INLINE_EXECUTOR, null);
    }

    @Test(expected = NullPointerException.class)
    public void testRemoveVcnUnderlyingNetworkPolicyListenerNullListener() {
        mVcnManager.removeVcnUnderlyingNetworkPolicyListener(null);
    }

    @Test
    public void testGetUnderlyingNetworkPolicy() throws Exception {
        NetworkCapabilities nc = new NetworkCapabilities();
        LinkProperties lp = new LinkProperties();
        when(mMockVcnManagementService.getUnderlyingNetworkPolicy(eq(nc), eq(lp)))
                .thenReturn(new VcnUnderlyingNetworkPolicy(false /* isTearDownRequested */, nc));

        VcnUnderlyingNetworkPolicy policy = mVcnManager.getUnderlyingNetworkPolicy(nc, lp);

        assertFalse(policy.isTeardownRequested());
        assertEquals(nc, policy.getMergedNetworkCapabilities());
        verify(mMockVcnManagementService).getUnderlyingNetworkPolicy(eq(nc), eq(lp));
    }

    @Test(expected = NullPointerException.class)
    public void testGetUnderlyingNetworkPolicyNullNetworkCapabilities() throws Exception {
        mVcnManager.getUnderlyingNetworkPolicy(null, new LinkProperties());
    }

    @Test(expected = NullPointerException.class)
    public void testGetUnderlyingNetworkPolicyNullLinkProperties() throws Exception {
        mVcnManager.getUnderlyingNetworkPolicy(new NetworkCapabilities(), null);
    }

    @Test
    public void testRegisterVcnStatusCallback() throws Exception {
        mVcnManager.registerVcnStatusCallback(SUB_GROUP, INLINE_EXECUTOR, mMockStatusCallback);

        verify(mMockVcnManagementService)
                .registerVcnStatusCallback(eq(SUB_GROUP), notNull(), any());
    }

    @Test(expected = IllegalStateException.class)
    public void testRegisterVcnStatusCallbackAlreadyRegistered() throws Exception {
        mVcnManager.registerVcnStatusCallback(SUB_GROUP, INLINE_EXECUTOR, mMockStatusCallback);
        mVcnManager.registerVcnStatusCallback(SUB_GROUP, INLINE_EXECUTOR, mMockStatusCallback);
    }

    @Test(expected = NullPointerException.class)
    public void testRegisterVcnStatusCallbackNullSubscriptionGroup() throws Exception {
        mVcnManager.registerVcnStatusCallback(null, INLINE_EXECUTOR, mMockStatusCallback);
    }

    @Test(expected = NullPointerException.class)
    public void testRegisterVcnStatusCallbackNullExecutor() throws Exception {
        mVcnManager.registerVcnStatusCallback(SUB_GROUP, null, mMockStatusCallback);
    }

    @Test(expected = NullPointerException.class)
    public void testRegisterVcnStatusCallbackNullCallback() throws Exception {
        mVcnManager.registerVcnStatusCallback(SUB_GROUP, INLINE_EXECUTOR, null);
    }

    @Test
    public void testUnregisterVcnStatusCallback() throws Exception {
        mVcnManager.registerVcnStatusCallback(SUB_GROUP, INLINE_EXECUTOR, mMockStatusCallback);

        mVcnManager.unregisterVcnStatusCallback(mMockStatusCallback);

        verify(mMockVcnManagementService).unregisterVcnStatusCallback(any());
    }

    @Test
    public void testUnregisterUnknownVcnStatusCallback() throws Exception {
        mVcnManager.unregisterVcnStatusCallback(mMockStatusCallback);

        verifyNoMoreInteractions(mMockVcnManagementService);
    }

    @Test(expected = NullPointerException.class)
    public void testUnregisterNullVcnStatusCallback() throws Exception {
        mVcnManager.unregisterVcnStatusCallback(null);
    }

    @Test
    public void testVcnStatusCallbackBinder() throws Exception {
        IVcnStatusCallback cbBinder =
                new VcnStatusCallbackBinder(INLINE_EXECUTOR, mMockStatusCallback);

        cbBinder.onVcnStatusChanged(VCN_STATUS_CODE_ACTIVE);
        verify(mMockStatusCallback).onStatusChanged(VCN_STATUS_CODE_ACTIVE);

        cbBinder.onGatewayConnectionError(
                GATEWAY_CONNECTION_NAME,
                VcnManager.VCN_ERROR_CODE_NETWORK_ERROR,
                UnknownHostException.class.getName(),
                "exception_message");
        verify(mMockStatusCallback)
                .onGatewayConnectionError(
                        any(int[].class),
                        eq(VcnManager.VCN_ERROR_CODE_NETWORK_ERROR),
                        any(UnknownHostException.class));
    }
}
