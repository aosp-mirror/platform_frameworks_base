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

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.systemui.R;

/**
 * A full screen view with a configurable illumination dot and scrim.
 */
public class UdfpsView extends View {
    private static final String TAG = "UdfpsView";

    private final Rect mScrimRect;
    private final Paint mScrimPaint;

    private float mSensorX;
    private float mSensorY;
    private final RectF mSensorRect;
    private final Paint mSensorPaint;
    private final float mSensorRadius;
    private final float mSensorMarginBottom;
    private final float mSensorTouchAreaCoefficient;

    private final Rect mTouchableRegion;
    private final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsListener;

    private boolean mIsFingerDown;

    public UdfpsView(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.UdfpsView, 0,
                0);
        try {
            if (!a.hasValue(R.styleable.UdfpsView_sensorRadius)) {
                throw new IllegalArgumentException("UdfpsView must contain sensorRadius");
            }
            if (!a.hasValue(R.styleable.UdfpsView_sensorMarginBottom)) {
                throw new IllegalArgumentException("UdfpsView must contain sensorMarginBottom");
            }
            if (!a.hasValue(R.styleable.UdfpsView_sensorTouchAreaCoefficient)) {
                throw new IllegalArgumentException(
                        "UdfpsView must contain sensorTouchAreaCoefficient");
            }
            mSensorRadius = a.getDimension(R.styleable.UdfpsView_sensorRadius, 0f);
            mSensorMarginBottom = a.getDimension(R.styleable.UdfpsView_sensorMarginBottom, 0f);
            mSensorTouchAreaCoefficient = a.getFloat(
                    R.styleable.UdfpsView_sensorTouchAreaCoefficient, 0f);
        } finally {
            a.recycle();
        }


        mScrimRect = new Rect();
        mScrimPaint = new Paint(0 /* flags */);
        mScrimPaint.setARGB(110 /* a */, 0 /* r */, 0 /* g */, 0 /* b */);

        mSensorRect = new RectF();
        mSensorPaint = new Paint(0 /* flags */);
        mSensorPaint.setColor(Color.WHITE);
        mSensorPaint.setStyle(Paint.Style.STROKE);

        mTouchableRegion = new Rect();
        mInsetsListener = internalInsetsInfo -> {
            internalInsetsInfo.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            internalInsetsInfo.touchableRegion.set(mTouchableRegion);
        };

        mIsFingerDown = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.v(TAG, "onAttachedToWindow");

        final int h = getLayoutParams().height;
        final int w = getLayoutParams().width;
        mScrimRect.set(0 /* left */, 0 /* top */, w, h);
        mSensorX = w / 2f;
        mSensorY = h - mSensorMarginBottom - mSensorRadius;
        mSensorRect.set(mSensorX - mSensorRadius, mSensorY - mSensorRadius,
                mSensorX + mSensorRadius, mSensorY + mSensorRadius);
        mSensorRect.roundOut(mTouchableRegion);

        getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.v(TAG, "onDetachedFromWindow");
        getViewTreeObserver().removeOnComputeInternalInsetsListener(mInsetsListener);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mIsFingerDown) {
            canvas.drawRect(mScrimRect, mScrimPaint);
        }
        canvas.drawOval(mSensorRect, mSensorPaint);
    }

    boolean isValidTouch(float x, float y, float pressure) {
        return x > (mSensorX - mSensorRadius * mSensorTouchAreaCoefficient)
                && x < (mSensorX + mSensorRadius * mSensorTouchAreaCoefficient)
                && y > (mSensorY - mSensorRadius * mSensorTouchAreaCoefficient)
                && y < (mSensorY + mSensorRadius * mSensorTouchAreaCoefficient);
    }

    boolean isFingerDown() {
        return mIsFingerDown;
    }

    void onFingerDown() {
        mIsFingerDown = true;
        mSensorPaint.setStyle(Paint.Style.FILL);
        postInvalidate();
    }

    void onFingerUp() {
        mIsFingerDown = false;
        mSensorPaint.setStyle(Paint.Style.STROKE);
        postInvalidate();
    }
}
