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
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import com.android.settingslib.Utils;

/**
 * This class contains implementation for methods that will be used when user has set a
 * non six digit pin on their device
 */
public class PinShapeNonHintingView extends LinearLayout implements PinShapeInput {

    private int mColor = Utils.getColorAttr(getContext(),
            android.R.attr.textColorPrimary).getDefaultColor();
    private int mPosition = 0;

    public PinShapeNonHintingView(Context context) {
        super(context);
    }

    public PinShapeNonHintingView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PinShapeNonHintingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public PinShapeNonHintingView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void append() {
        mPosition++;
    }

    @Override
    public void delete() {
        if (mPosition == 0) {
            return;
        } else {
            mPosition--;
        }
    }

    @Override
    public void setDrawColor(int color) {
        this.mColor = color;
    }

    @Override
    public void reset() {
        removeAllViews();
        mPosition = 0;
    }

    @Override
    public View getView() {
        return this;
    }
}
