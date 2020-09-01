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

import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.media.MediaRoute2Info;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MediaDeviceUtilsTest {

    private static final String TEST_ADDRESS = "11:22:33:44:55:66";
    private static final String TEST_ROUTE_ID = "test_route_id";

    @Mock
    private CachedBluetoothDevice mCachedDevice;
    @Mock
    private BluetoothDevice mBluetoothDevice;
    @Mock
    private MediaRoute2Info mRouteInfo;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void getId_returnCachedBluetoothDeviceAddress() {
        when(mCachedDevice.getAddress()).thenReturn(TEST_ADDRESS);

        final String id = MediaDeviceUtils.getId(mCachedDevice);

        assertThat(id).isEqualTo(TEST_ADDRESS);
    }

    @Test
    public void getId_returnBluetoothDeviceAddress() {
        when(mBluetoothDevice.getAddress()).thenReturn(TEST_ADDRESS);

        final String id = MediaDeviceUtils.getId(mBluetoothDevice);

        assertThat(id).isEqualTo(TEST_ADDRESS);
    }

    @Test
    public void getId_returnRouteInfoId() {
        when(mRouteInfo.getId()).thenReturn(TEST_ROUTE_ID);

        final String id = MediaDeviceUtils.getId(mRouteInfo);

        assertThat(id).isEqualTo(TEST_ROUTE_ID);
    }
}
