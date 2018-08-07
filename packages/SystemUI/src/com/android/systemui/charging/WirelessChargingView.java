/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.charging;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;

final class WirelessChargingView extends View {

    private Interpolator mInterpolator;
    private float mPathGone;
    private float mInterpolatedPathGone;
    private long mAnimationStartTime;
    private long mScaleDotsDuration;

    private boolean mFinishedAnimatingDots = false;
    private int mNumDots;

    private double mAngleOffset;
    private double mCurrAngleOffset;

    private int mDotsRadiusStart;
    private int mDotsRadiusEnd;
    private int mCurrDotRadius;

    private int mMainCircleStartRadius;
    private int mMainCircleEndRadius;
    private int mMainCircleCurrentRadius;

    private int mCenterX;
    private int mCenterY;

    private Paint mPaint;
    private Context mContext;

    public WirelessChargingView(Context context) {
        super(context);
        init(context, null);
    }

    public WirelessChargingView(Context context, AttributeSet attr) {
        super(context, attr);
        init(context, attr);
    }

    public WirelessChargingView(Context context, AttributeSet attr, int styleAttr) {
        super(context, attr, styleAttr);
        init(context, attr);
    }

    public void init(Context context, AttributeSet attr) {
        mContext = context;

        mDotsRadiusStart = context.getResources().getDimensionPixelSize(
                R.dimen.wireless_charging_dots_radius_start);
        mDotsRadiusEnd = context.getResources().getDimensionPixelSize(
                R.dimen.wireless_charging_dots_radius_end);

        mMainCircleStartRadius = context.getResources().getDimensionPixelSize(
                R.dimen.wireless_charging_circle_radius_start);
        mMainCircleEndRadius = context.getResources().getDimensionPixelSize(
                R.dimen.wireless_charging_circle_radius_end);
        mMainCircleCurrentRadius = mMainCircleStartRadius;

        mAngleOffset = context.getResources().getInteger(R.integer.wireless_charging_angle_offset);
        mScaleDotsDuration = (long) context.getResources().getInteger(
                R.integer.wireless_charging_scale_dots_duration);
        mNumDots = context.getResources().getInteger(R.integer.wireless_charging_num_dots);

        setupPaint();
        mInterpolator = Interpolators.LINEAR_OUT_SLOW_IN;
    }

    private void setupPaint() {
        mPaint = new Paint();
        mPaint.setColor(Utils.getColorAttr(mContext, R.attr.wallpaperTextColor));
    }

    public void setPaintColor(int color) {
        mPaint.setColor(color);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        if (mAnimationStartTime == 0) {
            mAnimationStartTime = System.currentTimeMillis();
        }

        updateDrawingParameters();
        drawCircles(canvas);

        if (!mFinishedAnimatingDots) {
            invalidate();
        }
    }

    /**
     * Draws a larger circle of radius {@link WirelessChargingView#mMainCircleEndRadius} composed of
     * {@link WirelessChargingView#mNumDots} smaller circles
     * @param canvas
     */
    private void drawCircles(Canvas canvas) {
        mCenterX = canvas.getWidth() / 2;
        mCenterY = canvas.getHeight() / 2;

        // draws mNumDots to compose a larger, main circle
        for (int circle = 0; circle < mNumDots; circle++) {
            double angle = ((mCurrAngleOffset) * Math.PI / 180) + (circle * ((2 * Math.PI)
                    / mNumDots));

            int x = (int) (mCenterX + Math.cos(angle) * (mMainCircleCurrentRadius +
                    mCurrDotRadius));
            int y = (int) (mCenterY + Math.sin(angle) * (mMainCircleCurrentRadius +
                    mCurrDotRadius));

            canvas.drawCircle(x, y, mCurrDotRadius, mPaint);
        }

        if (mMainCircleCurrentRadius >= mMainCircleEndRadius) {
            mFinishedAnimatingDots = true;
        }
    }

    private void updateDrawingParameters() {
        long now = System.currentTimeMillis();
        long timeSinceStart = now - mAnimationStartTime;
        mPathGone = getPathGone(now);
        mInterpolatedPathGone = mInterpolator.getInterpolation(mPathGone);

        // Position Dots: update main circle radius (that the dots compose)
        if (mPathGone < 1.0f) {
            mMainCircleCurrentRadius = mMainCircleStartRadius + (int) (mInterpolatedPathGone *
                    (mMainCircleEndRadius - mMainCircleStartRadius));
        } else {
            mMainCircleCurrentRadius = mMainCircleEndRadius;
        }

        // Scale Dots: update dot radius
        if (timeSinceStart < mScaleDotsDuration) {
            mCurrDotRadius = mDotsRadiusStart + (int) (mInterpolator.getInterpolation((float)
                    timeSinceStart / mScaleDotsDuration) * (mDotsRadiusEnd - mDotsRadiusStart));
        } else {
            mCurrDotRadius = mDotsRadiusEnd;
        }

        // Rotation Dot Group: dots rotate from 0 to 20 degrees
        mCurrAngleOffset = mAngleOffset * mPathGone;
    }

    /**
     * @return decimal depicting how far along the creation of the larger circle (of circles) is
     * For values < 1.0, the larger circle is being drawn
     * For values > 1.0 the larger circle has been drawn and further animation can occur
     */
    private float getPathGone(long now) {
        return (float) (now - mAnimationStartTime) / (WirelessChargingAnimation.DURATION);
    }
}