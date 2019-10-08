/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.View;
import android.widget.FrameLayout;

import com.android.keyguard.R;

/**
 * Positions clock faces (analog, digital, typographic) and handles pixel shifting
 * to prevent screen burn-in.
 */
public class ClockLayout extends FrameLayout {

    private static final int ANALOG_CLOCK_SHIFT_FACTOR = 3;
    /**
     * Clock face views.
     */
    private View mAnalogClock;

    /**
     * Pixel shifting amplitudes used to prevent screen burn-in.
     */
    private int mBurnInPreventionOffsetX;
    private int mBurnInPreventionOffsetY;

    private float mDarkAmount;

    public ClockLayout(Context context) {
        this(context, null);
    }

    public ClockLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ClockLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAnalogClock = findViewById(R.id.analog_clock);

        // Get pixel shifting X, Y amplitudes from resources.
        Resources resources = getResources();
        mBurnInPreventionOffsetX = resources.getDimensionPixelSize(
            R.dimen.burn_in_prevention_offset_x);
        mBurnInPreventionOffsetY = resources.getDimensionPixelSize(
            R.dimen.burn_in_prevention_offset_y);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        positionChildren();
    }

    void onTimeChanged() {
        positionChildren();
    }

    /**
     * See {@link com.android.systemui.plugins.ClockPlugin#setDarkAmount(float)}.
     */
    void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        positionChildren();
    }

    private void positionChildren() {
        final float offsetX = MathUtils.lerp(0f,
                getBurnInOffset(mBurnInPreventionOffsetX * 2, true) - mBurnInPreventionOffsetX,
                mDarkAmount);
        final float offsetY = MathUtils.lerp(0f,
                getBurnInOffset(mBurnInPreventionOffsetY * 2, false) - mBurnInPreventionOffsetY,
                mDarkAmount);

        // Put the analog clock in the middle of the screen.
        if (mAnalogClock != null) {
            mAnalogClock.setX(Math.max(0f, 0.5f * (getWidth() - mAnalogClock.getWidth()))
                    + ANALOG_CLOCK_SHIFT_FACTOR * offsetX);
            mAnalogClock.setY(Math.max(0f, 0.5f * (getHeight() - mAnalogClock.getHeight()))
                    + ANALOG_CLOCK_SHIFT_FACTOR * offsetY);
        }
    }
}
