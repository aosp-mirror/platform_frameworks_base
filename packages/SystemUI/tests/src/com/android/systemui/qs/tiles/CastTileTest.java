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

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.media.projection.MediaProjectionInfo;
import android.os.Handler;
import android.service.quicksettings.Tile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.lifecycle.LifecycleOwner;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.WifiIndicators;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;


@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
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
    SignalCallback mSignalCallback;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private HotspotController mHotspotController;
    @Mock
    private HotspotController.Callback mHotspotCallback;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private DialogLaunchAnimator mDialogLaunchAnimator;

    private TestableLooper mTestableLooper;
    private CastTile mCastTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);

        mCastTile = new CastTile(
                mHost,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mController,
                mKeyguard,
                mNetworkController,
                mHotspotController,
                mDialogLaunchAnimator
        );
        mCastTile.initialize();

        // We are not setting the mocks to listening, so we trigger a first refresh state to
        // set the initial state
        mCastTile.refreshState();

        mTestableLooper.processAllMessages();

        mCastTile.handleSetListening(true);
        ArgumentCaptor<SignalCallback> signalCallbackArgumentCaptor =
                ArgumentCaptor.forClass(SignalCallback.class);
        verify(mNetworkController).observe(any(LifecycleOwner.class),
                signalCallbackArgumentCaptor.capture());
        mSignalCallback = signalCallbackArgumentCaptor.getValue();

        ArgumentCaptor<HotspotController.Callback> hotspotCallbackArgumentCaptor =
                ArgumentCaptor.forClass(HotspotController.Callback.class);
        verify(mHotspotController).observe(any(LifecycleOwner.class),
                hotspotCallbackArgumentCaptor.capture());
        mHotspotCallback = hotspotCallbackArgumentCaptor.getValue();
    }

    // -------------------------------------------------
    // All these tests for enabled/disabled wifi have hotspot not enabled
    @Test
    public void testStateUnavailable_wifiDisabled() {
        IconState qsIcon = new IconState(false, 0, "");
        WifiIndicators indicators = new WifiIndicators(
                false, mock(IconState.class),
                qsIcon, false,false, "",
                false, "");
        mSignalCallback.setWifiIndicators(indicators);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void testStateUnavailable_wifiNotConnected() {
        IconState qsIcon = new IconState(false, 0, "");
        WifiIndicators indicators = new WifiIndicators(
                true, mock(IconState.class),
                qsIcon, false,false, "",
                false, "");
        mSignalCallback.setWifiIndicators(indicators);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    private void enableWifiAndProcessMessages() {
        IconState qsIcon = new IconState(true, 0, "");
        WifiIndicators indicators = new WifiIndicators(
                true, mock(IconState.class),
                qsIcon, false,false, "",
                false, "");
        mSignalCallback.setWifiIndicators(indicators);
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
    // -------------------------------------------------

    // -------------------------------------------------
    // All these tests for enabled/disabled hotspot have wifi not enabled
    @Test
    public void testStateUnavailable_hotspotDisabled() {
        mHotspotCallback.onHotspotChanged(false, 0);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void testStateUnavailable_hotspotEnabledNotConnected() {
        mHotspotCallback.onHotspotChanged(true, 0);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void testStateActive_hotspotEnabledAndConnectedAndCasting() {
        CastController.CastDevice device = new CastController.CastDevice();
        device.state = CastController.CastDevice.STATE_CONNECTED;
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        mHotspotCallback.onHotspotChanged(true, 1);
        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
    }

    @Test
    public void testStateInactive_hotspotEnabledAndConnectedAndNotCasting() {
        mHotspotCallback.onHotspotChanged(true, 1);
        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_INACTIVE, mCastTile.getState().state);
    }
    // -------------------------------------------------

    @Test
    public void testHandleClick_castDevicePresent() {
        CastController.CastDevice device = new CastController.CastDevice();
        device.state = CastDevice.STATE_CONNECTED;
        device.tag = mock(MediaRouter.RouteInfo.class);
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);
        when(mKeyguard.isShowing()).thenReturn(true);

        enableWifiAndProcessMessages();
        mCastTile.handleClick(null /* view */);
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
        mCastTile.handleClick(null /* view */);
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

    @Test
    public void testExpandView_wifiNotConnected() {
        mCastTile.refreshState();
        mTestableLooper.processAllMessages();

        assertFalse(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_wifiEnabledNotCasting() {
        enableWifiAndProcessMessages();

        assertTrue(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_casting_projection() {
        CastController.CastDevice device = new CastController.CastDevice();
        device.state = CastController.CastDevice.STATE_CONNECTED;
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        assertFalse(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_connecting_projection() {
        CastController.CastDevice connecting = new CastController.CastDevice();
        connecting.state = CastDevice.STATE_CONNECTING;
        connecting.name = "Test Casting Device";

        List<CastDevice> devices = new ArrayList<>();
        devices.add(connecting);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        assertFalse(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_casting_mediaRoute() {
        CastController.CastDevice device = new CastController.CastDevice();
        device.state = CastDevice.STATE_CONNECTED;
        device.tag = mock(MediaRouter.RouteInfo.class);
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        assertTrue(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_connecting_mediaRoute() {
        CastController.CastDevice connecting = new CastController.CastDevice();
        connecting.state = CastDevice.STATE_CONNECTING;
        connecting.tag = mock(RouteInfo.class);
        connecting.name = "Test Casting Device";

        List<CastDevice> devices = new ArrayList<>();
        devices.add(connecting);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        assertTrue(mCastTile.getState().forceExpandIcon);
    }
}
