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

package com.android.settingslib.graph;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.drawable.VectorDrawable;

import com.android.settingslib.R;
import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class BluetoothDeviceLayerDrawableTest {
    private static final int RES_ID = R.drawable.ic_bt_cellphone;
    private static final int BATTERY_LEVEL = 15;
    private static final float BATTERY_ICON_SCALE = 0.75f;
    private static final int BATTERY_ICON_PADDING_TOP = 6;
    private static final float TOLERANCE = 0.001f;

    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
    }

    @Test
    public void testCreateLayerDrawable_configCorrect() {
        BluetoothDeviceLayerDrawable drawable = BluetoothDeviceLayerDrawable.createLayerDrawable(
                mContext, RES_ID, BATTERY_LEVEL);

        assertThat(drawable.getDrawable(0)).isInstanceOf(VectorDrawable.class);
        assertThat(drawable.getDrawable(1)).isInstanceOf(
                BluetoothDeviceLayerDrawable.BatteryMeterDrawable.class);
        assertThat(drawable.getLayerInsetStart(1)).isEqualTo(
                drawable.getDrawable(0).getIntrinsicWidth());
        assertThat(drawable.getLayerInsetTop(1)).isEqualTo(0);
    }

    @Test
    public void testCreateLayerDrawable_withIconScale_configCorrect() {
        BluetoothDeviceLayerDrawable drawable = BluetoothDeviceLayerDrawable.createLayerDrawable(
                mContext, RES_ID, BATTERY_LEVEL, BATTERY_ICON_SCALE);

        assertThat(drawable.getDrawable(0)).isInstanceOf(VectorDrawable.class);
        assertThat(drawable.getDrawable(1)).isInstanceOf(
                BluetoothDeviceLayerDrawable.BatteryMeterDrawable.class);
        assertThat(drawable.getLayerInsetStart(1)).isEqualTo(
                drawable.getDrawable(0).getIntrinsicWidth());
        assertThat(drawable.getLayerInsetTop(1)).isEqualTo(BATTERY_ICON_PADDING_TOP);
    }

    @Test
    public void testBatteryMeterDrawable_configCorrect() {
        BluetoothDeviceLayerDrawable.BatteryMeterDrawable batteryDrawable =
                new BluetoothDeviceLayerDrawable.BatteryMeterDrawable(mContext,
                        R.color.meter_background_color, BATTERY_LEVEL);

        assertThat(batteryDrawable.getAspectRatio()).isWithin(TOLERANCE).of(0.35f);
        assertThat(batteryDrawable.getRadiusRatio()).isWithin(TOLERANCE).of(0f);
        assertThat(batteryDrawable.getBatteryLevel()).isEqualTo(BATTERY_LEVEL);
    }

    @Test
    public void testConstantState_returnTwinBluetoothLayerDrawable() {
        BluetoothDeviceLayerDrawable drawable = BluetoothDeviceLayerDrawable.createLayerDrawable(
                mContext, RES_ID, BATTERY_LEVEL);

        BluetoothDeviceLayerDrawable twinDrawable =
                (BluetoothDeviceLayerDrawable) drawable.getConstantState().newDrawable();

        assertThat(twinDrawable.getDrawable(0)).isEqualTo(drawable.getDrawable(0));
        assertThat(twinDrawable.getDrawable(1)).isEqualTo(drawable.getDrawable(1));
        assertThat(twinDrawable.getLayerInsetTop(1)).isEqualTo(
                drawable.getLayerInsetTop(1));
    }

    @Test
    public void testCreateLayerDrawable_bluetoothDrawable_hasCorrectFrameColor() {
        BluetoothDeviceLayerDrawable drawable = BluetoothDeviceLayerDrawable.createLayerDrawable(
                mContext, RES_ID, BATTERY_LEVEL);
        BluetoothDeviceLayerDrawable.BatteryMeterDrawable batteryMeterDrawable =
                (BluetoothDeviceLayerDrawable.BatteryMeterDrawable) drawable.getDrawable(1);

        assertThat(batteryMeterDrawable.mFrameColor).isEqualTo(
                mContext.getColor(R.color.meter_background_color));
    }
}
