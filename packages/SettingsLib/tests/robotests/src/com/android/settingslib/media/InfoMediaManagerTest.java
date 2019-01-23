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

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class InfoMediaManagerTest {

    private static final String TEST_PACKAGE_NAME = "com.test.packagename";
    private static final String TEST_ID = "test_id";

    @Mock
    private MediaRouter mMediaRouter;
    @Mock
    private MediaRouteSelector mSelector;

    private InfoMediaManager mInfoMediaManager;
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        mInfoMediaManager = new InfoMediaManager(mContext, TEST_PACKAGE_NAME, null);
        mInfoMediaManager.mMediaRouter = mMediaRouter;
        mInfoMediaManager.mSelector = mSelector;
    }

    @Test
    public void stopScan_shouldRemoveCallback() {
        mInfoMediaManager.stopScan();

        verify(mMediaRouter).removeCallback(mInfoMediaManager.mMediaRouterCallback);
    }

    @Test
    public void startScan_shouldAddCallback() {
        mInfoMediaManager.startScan();

        verify(mMediaRouter).addCallback(mSelector, mInfoMediaManager.mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
    }

    @Test
    public void onRouteAdded_mediaDeviceNotExistInList_addMediaDevice() {
        final MediaRouter.RouteInfo info = mock(MediaRouter.RouteInfo.class);
        when(info.getId()).thenReturn(TEST_ID);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRouteAdded(mMediaRouter, info);

        final MediaDevice infoDevice = mInfoMediaManager.mMediaDevices.get(0);
        assertThat(infoDevice.getId()).isEqualTo(TEST_ID);
    }

    @Test
    public void onRouteAdded_mediaDeviceExistInList_doNothing() {
        final MediaRouter.RouteInfo info = mock(MediaRouter.RouteInfo.class);
        when(info.getId()).thenReturn(TEST_ID);
        final InfoMediaDevice infoDevice = new InfoMediaDevice(mContext, info);
        mInfoMediaManager.mMediaDevices.add(infoDevice);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        final int size = mInfoMediaManager.mMediaDevices.size();
        assertThat(mediaDevice).isNotNull();

        mInfoMediaManager.mMediaRouterCallback.onRouteAdded(mMediaRouter, info);

        assertThat(mInfoMediaManager.mMediaDevices).hasSize(size);
    }

    @Test
    public void onRouteRemoved_mediaDeviceExistInList_removeMediaDevice() {
        final MediaRouter.RouteInfo info = mock(MediaRouter.RouteInfo.class);
        when(info.getId()).thenReturn(TEST_ID);
        final InfoMediaDevice infoDevice = new InfoMediaDevice(mContext, info);
        mInfoMediaManager.mMediaDevices.add(infoDevice);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        assertThat(mediaDevice).isNotNull();
        assertThat(mInfoMediaManager.mMediaDevices).hasSize(1);

        mInfoMediaManager.mMediaRouterCallback.onRouteRemoved(mMediaRouter, info);

        assertThat(mInfoMediaManager.mMediaDevices).isEmpty();
    }

    @Test
    public void onRouteRemoved_mediaDeviceNotExistInList_doNothing() {
        final MediaRouter.RouteInfo info = mock(MediaRouter.RouteInfo.class);
        when(info.getId()).thenReturn(TEST_ID);

        final MediaDevice mediaDevice = mInfoMediaManager.findMediaDevice(TEST_ID);
        final int size = mInfoMediaManager.mMediaDevices.size();
        assertThat(mediaDevice).isNull();

        mInfoMediaManager.mMediaRouterCallback.onRouteRemoved(mMediaRouter, info);

        assertThat(mInfoMediaManager.mMediaDevices).hasSize(size);
    }
}
