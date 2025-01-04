/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.accessibility;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.view.View;

// A visual indicator for the autoclick feature.
public class AutoclickIndicatorView extends View {
    private static final String TAG = AutoclickIndicatorView.class.getSimpleName();

    // TODO(b/383901288): allow users to customize the indicator area.
    static final float RADIUS = 50;

    private final Paint mPaint;

    // x and y coordinates of the visual indicator.
    private float mX;
    private float mY;

    // Status of whether the visual indicator should display or not.
    private boolean showIndicator = false;

    public AutoclickIndicatorView(Context context) {
        super(context);

        mPaint = new Paint();
        // TODO(b/383901288): update styling once determined by UX.
        mPaint.setARGB(255, 52, 103, 235);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(10);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (showIndicator) {
            canvas.drawCircle(mX, mY, RADIUS, mPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Get the screen dimensions.
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;

        setMeasuredDimension(screenWidth, screenHeight);
    }

    public void setCoordination(float x, float y) {
        mX = x;
        mY = y;
    }

    public void redrawIndicator() {
        showIndicator = true;
        invalidate();
    }

    public void clearIndicator() {
        showIndicator = false;
        invalidate();
    }
}
