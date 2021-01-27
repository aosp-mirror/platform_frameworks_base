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

import static com.android.server.vcn.VcnGatewayConnection.TEARDOWN_TIMEOUT_SECONDS;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/** Tests for VcnGatewayConnection.DisconnectedState */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionDisconnectingStateTest extends VcnGatewayConnectionTestBase {
    @Before
    public void setUp() throws Exception {
        super.setUp();

        mGatewayConnection.setIkeSession(mGatewayConnection.buildIkeSession());

        mGatewayConnection.transitionTo(mGatewayConnection.mDisconnectingState);
        mTestLooper.dispatchAll();
    }

    @Test
    public void testIkeSessionClosed() throws Exception {
        getIkeSessionCallback().onClosed();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectedState, mGatewayConnection.getCurrentState());
    }

    @Test
    public void testTimeoutExpired() throws Exception {
        mTestLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(TEARDOWN_TIMEOUT_SECONDS));
        mTestLooper.dispatchAll();

        verify(mMockIkeSession).kill();
    }

    @Test
    public void testTeardown() throws Exception {
        mGatewayConnection.teardownAsynchronously();
        mTestLooper.dispatchAll();

        // Should do nothing; already tearing down.
        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
    }
}
