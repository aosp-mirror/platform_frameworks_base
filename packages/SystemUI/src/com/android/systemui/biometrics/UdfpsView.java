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

import static com.android.systemui.statusbar.StatusBarState.FULLSCREEN_USER_SWITCHER;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.StatusBar;

/**
 * A view containing 1) A SurfaceView for HBM, and 2) A normal drawable view for all other
 * animations.
 */
public class UdfpsView extends FrameLayout implements DozeReceiver, UdfpsIlluminator,
        StatusBarStateController.StateListener, StatusBar.ExpansionChangedListener {
    private static final String TAG = "UdfpsView";

    private static final int DEBUG_TEXT_SIZE_PX = 32;

    @NonNull private final UdfpsSurfaceView mHbmSurfaceView;
    @NonNull private final UdfpsAnimationView mAnimationView;
    @NonNull private final RectF mSensorRect;
    @NonNull private final Paint mDebugTextPaint;

    @Nullable private UdfpsProgressBar mProgressBar;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;

    private final float mSensorTouchAreaCoefficient;
    @Nullable private String mDebugMessage;
    private boolean mIlluminationRequested;
    private int mStatusBarState;
    private boolean mNotificationShadeExpanded;
    @Nullable private UdfpsEnrollHelper mEnrollHelper;

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

        // Inflate UdfpsSurfaceView
        final LayoutInflater inflater = LayoutInflater.from(context);
        mHbmSurfaceView = (UdfpsSurfaceView) inflater.inflate(R.layout.udfps_surface_view,
                null, false);
        addView(mHbmSurfaceView);
        mHbmSurfaceView.setVisibility(View.INVISIBLE);

        // Inflate UdfpsAnimationView
        mAnimationView = (UdfpsAnimationView) inflater.inflate(R.layout.udfps_animation_view,
                null, false);
        mAnimationView.setParent(this);
        addView(mAnimationView);

        mSensorRect = new RectF();

        mDebugTextPaint = new Paint();
        mDebugTextPaint.setAntiAlias(true);
        mDebugTextPaint.setColor(Color.BLUE);
        mDebugTextPaint.setTextSize(DEBUG_TEXT_SIZE_PX);

        mIlluminationRequested = false;
    }

    void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    void setExtras(@Nullable UdfpsAnimation animation, @Nullable UdfpsEnrollHelper enrollHelper) {
        mAnimationView.setAnimation(animation);
        mEnrollHelper = enrollHelper;

        if (enrollHelper != null) {
            mEnrollHelper.updateProgress(mProgressBar);
            mProgressBar.setVisibility(enrollHelper.shouldShowProgressBar()
                    ? View.VISIBLE : View.GONE);
        } else {
            mProgressBar.setVisibility(View.GONE);
        }
    }

    @Override
    public void setHbmCallback(@Nullable HbmCallback callback) {
        mHbmSurfaceView.setHbmCallback(callback);
    }

    @Override
    public void dozeTimeTick() {
        mAnimationView.dozeTimeTick();
    }

    @Override
    public void onExpandedChanged(boolean isExpanded) {
        mNotificationShadeExpanded = isExpanded;
    }

    @Override
    public void onStateChanged(int newState) {
        mStatusBarState = newState;
    }

    @Override
    public void onExpansionChanged(float expansion, boolean expanded) {
        mAnimationView.onExpansionChanged(expansion, expanded);
    }

    @Override
    protected void onFinishInflate() {
        mProgressBar = findViewById(R.id.progress_bar);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mSensorRect.set(0 + mAnimationView.getPaddingX(),
                0 + mAnimationView.getPaddingY(),
                2 * mSensorProps.sensorRadius + mAnimationView.getPaddingX(),
                2 * mSensorProps.sensorRadius + mAnimationView.getPaddingY());

        mHbmSurfaceView.onSensorRectUpdated(new RectF(mSensorRect));
        mAnimationView.onSensorRectUpdated(new RectF(mSensorRect));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.v(TAG, "onAttachedToWindow");

        // Retrieve the colors each time, since it depends on day/night mode
        mAnimationView.updateColor();
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

    RectF getSensorRect() {
        return new RectF(mSensorRect);
    }

    void setDebugMessage(String message) {
        mDebugMessage = message;
        postInvalidate();
    }

    boolean isValidTouch(float x, float y, float pressure) {
        // The X and Y coordinates of the sensor's center.
        final float cx = mSensorRect.centerX();
        final float cy = mSensorRect.centerY();
        // Radii along the X and Y axes.
        final float rx = (mSensorRect.right - mSensorRect.left) / 2.0f;
        final float ry = (mSensorRect.bottom - mSensorRect.top) / 2.0f;

        return x > (cx - rx * mSensorTouchAreaCoefficient)
                && x < (cx + rx * mSensorTouchAreaCoefficient)
                && y > (cy - ry * mSensorTouchAreaCoefficient)
                && y < (cy + ry * mSensorTouchAreaCoefficient)
                && !shouldPauseAuth();
    }

    /**
     * States where UDFPS should temporarily not be authenticating. Instead of completely stopping
     * authentication which would cause the UDFPS icons to abruptly disappear, do it here by not
     * sending onFingerDown and smoothly animating away.
     */
    boolean shouldPauseAuth() {
        return (mNotificationShadeExpanded && mStatusBarState != KEYGUARD)
                || mStatusBarState == SHADE_LOCKED
                || mStatusBarState == FULLSCREEN_USER_SWITCHER;
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
        mAnimationView.setVisibility(View.INVISIBLE);
        mHbmSurfaceView.setVisibility(View.VISIBLE);
        mHbmSurfaceView.startIllumination(onIlluminatedRunnable);
    }

    @Override
    public void stopIllumination() {
        mIlluminationRequested = false;
        mAnimationView.setVisibility(View.VISIBLE);
        mHbmSurfaceView.setVisibility(View.INVISIBLE);
        mHbmSurfaceView.stopIllumination();
    }

    void onEnrollmentProgress(int remaining) {
        mEnrollHelper.onEnrollmentProgress(remaining, mProgressBar);
    }

    void onEnrollmentHelp() {

    }
}
