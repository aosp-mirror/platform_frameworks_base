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
 * limitations under the License
 */

package com.android.systemui;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;

/**
 * A view that only shows its drawable while the phone is charging.
 *
 * Also reloads its drawable upon density changes.
 */
public class ChargingView extends ImageView implements
        BatteryController.BatteryStateChangeCallback,
        ConfigurationController.ConfigurationListener {

    private BatteryController mBatteryController;
    private int mImageResource;
    private boolean mCharging;
    private boolean mDark;

    public ChargingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.src});
        int srcResId = a.getResourceId(0, 0);

        if (srcResId != 0) {
            mImageResource = srcResId;
        }

        a.recycle();

        updateVisibility();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBatteryController = Dependency.get(BatteryController.class);
        mBatteryController.addCallback(this);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mBatteryController.removeCallback(this);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mCharging = charging;
        updateVisibility();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        setImageResource(mImageResource);
    }

    public void setDark(boolean dark) {
        mDark = dark;
        updateVisibility();
    }

    private void updateVisibility() {
        setVisibility(mCharging && mDark ? VISIBLE : INVISIBLE);
    }
}
