/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.TextClock;

import kotlin.Unit;

/**
 * Displays the time with the hour positioned above the minutes. (ie: 09 above 30 is 9:30)
 * The time's text color is a gradient that changes its colors based on its controller.
 */
public class GradientTextClock extends TextClock {
    private int[] mGradientColors;
    private float[] mPositions;

    private TextAnimator mTextAnimator = null;

    public GradientTextClock(Context context) {
        this(context, null, 0, 0);
    }

    public GradientTextClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public GradientTextClock(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public GradientTextClock(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        addOnLayoutChangeListener(mOnLayoutChangeListener);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeOnLayoutChangeListener(mOnLayoutChangeListener);
    }

    @Override
    public void refreshTime() {
        super.refreshTime();
    }

    @Override
    public void setFormat12Hour(CharSequence format) {
        super.setFormat12Hour(FORMAT_12);
    }

    @Override
    public void setFormat24Hour(CharSequence format) {
        super.setFormat24Hour(FORMAT_24);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mTextAnimator == null) {
            mTextAnimator = new TextAnimator(getLayout(), () -> {
                invalidate();
                return Unit.INSTANCE;
            });
        } else {
            mTextAnimator.updateLayout(getLayout());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mTextAnimator.draw(canvas);
    }

    public void setGradientColors(int[] colors) {
        mGradientColors = colors;
        updatePaint();
    }

    public void setColorPositions(float[] positions) {
        mPositions = positions;
    }

    /**
     * Set text style with animation.
     *
     * By passing -1 to weight, the view preserve the current weight.
     * By passing -1 to textSize, the view preserve the current text size.
     *
     * @param weight text weight.
     * @param textSize font size.
     * @param animate true for changing text style with animation, otherwise false.
     */
    public void setTextStyle(
            @IntRange(from = 0, to = 1000) int weight,
            @FloatRange(from = 0) float textSize,
            boolean animate) {
        if (mTextAnimator != null) {
            mTextAnimator.setTextStyle(weight, textSize, animate, -1, null);
        }
    }

    private void updatePaint() {
        Shader shader = new LinearGradient(
                getX(), getY(), getX(), getMeasuredHeight() + getY(), mGradientColors, mPositions,
                Shader.TileMode.REPEAT);
        getPaint().setShader(shader);
        if (mTextAnimator != null) {
            mTextAnimator.setShader(shader);
        }
    }

    private final OnLayoutChangeListener mOnLayoutChangeListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (bottom != oldBottom || top != oldTop) {
                    updatePaint();
                }
            };

    public static final CharSequence FORMAT_12 = "hh\nmm";
    public static final CharSequence FORMAT_24 = "HH\nmm";
}
