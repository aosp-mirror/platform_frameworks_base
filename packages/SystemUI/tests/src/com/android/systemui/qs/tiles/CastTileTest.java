/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tiles;

import static junit.framework.TestCase.assertEquals;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.service.quicksettings.Tile;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.lifecycle.LifecycleOwner;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.NetworkController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;
import java.util.Set;


@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class CastTileTest extends SysuiTestCase {

    @Mock
    private CastController mController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private KeyguardMonitor mKeyguard;
    @Mock
    private NetworkController mNetworkController;
    @Mock
    private QSTileHost mHost;
    @Mock
    NetworkController.SignalCallback mCallback;

    private TestableLooper mTestableLooper;
    private CastTile mCastTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        mDependency.injectTestDependency(Dependency.BG_LOOPER, mTestableLooper.getLooper());
        mController = mDependency.injectMockDependency(CastController.class);
        mActivityStarter = mDependency.injectMockDependency(ActivityStarter.class);
        mKeyguard = mDependency.injectMockDependency(KeyguardMonitor.class);
        mNetworkController = mDependency.injectMockDependency(NetworkController.class);

        when(mHost.getContext()).thenReturn(mContext);

        mCastTile = new CastTile(mHost, mController, mKeyguard, mNetworkController,
                mActivityStarter);

        // We are not setting the mocks to listening, so we trigger a first refresh state to
        // set the initial state
        mCastTile.refreshState();

        mCastTile.handleSetListening(true);
        ArgumentCaptor<NetworkController.SignalCallback> signalCallbackArgumentCaptor =
                ArgumentCaptor.forClass(NetworkController.SignalCallback.class);
        verify(mNetworkController).observe(any(LifecycleOwner.class),
                signalCallbackArgumentCaptor.capture());
        mCallback = signalCallbackArgumentCaptor.getValue();

    }

    @Test
    public void testStateUnavailable_wifiDisabled() {
        NetworkController.IconState qsIcon =
                new NetworkController.IconState(false, 0, "");
        mCallback.setWifiIndicators(false, mock(NetworkController.IconState.class),
                qsIcon, false,false, "",
                false, "");
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void testStateUnavailable_wifiNotConnected() {
        NetworkController.IconState qsIcon =
                new NetworkController.IconState(false, 0, "");
        mCallback.setWifiIndicators(true, mock(NetworkController.IconState.class),
                qsIcon, false,false, "",
                false, "");
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void testStateActive_wifiEnabledAndCasting() {
        CastController.CastDevice device = mock(CastController.CastDevice.class);
        device.state = CastController.CastDevice.STATE_CONNECTED;
        Set<CastController.CastDevice> devices = new HashSet<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        NetworkController.IconState qsIcon =
                new NetworkController.IconState(true, 0, "");
        mCallback.setWifiIndicators(true, mock(NetworkController.IconState.class),
                qsIcon, false,false, "",
                false, "");
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
    }

    @Test
    public void testStateInactive_wifiEnabledNotCasting() {
        NetworkController.IconState qsIcon =
                new NetworkController.IconState(true, 0, "");
        mCallback.setWifiIndicators(true, mock(NetworkController.IconState.class),
                qsIcon, false,false, "",
                false, "");
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_INACTIVE, mCastTile.getState().state);
    }
}
