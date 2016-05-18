/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.net.INetworkStatsService;
import android.os.INetworkManagementService;
import android.os.test.TestLooper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


public class TetherInterfaceSMTest {
    private static final String IFACE_NAME = "testnet1";

    @Mock private INetworkManagementService mNMService;
    @Mock private INetworkStatsService mStatsService;
    @Mock private IControlsTethering mTetherHelper;

    private final TestLooper mLooper = new TestLooper();
    private final Object mMutex = new Object();
    private TetherInterfaceSM mTestedSm;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestedSm = new TetherInterfaceSM(IFACE_NAME, mLooper.getLooper(), false, mMutex,
                mNMService, mStatsService, mTetherHelper);
        mTestedSm.start();
    }

    @Test
    public void shouldDoNothingUntilRequested() {
        final int [] NOOP_COMMANDS = {
            TetherInterfaceSM.CMD_TETHER_MODE_DEAD,
            TetherInterfaceSM.CMD_TETHER_UNREQUESTED,
            TetherInterfaceSM.CMD_INTERFACE_UP,
            TetherInterfaceSM.CMD_CELL_DUN_ERROR,
            TetherInterfaceSM.CMD_IP_FORWARDING_ENABLE_ERROR,
            TetherInterfaceSM.CMD_IP_FORWARDING_DISABLE_ERROR,
            TetherInterfaceSM.CMD_START_TETHERING_ERROR,
            TetherInterfaceSM.CMD_STOP_TETHERING_ERROR,
            TetherInterfaceSM.CMD_SET_DNS_FORWARDERS_ERROR,
            TetherInterfaceSM.CMD_TETHER_CONNECTION_CHANGED
        };
        for (int command : NOOP_COMMANDS) {
            mTestedSm.sendMessage(command);
            dispatchUntilIdle();
            // None of those commands should trigger us to request action from
            // the rest of the system.
            verifyNoMoreInteractions(mNMService);
            verifyNoMoreInteractions(mStatsService);
            verifyNoMoreInteractions(mTetherHelper);
        }
    }

    private void dispatchUntilIdle() {
        for (int i = 0; i < 100; i++) {
            if (mLooper.isIdle()) {
                return;
            }
            mLooper.dispatchAll();
        }
        throw new RuntimeException("Failed to clear message loop.");
    }
}
