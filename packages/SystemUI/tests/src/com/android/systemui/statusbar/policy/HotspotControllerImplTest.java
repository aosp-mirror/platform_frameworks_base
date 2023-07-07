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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.TetheringManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class HotspotControllerImplTest extends SysuiTestCase {

    @Mock
    private UserTracker mUserTracker;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private TetheringManager mTetheringManager;
    @Mock
    private WifiManager mWifiManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private HotspotController.Callback mCallback1;
    @Mock
    private HotspotController.Callback mCallback2;
    @Mock
    private TetheringManager.TetheringInterfaceRegexps mTetheringInterfaceRegexps;
    @Captor
    private ArgumentCaptor<TetheringManager.TetheringEventCallback> mTetheringCallbackCaptor;
    private HotspotControllerImpl mController;
    private TestableLooper mLooper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = TestableLooper.get(this);

        mContext.addMockSystemService(WifiManager.class, mWifiManager);
        mContext.addMockSystemService(TetheringManager.class, mTetheringManager);
        mContext.addMockSystemService(UserManager.class, mUserManager);

        when(mUserManager.isUserAdmin(anyInt())).thenReturn(true);
        when(mTetheringInterfaceRegexps.getTetherableWifiRegexs()).thenReturn(
                Collections.singletonList("test"));

        doAnswer((InvocationOnMock invocation) -> {
            ((WifiManager.SoftApCallback) invocation.getArgument(1))
                    .onConnectedClientsChanged(new ArrayList<>());
            return null;
        }).when(mWifiManager).registerSoftApCallback(any(Executor.class),
                any(WifiManager.SoftApCallback.class));

        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_show_wifi_tethering, true);

        Handler handler = new Handler(mLooper.getLooper());

        mController = new HotspotControllerImpl(mContext, mUserTracker, handler, handler,
                mDumpManager);
        verify(mTetheringManager)
                .registerTetheringEventCallback(any(), mTetheringCallbackCaptor.capture());
    }

    @Test
    public void testAddingTwoCallbacksRegistersToWifiManagerOnce() {
        mController.addCallback(mCallback1);
        mController.addCallback(mCallback2);

        verify(mWifiManager, times(1)).registerSoftApCallback(any(), eq(mController));
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

    @Test
    public void testHotspotSupported_default() {
        assertTrue(mController.isHotspotSupported());
    }

    @Test
    public void testHotspotSupported_rightConditions() {
        mTetheringCallbackCaptor.getValue().onTetheringSupported(true);

        assertTrue(mController.isHotspotSupported());

        mTetheringCallbackCaptor.getValue()
                .onTetherableInterfaceRegexpsChanged(mTetheringInterfaceRegexps);

        assertTrue(mController.isHotspotSupported());
    }

    @Test
    public void testHotspotSupported_callbackCalledOnChange_tetheringSupported() {
        mController.addCallback(mCallback1);
        mTetheringCallbackCaptor.getValue().onTetheringSupported(false);

        verify(mCallback1).onHotspotAvailabilityChanged(false);
    }

    @Test
    public void testHotspotSupported_callbackCalledOnChange_tetherableInterfaces() {
        when(mTetheringInterfaceRegexps.getTetherableWifiRegexs())
                .thenReturn(Collections.emptyList());
        mController.addCallback(mCallback1);
        mTetheringCallbackCaptor.getValue()
                .onTetherableInterfaceRegexpsChanged(mTetheringInterfaceRegexps);

        verify(mCallback1).onHotspotAvailabilityChanged(false);
    }

    @Test
    public void testHotspotSupported_resource_false() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_show_wifi_tethering, false);

        Handler handler = new Handler(mLooper.getLooper());

        HotspotController controller =
                new HotspotControllerImpl(mContext, mUserTracker, handler, handler, mDumpManager);

        verifyNoMoreInteractions(mTetheringManager);
        assertFalse(controller.isHotspotSupported());
    }
}
