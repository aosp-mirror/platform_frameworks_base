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

package com.android.internal.inputmethod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.LinearLayout;

public class InputMethodRoot extends LinearLayout {
    private final Rect mGuardRect = new Rect();
    private final Paint mGuardPaint = new Paint();

    public InputMethodRoot(Context context) {
        this(context, null);
    }

    public InputMethodRoot(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public InputMethodRoot(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);
        setWillNotDraw(false);
        mGuardPaint.setColor(context.getResources()
                .getColor(com.android.internal.R.color.input_method_navigation_guard));
    }

    @Override
    protected boolean fitSystemWindows(Rect insets) {
        setPadding(0, 0, 0, insets.bottom);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // draw navigation bar guard
        final int w = getMeasuredWidth();
        final int h = getMeasuredHeight();
        mGuardRect.set(0, h - getPaddingBottom(), w, h);
        canvas.drawRect(mGuardRect, mGuardPaint);
    }
}
