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

import static com.android.systemui.flags.Flags.SIGNAL_CALLBACK_DEPRECATION;

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
import android.testing.TestableLooper;

import androidx.lifecycle.LifecycleOwner;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.WifiIndicators;
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastDevice;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
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
    private QSHost mHost;
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
    private DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    private QsEventLogger mUiEventLogger;

    private final TileJavaAdapter mJavaAdapter = new TileJavaAdapter();
    private final FakeConnectivityRepository mConnectivityRepository =
            new FakeConnectivityRepository();
    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();

    private TestableLooper mTestableLooper;
    private CastTile mCastTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);
    }

    @After
    public void tearDown() {
        mCastTile.destroy();
        mTestableLooper.processAllMessages();
    }

    // -------------------------------------------------
    // All these tests for enabled/disabled wifi have hotspot not enabled
    @Test
    public void testStateUnavailable_wifiDisabled() {
        createAndStartTileOldImpl();
        IconState qsIcon = new IconState(false, 0, "");
        WifiIndicators indicators = new WifiIndicators(
                false, mock(IconState.class),
                qsIcon, false, false, "",
                false, "");
        mSignalCallback.setWifiIndicators(indicators);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void testStateUnavailable_wifiNotConnected() {
        createAndStartTileOldImpl();
        IconState qsIcon = new IconState(false, 0, "");
        WifiIndicators indicators = new WifiIndicators(
                true, mock(IconState.class),
                qsIcon, false, false, "",
                false, "");
        mSignalCallback.setWifiIndicators(indicators);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    private void enableWifiAndProcessMessages() {
        IconState qsIcon = new IconState(true, 0, "");
        WifiIndicators indicators = new WifiIndicators(
                true, mock(IconState.class),
                qsIcon, false, false, "",
                false, "");
        mSignalCallback.setWifiIndicators(indicators);
        mTestableLooper.processAllMessages();
    }

    @Test
    public void testStateActive_wifiEnabledAndCasting() {
        createAndStartTileOldImpl();
        CastDevice device = createConnectedCastDevice();
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
    }

    @Test
    public void testStateInactive_wifiEnabledNotCasting() {
        createAndStartTileOldImpl();
        enableWifiAndProcessMessages();
        assertEquals(Tile.STATE_INACTIVE, mCastTile.getState().state);
    }
    // -------------------------------------------------

    // -------------------------------------------------
    // All these tests for enabled/disabled wifi have hotspot not enabled, and have the
    // SIGNAL_CALLBACK_DEPRECATION flag set to true

    @Test
    public void stateUnavailable_noDefaultNetworks_newPipeline() {
        createAndStartTileNewImpl();
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void stateUnavailable_mobileConnected_newPipeline() {
        createAndStartTileNewImpl();
        mConnectivityRepository.setMobileConnected(true);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void stateInactive_wifiConnected_newPipeline() {
        createAndStartTileNewImpl();
        mConnectivityRepository.setWifiConnected(true);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_INACTIVE, mCastTile.getState().state);
    }

    @Test
    public void stateInactive_ethernetConnected_newPipeline() {
        createAndStartTileNewImpl();
        mConnectivityRepository.setEthernetConnected(true);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_INACTIVE, mCastTile.getState().state);
    }

    @Test
    public void stateActive_wifiConnectedAndCasting_newPipeline() {
        createAndStartTileNewImpl();
        CastDevice device = createConnectedCastDevice();
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        mConnectivityRepository.setWifiConnected(true);

        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
    }

    @Test
    public void stateActive_ethernetConnectedAndCasting_newPipeline() {
        createAndStartTileNewImpl();
        CastDevice device = createConnectedCastDevice();
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        mConnectivityRepository.setEthernetConnected(true);

        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
    }

    // -------------------------------------------------

    // -------------------------------------------------
    // All these tests for enabled/disabled hotspot have wifi not enabled
    @Test
    public void testStateUnavailable_hotspotDisabled() {
        createAndStartTileOldImpl();
        mHotspotCallback.onHotspotChanged(false, 0);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void testStateUnavailable_hotspotEnabledNotConnected() {
        createAndStartTileOldImpl();
        mHotspotCallback.onHotspotChanged(true, 0);
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_UNAVAILABLE, mCastTile.getState().state);
    }

    @Test
    public void testStateActive_hotspotEnabledAndConnectedAndCasting() {
        createAndStartTileOldImpl();
        CastDevice device = createConnectedCastDevice();
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        mHotspotCallback.onHotspotChanged(true, 1);
        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
    }

    @Test
    public void testStateInactive_hotspotEnabledAndConnectedAndNotCasting() {
        createAndStartTileOldImpl();
        mHotspotCallback.onHotspotChanged(true, 1);
        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_INACTIVE, mCastTile.getState().state);
    }
    // -------------------------------------------------

    @Test
    public void testHandleClick_castDevicePresent() {
        createAndStartTileOldImpl();
        CastDevice device = new CastDevice(
                "id",
                /* name= */ null,
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaRouter,
                /* tag= */ mock(MediaRouter.RouteInfo.class));
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
        createAndStartTileOldImpl();
        CastDevice device = new CastDevice(
                "id",
                /* name= */ null,
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaProjection,
                /* tag= */ mock(MediaProjectionInfo.class));
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
        createAndStartTileOldImpl();
        CastDevice device = new CastDevice(
                "id",
                /* name= */ "Test Projection Device",
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaProjection,
                /* tag= */ mock(MediaProjectionInfo.class));
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
        assertTrue(mCastTile.getState().secondaryLabel.toString()
                .startsWith("Test Projection Device"));
    }

    @Test
    public void testUpdateState_castingAndProjection() {
        createAndStartTileOldImpl();
        CastDevice casting = new CastDevice(
                "id1",
                /* name= */ "Test Casting Device",
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaRouter,
                /* tag= */ mock(RouteInfo.class));
        CastDevice projection = new CastDevice(
                "id2",
                /* name= */ "Test Projection Device",
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaProjection,
                /* tag= */ mock(MediaProjectionInfo.class));

        List<CastDevice> devices = new ArrayList<>();
        devices.add(casting);
        devices.add(projection);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        // Note here that the tile should be active, and should choose casting over projection.
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
        assertTrue(mCastTile.getState().secondaryLabel.toString()
                .startsWith("Test Casting Device"));
    }

    @Test
    public void testUpdateState_connectedAndConnecting() {
        createAndStartTileOldImpl();
        CastDevice connecting = new CastDevice(
                "id",
                /* name= */ "Test Connecting Device",
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connecting,
                /* origin= */ CastDevice.CastOrigin.MediaRouter,
                /* tag= */ mock(RouteInfo.class));
        CastDevice connected = new CastDevice(
                "id",
                /* name= */ "Test Connected Device",
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaRouter,
                /* tag= */ mock(RouteInfo.class));
        List<CastDevice> devices = new ArrayList<>();
        devices.add(connecting);
        devices.add(connected);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        // Tile should be connected and always prefer the connected device.
        assertEquals(Tile.STATE_ACTIVE, mCastTile.getState().state);
        assertTrue(mCastTile.getState().secondaryLabel.toString()
                .startsWith("Test Connected Device"));
    }

    @Test
    public void testExpandView_wifiNotConnected() {
        createAndStartTileOldImpl();
        mCastTile.refreshState();
        mTestableLooper.processAllMessages();

        assertFalse(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_wifiEnabledNotCasting() {
        createAndStartTileOldImpl();
        enableWifiAndProcessMessages();

        assertTrue(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_casting_projection() {
        createAndStartTileOldImpl();
        CastDevice device = createConnectedCastDevice();
        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        assertFalse(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_connecting_projection() {
        createAndStartTileOldImpl();
        CastDevice connecting = new CastDevice(
                "id",
                /* name= */
                "Test Projection Device",
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaProjection,
                /* tag= */ mock(MediaProjectionInfo.class));

        List<CastDevice> devices = new ArrayList<>();
        devices.add(connecting);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        assertFalse(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_casting_mediaRoute() {
        createAndStartTileOldImpl();
        CastDevice device = new CastDevice(
                "id",
                /* name= */ "Test Router Device",
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaRouter,
                /* tag= */ mock(RouteInfo.class));

        List<CastDevice> devices = new ArrayList<>();
        devices.add(device);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        assertTrue(mCastTile.getState().forceExpandIcon);
    }

    @Test
    public void testExpandView_connecting_mediaRoute() {
        createAndStartTileOldImpl();
        CastDevice connecting = new CastDevice(
                "id",
                /* name= */ "Test Router Device",
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connecting,
                /* origin= */ CastDevice.CastOrigin.MediaRouter,
                /* tag= */ mock(RouteInfo.class));
        List<CastDevice> devices = new ArrayList<>();
        devices.add(connecting);
        when(mController.getCastDevices()).thenReturn(devices);

        enableWifiAndProcessMessages();

        assertTrue(mCastTile.getState().forceExpandIcon);
    }

    /**
     * For simplicity, let this method still set the field even though that's kind of gross
     */
    private void createAndStartTileOldImpl() {
        mFeatureFlags.set(SIGNAL_CALLBACK_DEPRECATION, false);
        mCastTile = new CastTile(
                mHost,
                mUiEventLogger,
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
                mDialogTransitionAnimator,
                mConnectivityRepository,
                mJavaAdapter,
                mFeatureFlags
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

    private void createAndStartTileNewImpl() {
        mFeatureFlags.set(SIGNAL_CALLBACK_DEPRECATION, true);
        mCastTile = new CastTile(
                mHost,
                mUiEventLogger,
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
                mDialogTransitionAnimator,
                mConnectivityRepository,
                mJavaAdapter,
                mFeatureFlags
        );
        mCastTile.initialize();

        // Since we do not capture the callbacks like in the old impl, set the state to RESUMED
        // So that TileJavaAdapter is collecting on flows
        mCastTile.setListening(new Object(), true);

        mTestableLooper.processAllMessages();

        ArgumentCaptor<HotspotController.Callback> hotspotCallbackArgumentCaptor =
                ArgumentCaptor.forClass(HotspotController.Callback.class);
        verify(mHotspotController).observe(any(LifecycleOwner.class),
                hotspotCallbackArgumentCaptor.capture());
        mHotspotCallback = hotspotCallbackArgumentCaptor.getValue();
    }

    private CastDevice createConnectedCastDevice() {
        return new CastDevice(
                "id",
                /* name= */ null,
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaProjection,
                /* tag= */ null);
    }
}
