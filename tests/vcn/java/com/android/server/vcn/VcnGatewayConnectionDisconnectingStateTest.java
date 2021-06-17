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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for VcnGatewayConnection.DisconnectedState */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionDisconnectingStateTest extends VcnGatewayConnectionTestBase {
    @Before
    public void setUp() throws Exception {
        super.setUp();

        mGatewayConnection.setIkeSession(
                mGatewayConnection.buildIkeSession(TEST_UNDERLYING_NETWORK_RECORD_2.network));

        // ensure that mGatewayConnection has an underlying Network before entering
        // DisconnectingState
        mGatewayConnection.setUnderlyingNetwork(TEST_UNDERLYING_NETWORK_RECORD_2);
        mGatewayConnection.transitionTo(mGatewayConnection.mDisconnectingState);
        mTestLooper.dispatchAll();
    }

    @Test
    public void testIkeSessionClosed() throws Exception {
        getIkeSessionCallback().onClosed();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mRetryTimeoutState, mGatewayConnection.getCurrentState());
        verify(mMockIkeSession).close();
        verify(mMockIkeSession, never()).kill();
        verifyTeardownTimeoutAlarmAndGetCallback(true /* expectCanceled */);
    }

    @Test
    public void testTimeoutExpired() throws Exception {
        Runnable delayedEvent =
                verifyTeardownTimeoutAlarmAndGetCallback(false /* expectCanceled */);

        // Can't use mTestLooper to advance the time since VcnGatewayConnection uses WakeupMessages
        // (which are mocked here). Directly invoke the runnable instead. This is still sufficient,
        // since verifyTeardownTimeoutAlarmAndGetCallback() verifies the WakeupMessage was scheduled
        // with the correct delay.
        delayedEvent.run();
        mTestLooper.dispatchAll();

        verify(mMockIkeSession).kill();
    }

    @Test
    public void testTeardown() throws Exception {
        mGatewayConnection.teardownAsynchronously();
        mTestLooper.dispatchAll();

        // Should do nothing; already tearing down.
        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        verifyTeardownTimeoutAlarmAndGetCallback(false /* expectCanceled */);
        assertTrue(mGatewayConnection.isQuitting());
    }

    @Test
    public void testSafeModeTimeoutNotifiesCallbackAndUnregistersNetworkAgent() {
        verifySafeModeTimeoutNotifiesCallbackAndUnregistersNetworkAgent(
                mGatewayConnection.mDisconnectingState);
    }

    @Test
    public void testNonTeardownDisconnectRequest() throws Exception {
        mGatewayConnection.sendDisconnectRequestedAndAcquireWakelock("TEST", false);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        assertFalse(mGatewayConnection.isQuitting());
    }
}
