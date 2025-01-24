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

package com.android.server.accessibility.autoclick;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.accessibility.AccessibilityManager.AUTOCLICK_CURSOR_AREA_SIZE_DEFAULT;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.LinearInterpolator;

import androidx.annotation.VisibleForTesting;

// A visual indicator for the autoclick feature.
public class AutoclickIndicatorView extends View {
    private static final String TAG = AutoclickIndicatorView.class.getSimpleName();

    // TODO(b/383901288): update delay time once determined by UX.
    static final int SHOW_INDICATOR_DELAY_TIME = 150;

    static final int MINIMAL_ANIMATION_DURATION = 50;

    private int mRadius = AUTOCLICK_CURSOR_AREA_SIZE_DEFAULT;

    private final Paint mPaint;

    private final ValueAnimator mAnimator;

    private final RectF mRingRect;

    // x and y coordinates of the visual indicator.
    private float mX;
    private float mY;

    // Current sweep angle of the animated ring.
    private float mSweepAngle;

    private int mAnimationDuration = AccessibilityManager.AUTOCLICK_DELAY_DEFAULT;

    // Status of whether the visual indicator should display or not.
    private boolean showIndicator = false;

    public AutoclickIndicatorView(Context context) {
        super(context);

        mPaint = new Paint();
        // TODO(b/383901288): update styling once determined by UX.
        mPaint.setARGB(255, 52, 103, 235);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(10);

        mAnimator = ValueAnimator.ofFloat(0, 360);
        mAnimator.setDuration(mAnimationDuration);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(
                animation -> {
                    mSweepAngle = (float) animation.getAnimatedValue();
                    // Redraw the view with the updated angle.
                    invalidate();
                });

        mRingRect = new RectF();
    }

    /**
     * Retrieves the layout params for AutoclickIndicatorView, used when it's added to the Window
     * Manager.
     */
    public final WindowManager.LayoutParams getLayoutParams() {
        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        layoutParams.flags =
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        layoutParams.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        layoutParams.setFitInsetsTypes(WindowInsets.Type.statusBars());
        layoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        layoutParams.format = PixelFormat.TRANSLUCENT;
        layoutParams.setTitle(AutoclickIndicatorView.class.getSimpleName());
        layoutParams.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
        return layoutParams;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (showIndicator) {
            mRingRect.set(
                    /* left= */ mX - mRadius,
                    /* top= */ mY - mRadius,
                    /* right= */ mX + mRadius,
                    /* bottom= */ mY + mRadius);
            canvas.drawArc(mRingRect, /* startAngle= */ -90, mSweepAngle, false, mPaint);
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

    public void setRadius(int radius) {
        mRadius = radius;
    }

    @VisibleForTesting
    int getRadiusForTesting() {
        return mRadius;
    }

    public void redrawIndicator() {
        showIndicator = true;
        invalidate();
        mAnimator.start();
    }

    public void clearIndicator() {
        showIndicator = false;
        mAnimator.cancel();
        invalidate();
    }

    public void setAnimationDuration(int duration) {
        mAnimationDuration = Math.max(duration, MINIMAL_ANIMATION_DURATION);
        mAnimator.setDuration(mAnimationDuration);
    }
}
