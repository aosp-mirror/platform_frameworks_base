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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class InfoMediaDeviceTest {

    private static final String TEST_PACKAGE_NAME = "com.test.packagename";
    private static final String TEST_ID = "test_id";
    private static final String TEST_NAME = "test_name";

    @Mock
    private MediaRouter2Manager mRouterManager;
    @Mock
    private MediaRoute2Info mRouteInfo;


    private Context mContext;
    private InfoMediaDevice mInfoMediaDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        mInfoMediaDevice = new InfoMediaDevice(mContext, mRouterManager, mRouteInfo,
                TEST_PACKAGE_NAME);
    }

    @Test
    public void getName_shouldReturnName() {
        when(mRouteInfo.getName()).thenReturn(TEST_NAME);

        assertThat(mInfoMediaDevice.getName()).isEqualTo(TEST_NAME);
    }

    @Test
    public void getSummary_clientPackageNameIsNull_returnNull() {
        when(mRouteInfo.getClientPackageName()).thenReturn(null);

        assertThat(mInfoMediaDevice.getSummary()).isEqualTo(null);
    }

    @Test
    public void getSummary_clientPackageNameIsNotNull_returnActive() {
        when(mRouteInfo.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaDevice.getSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));
    }

    @Test
    public void getId_shouldReturnId() {
        when(mRouteInfo.getId()).thenReturn(TEST_ID);

        assertThat(mInfoMediaDevice.getId()).isEqualTo(TEST_ID);
    }

    @Test
    public void connect_shouldSelectRoute() {
        mInfoMediaDevice.connect();

        verify(mRouterManager).selectRoute(TEST_PACKAGE_NAME, mRouteInfo);
    }
}
