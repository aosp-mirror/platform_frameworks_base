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

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.support.annotation.VisibleForTesting;
import android.view.Gravity;
import android.view.View;

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
     * This is a vertical layout drawable while bluetooth icon at top and battery icon at bottom.
     *
     * @param context      used to get the spec for icon
     * @param resId        represents the bluetooth device drawable
     * @param batteryLevel the battery level for bluetooth device
     */
    public static BluetoothDeviceLayerDrawable createLayerDrawable(Context context, int resId,
            int batteryLevel) {
        final Drawable deviceDrawable = context.getDrawable(resId);

        final BatteryMeterDrawable batteryDrawable = new BatteryMeterDrawable(context,
                R.color.meter_background_color, batteryLevel);
        final int pad = context.getResources()
                .getDimensionPixelSize(R.dimen.bt_battery_padding);
        batteryDrawable.setPadding(0, pad, 0, pad);

        final BluetoothDeviceLayerDrawable drawable = new BluetoothDeviceLayerDrawable(
                new Drawable[]{deviceDrawable,
                        rotateDrawable(context.getResources(), batteryDrawable)});
        // Set the bluetooth icon at the top
        drawable.setLayerGravity(0 /* index of deviceDrawable */, Gravity.TOP);
        // Set battery icon right below the bluetooth icon
        drawable.setLayerInset(1 /* index of batteryDrawable */, 0,
                deviceDrawable.getIntrinsicHeight(), 0, 0);

        drawable.setConstantState(context, resId, batteryLevel);

        return drawable;
    }

    /**
     * Rotate the {@code drawable} by 90 degree clockwise and return rotated {@link Drawable}
     */
    private static Drawable rotateDrawable(Resources res, Drawable drawable) {
        // Get the bitmap from drawable
        final Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        // Create rotate matrix
        final Matrix matrix = new Matrix();
        matrix.postRotate(
                res.getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_LTR
                        ? 90 : 270);

        // Create new bitmap with rotate matrix
        final Bitmap rotateBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                bitmap.getHeight(), matrix, true);
        bitmap.recycle();

        return new BitmapDrawable(res, rotateBitmap);
    }

    public void setConstantState(Context context, int resId, int batteryLevel) {
        mState = new BluetoothDeviceLayerDrawableState(context, resId, batteryLevel);
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

        public BatteryMeterDrawable(Context context, int frameColor, int batteryLevel) {
            super(context, frameColor);
            final Resources resources = context.getResources();
            mButtonHeightFraction = resources.getFraction(
                    R.fraction.bt_battery_button_height_fraction, 1, 1);
            mAspectRatio = resources.getFraction(R.fraction.bt_battery_ratio_fraction, 1, 1);

            final int tintColor = Utils.getColorAttr(context, android.R.attr.colorControlNormal);
            setColorFilter(new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN));
            setBatteryLevel(batteryLevel);
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

        public BluetoothDeviceLayerDrawableState(Context context, int resId,
                int batteryLevel) {
            this.context = context;
            this.resId = resId;
            this.batteryLevel = batteryLevel;
        }

        @Override
        public Drawable newDrawable() {
            return createLayerDrawable(context, resId, batteryLevel);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }
    }
}
