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
import com.android.systemui.statusbar.phone.KeyguardBypassController;

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
    private static final CharSequence SINGLE_LINE_FORMAT_24_HOUR = "HH:mm";
    private static final long DOZE_ANIM_DURATION = 300;
    private static final long APPEAR_ANIM_DURATION = 350;
    private static final long CHARGE_ANIM_DURATION_PHASE_0 = 500;
    private static final long CHARGE_ANIM_DURATION_PHASE_1 = 1000;

    private final Calendar mTime = Calendar.getInstance();

    private final int mDozingWeight;
    private final int mLockScreenWeight;
    private CharSequence mFormat;
    private CharSequence mDescFormat;
    private int mDozingColor;
    private int mLockScreenColor;
    private float mLineSpacingScale = 1f;
    private int mChargeAnimationDelay = 0;

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
            mChargeAnimationDelay = ta.getInt(
                    R.styleable.AnimatableClockView_chargeAnimationDelay, 200);
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

    void animateAppearOnLockscreen() {
        if (mTextAnimator == null) {
            return;
        }

        setTextStyle(
                mDozingWeight,
                -1 /* text size, no update */,
                mLockScreenColor,
                false /* animate */,
                0 /* duration */,
                0 /* delay */,
                null /* onAnimationEnd */);

        setTextStyle(
                mLockScreenWeight,
                -1 /* text size, no update */,
                mLockScreenColor,
                true, /* animate */
                APPEAR_ANIM_DURATION,
                0 /* delay */,
                null /* onAnimationEnd */);
    }

    void animateDisappear() {
        if (mTextAnimator == null) {
            return;
        }

        setTextStyle(
                0 /* weight */,
                -1 /* text size, no update */,
                null /* color, no update */,
                true /* animate */,
                KeyguardBypassController.BYPASS_FADE_DURATION /* duration */,
                0 /* delay */,
                null /* onAnimationEnd */);
    }

    void animateCharge(DozeStateGetter dozeStateGetter) {
        if (mTextAnimator == null || mTextAnimator.isRunning()) {
            // Skip charge animation if dozing animation is already playing.
            return;
        }
        Runnable startAnimPhase2 = () -> setTextStyle(
                dozeStateGetter.isDozing() ? mDozingWeight : mLockScreenWeight/* weight */,
                -1,
                null,
                true /* animate */,
                CHARGE_ANIM_DURATION_PHASE_1,
                0 /* delay */,
                null /* onAnimationEnd */);
        setTextStyle(dozeStateGetter.isDozing() ? mLockScreenWeight : mDozingWeight/* weight */,
                -1,
                null,
                true /* animate */,
                CHARGE_ANIM_DURATION_PHASE_0,
                mChargeAnimationDelay,
                startAnimPhase2);
    }

    void animateDoze(boolean isDozing, boolean animate) {
        setTextStyle(isDozing ? mDozingWeight : mLockScreenWeight /* weight */,
                -1,
                isDozing ? mDozingColor : mLockScreenColor,
                animate,
                DOZE_ANIM_DURATION,
                0 /* delay */,
                null /* onAnimationEnd */);
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
            Integer color,
            boolean animate,
            long duration,
            long delay,
            Runnable onAnimationEnd) {
        if (mTextAnimator != null) {
            mTextAnimator.setTextStyle(weight, textSize, color, animate, duration, null,
                    delay, onAnimationEnd);
        } else {
            // when the text animator is set, update its start values
            mOnTextAnimatorInitialized =
                    () -> mTextAnimator.setTextStyle(
                            weight, textSize, color, false, duration, null,
                            delay, onAnimationEnd);
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

    interface DozeStateGetter {
        boolean isDozing();
    }
}
