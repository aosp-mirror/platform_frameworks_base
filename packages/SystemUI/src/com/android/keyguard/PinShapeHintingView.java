/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.graphics.drawable.DrawableCompat;

import com.android.settingslib.Utils;
import com.android.systemui.R;

/**
 * This class contains implementation for methods that will be used when user has set a
 * six digit pin on their device
 */
public class PinShapeHintingView extends LinearLayout implements PinShapeInput {

    private int mPinLength;
    private int mDotDiameter;
    private int mDotSpacing;
    private int mColor = Utils.getColorAttr(getContext(), android.R.attr.textColorPrimary)
            .getDefaultColor();
    private int mPosition = 0;
    private static final int DEFAULT_PIN_LENGTH = 6;

    public PinShapeHintingView(Context context) {
        super(context);
    }

    public PinShapeHintingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPinLength = DEFAULT_PIN_LENGTH;
        mDotDiameter = context.getResources().getDimensionPixelSize(R.dimen.default_dot_diameter);
        mDotSpacing = context.getResources().getDimensionPixelSize(R.dimen.default_dot_spacing);

        for (int i = 0; i < mPinLength; i++) {
            ImageView pinDot = new ImageView(context, attrs);
            LayoutParams layoutParams = new LayoutParams(mDotDiameter, mDotDiameter);
            pinDot.setLayoutParams(layoutParams);
            pinDot.setImageResource(R.drawable.pin_dot_avd);
            if (pinDot.getDrawable() != null) {
                Drawable drawable = DrawableCompat.wrap(pinDot.getDrawable());
                DrawableCompat.setTint(drawable, mColor);
            }
            addView(pinDot);
        }
    }

    public PinShapeHintingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public PinShapeHintingView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void append() {
        if (mPosition == DEFAULT_PIN_LENGTH) {
            return;
        }
        mPosition++;
    }

    @Override
    public void delete() {
        if (mPosition == 0) {
            return;
        }
        mPosition--;
    }

    @Override
    public void setDrawColor(int color) {
        this.mColor = color;
    }

    @Override
    public void reset() {
        int size = mPosition;
        for (int i = 0; i < size; i++) {
            delete();
        }
        mPosition = 0;
    }

    @Override
    public View getView() {
        return this;
    }
}
