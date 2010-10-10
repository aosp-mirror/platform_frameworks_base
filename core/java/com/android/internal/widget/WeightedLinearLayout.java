/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.widget.LinearLayout;

import static android.view.View.MeasureSpec.*;
import static com.android.internal.R.*;

/**
 * A special layout when measured in AT_MOST will take up a given percentage of
 * the available space.
 */
public class WeightedLinearLayout extends LinearLayout {
    private float mMajorWeightMin;
    private float mMinorWeightMin;
    private float mMajorWeightMax;
    private float mMinorWeightMax;

    public WeightedLinearLayout(Context context) {
        super(context);
    }

    public WeightedLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        TypedArray a = 
            context.obtainStyledAttributes(attrs, styleable.WeightedLinearLayout);

        mMajorWeightMin = a.getFloat(styleable.WeightedLinearLayout_majorWeightMin, 0.0f);
        mMinorWeightMin = a.getFloat(styleable.WeightedLinearLayout_minorWeightMin, 0.0f);
        mMajorWeightMax = a.getFloat(styleable.WeightedLinearLayout_majorWeightMax, 0.0f);
        mMinorWeightMax = a.getFloat(styleable.WeightedLinearLayout_minorWeightMax, 0.0f);
        
        a.recycle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        final int screenWidth = metrics.widthPixels;
        final boolean isPortrait = screenWidth < metrics.heightPixels;

        final int widthMode = getMode(widthMeasureSpec);

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = getMeasuredWidth();
        boolean measure = false;

        widthMeasureSpec = MeasureSpec.makeMeasureSpec(width, EXACTLY);

        final float widthWeightMin = isPortrait ? mMinorWeightMin : mMajorWeightMin;
        final float widthWeightMax = isPortrait ? mMinorWeightMax : mMajorWeightMax;
        if (widthMode == AT_MOST) {
            final int weightedMin = (int) (screenWidth * widthWeightMin);
            final int weightedMax = (int) (screenWidth * widthWeightMin);
            if (widthWeightMin > 0.0f && width < weightedMin) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(weightedMin, EXACTLY);
                measure = true;
            } else if (widthWeightMax > 0.0f && width > weightedMax) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(weightedMax, EXACTLY);
                measure = true;
            }
        }

        // TODO: Support height?

        if (measure) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
