/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.settingslib.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothDevice;
import android.graphics.drawable.Drawable;

import com.android.settingslib.graph.BluetoothDeviceLayerDrawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class BluetoothUtilsTest {

    @Test
    public void testGetBluetoothDrawable_noBatteryLevel_returnSimpleDrawable() {
        final Drawable drawable = BluetoothUtils.getBluetoothDrawable(
                RuntimeEnvironment.application, com.android.internal.R.drawable.ic_bt_laptop,
                BluetoothDevice.BATTERY_LEVEL_UNKNOWN, 1 /* iconScale */);

        assertThat(drawable).isNotInstanceOf(BluetoothDeviceLayerDrawable.class);
    }

    @Test
    public void testGetBluetoothDrawable_hasBatteryLevel_returnLayerDrawable() {
        final Drawable drawable = BluetoothUtils.getBluetoothDrawable(
                RuntimeEnvironment.application, com.android.internal.R.drawable.ic_bt_laptop,
                10 /* batteryLevel */, 1 /* iconScale */);

        assertThat(drawable).isInstanceOf(BluetoothDeviceLayerDrawable.class);
    }
}
