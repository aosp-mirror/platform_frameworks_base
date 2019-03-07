/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class HotspotControllerImplTest extends SysuiTestCase {

    @Mock
    private ConnectivityManager mConnectivityManager;
    @Mock
    private WifiManager mWifiManager;
    @Mock
    private HotspotController.Callback mCallback1;
    @Mock
    private HotspotController.Callback mCallback2;
    private HotspotControllerImpl mController;
    private TestableLooper mLooper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = TestableLooper.get(this);

        mContext.addMockSystemService(ConnectivityManager.class, mConnectivityManager);
        mContext.addMockSystemService(WifiManager.class, mWifiManager);

        doAnswer((InvocationOnMock invocation) -> {
            ((WifiManager.SoftApCallback) invocation.getArgument(0)).onNumClientsChanged(1);
            return null;
        }).when(mWifiManager).registerSoftApCallback(any(WifiManager.SoftApCallback.class),
                any(Handler.class));

        mController = new HotspotControllerImpl(mContext, new Handler(mLooper.getLooper()));
    }

    @Test
    public void testAddingTwoCallbacksRegistersToWifiManagerOnce() {
        mController.addCallback(mCallback1);
        mController.addCallback(mCallback2);

        verify(mWifiManager, times(1)).registerSoftApCallback(eq(mController), any());
    }

    @Test
    public void testAddingCallbacksDoesntUpdateAll() {
        mController.addCallback(mCallback1);
        mController.addCallback(mCallback2);

        mLooper.processAllMessages();
        // Each callback should be updated only once
        verify(mCallback1, times(1)).onHotspotChanged(anyBoolean(), anyInt());
        verify(mCallback2, times(1)).onHotspotChanged(anyBoolean(), anyInt());
    }

    @Test
    public void testRemovingTwoCallbacksUnegistersToWifiManagerOnce() {
        mController.addCallback(mCallback1);
        mController.addCallback(mCallback2);

        mController.removeCallback(mCallback1);
        mController.removeCallback(mCallback2);

        verify(mWifiManager, times(1)).unregisterSoftApCallback(mController);
    }

    @Test
    public void testDoNotUnregisterIfRemainingCallbacks() {
        mController.addCallback(mCallback1);
        mController.addCallback(mCallback2);

        verify(mWifiManager, never()).unregisterSoftApCallback(any());
    }

}
