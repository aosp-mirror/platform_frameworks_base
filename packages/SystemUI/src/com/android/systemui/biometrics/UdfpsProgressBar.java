/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ProgressBar;

import com.android.systemui.R;

/**
 * A (determinate) progress bar in the form of a ring. The progress bar goes clockwise starting
 * from the 12 o'clock position. This view maintain equal width and height using a strategy similar
 * to "centerInside" for ImageView.
 */
public class UdfpsProgressBar extends ProgressBar {

    public UdfpsProgressBar(Context context) {
        this(context, null);
    }

    public UdfpsProgressBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UdfpsProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, R.style.UdfpsProgressBarStyle);
    }

    public UdfpsProgressBar(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final int measuredHeight = getMeasuredHeight();
        final int measuredWidth = getMeasuredWidth();

        final int length = Math.min(measuredHeight, measuredWidth);
        setMeasuredDimension(length, length);
    }
}