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


import android.content.Context;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;
import com.android.settingslib.Utils;

/**
 * LayerDrawable contains the bluetooth device icon and battery gauge icon
 */
public class BluetoothDeviceLayerDrawable extends LayerDrawable {

    private BluetoothDeviceLayerDrawableState mState;

    private BluetoothDeviceLayerDrawable(@NonNull Drawable[] layers) {
        super(layers);
    }

    /**
     * Create the {@link LayerDrawable} that contains bluetooth device icon and battery icon.
     * This is a horizontal layout drawable while bluetooth icon at start and battery icon at end.
     *
     * @param context      used to get the spec for icon
     * @param resId        represents the bluetooth device drawable
     * @param batteryLevel the battery level for bluetooth device
     */
    public static BluetoothDeviceLayerDrawable createLayerDrawable(Context context, int resId,
            int batteryLevel) {
        return createLayerDrawable(context, resId, batteryLevel, 1 /*iconScale*/);
    }

    /**
     * Create the {@link LayerDrawable} that contains bluetooth device icon and battery icon.
     * This is a horizontal layout drawable while bluetooth icon at start and battery icon at end.
     *
     * @param context      used to get the spec for icon
     * @param resId        represents the bluetooth device drawable
     * @param batteryLevel the battery level for bluetooth device
     * @param iconScale    the ratio of height between battery icon and bluetooth icon
     */
    public static BluetoothDeviceLayerDrawable createLayerDrawable(Context context, int resId,
            int batteryLevel, float iconScale) {
        final Drawable deviceDrawable = context.getDrawable(resId);

        final BatteryMeterDrawable batteryDrawable = new BatteryMeterDrawable(context,
                context.getColor(R.color.meter_background_color), batteryLevel);
        final int pad = context.getResources().getDimensionPixelSize(R.dimen.bt_battery_padding);
        batteryDrawable.setPadding(pad, pad, pad, pad);

        final BluetoothDeviceLayerDrawable drawable = new BluetoothDeviceLayerDrawable(
                new Drawable[]{deviceDrawable, batteryDrawable});
        // Set the bluetooth icon at the left
        drawable.setLayerGravity(0 /* index of deviceDrawable */, Gravity.START);
        // Set battery icon to the right of the bluetooth icon
        drawable.setLayerInsetStart(1 /* index of batteryDrawable */,
                deviceDrawable.getIntrinsicWidth());
        drawable.setLayerInsetTop(1 /* index of batteryDrawable */,
                (int) (deviceDrawable.getIntrinsicHeight() * (1 - iconScale)));

        drawable.setConstantState(context, resId, batteryLevel, iconScale);

        return drawable;
    }

    public void setConstantState(Context context, int resId, int batteryLevel, float iconScale) {
        mState = new BluetoothDeviceLayerDrawableState(context, resId, batteryLevel, iconScale);
    }

    @Override
    public ConstantState getConstantState() {
        return mState;
    }

    /**
     * Battery gauge icon with new spec.
     */
    @VisibleForTesting
    static class BatteryMeterDrawable extends BatteryMeterDrawableBase {
        private final float mAspectRatio;
        @VisibleForTesting
        int mFrameColor;

        public BatteryMeterDrawable(Context context, int frameColor, int batteryLevel) {
            super(context, frameColor);
            final Resources resources = context.getResources();
            mButtonHeightFraction = resources.getFraction(
                    R.fraction.bt_battery_button_height_fraction, 1, 1);
            mAspectRatio = resources.getFraction(R.fraction.bt_battery_ratio_fraction, 1, 1);

            final int tintColor = Utils.getColorAttrDefaultColor(context,
                    android.R.attr.colorControlNormal);
            setColorFilter(new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN));
            setBatteryLevel(batteryLevel);
            mFrameColor = frameColor;
        }

        @Override
        protected float getAspectRatio() {
            return mAspectRatio;
        }

        @Override
        protected float getRadiusRatio() {
            // Remove the round edge
            return 0;
        }
    }

    /**
     * {@link ConstantState} to restore the {@link BluetoothDeviceLayerDrawable}
     */
    private static class BluetoothDeviceLayerDrawableState extends ConstantState {
        Context context;
        int resId;
        int batteryLevel;
        float iconScale;

        public BluetoothDeviceLayerDrawableState(Context context, int resId,
                int batteryLevel, float iconScale) {
            this.context = context;
            this.resId = resId;
            this.batteryLevel = batteryLevel;
            this.iconScale = iconScale;
        }

        @Override
        public Drawable newDrawable() {
            return createLayerDrawable(context, resId, batteryLevel, iconScale);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }
    }
}
