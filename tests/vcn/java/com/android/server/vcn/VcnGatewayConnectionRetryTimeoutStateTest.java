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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for VcnGatewayConnection.RetryTimeoutState */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionRetryTimeoutStateTest extends VcnGatewayConnectionTestBase {
    private long mFirstRetryInterval;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        mFirstRetryInterval = mConfig.getRetryInterval()[0];

        mGatewayConnection.setUnderlyingNetwork(TEST_UNDERLYING_NETWORK_RECORD_1);
        mGatewayConnection.transitionTo(mGatewayConnection.mRetryTimeoutState);
        mTestLooper.dispatchAll();
    }

    @Test
    public void testNewNetworkTriggerRetry() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_2);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectingState, mGatewayConnection.getCurrentState());
        verifyRetryTimeoutAlarmAndGetCallback(mFirstRetryInterval, true /* expectCanceled */);
    }

    @Test
    public void testSameNetworkDoesNotTriggerRetry() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_1);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mRetryTimeoutState, mGatewayConnection.getCurrentState());
        verifyRetryTimeoutAlarmAndGetCallback(mFirstRetryInterval, false /* expectCanceled */);
    }

    @Test
    public void testNullNetworkTriggersDisconnect() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(null);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectedState, mGatewayConnection.getCurrentState());
        verifyRetryTimeoutAlarmAndGetCallback(mFirstRetryInterval, true /* expectCanceled */);
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
    }

    @Test
    public void testSafeModeTimeoutNotifiesCallback() {
        verifySafeModeTimeoutNotifiesCallback(mGatewayConnection.mRetryTimeoutState);
    }

    @Test
    public void testTeardownDisconnectRequest() throws Exception {
        mGatewayConnection.teardownAsynchronously();
        mTestLooper.dispatchAll();

        assertNull(mGatewayConnection.getCurrentState());
        assertTrue(mGatewayConnection.isQuitting());
    }

    @Test
    public void testNonTeardownDisconnectRequest() throws Exception {
        mGatewayConnection.sendDisconnectRequestedAndAcquireWakelock("TEST", false);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectedState, mGatewayConnection.getCurrentState());
        assertFalse(mGatewayConnection.isQuitting());
    }
}
