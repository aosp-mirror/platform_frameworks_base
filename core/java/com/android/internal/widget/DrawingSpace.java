/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.util.AttributeSet;
import android.view.View;

/**
 * Implementation of {@link android.widget.Space} that uses normal View drawing
 * rather than a no-op. Useful for dialogs and other places where the base View
 * class is too greedy when measured with AT_MOST.
 */
public final class DrawingSpace extends View {
    public DrawingSpace(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public DrawingSpace(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DrawingSpace(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DrawingSpace(Context context) {
        this(context, null);
    }

    /**
     * Compare to: {@link View#getDefaultSize(int, int)}
     * <p>
     * If mode is AT_MOST, return the child size instead of the parent size
     * (unless it is too big).
     */
    private static int getDefaultSizeNonGreedy(int size, int measureSpec) {
        int result = size;
        int specMode = MeasureSpec.getMode(measureSpec);
        int specSize = MeasureSpec.getSize(measureSpec);

        switch (specMode) {
            case MeasureSpec.UNSPECIFIED:
                result = size;
                break;
            case MeasureSpec.AT_MOST:
                result = Math.min(size, specSize);
                break;
            case MeasureSpec.EXACTLY:
                result = specSize;
                break;
        }
        return result;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                getDefaultSizeNonGreedy(getSuggestedMinimumWidth(), widthMeasureSpec),
                getDefaultSizeNonGreedy(getSuggestedMinimumHeight(), heightMeasureSpec));
    }
}
