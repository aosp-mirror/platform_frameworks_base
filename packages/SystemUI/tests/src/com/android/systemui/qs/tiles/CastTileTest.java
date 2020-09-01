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

import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.media.projection.MediaProjectionInfo;
import android.service.quicksettings.Tile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.lifecycle.LifecycleOwner;
import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;


@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class CastTileTest extends SysuiTestCase {

    @Mock
    private CastController mController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private KeyguardStateController mKeyguard;
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
        mKeyguard = mDependency.injectMockDependency(KeyguardStateController.class);
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

    private void enableWifiAndProcessMessages() {
        NetworkController.IconState qsIcon =
                new NetworkController.IconState(true, 0, "");
        mCallback.setWifiIndicators(true, mock(NetworkController.IconState.class),
                qsIcon, false,false, "",
                false, "");
        mTestableLooper.processAllMessages();
    }

    @Test
    public void testStateActive_wifiEnabledAndCasting() {
        CastController.CastDevice device = new CastController.CastDevice();
        device.state = CastController.CastDevice.STATE_CONNECTED;
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
    }

    @Test
    public void testStateInactive_wifiEnabledNotCasting() {
        enableWifiAndProcessMessages();
        assertEquals(Tile.STATE_INACTIVE, mCastTile.getState().state);
    }

    @Test
    public void testHandleClick_castDevicePresent() {
        CastController.CastDevice device = new CastController.CastDevice();
        device.state = CastDevice.STATE_CONNECTED;
        device.tag = mock(MediaRouter.RouteInfo.class);
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();
        mCastTile.handleClick();
        mTestableLooper.processAllMessages();

        verify(mActivityStarter, times(1)).postQSRunnableDismissingKeyguard(any());
    }

    @Test
    public void testHandleClick_projectionOnly() {
        CastController.CastDevice device = new CastController.CastDevice();
        device.state = CastDevice.STATE_CONNECTED;
        device.tag = mock(MediaProjectionInfo.class);
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();
        mCastTile.handleClick();
        mTestableLooper.processAllMessages();

        verify(mController, times(1)).stopCasting(same(device));
    }

    @Test
    public void testUpdateState_projectionOnly() {
        CastController.CastDevice device = new CastController.CastDevice();
        device.state = CastDevice.STATE_CONNECTED;
        device.tag = mock(MediaProjectionInfo.class);
        device.name = "Test Projection Device";
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
        assertTrue(mCastTile.getState().secondaryLabel.toString().startsWith(device.name));
    }

    @Test
    public void testUpdateState_castingAndProjection() {
        CastController.CastDevice casting = new CastController.CastDevice();
        casting.state = CastDevice.STATE_CONNECTED;
        casting.tag = mock(RouteInfo.class);
        casting.name = "Test Casting Device";

        CastController.CastDevice projection = new CastController.CastDevice();
        projection.state = CastDevice.STATE_CONNECTED;
        projection.tag = mock(MediaProjectionInfo.class);
        projection.name = "Test Projection Device";

        List<CastDevice> devices = new ArrayList<>();
        devices.add(casting);
        devices.add(projection);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        // Note here that the tile should be active, and should choose casting over projection.
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
        assertTrue(mCastTile.getState().secondaryLabel.toString().startsWith(casting.name));
    }

    @Test
    public void testUpdateState_connectedAndConnecting() {
        CastController.CastDevice connecting = new CastController.CastDevice();
        connecting.state = CastDevice.STATE_CONNECTING;
        connecting.tag = mock(RouteInfo.class);
        connecting.name = "Test Casting Device";

        CastController.CastDevice connected = new CastController.CastDevice();
        connected.state = CastDevice.STATE_CONNECTED;
        connected.tag = mock(RouteInfo.class);
        connected.name = "Test Casting Device";

        List<CastDevice> devices = new ArrayList<>();
        devices.add(connecting);
        devices.add(connected);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        // Tile should be connected and always prefer the connected device.
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
        assertTrue(mCastTile.getState().secondaryLabel.toString().startsWith(connected.name));
    }
}
