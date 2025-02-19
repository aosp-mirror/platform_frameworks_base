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

import static com.android.server.vcn.VcnGatewayConnection.VcnNetworkAgent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

// TODO: b/374174952 After B finalization, use Sdk36ModuleController to ensure VCN tests only run on
// Android B/B+
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@SmallTest
public class VcnGatewayConnectionRetryTimeoutStateTest extends VcnGatewayConnectionTestBase {
    private long mFirstRetryInterval;
    private VcnNetworkAgent mNetworkAgent;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        mFirstRetryInterval = mConfig.getRetryIntervalsMillis()[0];
        mNetworkAgent = mock(VcnNetworkAgent.class);

        mGatewayConnection.setUnderlyingNetwork(TEST_UNDERLYING_NETWORK_RECORD_1);
        mGatewayConnection.transitionTo(mGatewayConnection.mRetryTimeoutState);
        mTestLooper.dispatchAll();

        mGatewayConnection.setNetworkAgent(mNetworkAgent);
    }

    @Test
    public void testNewNetworkTriggerRetry() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_2);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectingState, mGatewayConnection.getCurrentState());
        verifyRetryTimeoutAlarmAndGetCallback(mFirstRetryInterval, true /* expectCanceled */);

        assertNotNull(mGatewayConnection.getNetworkAgent());
        verify(mNetworkAgent, never()).unregister();
    }

    @Test
    public void testSameNetworkDoesNotTriggerRetry() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_1);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mRetryTimeoutState, mGatewayConnection.getCurrentState());
        verifyRetryTimeoutAlarmAndGetCallback(mFirstRetryInterval, false /* expectCanceled */);

        assertNotNull(mGatewayConnection.getNetworkAgent());
        verify(mNetworkAgent, never()).unregister();
    }

    @Test
    public void testNullNetworkTriggersDisconnect() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkControllerCallback()
                .onSelectedUnderlyingNetworkChanged(null);
        mTestLooper.dispatchAll();

        // Verify that sending a non-quitting disconnect request does not unset the isQuitting flag
        mGatewayConnection.sendDisconnectRequestedAndAcquireWakelock("TEST", false);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectedState, mGatewayConnection.getCurrentState());
        verifyRetryTimeoutAlarmAndGetCallback(mFirstRetryInterval, true /* expectCanceled */);

        assertNull(mGatewayConnection.getNetworkAgent());
        verify(mNetworkAgent).unregister();
    }

    @Test
    public void testTimeoutElapsingTriggersRetry() throws Exception {
        final Runnable delayedEvent =
                verifyRetryTimeoutAlarmAndGetCallback(
                        mFirstRetryInterval, false /* expectCanceled */);

        // Can't use mTestLooper to advance the time since VcnGatewayConnection uses WakeupMessages
        // (which are mocked here). Directly invoke the runnable instead. This is still sufficient,
        // since verifyRetryTimeoutAlarmAndGetCallback() verifies the WakeupMessage was scheduled
        // with the correct delay.
        delayedEvent.run();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectingState, mGatewayConnection.getCurrentState());
        verifyRetryTimeoutAlarmAndGetCallback(mFirstRetryInterval, true /* expectCanceled */);

        assertNotNull(mGatewayConnection.getNetworkAgent());
        verify(mNetworkAgent, never()).unregister();
    }

    @Test
    public void testSafeModeTimeoutNotifiesCallbackAndUnregistersNetworkAgent() {
        verifySafeModeTimeoutNotifiesCallbackAndUnregistersNetworkAgent(
                mGatewayConnection.mRetryTimeoutState);
    }

    @Test
    public void testTeardownDisconnectRequest() throws Exception {
        mGatewayConnection.teardownAsynchronously();
        mTestLooper.dispatchAll();

        assertNull(mGatewayConnection.getCurrentState());
        assertTrue(mGatewayConnection.isQuitting());

        assertNull(mGatewayConnection.getNetworkAgent());
        verify(mNetworkAgent).unregister();
    }

    @Test
    public void testNonTeardownDisconnectRequest() throws Exception {
        mGatewayConnection.sendDisconnectRequestedAndAcquireWakelock("TEST", false);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectedState, mGatewayConnection.getCurrentState());
        assertFalse(mGatewayConnection.isQuitting());

        assertNull(mGatewayConnection.getNetworkAgent());
        verify(mNetworkAgent).unregister();
    }
}
