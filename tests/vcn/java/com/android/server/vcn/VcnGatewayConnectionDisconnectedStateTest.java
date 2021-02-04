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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for VcnGatewayConnection.DisconnectedState */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionDisconnectedStateTest extends VcnGatewayConnectionTestBase {
    @Before
    public void setUp() throws Exception {
        super.setUp();

        mGatewayConnection.transitionTo(mGatewayConnection.mDisconnectedState);
        mTestLooper.dispatchAll();
    }

    @Test
    public void testEnterWhileNotRunningTriggersQuit() throws Exception {
        final VcnGatewayConnection vgc =
                new VcnGatewayConnection(
                        mVcnContext, TEST_SUB_GRP, TEST_SUBSCRIPTION_SNAPSHOT, mConfig, mDeps);

        vgc.setIsRunning(false);
        vgc.transitionTo(vgc.mDisconnectedState);
        mTestLooper.dispatchAll();

        assertNull(vgc.getCurrentState());
    }

    @Test
    public void testNetworkChangesTriggerStateTransitions() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_1);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectingState, mGatewayConnection.getCurrentState());
    }

    @Test
    public void testNullNetworkDoesNotTriggerStateTransition() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(null);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectedState, mGatewayConnection.getCurrentState());
    }

    @Test
    public void testTeardown() throws Exception {
        mGatewayConnection.teardownAsynchronously();
        mTestLooper.dispatchAll();

        assertNull(mGatewayConnection.getCurrentState());
        verify(mIpSecSvc).deleteTunnelInterface(eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), any());
    }
}
