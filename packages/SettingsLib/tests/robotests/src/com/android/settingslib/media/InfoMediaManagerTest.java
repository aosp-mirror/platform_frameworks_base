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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class InfoMediaManagerTest {

    private static final String TEST_PACKAGE_NAME = "com.test.packagename";
    private static final String TEST_ID = "test_id";

    @Mock
    private MediaRouter2Manager mRouterManager;

    private InfoMediaManager mInfoMediaManager;
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        mInfoMediaManager = new InfoMediaManager(mContext, TEST_PACKAGE_NAME, null);
        mInfoMediaManager.mRouterManager = mRouterManager;
    }

    @Test
    public void stopScan_shouldRemoveCallback() {
        mInfoMediaManager.stopScan();

        verify(mRouterManager).unregisterCallback(mInfoMediaManager.mMediaRouterCallback);
    }

    @Test
    public void startScan_shouldAddCallback() {
        mInfoMediaManager.startScan();

        verify(mRouterManager).registerCallback(mInfoMediaManager.mExecutor,
                mInfoMediaManager.mMediaRouterCallback);
    }

    @Test
    public void onRouteAdded_shouldAddMediaDevice() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        when(mRouterManager.getAvailableRoutes(TEST_PACKAGE_NAME)).thenReturn(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRoutesAdded(routes);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void onControlCategoriesChanged_samePackageName_shouldAddMediaDevice() {
        final MediaRoute2Info info = mock(MediaRoute2Info.class);
        when(info.getId()).thenReturn(TEST_ID);
        when(info.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        final List<MediaRoute2Info> routes = new ArrayList<>();
        routes.add(info);
        when(mRouterManager.getAvailableRoutes(TEST_PACKAGE_NAME)).thenReturn(routes);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onControlCategoriesChanged(TEST_PACKAGE_NAME, null);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
        assertThat(mInfoMediaManager.getCurrentConnectedDevice()).isEqualTo(infoDevice);
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(routes.size());
    }

    @Test
    public void onControlCategoriesChanged_differentPackageName_doNothing() {
        mInfoMediaManager.mMediaRouterCallback.onControlCategoriesChanged("com.fake.play", null);

        assertThat(mInfoMediaManager.mMediaDevices).hasSize(0);
    }
}
