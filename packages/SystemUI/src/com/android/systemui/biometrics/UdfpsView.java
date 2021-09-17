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
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.biometrics.UdfpsHbmTypes.HbmType;
import com.android.systemui.doze.DozeReceiver;

/**
 * A view containing 1) A SurfaceView for HBM, and 2) A normal drawable view for all other
 * animations.
 */
public class UdfpsView extends FrameLayout implements DozeReceiver, UdfpsIlluminator {
    private static final String TAG = "UdfpsView";

    private static final String SETTING_HBM_TYPE =
            "com.android.systemui.biometrics.UdfpsSurfaceView.hbmType";
    private static final @HbmType int DEFAULT_HBM_TYPE = UdfpsHbmTypes.LOCAL_HBM;

    private static final int DEBUG_TEXT_SIZE_PX = 32;

    @NonNull private final RectF mSensorRect;
    @NonNull private final Paint mDebugTextPaint;
    private final float mSensorTouchAreaCoefficient;
    private final int mOnIlluminatedDelayMs;
    private final @HbmType int mHbmType;

    // Only used for UdfpsHbmTypes.GLOBAL_HBM.
    @Nullable private UdfpsSurfaceView mGhbmView;
    // Can be different for enrollment, BiometricPrompt, Keyguard, etc.
    @Nullable private UdfpsAnimationViewController mAnimationViewController;
    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;
    @Nullable private UdfpsHbmProvider mHbmProvider;
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

        mOnIlluminatedDelayMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_udfps_illumination_transition_ms);

        if (Build.IS_ENG || Build.IS_USERDEBUG) {
            mHbmType = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    SETTING_HBM_TYPE, DEFAULT_HBM_TYPE, UserHandle.USER_CURRENT);
        } else {
            mHbmType = DEFAULT_HBM_TYPE;
        }
    }

    // Don't propagate any touch events to the child views.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mAnimationViewController == null
                || !mAnimationViewController.shouldPauseAuth();
    }

    @Override
    protected void onFinishInflate() {
        if (mHbmType == UdfpsHbmTypes.GLOBAL_HBM) {
            mGhbmView = findViewById(R.id.hbm_view);
        }
    }

    void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    @Override
    public void setHbmProvider(@Nullable UdfpsHbmProvider hbmProvider) {
        mHbmProvider = hbmProvider;
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
        final SensorLocationInternal location = mSensorProps.getLocation();
        mSensorRect.set(
                paddingX,
                paddingY,
                2 * location.sensorRadius + paddingX,
                2 * location.sensorRadius + paddingY);

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

        if (mGhbmView != null) {
            mGhbmView.setGhbmIlluminationListener(this::doIlluminate);
            mGhbmView.setVisibility(View.VISIBLE);
            mGhbmView.startGhbmIllumination(onIlluminatedRunnable);
        } else {
            doIlluminate(null /* surface */, onIlluminatedRunnable);
        }
    }

    private void doIlluminate(@Nullable Surface surface, @Nullable Runnable onIlluminatedRunnable) {
        if (mGhbmView != null && surface == null) {
            Log.e(TAG, "doIlluminate | surface must be non-null for GHBM");
        }
        mHbmProvider.enableHbm(mHbmType, surface, () -> {
            if (mGhbmView != null) {
                mGhbmView.drawIlluminationDot(mSensorRect);
            }
            if (onIlluminatedRunnable != null) {
                // No framework API can reliably tell when a frame reaches the panel. A timeout
                // is the safest solution.
                postDelayed(onIlluminatedRunnable, mOnIlluminatedDelayMs);
            } else {
                Log.w(TAG, "doIlluminate | onIlluminatedRunnable is null");
            }
        });
    }

    @Override
    public void stopIllumination() {
        mIlluminationRequested = false;
        if (mAnimationViewController != null) {
            mAnimationViewController.onIlluminationStopped();
        }
        if (mGhbmView != null) {
            mGhbmView.setGhbmIlluminationListener(null);
            mGhbmView.setVisibility(View.INVISIBLE);
        }
        mHbmProvider.disableHbm(null /* onHbmDisabled */);
    }
}
