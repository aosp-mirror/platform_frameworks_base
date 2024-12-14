/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.android.internal.R;

/**
 * This custom subclass of FrameLayout enforces that its calculated height be no larger than the
 * given maximum height (if any).
 *
 * @hide
 */
public class MaxHeightFrameLayout extends FrameLayout {

    private int mMaxHeight = Integer.MAX_VALUE;

    public MaxHeightFrameLayout(@NonNull Context context) {
        this(context, null);
    }

    public MaxHeightFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MaxHeightFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MaxHeightFrameLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.MaxHeightFrameLayout, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, R.styleable.MaxHeightFrameLayout,
                attrs, a, defStyleAttr, defStyleRes);

        setMaxHeight(a.getDimensionPixelSize(R.styleable.MaxHeightFrameLayout_maxHeight,
                Integer.MAX_VALUE));
    }

    /**
     * Gets the maximum height of this view, in pixels.
     *
     * @see #setMaxHeight(int)
     *
     * @attr ref android.R.styleable#MaxHeightFrameLayout_maxHeight
     */
    @Px
    public int getMaxHeight() {
        return mMaxHeight;
    }

    /**
     * Sets the maximum height this view can have.
     *
     * @param maxHeight the maximum height, in pixels
     *
     * @see #getMaxHeight()
     *
     * @attr ref android.R.styleable#MaxHeightFrameLayout_maxHeight
     */
    public void setMaxHeight(@Px int maxHeight) {
        mMaxHeight = maxHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getSize(heightMeasureSpec) > mMaxHeight) {
            final int mode = MeasureSpec.getMode(heightMeasureSpec);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(mMaxHeight, mode);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
