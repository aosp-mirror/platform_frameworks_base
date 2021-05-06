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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.doze.DozeReceiver;

/**
 * A view containing 1) A SurfaceView for HBM, and 2) A normal drawable view for all other
 * animations.
 */
public class UdfpsView extends FrameLayout implements DozeReceiver, UdfpsIlluminator {
    private static final String TAG = "UdfpsView";

    private static final int DEBUG_TEXT_SIZE_PX = 32;

    @NonNull private final RectF mSensorRect;
    @NonNull private final Paint mDebugTextPaint;

    @NonNull private UdfpsSurfaceView mHbmSurfaceView;
    @Nullable private UdfpsAnimationViewController mAnimationViewController;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;

    private final float mSensorTouchAreaCoefficient;
    @Nullable private String mDebugMessage;
    private boolean mIlluminationRequested;

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

        mSensorRect = new RectF();

        mDebugTextPaint = new Paint();
        mDebugTextPaint.setAntiAlias(true);
        mDebugTextPaint.setColor(Color.BLUE);
        mDebugTextPaint.setTextSize(DEBUG_TEXT_SIZE_PX);

        mIlluminationRequested = false;
    }

    // Don't propagate any touch events to the child views.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mAnimationViewController == null
                || !mAnimationViewController.shouldPauseAuth();
    }

    @Override
    protected void onFinishInflate() {
        mHbmSurfaceView = findViewById(R.id.hbm_view);
    }

    void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    @Override
    public void setHbmCallback(@Nullable HbmCallback callback) {
        mHbmSurfaceView.setHbmCallback(callback);
    }

    @Override
    public void dozeTimeTick() {
        if (mAnimationViewController != null) {
            mAnimationViewController.dozeTimeTick();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int paddingX = mAnimationViewController == null ? 0
                : mAnimationViewController.getPaddingX();
        int paddingY = mAnimationViewController == null ? 0
                : mAnimationViewController.getPaddingY();
        mSensorRect.set(
                paddingX,
                paddingY,
                2 * mSensorProps.sensorRadius + paddingX,
                2 * mSensorProps.sensorRadius + paddingY);

        mHbmSurfaceView.onSensorRectUpdated(new RectF(mSensorRect));
        if (mAnimationViewController != null) {
            mAnimationViewController.onSensorRectUpdated(new RectF(mSensorRect));
        }
    }

    void onTouchOutsideView() {
        if (mAnimationViewController != null) {
            mAnimationViewController.onTouchOutsideView();
        }
    }

    void setAnimationViewController(
            @Nullable UdfpsAnimationViewController animationViewController) {
        mAnimationViewController = animationViewController;
    }

    @Nullable UdfpsAnimationViewController getAnimationViewController() {
        return mAnimationViewController;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.v(TAG, "onAttachedToWindow");
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.v(TAG, "onDetachedFromWindow");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mIlluminationRequested) {
            if (!TextUtils.isEmpty(mDebugMessage)) {
                canvas.drawText(mDebugMessage, 0, 160, mDebugTextPaint);
            }
        }
    }

    void setDebugMessage(String message) {
        mDebugMessage = message;
        postInvalidate();
    }

    boolean isWithinSensorArea(float x, float y) {
        // The X and Y coordinates of the sensor's center.
        final PointF translation = mAnimationViewController == null
                ? new PointF(0, 0)
                : mAnimationViewController.getTouchTranslation();
        final float cx = mSensorRect.centerX() + translation.x;
        final float cy = mSensorRect.centerY() + translation.y;
        // Radii along the X and Y axes.
        final float rx = (mSensorRect.right - mSensorRect.left) / 2.0f;
        final float ry = (mSensorRect.bottom - mSensorRect.top) / 2.0f;

        return x > (cx - rx * mSensorTouchAreaCoefficient)
                && x < (cx + rx * mSensorTouchAreaCoefficient)
                && y > (cy - ry * mSensorTouchAreaCoefficient)
                && y < (cy + ry * mSensorTouchAreaCoefficient)
                && !mAnimationViewController.shouldPauseAuth();
    }

    boolean isIlluminationRequested() {
        return mIlluminationRequested;
    }

    /**
     * @param onIlluminatedRunnable Runs when the first illumination frame reaches the panel.
     */
    @Override
    public void startIllumination(@Nullable Runnable onIlluminatedRunnable) {
        mIlluminationRequested = true;
        if (mAnimationViewController != null) {
            mAnimationViewController.onIlluminationStarting();
        }
        mHbmSurfaceView.setVisibility(View.VISIBLE);
        mHbmSurfaceView.startIllumination(onIlluminatedRunnable);
    }

    @Override
    public void stopIllumination() {
        mIlluminationRequested = false;
        if (mAnimationViewController != null) {
            mAnimationViewController.onIlluminationStopped();
        }
        mHbmSurfaceView.setVisibility(View.INVISIBLE);
        mHbmSurfaceView.stopIllumination();
    }
}
