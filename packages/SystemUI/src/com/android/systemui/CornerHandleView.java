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
import android.graphics.Path;
import android.graphics.RectF;
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
    private static final float STROKE_DP_LARGE = 2f;
    private static final float STROKE_DP_SMALL = 1.95f;
    // Radius to use if none is available.
    private static final int FALLBACK_RADIUS_DP = 15;
    private static final float MARGIN_DP = 8;
    private static final int MAX_ARC_DEGREES = 90;
    // Arc length along the phone's perimeter used to measure the desired angle.
    private static final float ARC_LENGTH_DP = 31f;

    private Paint mPaint;
    private int mLightColor;
    private int mDarkColor;
    private Path mPath;

    public CornerHandleView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeWidth(getStrokePx());

        final int dualToneDarkTheme = Utils.getThemeAttr(mContext, R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(mContext, R.attr.lightIconTheme);
        Context lightContext = new ContextThemeWrapper(mContext, dualToneLightTheme);
        Context darkContext = new ContextThemeWrapper(mContext, dualToneDarkTheme);
        mLightColor = Utils.getColorAttrDefaultColor(lightContext, R.attr.singleToneColor);
        mDarkColor = Utils.getColorAttrDefaultColor(darkContext, R.attr.singleToneColor);

        updatePath();
    }

    private void updatePath() {
        mPath = new Path();

        float marginPx = getMarginPx();
        float radiusPx = getInnerRadiusPx();
        float halfStrokePx = getStrokePx() / 2f;
        float angle = getAngle();
        float startAngle = 180 + ((90 - angle) / 2);
        RectF circle = new RectF(marginPx + halfStrokePx,
                marginPx + halfStrokePx,
                marginPx + 2 * radiusPx - halfStrokePx,
                marginPx + 2 * radiusPx - halfStrokePx);

        if (angle >= 90f) {
            float innerCircumferenceDp = convertPixelToDp(radiusPx * 2 * (float) Math.PI,
                    mContext);
            float arcDp = innerCircumferenceDp * getAngle() / 360f;
            // Add additional "arms" to the two ends of the arc. The length computation is
            // hand-tuned.
            float lineLengthPx = convertDpToPixel((ARC_LENGTH_DP - arcDp - MARGIN_DP) / 2,
                    mContext);

            mPath.moveTo(marginPx + halfStrokePx, marginPx + radiusPx + lineLengthPx);
            mPath.lineTo(marginPx + halfStrokePx, marginPx + radiusPx);
            mPath.arcTo(circle, startAngle, angle);
            mPath.moveTo(marginPx + radiusPx, marginPx + halfStrokePx);
            mPath.lineTo(marginPx + radiusPx + lineLengthPx, marginPx + halfStrokePx);
        } else {
            mPath.arcTo(circle, startAngle, angle);
        }
    }

    /**
     * Receives an intensity from 0 (lightest) to 1 (darkest) and sets the handle color
     * appropriately. Intention is to match the home handle color.
     */
    public void updateDarkness(float darkIntensity) {
        mPaint.setColor((int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mLightColor,
                mDarkColor));
        if (getVisibility() == VISIBLE) {
            invalidate();
        }
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawPath(mPath, mPaint);
    }

    private static float convertDpToPixel(float dp, Context context) {
        return dp * ((float) context.getResources().getDisplayMetrics().densityDpi
                / DisplayMetrics.DENSITY_DEFAULT);
    }

    private static float convertPixelToDp(float px, Context context) {
        return px * DisplayMetrics.DENSITY_DEFAULT
                / ((float) context.getResources().getDisplayMetrics().densityDpi);
    }

    private float getAngle() {
        // Measure a length of ARC_LENGTH_DP along the *screen's* perimeter, get the angle and cap
        // it at 90.
        float circumferenceDp = convertPixelToDp((
                getOuterRadiusPx()) * 2 * (float) Math.PI, mContext);
        float angleDeg = (ARC_LENGTH_DP / circumferenceDp) * 360;
        if (angleDeg > MAX_ARC_DEGREES) {
            angleDeg = MAX_ARC_DEGREES;
        }
        return angleDeg;
    }

    private float getMarginPx() {
        return convertDpToPixel(MARGIN_DP, mContext);
    }

    private float getInnerRadiusPx() {
        return getOuterRadiusPx() - getMarginPx();
    }

    private float getOuterRadiusPx() {
        // Attempt to get the bottom corner radius, otherwise fall back on the generic or top
        // values. If none are available, use the FALLBACK_RADIUS_DP.
        int radius = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.rounded_corner_radius_bottom);
        if (radius == 0) {
            radius = getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.rounded_corner_radius);
        }
        if (radius == 0) {
            radius = getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.rounded_corner_radius_top);
        }
        if (radius == 0) {
            radius = (int) convertDpToPixel(FALLBACK_RADIUS_DP, mContext);
        }
        return radius;
    }

    private float getStrokePx() {
        // Use a slightly smaller stroke if we need to cover the full corner angle.
        return convertDpToPixel((getAngle() < 90) ? STROKE_DP_LARGE : STROKE_DP_SMALL,
                getContext());
    }
}
