/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.android.settingslib.Utils;

/**
 * CornerHandleView draws an inset arc intended to be displayed within the screen decoration
 * corners.
 */
public class CornerHandleView extends View {
    private static final boolean ALLOW_TUNING = false;
    private static final int ANGLE_DEGREES = 50;
    public static final int MARGIN_DP = 11;
    public static final int RADIUS_DP = 37;
    public static final float STROKE_DP = 2.5f;

    private Paint mPaint;
    private int mLightColor;
    private int mDarkColor;
    private RectF mOval;


    public CornerHandleView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeWidth(getStrokePx());

        final int dualToneDarkTheme = Utils.getThemeAttr(mContext,
                R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(mContext,
                R.attr.lightIconTheme);
        Context lightContext = new ContextThemeWrapper(mContext, dualToneLightTheme);
        Context darkContext = new ContextThemeWrapper(mContext, dualToneDarkTheme);
        mLightColor = Utils.getColorAttrDefaultColor(lightContext,
                R.attr.singleToneColor);
        mDarkColor = Utils.getColorAttrDefaultColor(darkContext,
                R.attr.singleToneColor);

        updateOval();
    }

    /**
     * Receives an intensity from 0 (lightest) to 1 (darkest) and sets the handle color
     * approriately. Intention is to match the home handle color.
     */
    public void updateDarkness(float darkIntensity) {
        mPaint.setColor((int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mLightColor,
                mDarkColor));
        invalidate();
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (ALLOW_TUNING) {
            mPaint.setStrokeWidth(getStrokePx());
            updateOval();
        }

        canvas.drawArc(mOval, 180 + ((90 - getAngle()) / 2), getAngle(), false,
                mPaint);
    }

    // TODO(b/133834204): Remove tweaking of corner handles
    private static float convertDpToPixel(float dp, Context context) {
        return dp * ((float) context.getResources().getDisplayMetrics().densityDpi
                / DisplayMetrics.DENSITY_DEFAULT);
    }

    private void updateOval() {
        mOval = new RectF(getMarginPx() - (getStrokePx() / 2.f),
                getMarginPx() - (getStrokePx() / 2.f),
                getMarginPx() + (2 * (getRadiusPx()) + (getStrokePx() / 2.f)),
                getMarginPx() + 2 * getRadiusPx() + (getStrokePx() / 2.f));
    }

    private int getAngle() {
        if (ALLOW_TUNING) {
            return SystemProperties.getInt("CORNER_HANDLE_ANGLE_DEGREES", ANGLE_DEGREES);
        } else {
            return ANGLE_DEGREES;
        }
    }

    private int getMarginPx() {
        if (ALLOW_TUNING) {
            return SystemProperties.getInt("CORNER_HANDLE_MARGIN_PX",
                    (int) convertDpToPixel(MARGIN_DP, getContext()));
        } else {
            return (int) convertDpToPixel(MARGIN_DP, getContext());
        }
    }

    private int getRadiusPx() {
        if (ALLOW_TUNING) {
            return SystemProperties.getInt("CORNER_HANDLE_RADIUS_PX",
                    (int) convertDpToPixel(RADIUS_DP, getContext()));
        } else {
            return (int) convertDpToPixel(RADIUS_DP, getContext());
        }
    }

    private int getStrokePx() {
        if (ALLOW_TUNING) {
            return SystemProperties.getInt("CORNER_HANDLE_STROKE_PX",
                    (int) convertDpToPixel(STROKE_DP, getContext()));
        } else {
            return (int) convertDpToPixel(STROKE_DP, getContext());
        }
    }
}
