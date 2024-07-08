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

package com.android.settingslib.bluetooth.devicesettings;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.Bundle;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class DeviceInfoTest {
    @Test
    public void build_withoutBluetoothAddress_fail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    DeviceInfo unused =
                            new DeviceInfo.Builder()
                                    .setExtras(buildBundle("key1", "value1"))
                                    .build();
                });
    }

    @Test
    public void build_withoutExtra_successfully() {
        DeviceInfo unused = new DeviceInfo.Builder().setBluetoothAddress("12:34:56:78").build();
    }

    @Test
    public void build_withAllFields_successfully() {
        DeviceInfo unused =
                new DeviceInfo.Builder()
                        .setBluetoothAddress("12:34:56:78")
                        .setExtras(buildBundle("key1", "value1"))
                        .build();
    }

    @Test
    public void getMethods() {
        DeviceInfo info =
                new DeviceInfo.Builder()
                        .setBluetoothAddress("12:34:56:78")
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(info.getBluetoothAddress()).isEqualTo("12:34:56:78");
        assertThat(info.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation() {
        DeviceInfo info =
                new DeviceInfo.Builder()
                        .setBluetoothAddress("12:34:56:78")
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceInfo fromParcel = writeAndRead(info);

        assertThat(fromParcel.getBluetoothAddress()).isEqualTo(info.getBluetoothAddress());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(info.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private DeviceInfo writeAndRead(DeviceInfo state) {
        Parcel parcel = Parcel.obtain();
        state.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        DeviceInfo fromParcel = DeviceInfo.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
