/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.settingslib.media;

import static android.media.MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;
import static android.media.MediaRoute2ProviderService.REASON_NETWORK_ERROR;
import static android.media.MediaRoute2ProviderService.REASON_UNKNOWN_ERROR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.media.RoutingSessionInfo;
import android.media.session.MediaSessionManager;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.testutils.shadow.ShadowRouter2Manager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowRouter2Manager.class})
public class InfoMediaManagerTest {

    private static final String TEST_PACKAGE_NAME = "com.test.packagename";
    private static final String TEST_ID = "test_id";
    private static final String TEST_NAME = "test_name";

    @Mock
    private MediaRouter2Manager mRouterManager;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private MediaManager.MediaDeviceCallback mCallback;
    @Mock
    private MediaSessionManager mMediaSessionManager;

    private InfoMediaManager mInfoMediaManager;
    private Context mContext;
    private ShadowRouter2Manager mShadowRouter2Manager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);

        doReturn(mMediaSessionManager).when(mContext).getSystemService(
                Context.MEDIA_SESSION_SERVICE);
        mInfoMediaManager =
                new InfoMediaManager(mContext, TEST_PACKAGE_NAME, null, mLocalBluetoothManager);
        mShadowRouter2Manager = ShadowRouter2Manager.getShadow();
        mInfoMediaManager.mRouterManager = MediaRouter2Manager.getInstance(mContext);
    }

    @Test
    public void onRouteAdded_getAvailableRoutes_shouldAddMediaDevice() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo);
        final List<String> selectedRoutes = new ArrayList<>();
        selectedRoutes.add(TEST_ID);
        when(sessionInfo.getSelectedRoutes()).thenReturn(selectedRoutes);
        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setTransferableRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRoutesAdded(routes);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void onRouteAdded_buildAllRoutes_shouldAddMediaDevice() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.isSystemRoute()).thenReturn(true);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setAllRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mPackageName = "";
        mInfoMediaManager.mMediaRouterCallback.onRoutesAdded(routes);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void onPreferredFeaturesChanged_samePackageName_shouldAddMediaDevice() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo);
        final List<String> selectedRoutes = new ArrayList<>();
        selectedRoutes.add(TEST_ID);
        when(sessionInfo.getSelectedRoutes()).thenReturn(selectedRoutes);
        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setTransferableRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onPreferredFeaturesChanged(TEST_PACKAGE_NAME, null);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void onPreferredFeaturesChanged_differentPackageName_doNothing() {
        mInfoMediaManager.mMediaRouterCallback.onPreferredFeaturesChanged("com.fake.play", null);

        assertThat(mInfoMediaManager.mMediaDevices).hasSize(0);
    }

    @Test
    public void onRoutesChanged_getAvailableRoutes_shouldAddMediaDevice() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo);
        final List<String> selectedRoutes = new ArrayList<>();
        selectedRoutes.add(TEST_ID);
        when(sessionInfo.getSelectedRoutes()).thenReturn(selectedRoutes);
        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setTransferableRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRoutesChanged(routes);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void onRoutesChanged_buildAllRoutes_shouldAddMediaDevice() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.isSystemRoute()).thenReturn(true);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setAllRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mPackageName = "";
        mInfoMediaManager.mMediaRouterCallback.onRoutesChanged(routes);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void connectDeviceWithoutPackageName_noSession_returnFalse() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        final MediaDevice device = new InfoMediaDevice(mContext, mInfoMediaManager.mRouterManager,
                info, TEST_PACKAGE_NAME);

        final List<RoutingSessionInfo> infos = new ArrayList<>();

        mShadowRouter2Manager.setRemoteSessions(infos);

        assertThat(mInfoMediaManager.connectDeviceWithoutPackageName(device)).isFalse();
    }

    @Test
    public void onRoutesRemoved_getAvailableRoutes_shouldAddMediaDevice() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo);
        final List<String> selectedRoutes = new ArrayList<>();
        selectedRoutes.add(TEST_ID);
        when(sessionInfo.getSelectedRoutes()).thenReturn(selectedRoutes);
        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        when(mRouterManager.getAvailableRoutes(TEST_PACKAGE_NAME)).thenReturn(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRoutesRemoved(routes);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void onRoutesRemoved_buildAllRoutes_shouldAddMediaDevice() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.isSystemRoute()).thenReturn(true);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        when(mRouterManager.getAllRoutes()).thenReturn(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mPackageName = "";
        mInfoMediaManager.mMediaRouterCallback.onRoutesRemoved(routes);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void addDeviceToPlayMedia_packageNameIsNull_returnFalse() {
        mInfoMediaManager.mPackageName = null;
        final MediaDevice device = mock(MediaDevice.class);

        assertThat(mInfoMediaManager.addDeviceToPlayMedia(device)).isFalse();
    }

    @Test
    public void addDeviceToPlayMedia_containSelectableRoutes_returnTrue() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final MediaDevice device =
                new InfoMediaDevice(mContext, mInfoMediaManager.mRouterManager, route2Info,
                        TEST_PACKAGE_NAME);

        final List<String> list = new ArrayList<>();
        list.add(TEST_ID);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.getSelectableRoutes()).thenReturn(list);
        when(route2Info.getId()).thenReturn(TEST_ID);
        when(route2Info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaManager.addDeviceToPlayMedia(device)).isTrue();
    }

    @Test
    public void addDeviceToPlayMedia_notContainSelectableRoutes_returnFalse() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final MediaDevice device =
                new InfoMediaDevice(mContext, mInfoMediaManager.mRouterManager, route2Info,
                        TEST_PACKAGE_NAME);

        final List<String> list = new ArrayList<>();
        list.add("fake_id");

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.getSelectableRoutes()).thenReturn(list);
        when(route2Info.getId()).thenReturn(TEST_ID);
        when(route2Info.getName()).thenReturn(TEST_NAME);
        when(route2Info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaManager.addDeviceToPlayMedia(device)).isFalse();
    }

    @Test
    public void removeDeviceFromMedia_packageNameIsNull_returnFalse() {
        mInfoMediaManager.mPackageName = null;
        final MediaDevice device = mock(MediaDevice.class);

        assertThat(mInfoMediaManager.removeDeviceFromPlayMedia(device)).isFalse();
    }

    @Test
    public void removeDeviceFromMedia_containSelectedRoutes_returnTrue() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final MediaDevice device =
                new InfoMediaDevice(mContext, mInfoMediaManager.mRouterManager, route2Info,
                        TEST_PACKAGE_NAME);

        final List<String> list = new ArrayList<>();
        list.add(TEST_ID);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.getSelectedRoutes()).thenReturn(list);
        when(route2Info.getId()).thenReturn(TEST_ID);
        when(route2Info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaManager.removeDeviceFromPlayMedia(device)).isTrue();
    }

    @Test
    public void removeDeviceFromMedia_notContainSelectedRoutes_returnFalse() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final MediaDevice device =
                new InfoMediaDevice(mContext, mInfoMediaManager.mRouterManager, route2Info,
                        TEST_PACKAGE_NAME);

        final List<String> list = new ArrayList<>();
        list.add("fake_id");

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.getSelectedRoutes()).thenReturn(list);
        when(route2Info.getId()).thenReturn(TEST_ID);
        when(route2Info.getName()).thenReturn(TEST_NAME);
        when(route2Info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaManager.removeDeviceFromPlayMedia(device)).isFalse();
    }

    @Test
    public void getSelectableMediaDevice_packageNameIsNull_returnFalse() {
        mInfoMediaManager.mPackageName = null;

        assertThat(mInfoMediaManager.getSelectableMediaDevice()).isEmpty();
    }

    @Test
    public void getSelectableMediaDevice_notContainPackageName_returnEmpty() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn("com.fake.packagename");

        assertThat(mInfoMediaManager.getSelectableMediaDevice()).isEmpty();
    }

    @Test
    public void getDeselectableMediaDevice_packageNameIsNull_returnFalse() {
        mInfoMediaManager.mPackageName = null;

        assertThat(mInfoMediaManager.getDeselectableMediaDevice()).isEmpty();
    }

    @Test
    public void getDeselectableMediaDevice_checkList() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);
        final List<MediaRoute2Info> mediaRoute2Infos = new ArrayList<>();
        final MediaRoute2Info mediaRoute2Info = mock(MediaRoute2Info.class);
        mediaRoute2Infos.add(mediaRoute2Info);
        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        mShadowRouter2Manager.setDeselectableRoutes(mediaRoute2Infos);
        when(mediaRoute2Info.getName()).thenReturn(TEST_NAME);

        final List<MediaDevice> mediaDevices = mInfoMediaManager.getDeselectableMediaDevice();

        assertThat(mediaDevices.size()).isEqualTo(1);
        assertThat(mediaDevices.get(0).getName()).isEqualTo(TEST_NAME);
    }

    @Test
    public void adjustSessionVolume_routingSessionInfoIsNull_noCrash() {
        mInfoMediaManager.adjustSessionVolume(null, 10);
    }

    @Test
    public void adjustSessionVolume_packageNameIsNull_noCrash() {
        mInfoMediaManager.mPackageName = null;

        mInfoMediaManager.adjustSessionVolume(10);
    }

    @Test
    public void getSessionVolumeMax_packageNameIsNull_returnNotFound() {
        mInfoMediaManager.mPackageName = null;

        assertThat(mInfoMediaManager.getSessionVolumeMax()).isEqualTo(-1);
    }

    @Test
    public void getSessionVolumeMax_containPackageName_returnMaxVolume() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        mInfoMediaManager.getSessionVolumeMax();

        verify(info).getVolumeMax();
    }

    @Test
    public void getSessionVolumeMax_routeSessionInfoIsNull_returnNotFound() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = null;
        routingSessionInfos.add(info);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        assertThat(mInfoMediaManager.getSessionVolumeMax()).isEqualTo(-1);
    }

    @Test
    public void getSessionVolume_packageNameIsNull_returnNotFound() {
        mInfoMediaManager.mPackageName = null;

        assertThat(mInfoMediaManager.getSessionVolume()).isEqualTo(-1);
    }

    @Test
    public void getSessionVolume_containPackageName_returnMaxVolume() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        mInfoMediaManager.getSessionVolume();

        verify(info).getVolume();
    }

    @Test
    public void getSessionVolume_routeSessionInfoIsNull_returnNotFound() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = null;
        routingSessionInfos.add(info);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        assertThat(mInfoMediaManager.getSessionVolume()).isEqualTo(-1);
    }

    @Test
    public void getActiveMediaSession_returnActiveSession() {
        RoutingSessionInfo sysSessionInfo = mock(RoutingSessionInfo.class);
        final List<RoutingSessionInfo> infos = new ArrayList<>();
        infos.add(mock(RoutingSessionInfo.class));
        final List<RoutingSessionInfo> activeSessionInfos = new ArrayList<>();
        activeSessionInfos.add(sysSessionInfo);
        activeSessionInfos.addAll(infos);

        mShadowRouter2Manager.setSystemRoutingSession(sysSessionInfo);
        mShadowRouter2Manager.setRemoteSessions(infos);

        assertThat(mInfoMediaManager.getActiveMediaSession())
                .containsExactlyElementsIn(activeSessionInfos);
    }

    @Test
    public void releaseSession_packageNameIsNull_returnFalse() {
        mInfoMediaManager.mPackageName = null;

        assertThat(mInfoMediaManager.releaseSession()).isFalse();
    }

    @Test
    public void releaseSession_removeSuccessfully_returnTrue() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaManager.releaseSession()).isTrue();
    }

    @Test
    public void getSessionName_packageNameIsNull_returnNull() {
        mInfoMediaManager.mPackageName = null;

        assertThat(mInfoMediaManager.getSessionName()).isNull();
    }

    @Test
    public void getSessionName_routeSessionInfoIsNull_returnNull() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = null;
        routingSessionInfos.add(info);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        assertThat(mInfoMediaManager.getSessionName()).isNull();
    }

    @Test
    public void getSessionName_containPackageName_returnName() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.getName()).thenReturn(TEST_NAME);

        assertThat(mInfoMediaManager.getSessionName()).isEqualTo(TEST_NAME);
    }

    @Test
    public void onTransferFailed_shouldDispatchOnRequestFailed() {
        mInfoMediaManager.registerCallback(mCallback);

        mInfoMediaManager.mMediaRouterCallback.onTransferFailed(null, null);

        verify(mCallback).onRequestFailed(REASON_UNKNOWN_ERROR);
    }

    @Test
    public void onRequestFailed_shouldDispatchOnRequestFailed() {
        mInfoMediaManager.registerCallback(mCallback);

        mInfoMediaManager.mMediaRouterCallback.onRequestFailed(REASON_NETWORK_ERROR);

        verify(mCallback).onRequestFailed(REASON_NETWORK_ERROR);
    }

    @Test
    public void onTransferred_getAvailableRoutes_shouldAddMediaDevice() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo);
        final List<String> selectedRoutes = new ArrayList<>();
        selectedRoutes.add(TEST_ID);
        when(sessionInfo.getSelectedRoutes()).thenReturn(selectedRoutes);
        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        mInfoMediaManager.registerCallback(mCallback);

        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setTransferableRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onTransferred(sessionInfo, sessionInfo);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
        verify(mCallback).onConnectedDeviceChanged(TEST_ID);
    }

    @Test
    public void onTransferred_buildAllRoutes_shouldAddMediaDevice() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        mInfoMediaManager.registerCallback(mCallback);

        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.isSystemRoute()).thenReturn(true);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setAllRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mPackageName = "";
        mInfoMediaManager.mMediaRouterCallback.onTransferred(sessionInfo, sessionInfo);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
        verify(mCallback).onConnectedDeviceChanged(null);
    }

    @Test
    public void onSessionUpdated_shouldDispatchDataChanged() {
        mInfoMediaManager.registerCallback(mCallback);

        mInfoMediaManager.mMediaRouterCallback.onSessionUpdated(mock(RoutingSessionInfo.class));

        verify(mCallback).onDeviceAttributesChanged();
    }

    @Test
    public void addMediaDevice_verifyDeviceTypeCanCorrespondToMediaDevice() {
        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final CachedBluetoothDeviceManager cachedBluetoothDeviceManager =
                mock(CachedBluetoothDeviceManager.class);
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);

        when(route2Info.getType()).thenReturn(TYPE_REMOTE_SPEAKER);
        mInfoMediaManager.addMediaDevice(route2Info);
        assertThat(mInfoMediaManager.mMediaDevices.get(0) instanceof InfoMediaDevice).isTrue();

        when(route2Info.getType()).thenReturn(TYPE_USB_DEVICE);
        mInfoMediaManager.mMediaDevices.clear();
        mInfoMediaManager.addMediaDevice(route2Info);
        assertThat(mInfoMediaManager.mMediaDevices.get(0) instanceof PhoneMediaDevice).isTrue();

        when(route2Info.getType()).thenReturn(TYPE_WIRED_HEADSET);
        mInfoMediaManager.mMediaDevices.clear();
        mInfoMediaManager.addMediaDevice(route2Info);
        assertThat(mInfoMediaManager.mMediaDevices.get(0) instanceof PhoneMediaDevice).isTrue();

        when(route2Info.getType()).thenReturn(TYPE_BLUETOOTH_A2DP);
        when(route2Info.getAddress()).thenReturn("00:00:00:00:00:00");
        when(mLocalBluetoothManager.getCachedDeviceManager())
                .thenReturn(cachedBluetoothDeviceManager);
        when(cachedBluetoothDeviceManager.findDevice(any(BluetoothDevice.class)))
                .thenReturn(cachedDevice);
        mInfoMediaManager.mMediaDevices.clear();
        mInfoMediaManager.addMediaDevice(route2Info);
        assertThat(mInfoMediaManager.mMediaDevices.get(0) instanceof BluetoothMediaDevice).isTrue();

        when(route2Info.getType()).thenReturn(TYPE_BUILTIN_SPEAKER);
        mInfoMediaManager.mMediaDevices.clear();
        mInfoMediaManager.addMediaDevice(route2Info);
        assertThat(mInfoMediaManager.mMediaDevices.get(0) instanceof PhoneMediaDevice).isTrue();
    }

    @Test
    public void addMediaDevice_cachedBluetoothDeviceIsNull_shouldNotAdded() {
        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final CachedBluetoothDeviceManager cachedBluetoothDeviceManager =
                mock(CachedBluetoothDeviceManager.class);

        when(route2Info.getType()).thenReturn(TYPE_BLUETOOTH_A2DP);
        when(route2Info.getAddress()).thenReturn("00:00:00:00:00:00");
        when(mLocalBluetoothManager.getCachedDeviceManager())
                .thenReturn(cachedBluetoothDeviceManager);
        when(cachedBluetoothDeviceManager.findDevice(any(BluetoothDevice.class)))
                .thenReturn(null);

        mInfoMediaManager.mMediaDevices.clear();
        mInfoMediaManager.addMediaDevice(route2Info);

        assertThat(mInfoMediaManager.mMediaDevices.size()).isEqualTo(0);
    }

    @Test
    public void shouldDisableMediaOutput_infosSizeEqual1_returnsTrue() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        final List<MediaRoute2Info> infos = new ArrayList<>();
        infos.add(info);
        mShadowRouter2Manager.setTransferableRoutes(infos);

        when(info.getType()).thenReturn(TYPE_REMOTE_SPEAKER);

        assertThat(mInfoMediaManager.shouldDisableMediaOutput("test")).isTrue();
    }

    @Test
    public void shouldDisableMediaOutput_infosSizeEqual1AndNotCastDevice_returnsFalse() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        final List<MediaRoute2Info> infos = new ArrayList<>();
        infos.add(info);
        mShadowRouter2Manager.setTransferableRoutes(infos);

        when(info.getType()).thenReturn(TYPE_BUILTIN_SPEAKER);

        assertThat(mInfoMediaManager.shouldDisableMediaOutput("test")).isFalse();
    }


    @Test
    public void shouldDisableMediaOutput_infosSizeOverThan1_returnsFalse() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        final MediaRoute2Info info2 = mock(MediaRoute2Info.class);
        final List<MediaRoute2Info> infos = new ArrayList<>();
        infos.add(info);
        infos.add(info2);
        mShadowRouter2Manager.setTransferableRoutes(infos);

        when(info.getType()).thenReturn(TYPE_REMOTE_SPEAKER);
        when(info2.getType()).thenReturn(TYPE_REMOTE_SPEAKER);

        assertThat(mInfoMediaManager.shouldDisableMediaOutput("test")).isFalse();
    }
}
