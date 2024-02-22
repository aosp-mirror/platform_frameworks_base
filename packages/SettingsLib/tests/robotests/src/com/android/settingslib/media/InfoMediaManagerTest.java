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
import static android.media.MediaRoute2Info.TYPE_REMOTE_TV;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;
import static android.media.MediaRoute2ProviderService.REASON_NETWORK_ERROR;
import static android.media.MediaRoute2ProviderService.REASON_UNKNOWN_ERROR;

import static com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_SELECTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.media.RouteListingPreference;
import android.media.RoutingSessionInfo;
import android.media.session.MediaSessionManager;
import android.os.Build;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.testutils.shadow.ShadowRouter2Manager;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowRouter2Manager.class})
public class InfoMediaManagerTest {

    private static final String TEST_PACKAGE_NAME = "com.test.packagename";
    private static final String TEST_PACKAGE_NAME_2 = "com.test.packagename2";
    private static final String TEST_ID = "test_id";
    private static final String TEST_ID_1 = "test_id_1";
    private static final String TEST_ID_2 = "test_id_2";
    private static final String TEST_ID_3 = "test_id_3";
    private static final String TEST_ID_4 = "test_id_4";

    private static final String TEST_NAME = "test_name";
    private static final String TEST_DUPLICATED_ID_1 = "test_duplicated_id_1";
    private static final String TEST_DUPLICATED_ID_2 = "test_duplicated_id_2";
    private static final String TEST_DUPLICATED_ID_3 = "test_duplicated_id_3";

    @Mock
    private MediaRouter2Manager mRouterManager;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private MediaManager.MediaDeviceCallback mCallback;
    @Mock
    private MediaSessionManager mMediaSessionManager;
    @Mock
    private ComponentName mComponentName;

    private ManagerInfoMediaManager mInfoMediaManager;
    private Context mContext;
    private ShadowRouter2Manager mShadowRouter2Manager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);

        doReturn(mMediaSessionManager).when(mContext).getSystemService(
                Context.MEDIA_SESSION_SERVICE);
        mInfoMediaManager =
                new ManagerInfoMediaManager(
                        mContext, TEST_PACKAGE_NAME, null, mLocalBluetoothManager);
        mShadowRouter2Manager = ShadowRouter2Manager.getShadow();
        mInfoMediaManager.mRouterManager = MediaRouter2Manager.getInstance(mContext);
    }

    @Test
    public void stopScan_notStartFirst_notCallsUnregister() {
        mInfoMediaManager.mRouterManager = mRouterManager;
        mInfoMediaManager.stopScan();

        verify(mRouterManager, never()).unregisterScanRequest();
    }

    @Test
    public void stopScan_startFirst_callsUnregister() {
        RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        mInfoMediaManager.mRouterManager = mRouterManager;
        // Since test is running in Robolectric, return a fake session to avoid NPE.
        when(mRouterManager.getRoutingSessions(anyString())).thenReturn(List.of(sessionInfo));

        mInfoMediaManager.startScan();
        mInfoMediaManager.stopScan();

        verify(mRouterManager).unregisterScanRequest();
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
        when(info.getDeduplicationIds()).thenReturn(Set.of());

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setTransferableRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRoutesUpdated();

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void onSessionReleased_shouldUpdateConnectedDevice() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo1 = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo1);
        final RoutingSessionInfo sessionInfo2 = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo2);

        final List<String> selectedRoutesSession1 = new ArrayList<>();
        selectedRoutesSession1.add(TEST_ID_1);
        when(sessionInfo1.getSelectedRoutes()).thenReturn(selectedRoutesSession1);

        final List<String> selectedRoutesSession2 = new ArrayList<>();
        selectedRoutesSession2.add(TEST_ID_2);
        when(sessionInfo2.getSelectedRoutes()).thenReturn(selectedRoutesSession2);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        final MediaRoute2Info info1 = mock(MediaRoute2Info.class);
        when(info1.getId()).thenReturn(TEST_ID_1);
        when(info1.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        final MediaRoute2Info info2 = mock(MediaRoute2Info.class);
        when(info2.getId()).thenReturn(TEST_ID_2);
        when(info2.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info1);
        routes.add(info2);
        mShadowRouter2Manager.setAllRoutes(routes);
        mShadowRouter2Manager.setTransferableRoutes(routes);

        final MediaDevice mediaDevice1 = mInfoMediaManager.findMediaDevice(TEST_ID_1);
        assertThat(mediaDevice1).isNull();
        final MediaDevice mediaDevice2 = mInfoMediaManager.findMediaDevice(TEST_ID_2);
        assertThat(mediaDevice2).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRoutesUpdated();
        final MediaDevice infoDevice1 = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice1.getId()).isEqualTo(TEST_ID_1);
        final MediaDevice infoDevice2 = mInfoMediaManager.mMediaDevices.get(1);
        assertThat(infoDevice2.getId()).isEqualTo(TEST_ID_2);
        // The active routing session is the last one in the list, which maps to infoDevice2.
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice2);

        routingSessionInfos.remove(sessionInfo2);
        mInfoMediaManager.mMediaRouterCallback.onSessionReleased(sessionInfo2);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice1);
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
        when(info.getDeduplicationIds()).thenReturn(Set.of());

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
        when(info.getDeduplicationIds()).thenReturn(Set.of());

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setTransferableRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRoutesUpdated();

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void onRoutesChanged_getAvailableRoutes_shouldFilterDevice() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT",
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo);

        final List<String> selectedRoutes = new ArrayList<>();
        selectedRoutes.add(TEST_ID);
        when(sessionInfo.getSelectedRoutes()).thenReturn(selectedRoutes);
        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);

        mShadowRouter2Manager.setTransferableRoutes(getRoutesListWithDuplicatedIds());

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRoutesUpdated();

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(2);
    }

    @Test
    public void onRouteChanged_getAvailableRoutesWithPreferenceListExit_ordersRoutes() {
        RouteListingPreference routeListingPreference = setUpPreferenceList(TEST_PACKAGE_NAME);
        setUpSelectedRoutes(TEST_PACKAGE_NAME);

        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo);

        when(mRouterManager.getRoutingSessions(TEST_PACKAGE_NAME)).thenReturn(routingSessionInfos);
        when(sessionInfo.getSelectedRoutes()).thenReturn(ImmutableList.of(TEST_ID));

        setAvailableRoutesList(TEST_PACKAGE_NAME);

        mInfoMediaManager.mRouterManager = mRouterManager;
        mInfoMediaManager.mMediaRouterCallback.onRouteListingPreferenceUpdated(TEST_PACKAGE_NAME,
                routeListingPreference);
        mInfoMediaManager.mMediaRouterCallback.onRoutesUpdated();

        assertThat(mInfoMediaManager.mMediaDevices).hasSize(4);
        assertThat(mInfoMediaManager.mMediaDevices.get(0).getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.mMediaDevices.get(1).getId()).isEqualTo(TEST_ID_1);
        assertThat(mInfoMediaManager.mMediaDevices.get(2).getId()).isEqualTo(TEST_ID_4);
        assertThat(mInfoMediaManager.mMediaDevices.get(2).isSuggestedDevice()).isTrue();
        assertThat(mInfoMediaManager.mMediaDevices.get(3).getId()).isEqualTo(TEST_ID_3);
    }

    @Test
    public void onRouteChanged_preferenceListUpdateWithDifferentPkg_notOrdersRoutes() {
        RouteListingPreference routeListingPreference = setUpPreferenceList(TEST_PACKAGE_NAME_2);
        setUpSelectedRoutes(TEST_PACKAGE_NAME);

        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo);

        when(mRouterManager.getRoutingSessions(TEST_PACKAGE_NAME)).thenReturn(routingSessionInfos);
        when(sessionInfo.getSelectedRoutes()).thenReturn(ImmutableList.of(TEST_ID));

        setAvailableRoutesList(TEST_PACKAGE_NAME);
        mInfoMediaManager.mRouterManager = mRouterManager;
        mInfoMediaManager.mMediaRouterCallback.onRouteListingPreferenceUpdated(TEST_PACKAGE_NAME_2,
                routeListingPreference);
        mInfoMediaManager.mMediaRouterCallback.onRoutesUpdated();

        assertThat(mInfoMediaManager.mMediaDevices).hasSize(1);
        assertThat(mInfoMediaManager.mMediaDevices.get(0).getId()).isEqualTo(TEST_ID);
    }

    private RouteListingPreference setUpPreferenceList(String packageName) {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT",
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        final List<RouteListingPreference.Item> preferenceItemList = new ArrayList<>();
        RouteListingPreference.Item item1 =
                new RouteListingPreference.Item.Builder(TEST_ID_4)
                        .setFlags(RouteListingPreference.Item.FLAG_SUGGESTED)
                        .build();
        RouteListingPreference.Item item2 = new RouteListingPreference.Item.Builder(
                TEST_ID_3).build();
        preferenceItemList.add(item1);
        preferenceItemList.add(item2);

        RouteListingPreference routeListingPreference =
                new RouteListingPreference.Builder().setItems(
                        preferenceItemList).setUseSystemOrdering(false).build();
        when(mRouterManager.getRouteListingPreference(packageName))
                .thenReturn(routeListingPreference);
        return routeListingPreference;
    }

    private void setUpSelectedRoutes(String packageName) {
        final List<MediaRoute2Info> selectedRoutes = new ArrayList<>();
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(packageName);
        when(info.isSystemRoute()).thenReturn(true);
        selectedRoutes.add(info);
        when(mRouterManager.getSelectedRoutes(any())).thenReturn(selectedRoutes);
    }

    private List<MediaRoute2Info> setAvailableRoutesList(String packageName) {
        final List<MediaRoute2Info> availableRoutes = new ArrayList<>();
        final MediaRoute2Info availableInfo1 = mock(MediaRoute2Info.class);
        when(availableInfo1.getId()).thenReturn(TEST_ID_2);
        when(availableInfo1.getClientPackageName()).thenReturn(packageName);
        when(availableInfo1.getType()).thenReturn(TYPE_REMOTE_TV);
        availableRoutes.add(availableInfo1);

        final MediaRoute2Info availableInfo2 = mock(MediaRoute2Info.class);
        when(availableInfo2.getId()).thenReturn(TEST_ID_3);
        when(availableInfo2.getClientPackageName()).thenReturn(packageName);
        availableRoutes.add(availableInfo2);

        final MediaRoute2Info availableInfo3 = mock(MediaRoute2Info.class);
        when(availableInfo3.getId()).thenReturn(TEST_ID_4);
        when(availableInfo3.getClientPackageName()).thenReturn(packageName);
        availableRoutes.add(availableInfo3);

        final MediaRoute2Info availableInfo4 = mock(MediaRoute2Info.class);
        when(availableInfo4.getId()).thenReturn(TEST_ID_1);
        when(availableInfo4.isSystemRoute()).thenReturn(true);
        when(availableInfo4.getClientPackageName()).thenReturn(packageName);
        availableRoutes.add(availableInfo4);

        when(mRouterManager.getAvailableRoutes(packageName)).thenReturn(availableRoutes);

        return availableRoutes;
    }

    @Test
    public void hasPreferenceRouteListing_oldSdkVersion_returnsFalse() {
        assertThat(mInfoMediaManager.preferRouteListingOrdering()).isFalse();
    }

    @Test
    public void hasPreferenceRouteListing_newSdkVersionWithPreferenceExist_returnsTrue() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT",
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mRouterManager.getRouteListingPreference(any())).thenReturn(
                new RouteListingPreference.Builder().setItems(
                        ImmutableList.of()).setUseSystemOrdering(false).build());
        mInfoMediaManager.mRouterManager = mRouterManager;

        assertThat(mInfoMediaManager.preferRouteListingOrdering()).isTrue();
    }

    @Test
    public void hasPreferenceRouteListing_newSdkVersionWithPreferenceNotExist_returnsFalse() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT",
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);

        when(mRouterManager.getRouteListingPreference(any())).thenReturn(null);

        assertThat(mInfoMediaManager.preferRouteListingOrdering()).isFalse();
    }

    @Test
    public void getInAppOnlyItemRoutingReceiver_oldSdkVersion_returnsNull() {
        assertThat(mInfoMediaManager.getLinkedItemComponentName()).isNull();
    }

    @Test
    public void getInAppOnlyItemRoutingReceiver_newSdkVersionWithReceiverExist_returns() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT",
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        when(mRouterManager.getRouteListingPreference(any())).thenReturn(
                new RouteListingPreference.Builder().setItems(
                        ImmutableList.of()).setUseSystemOrdering(
                        false).setLinkedItemComponentName(mComponentName).build());
        mInfoMediaManager.mRouterManager = mRouterManager;

        assertThat(mInfoMediaManager.getLinkedItemComponentName()).isEqualTo(mComponentName);
    }

    private List<MediaRoute2Info> getRoutesListWithDuplicatedIds() {
        final List<MediaRoute2Info> routes = new ArrayList<>();
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.isSystemRoute()).thenReturn(true);
        when(info.getDeduplicationIds()).thenReturn(
                Set.of(TEST_DUPLICATED_ID_1, TEST_DUPLICATED_ID_2));
        routes.add(info);

        final MediaRoute2Info info1 = mock(MediaRoute2Info.class);
        when(info1.getId()).thenReturn(TEST_ID_1);
        when(info1.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info1.isSystemRoute()).thenReturn(true);
        when(info1.getDeduplicationIds()).thenReturn(Set.of(TEST_DUPLICATED_ID_3));
        routes.add(info1);

        final MediaRoute2Info info2 = mock(MediaRoute2Info.class);
        when(info2.getId()).thenReturn(TEST_ID_2);
        when(info2.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info2.isSystemRoute()).thenReturn(true);
        when(info2.getDeduplicationIds()).thenReturn(Set.of(TEST_DUPLICATED_ID_3));
        routes.add(info2);

        final MediaRoute2Info info3 = mock(MediaRoute2Info.class);
        when(info3.getId()).thenReturn(TEST_ID_3);
        when(info3.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info3.isSystemRoute()).thenReturn(true);
        when(info3.getDeduplicationIds()).thenReturn(Set.of(TEST_DUPLICATED_ID_1));
        routes.add(info3);

        final MediaRoute2Info info4 = mock(MediaRoute2Info.class);
        when(info4.getId()).thenReturn(TEST_ID_4);
        when(info4.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info4.isSystemRoute()).thenReturn(true);
        when(info4.getDeduplicationIds()).thenReturn(Set.of(TEST_DUPLICATED_ID_2));
        routes.add(info4);

        return routes;
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
        when(info.getDeduplicationIds()).thenReturn(Set.of());

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setTransferableRoutes(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRoutesUpdated();

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void addDeviceToPlayMedia_containSelectableRoutes_returnTrue() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final MediaDevice device = new InfoMediaDevice(mContext, route2Info);

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
        final MediaDevice device = new InfoMediaDevice(mContext, route2Info);

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
    public void removeDeviceFromMedia_containSelectedRoutes_returnTrue() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final MediaDevice device = new InfoMediaDevice(mContext, route2Info);

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
        final MediaDevice device = new InfoMediaDevice(mContext, route2Info);

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
    public void getSelectableMediaDevice_notContainPackageName_returnEmpty() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(info);

        mShadowRouter2Manager.setRoutingSessions(routingSessionInfos);
        when(info.getClientPackageName()).thenReturn("com.fake.packagename");

        assertThat(mInfoMediaManager.getSelectableMediaDevices()).isEmpty();
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
        when(mediaRoute2Info.getId()).thenReturn(TEST_ID);

        final List<MediaDevice> mediaDevices = mInfoMediaManager.getDeselectableMediaDevices();

        assertThat(mediaDevices.size()).isEqualTo(1);
        assertThat(mediaDevices.get(0).getName()).isEqualTo(TEST_NAME);
    }

    @Test
    public void adjustSessionVolume_routingSessionInfoIsNull_noCrash() {
        mInfoMediaManager.adjustSessionVolume(null, 10);
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
    public void getRemoteSessions_returnsRemoteSessions() {
        final List<RoutingSessionInfo> infos = new ArrayList<>();
        infos.add(mock(RoutingSessionInfo.class));
        mShadowRouter2Manager.setRemoteSessions(infos);

        assertThat(mInfoMediaManager.getRemoteSessions()).containsExactlyElementsIn(infos);
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
    public void onTransferFailed_notDispatchOnRequestFailed() {
        mInfoMediaManager.registerCallback(mCallback);

        mInfoMediaManager.mMediaRouterCallback.onTransferFailed(null, null);

        verify(mCallback, never()).onRequestFailed(REASON_UNKNOWN_ERROR);
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

        when(info.getDeduplicationIds()).thenReturn(Set.of());
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
    public void onSessionUpdated_shouldDispatchDeviceListAdded() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(info.isSystemRoute()).thenReturn(true);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        mShadowRouter2Manager.setAllRoutes(routes);

        mInfoMediaManager.registerCallback(mCallback);

        mInfoMediaManager.mMediaRouterCallback.onSessionUpdated(mock(RoutingSessionInfo.class));

        verify(mCallback).onDeviceListAdded(any());
    }

    @Test
    public void addMediaDevice_verifyDeviceTypeCanCorrespondToMediaDevice() {
        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final CachedBluetoothDeviceManager cachedBluetoothDeviceManager =
                mock(CachedBluetoothDeviceManager.class);
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);

        when(route2Info.getType()).thenReturn(TYPE_REMOTE_SPEAKER);
        when(route2Info.getId()).thenReturn(TEST_ID);
        mInfoMediaManager.addMediaDevice(route2Info);
        assertThat(mInfoMediaManager.mMediaDevices.get(0) instanceof InfoMediaDevice).isTrue();

        when(route2Info.getType()).thenReturn(TYPE_USB_DEVICE);
        when(route2Info.getId()).thenReturn(TEST_ID);
        mInfoMediaManager.mMediaDevices.clear();
        mInfoMediaManager.addMediaDevice(route2Info);
        assertThat(mInfoMediaManager.mMediaDevices.get(0) instanceof PhoneMediaDevice).isTrue();

        when(route2Info.getType()).thenReturn(TYPE_WIRED_HEADSET);
        when(route2Info.getId()).thenReturn(TEST_ID);
        mInfoMediaManager.mMediaDevices.clear();
        mInfoMediaManager.addMediaDevice(route2Info);
        assertThat(mInfoMediaManager.mMediaDevices.get(0) instanceof PhoneMediaDevice).isTrue();

        when(route2Info.getType()).thenReturn(TYPE_BLUETOOTH_A2DP);
        when(route2Info.getAddress()).thenReturn("00:00:00:00:00:00");
        when(route2Info.getId()).thenReturn(TEST_ID);
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
    public void addMediaDevice_deviceIncludedInSelectedDevices_shouldSetAsCurrentConnected() {
        final MediaRoute2Info route2Info = mock(MediaRoute2Info.class);
        final CachedBluetoothDeviceManager cachedBluetoothDeviceManager =
                mock(CachedBluetoothDeviceManager.class);
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo sessionInfo = mock(RoutingSessionInfo.class);
        routingSessionInfos.add(sessionInfo);

        when(mRouterManager.getRoutingSessions(TEST_PACKAGE_NAME)).thenReturn(routingSessionInfos);
        when(sessionInfo.getSelectedRoutes()).thenReturn(ImmutableList.of(TEST_ID));
        when(route2Info.getType()).thenReturn(TYPE_BLUETOOTH_A2DP);
        when(route2Info.getAddress()).thenReturn("00:00:00:00:00:00");
        when(route2Info.getId()).thenReturn(TEST_ID);
        when(mLocalBluetoothManager.getCachedDeviceManager())
                .thenReturn(cachedBluetoothDeviceManager);
        when(cachedBluetoothDeviceManager.findDevice(any(BluetoothDevice.class)))
                .thenReturn(cachedDevice);
        mInfoMediaManager.mRouterManager = mRouterManager;

        mInfoMediaManager.mMediaDevices.clear();
        mInfoMediaManager.addMediaDevice(route2Info);

        MediaDevice device = mInfoMediaManager.mMediaDevices.get(0);

        assertThat(device instanceof BluetoothMediaDevice).isTrue();
        assertThat(device.getState()).isEqualTo(STATE_SELECTED);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(device);
    }
}
