/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.location.contexthub;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.contexthub.HubEndpointInfo;
import android.hardware.contexthub.HubEndpointInfo.HubEndpointIdentifier;
import android.hardware.contexthub.IContextHubEndpoint;
import android.hardware.contexthub.IContextHubEndpointCallback;
import android.hardware.contexthub.IEndpointCommunication;
import android.os.Binder;
import android.os.RemoteException;
import android.platform.test.annotations.Postsubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;

@RunWith(AndroidJUnit4.class)
@Postsubmit
// TODO(b/378944402): Enable test in presubmit
public class ContextHubEndpointTest {
    private static final int SESSION_ID_RANGE = ContextHubEndpointManager.SERVICE_SESSION_RANGE;
    private static final int MIN_SESSION_ID = 0;
    private static final int MAX_SESSION_ID = MIN_SESSION_ID + SESSION_ID_RANGE - 1;

    private static final String ENDPOINT_NAME = "Example test endpoint";
    private static final int ENDPOINT_ID = 1;
    private static final String ENDPOINT_PACKAGE_NAME = "com.android.server.location.contexthub";

    private ContextHubClientManager mClientManager;
    private ContextHubEndpointManager mEndpointManager;
    private HubInfoRegistry mHubInfoRegistry;
    private ContextHubTransactionManager mTransactionManager;
    private Context mContext;
    @Mock private IEndpointCommunication mMockEndpointCommunications;
    @Mock private IContextHubWrapper mMockContextHubWrapper;
    @Mock private IContextHubEndpointCallback mMockCallback;
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setUp() throws RemoteException, InstantiationException {
        when(mMockContextHubWrapper.getHubs()).thenReturn(Collections.emptyList());
        when(mMockContextHubWrapper.getEndpoints()).thenReturn(Collections.emptyList());
        when(mMockContextHubWrapper.registerEndpointHub(any(), any()))
                .thenReturn(mMockEndpointCommunications);
        when(mMockEndpointCommunications.requestSessionIdRange(SESSION_ID_RANGE))
                .thenReturn(new int[] {MIN_SESSION_ID, MAX_SESSION_ID});
        when(mMockCallback.asBinder()).thenReturn(new Binder());

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mHubInfoRegistry = new HubInfoRegistry(mContext, mMockContextHubWrapper);
        mClientManager = new ContextHubClientManager(mContext, mMockContextHubWrapper);
        mTransactionManager =
                new ContextHubTransactionManager(
                        mMockContextHubWrapper, mClientManager, new NanoAppStateManager());
        mEndpointManager =
                new ContextHubEndpointManager(
                        mContext, mMockContextHubWrapper, mHubInfoRegistry, mTransactionManager);
        mEndpointManager.init();
    }

    @Test
    public void testRegisterEndpoint() throws RemoteException {
        // Register an endpoint and confirm we can get a valid IContextHubEndoint reference
        HubEndpointInfo info =
                new HubEndpointInfo(
                        ENDPOINT_NAME, ENDPOINT_ID, ENDPOINT_PACKAGE_NAME, Collections.emptyList());
        IContextHubEndpoint endpoint =
                mEndpointManager.registerEndpoint(
                        info, mMockCallback, ENDPOINT_PACKAGE_NAME, /* attributionTag= */ null);
        assertThat(mEndpointManager.getNumRegisteredClients()).isEqualTo(1);
        assertThat(endpoint).isNotNull();
        HubEndpointInfo assignedInfo = endpoint.getAssignedHubEndpointInfo();
        assertThat(assignedInfo).isNotNull();
        HubEndpointIdentifier assignedIdentifier = assignedInfo.getIdentifier();
        assertThat(assignedIdentifier).isNotNull();

        // Unregister the endpoint and confirm proper clean-up
        mEndpointManager.unregisterEndpoint(assignedIdentifier.getEndpoint());
        assertThat(mEndpointManager.getNumRegisteredClients()).isEqualTo(0);
    }

    @Test
    public void testReserveSessionId() {
        assertThat(mEndpointManager.getNumAvailableSessions()).isEqualTo(SESSION_ID_RANGE);

        int sessionId = mEndpointManager.reserveSessionId();
        assertThat(sessionId).isAtLeast(MIN_SESSION_ID);
        assertThat(sessionId).isAtMost(MAX_SESSION_ID);
        assertThat(mEndpointManager.getNumAvailableSessions()).isEqualTo(SESSION_ID_RANGE - 1);

        mEndpointManager.returnSessionId(sessionId);
        assertThat(mEndpointManager.getNumAvailableSessions()).isEqualTo(SESSION_ID_RANGE);
    }
}
