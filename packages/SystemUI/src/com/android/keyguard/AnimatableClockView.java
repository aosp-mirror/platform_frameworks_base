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
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.icu.text.DateTimePatternGenerator;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.Calendar;
import java.util.TimeZone;

import kotlin.Unit;

/**
 * Displays the time with the hour positioned above the minutes. (ie: 09 above 30 is 9:30)
 * The time's text color is a gradient that changes its colors based on its controller.
 */
public class AnimatableClockView extends TextView {
    private static final CharSequence DOUBLE_LINE_FORMAT_12_HOUR = "hh\nmm";
    private static final CharSequence DOUBLE_LINE_FORMAT_24_HOUR = "HH\nmm";
    private static final CharSequence SINGLE_LINE_FORMAT_12_HOUR = "h:mm";
    private static final CharSequence SINGLE_LINE_FORMAT_24_HOUR = "H:mm";
    private static final long ANIM_DURATION = 300;

    private final Calendar mTime = Calendar.getInstance();

    private final int mDozingWeight;
    private final int mLockScreenWeight;
    private CharSequence mFormat;
    private CharSequence mDescFormat;
    private int mDozingColor;
    private int mLockScreenColor;
    private float mLineSpacingScale = 1f;

    private TextAnimator mTextAnimator = null;
    private Runnable mOnTextAnimatorInitialized;

    private boolean mIsSingleLine;

    public AnimatableClockView(Context context) {
        this(context, null, 0, 0);
    }

    public AnimatableClockView(Context context, AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public AnimatableClockView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public AnimatableClockView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        TypedArray ta = context.obtainStyledAttributes(
                attrs, R.styleable.AnimatableClockView, defStyleAttr, defStyleRes);
        try {
            mDozingWeight = ta.getInt(R.styleable.AnimatableClockView_dozeWeight, 100);
            mLockScreenWeight = ta.getInt(R.styleable.AnimatableClockView_lockScreenWeight, 300);
        } finally {
            ta.recycle();
        }

        ta = context.obtainStyledAttributes(
                attrs, android.R.styleable.TextView, defStyleAttr, defStyleRes);
        try {
            mIsSingleLine = ta.getBoolean(android.R.styleable.TextView_singleLine, false);
        } finally {
            ta.recycle();
        }

        refreshFormat();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshFormat();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    void refreshTime() {
        mTime.setTimeInMillis(System.currentTimeMillis());
        setText(DateFormat.format(mFormat, mTime));
        setContentDescription(DateFormat.format(mDescFormat, mTime));
    }

    void onTimeZoneChanged(TimeZone timeZone) {
        mTime.setTimeZone(timeZone);
        refreshFormat();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mTextAnimator == null) {
            mTextAnimator = new TextAnimator(
                    getLayout(),
                    () -> {
                        invalidate();
                        return Unit.INSTANCE;
                    });
            if (mOnTextAnimatorInitialized != null) {
                mOnTextAnimatorInitialized.run();
                mOnTextAnimatorInitialized = null;
            }
        } else {
            mTextAnimator.updateLayout(getLayout());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mTextAnimator.draw(canvas);
    }

    void setLineSpacingScale(float scale) {
        mLineSpacingScale = scale;
        setLineSpacing(0, mLineSpacingScale);
    }

    void setColors(int dozingColor, int lockScreenColor) {
        mDozingColor = dozingColor;
        mLockScreenColor = lockScreenColor;
    }

    void animateDoze(boolean isDozing, boolean animate) {
        setTextStyle(isDozing ? mDozingWeight : mLockScreenWeight /* weight */,
                -1,
                isDozing ? mDozingColor : mLockScreenColor,
                animate);
    }

    /**
     * Set text style with an optional animation.
     *
     * By passing -1 to weight, the view preserves its current weight.
     * By passing -1 to textSize, the view preserves its current text size.
     *
     * @param weight text weight.
     * @param textSize font size.
     * @param animate true to animate the text style change, otherwise false.
     */
    private void setTextStyle(
            @IntRange(from = 0, to = 1000) int weight,
            @FloatRange(from = 0) float textSize,
            int color,
            boolean animate) {
        if (mTextAnimator != null) {
            mTextAnimator.setTextStyle(weight, textSize, color, animate, ANIM_DURATION, null);
        } else {
            // when the text animator is set, update its start values
            mOnTextAnimatorInitialized =
                    () -> mTextAnimator.setTextStyle(
                            weight, textSize, color, false, ANIM_DURATION, null);
        }
    }

    void refreshFormat() {
        final boolean use24HourFormat = DateFormat.is24HourFormat(getContext());
        if (mIsSingleLine && use24HourFormat) {
            mFormat = SINGLE_LINE_FORMAT_24_HOUR;
        } else if (!mIsSingleLine && use24HourFormat) {
            mFormat = DOUBLE_LINE_FORMAT_24_HOUR;
        } else if (mIsSingleLine && !use24HourFormat) {
            mFormat = SINGLE_LINE_FORMAT_12_HOUR;
        } else {
            mFormat = DOUBLE_LINE_FORMAT_12_HOUR;
        }

        mDescFormat = getBestDateTimePattern(getContext(), use24HourFormat ? "Hm" : "hm");
        refreshTime();
    }

    private static String getBestDateTimePattern(Context context, String skeleton) {
        DateTimePatternGenerator dtpg = DateTimePatternGenerator.getInstance(
                context.getResources().getConfiguration().locale);
        return dtpg.getBestPattern(skeleton);
    }
}
