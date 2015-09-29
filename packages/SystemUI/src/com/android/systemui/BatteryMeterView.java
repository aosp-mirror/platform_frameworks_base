/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.systemui;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.util.AttributeSet;
import android.widget.ImageView;
import com.android.systemui.statusbar.policy.BatteryController;

public class BatteryMeterView extends ImageView implements BatteryController.BatteryStateChangeCallback {

    private final BatteryMeterDrawable mDrawable;
    private BatteryController mBatteryController;

    public BatteryMeterView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(R.color.batterymeter_frame_color));
        mDrawable = new BatteryMeterDrawable(context, new Handler(), frameColor);
        atts.recycle();

        setImageDrawable(mDrawable);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBatteryController.addStateChangedCallback(this);
        mDrawable.startListening();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mBatteryController.removeStateChangedCallback(this);
        mDrawable.stopListening();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        setContentDescription(
                getContext().getString(R.string.accessibility_battery_level, level));
    }

    @Override
    public void onPowerSaveChanged() {

    }

    public void setBatteryController(BatteryController mBatteryController) {
        this.mBatteryController = mBatteryController;
        mDrawable.setBatteryController(mBatteryController);
    }

    public void setDarkIntensity(float f) {
        mDrawable.setDarkIntensity(f);
    }
}
