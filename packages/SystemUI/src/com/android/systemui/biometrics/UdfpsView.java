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

    @NonNull private final RectF mSensorRect;
    @NonNull private final Paint mDebugTextPaint;

    @NonNull private UdfpsSurfaceView mHbmSurfaceView;
    @Nullable private UdfpsAnimationView mAnimationView;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;

    private final float mSensorTouchAreaCoefficient;
    @Nullable private String mDebugMessage;
    private boolean mIlluminationRequested;
    private int mStatusBarState;
    private boolean mNotificationShadeExpanded;

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
        return true;
    }

    @Override
    protected void onFinishInflate() {
        mHbmSurfaceView = findViewById(R.id.hbm_view);
    }

    void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    void setAnimationView(@NonNull UdfpsAnimationView animation) {
        mAnimationView = animation;
        animation.setParent(this);

        // TODO: Consider using a ViewStub placeholder to maintain positioning and inflating it
        //  after the animation type has been decided.
        addView(animation, 0);
    }

    @Override
    public void setHbmCallback(@Nullable HbmCallback callback) {
        mHbmSurfaceView.setHbmCallback(callback);
    }

    @Override
    public void dozeTimeTick() {
        if (mAnimationView == null) {
            return;
        }
        mAnimationView.dozeTimeTick();
    }

    @Override
    public void onStateChanged(int newState) {
        mStatusBarState = newState;
    }

    @Override
    public void onExpansionChanged(float expansion, boolean expanded) {
        mNotificationShadeExpanded = expanded;

        if (mAnimationView != null) {
            mAnimationView.onExpansionChanged(expansion, expanded);
        }
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

    void setDebugMessage(String message) {
        mDebugMessage = message;
        postInvalidate();
    }

    boolean isWithinSensorArea(float x, float y) {
        // The X and Y coordinates of the sensor's center.
        final PointF translation = mAnimationView.getTouchTranslation();
        final float cx = mSensorRect.centerX() + translation.x;
        final float cy = mSensorRect.centerY() + translation.y;
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
        mAnimationView.onIlluminationStarting();
        mHbmSurfaceView.setVisibility(View.VISIBLE);
        mHbmSurfaceView.startIllumination(onIlluminatedRunnable);
    }

    @Override
    public void stopIllumination() {
        mIlluminationRequested = false;
        mAnimationView.onIlluminationStopped();
        mHbmSurfaceView.setVisibility(View.INVISIBLE);
        mHbmSurfaceView.stopIllumination();
    }
}
