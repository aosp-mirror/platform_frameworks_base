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

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.systemui.R;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;

/**
 * A full screen view with a configurable illumination dot and scrim.
 */
public class UdfpsView extends View implements DozeReceiver,
        StatusBarStateController.StateListener {
    private static final String TAG = "UdfpsView";

    // Values in pixels.
    private static final float SENSOR_SHADOW_RADIUS = 2.0f;
    private static final float SENSOR_OUTLINE_WIDTH = 2.0f;

    private static final int DEBUG_TEXT_SIZE_PX = 32;

    @NonNull private final Rect mScrimRect;
    @NonNull private final Paint mScrimPaint;
    @NonNull private final Paint mDebugTextPaint;

    @NonNull private final RectF mSensorRect;
    @NonNull private final Paint mSensorPaint;
    private final float mSensorTouchAreaCoefficient;
    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;

    // Stores rounded up values from mSensorRect. Necessary for APIs that only take Rect (not RecF).
    @NonNull private final Rect mTouchableRegion;
    // mInsetsListener is used to set the touchable region for our window. Our window covers the
    // whole screen, and by default its touchable region is the whole screen. We use
    // mInsetsListener to restrict the touchable region and allow the touches outside of the sensor
    // to propagate to the rest of the UI.
    @NonNull private final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsListener;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;

    // AOD anti-burn-in offsets
    private float mInterpolatedDarkAmount;
    private float mBurnInOffsetX;
    private float mBurnInOffsetY;

    private boolean mIsScrimShowing;
    private boolean mIsHbmSupported;
    @Nullable private String mDebugMessage;

    public UdfpsView(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.UdfpsView, 0,
                0);
        try {
            if (!a.hasValue(R.styleable.UdfpsView_sensorTouchAreaCoefficient)) {
                throw new IllegalArgumentException(
                        "UdfpsView must contain sensorTouchAreaCoefficient");
            }
            mSensorTouchAreaCoefficient = a.getFloat(
                    R.styleable.UdfpsView_sensorTouchAreaCoefficient, 0f);
        } finally {
            a.recycle();
        }

        mMaxBurnInOffsetX = getResources()
                .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x);
        mMaxBurnInOffsetY = getResources()
                .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);

        mScrimRect = new Rect();
        mScrimPaint = new Paint(0 /* flags */);
        mScrimPaint.setColor(Color.BLACK);

        mSensorRect = new RectF();
        mSensorPaint = new Paint(0 /* flags */);
        mSensorPaint.setAntiAlias(true);
        mSensorPaint.setColor(Color.WHITE);
        mSensorPaint.setStyle(Paint.Style.STROKE);
        mSensorPaint.setStrokeWidth(SENSOR_OUTLINE_WIDTH);
        mSensorPaint.setShadowLayer(SENSOR_SHADOW_RADIUS, 0, 0, Color.BLACK);

        mDebugTextPaint = new Paint();
        mDebugTextPaint.setAntiAlias(true);
        mDebugTextPaint.setColor(Color.BLUE);
        mDebugTextPaint.setTextSize(DEBUG_TEXT_SIZE_PX);

        mTouchableRegion = new Rect();
        mInsetsListener = internalInsetsInfo -> {
            internalInsetsInfo.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            internalInsetsInfo.touchableRegion.set(mTouchableRegion);
        };

        mIsScrimShowing = false;
    }

    void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    @Override
    public void dozeTimeTick() {
        updateAodPosition();
    }

    @Override
    public void onDozeAmountChanged(float linear, float eased) {
        mInterpolatedDarkAmount = eased;
        updateAodPosition();
    }

    private void updateAodPosition() {
        mBurnInOffsetX = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                        - mMaxBurnInOffsetX,
                mInterpolatedDarkAmount);
        mBurnInOffsetY = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                        - 0.5f * mMaxBurnInOffsetY,
                mInterpolatedDarkAmount);
        postInvalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.v(TAG, "onAttachedToWindow");

        final int h = getLayoutParams().height;
        final int w = getLayoutParams().width;
        mScrimRect.set(0 /* left */, 0 /* top */, w, h);
        mSensorRect.set(mSensorProps.sensorLocationX - mSensorProps.sensorRadius,
                mSensorProps.sensorLocationY - mSensorProps.sensorRadius,
                mSensorProps.sensorLocationX + mSensorProps.sensorRadius,
                mSensorProps.sensorLocationY + mSensorProps.sensorRadius);

        // Sets mTouchableRegion with rounded up values from mSensorRect.
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

        if (mIsScrimShowing && mIsHbmSupported) {
            // Only draw the scrim if HBM is supported.
            canvas.drawRect(mScrimRect, mScrimPaint);
        }

        // Translation should affect everything but the scrim.
        canvas.save();
        canvas.translate(mBurnInOffsetX, mBurnInOffsetY);
        if (!TextUtils.isEmpty(mDebugMessage)) {
            canvas.drawText(mDebugMessage, 0, 160, mDebugTextPaint);
        }
        canvas.drawOval(mSensorRect, mSensorPaint);
        canvas.restore();
    }

    RectF getSensorRect() {
        return new RectF(mSensorRect);
    }

    void setHbmSupported(boolean value) {
        mIsHbmSupported = value;
    }

    void setDebugMessage(String message) {
        mDebugMessage = message;
        postInvalidate();
    }

    boolean isValidTouch(float x, float y, float pressure) {
        return x > (mSensorProps.sensorLocationX
                - mSensorProps.sensorRadius * mSensorTouchAreaCoefficient)
                && x < (mSensorProps.sensorLocationX
                + mSensorProps.sensorRadius * mSensorTouchAreaCoefficient)
                && y > (mSensorProps.sensorLocationY
                - mSensorProps.sensorRadius * mSensorTouchAreaCoefficient)
                && y < (mSensorProps.sensorLocationY
                + mSensorProps.sensorRadius * mSensorTouchAreaCoefficient);
    }

    void setScrimAlpha(int alpha) {
        mScrimPaint.setAlpha(alpha);
    }

    boolean isScrimShowing() {
        return mIsScrimShowing;
    }

    void showScrimAndDot() {
        mIsScrimShowing = true;
        mSensorPaint.setStyle(Paint.Style.FILL);
        invalidate();
    }

    void hideScrimAndDot() {
        mIsScrimShowing = false;
        mSensorPaint.setStyle(Paint.Style.STROKE);
        invalidate();
    }
}
