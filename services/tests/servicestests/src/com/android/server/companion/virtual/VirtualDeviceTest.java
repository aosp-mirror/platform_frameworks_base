/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.virtual;

import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.Context.DEVICE_ID_INVALID;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.companion.virtual.VirtualDevice;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualDeviceTest {

    private static final int VIRTUAL_DEVICE_ID = 42;
    private static final String PERSISTENT_ID = "persistentId";
    private static final String DEVICE_NAME = "VirtualDeviceName";

    @Test
    public void build_invalidId_shouldThrowIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualDevice(DEVICE_ID_INVALID, PERSISTENT_ID, DEVICE_NAME));
    }

    @Test
    public void build_defaultId_shouldThrowIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualDevice(DEVICE_ID_DEFAULT, PERSISTENT_ID, DEVICE_NAME));
    }

    @Test
    public void build_onlyRequiredFields() {
        VirtualDevice virtualDevice =
                new VirtualDevice(VIRTUAL_DEVICE_ID, /*persistentId=*/null, /*name=*/null);
        assertThat(virtualDevice.getDeviceId()).isEqualTo(VIRTUAL_DEVICE_ID);
        assertThat(virtualDevice.getPersistentDeviceId()).isNull();
        assertThat(virtualDevice.getName()).isNull();
    }

    @Test
    public void parcelable_shouldRecreateSuccessfully() {
        VirtualDevice originalDevice =
                new VirtualDevice(VIRTUAL_DEVICE_ID, PERSISTENT_ID, DEVICE_NAME);
        Parcel parcel = Parcel.obtain();
        originalDevice.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VirtualDevice device = VirtualDevice.CREATOR.createFromParcel(parcel);
        assertThat(device).isEqualTo(originalDevice);
        assertThat(device.getDeviceId()).isEqualTo(VIRTUAL_DEVICE_ID);
        assertThat(device.getPersistentDeviceId()).isEqualTo(PERSISTENT_ID);
        assertThat(device.getName()).isEqualTo(DEVICE_NAME);
    }
}
