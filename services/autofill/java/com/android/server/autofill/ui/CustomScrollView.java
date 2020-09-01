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
package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.sDebug;

import android.content.Context;
import android.graphics.Point;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.widget.ScrollView;

import com.android.internal.R;

/**
 * Custom scroll view that stretches to a maximum height.
 */
public class CustomScrollView extends ScrollView {

    private static final String TAG = "CustomScrollView";

    private int mWidth = -1;
    private int mHeight = -1;

    public CustomScrollView(Context context) {
        super(context);
    }

    public CustomScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CustomScrollView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (getChildCount() == 0) {
            // Should not happen
            Slog.e(TAG, "no children");
            return;
        }

        mWidth = MeasureSpec.getSize(widthMeasureSpec);
        calculateDimensions();
        setMeasuredDimension(mWidth, mHeight);
    }

    private void calculateDimensions() {
        if (mHeight != -1) return;

        final TypedValue typedValue = new TypedValue();
        final Point point = new Point();
        final Context context = getContext();
        context.getDisplayNoVerify().getSize(point);
        context.getTheme().resolveAttribute(R.attr.autofillSaveCustomSubtitleMaxHeight,
                typedValue, true);
        final View child = getChildAt(0);
        final int childHeight = child.getMeasuredHeight();
        final int maxHeight = (int) typedValue.getFraction(point.y, point.y);

        mHeight = Math.min(childHeight, maxHeight);
        if (sDebug) {
            Slog.d(TAG, "calculateDimensions(): maxHeight=" + maxHeight
                    + ", childHeight=" + childHeight + ", w=" + mWidth + ", h=" + mHeight);
        }
    }
}
