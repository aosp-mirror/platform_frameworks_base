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

import static androidx.test.InstrumentationRegistry.getContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.vcn.VcnManager.VcnUnderlyingNetworkPolicyListener;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;

public class VcnManagerTest {
    private static final Executor INLINE_EXECUTOR = Runnable::run;

    private IVcnManagementService mMockVcnManagementService;
    private VcnUnderlyingNetworkPolicyListener mMockPolicyListener;

    private Context mContext;
    private VcnManager mVcnManager;

    @Before
    public void setUp() {
        mMockVcnManagementService = mock(IVcnManagementService.class);
        mMockPolicyListener = mock(VcnUnderlyingNetworkPolicyListener.class);

        mContext = getContext();
        mVcnManager = new VcnManager(mContext, mMockVcnManagementService);
    }

    @Test
    public void testAddVcnUnderlyingNetworkPolicyListener() throws Exception {
        mVcnManager.addVcnUnderlyingNetworkPolicyListener(INLINE_EXECUTOR, mMockPolicyListener);

        ArgumentCaptor<IVcnUnderlyingNetworkPolicyListener> captor =
                ArgumentCaptor.forClass(IVcnUnderlyingNetworkPolicyListener.class);
        verify(mMockVcnManagementService).addVcnUnderlyingNetworkPolicyListener(captor.capture());

        assertTrue(VcnManager.REGISTERED_POLICY_LISTENERS.containsKey(mMockPolicyListener));

        IVcnUnderlyingNetworkPolicyListener listenerWrapper = captor.getValue();
        listenerWrapper.onPolicyChanged();
        verify(mMockPolicyListener).onPolicyChanged();
    }

    @Test
    public void testRemoveVcnUnderlyingNetworkPolicyListener() throws Exception {
        mVcnManager.addVcnUnderlyingNetworkPolicyListener(INLINE_EXECUTOR, mMockPolicyListener);

        mVcnManager.removeVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        assertFalse(VcnManager.REGISTERED_POLICY_LISTENERS.containsKey(mMockPolicyListener));
        verify(mMockVcnManagementService)
                .addVcnUnderlyingNetworkPolicyListener(
                        any(IVcnUnderlyingNetworkPolicyListener.class));
    }

    @Test
    public void testRemoveVcnUnderlyingNetworkPolicyListenerUnknownListener() throws Exception {
        mVcnManager.removeVcnUnderlyingNetworkPolicyListener(mMockPolicyListener);

        assertFalse(VcnManager.REGISTERED_POLICY_LISTENERS.containsKey(mMockPolicyListener));
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
}
