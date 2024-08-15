/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bluetooth.qsdialog.DeviceItem;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link HearingDevicesListAdapter}. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesListAdapterTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    private static final String DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF";

    @Mock
    private DeviceItem mHearingDeviceItem;
    @Mock
    private CachedBluetoothDevice mCachedDevice;
    @Mock
    private HearingDevicesListAdapter.HearingDeviceItemCallback mDeviceItemCallback;
    private HearingDevicesListAdapter mAdapter;

    @Before
    public void setUp() {
        when(mCachedDevice.getAddress()).thenReturn(DEVICE_ADDRESS);
        when(mHearingDeviceItem.getCachedBluetoothDevice()).thenReturn(mCachedDevice);
    }

    @Test
    public void constructor_oneItem_getOneCount() {
        mAdapter = new HearingDevicesListAdapter(List.of(mHearingDeviceItem), mDeviceItemCallback);

        assertThat(mAdapter.getItemCount()).isEqualTo(1);
    }

    @Test
    public void refreshDeviceItemList_oneItem_getOneCount() {
        mAdapter = new HearingDevicesListAdapter(new ArrayList<>(), mDeviceItemCallback);

        mAdapter.refreshDeviceItemList(List.of(mHearingDeviceItem));

        assertThat(mAdapter.getItemCount()).isEqualTo(1);
    }
}
