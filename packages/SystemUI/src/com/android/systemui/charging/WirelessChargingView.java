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
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.settingslib.Utils;
import com.android.systemui.R;

final class WirelessChargingView extends View {

    private Interpolator mInterpolator;
    private float mPathGone;
    private float mInterpolatedPathGone;
    private long mAnimationStartTime;
    private long mStartSpinCircleAnimationTime;
    private long mAnimationOffset = 500;
    private long mTotalAnimationDuration = WirelessChargingAnimation.DURATION - mAnimationOffset;
    private long mExpandingCircle = (long) (mTotalAnimationDuration * .9);
    private long mSpinCircleAnimationTime = mTotalAnimationDuration - mExpandingCircle;

    private boolean mFinishedAnimatingSpinningCircles = false;

    private int mStartAngle = -90;
    private int mNumSmallCircles = 20;
    private int mSmallCircleRadius = 10;

    private int mMainCircleStartRadius = 100;
    private int mMainCircleEndRadius = 230;
    private int mMainCircleCurrentRadius = mMainCircleStartRadius;

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
        setupPaint();
        mInterpolator = new DecelerateInterpolator();
    }

    private void setupPaint() {
        mPaint = new Paint();
        mPaint.setColor(Utils.getColorAttr(mContext, R.attr.wallpaperTextColor));
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        if (mAnimationStartTime == 0) {
            mAnimationStartTime = System.currentTimeMillis();
        }

        updateDrawingParameters();
        drawCircles(canvas);

        if (!mFinishedAnimatingSpinningCircles) {
            invalidate();
        }
    }

    /**
     * Draws a larger circle of radius {@link WirelessChargingView#mMainCircleEndRadius} composed of
     * {@link WirelessChargingView#mNumSmallCircles} smaller circles
     * @param canvas
     */
    private void drawCircles(Canvas canvas) {
        mCenterX = canvas.getWidth() / 2;
        mCenterY = canvas.getHeight() / 2;

        // angleOffset makes small circles look like they're moving around the main circle
        float angleOffset = mPathGone * 10;

        // draws mNumSmallCircles to compose a larger, main circle
        for (int circle = 0; circle < mNumSmallCircles; circle++) {
            double angle = ((mStartAngle + angleOffset) * Math.PI / 180) + (circle * ((2 * Math.PI)
                    / mNumSmallCircles));

            int x = (int) (mCenterX + Math.cos(angle) * (mMainCircleCurrentRadius +
                    mSmallCircleRadius));
            int y = (int) (mCenterY + Math.sin(angle) * (mMainCircleCurrentRadius +
                    mSmallCircleRadius));

            canvas.drawCircle(x, y, mSmallCircleRadius, mPaint);
        }

        if (mMainCircleCurrentRadius >= mMainCircleEndRadius && !isSpinCircleAnimationStarted()) {
            mStartSpinCircleAnimationTime = System.currentTimeMillis();
        }

        if (isSpinAnimationFinished()) {
            mFinishedAnimatingSpinningCircles = true;
        }
    }

    private boolean isSpinCircleAnimationStarted() {
        return mStartSpinCircleAnimationTime != 0;
    }

    private boolean isSpinAnimationFinished() {
        return isSpinCircleAnimationStarted() && System.currentTimeMillis() -
                mStartSpinCircleAnimationTime > mSpinCircleAnimationTime;
    }

    private void updateDrawingParameters() {
        mPathGone = getPathGone(System.currentTimeMillis());
        mInterpolatedPathGone = mInterpolator.getInterpolation(mPathGone);

        if (mPathGone < 1.0f) {
            mMainCircleCurrentRadius = mMainCircleStartRadius + (int) (mInterpolatedPathGone *
                    (mMainCircleEndRadius - mMainCircleStartRadius));
        } else {
            mMainCircleCurrentRadius = mMainCircleEndRadius;
        }
    }

    /**
     * @return decimal depicting how far along the creation of the larger circle (of circles) is
     * For values < 1.0, the larger circle is being drawn
     * For values > 1.0 the larger circle has been drawn and further animation can occur
     */
    private float getPathGone(long now) {
        return (float) (now - mAnimationStartTime) / (mExpandingCircle);
    }
}