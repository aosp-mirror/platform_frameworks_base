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
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.android.systemui.R;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.ScrimController;

/**
 * A full screen view with a configurable illumination dot and scrim.
 */
public class UdfpsView extends SurfaceView implements DozeReceiver,
        StatusBarStateController.StateListener, ScrimController.ScrimChangedListener {
    private static final String TAG = "UdfpsView";

    /**
     * Interface for controlling the high-brightness mode (HBM). UdfpsView can use this callback to
     * enable the HBM while showing the fingerprint illumination, and to disable the HBM after the
     * illumination is no longer necessary.
     */
    interface HbmCallback {
        /**
         * UdfpsView will call this to enable the HBM before drawing the illumination dot.
         *
         * @param surface A valid surface for which the HBM should be enabled.
         */
        void enableHbm(@NonNull Surface surface);

        /**
         * UdfpsView will call this to disable the HBM when the illumination is not longer needed.
         *
         * @param surface A valid surface for which the HBM should be disabled.
         */
        void disableHbm(@NonNull Surface surface);
    }

    /**
     * This is used instead of {@link android.graphics.drawable.Drawable}, because the latter has
     * several abstract methods that are not used here but require implementation.
     */
    private interface SimpleDrawable {
        void draw(Canvas canvas);
    }

    // Radius in pixels.
    private static final float SENSOR_SHADOW_RADIUS = 2.0f;

    private static final int DEBUG_TEXT_SIZE_PX = 32;

    @NonNull private final SurfaceHolder mHolder;
    @NonNull private final RectF mSensorRect;
    @NonNull private final Paint mSensorPaint;
    @NonNull private final Paint mDebugTextPaint;
    @NonNull private final SimpleDrawable mIlluminationDotDrawable;
    @NonNull private final SimpleDrawable mClearSurfaceDrawable;

    @Nullable private UdfpsAnimation mUdfpsAnimation;
    @Nullable private HbmCallback mHbmCallback;
    @Nullable private Runnable mOnIlluminatedRunnable;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;

    private final float mSensorTouchAreaCoefficient;
    @Nullable private String mDebugMessage;
    private boolean mIlluminationRequested;
    private int mStatusBarState;
    private boolean mNotificationShadeExpanded;
    private int mNotificationPanelAlpha;

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

        mHolder = getHolder();
        mHolder.setFormat(PixelFormat.RGBA_8888);

        mSensorRect = new RectF();
        mSensorPaint = new Paint(0 /* flags */);
        mSensorPaint.setAntiAlias(true);
        mSensorPaint.setARGB(255, 255, 255, 255);
        mSensorPaint.setStyle(Paint.Style.FILL);

        mDebugTextPaint = new Paint();
        mDebugTextPaint.setAntiAlias(true);
        mDebugTextPaint.setColor(Color.BLUE);
        mDebugTextPaint.setTextSize(DEBUG_TEXT_SIZE_PX);

        mIlluminationDotDrawable = canvas -> canvas.drawOval(mSensorRect, mSensorPaint);
        mClearSurfaceDrawable = canvas -> canvas.drawColor(0, PorterDuff.Mode.CLEAR);

        mIlluminationRequested = false;
        // SurfaceView sets this to true by default. We must set it to false to allow
        // onDraw to be called.
        setWillNotDraw(false);
    }

    void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    void setUdfpsAnimation(@Nullable UdfpsAnimation animation) {
        mUdfpsAnimation = animation;
    }

    /**
     * Sets a callback that can be used to enable and disable the high-brightness mode (HBM).
     */
    void setHbmCallback(@Nullable HbmCallback callback) {
        mHbmCallback = callback;
    }

    /**
     * Sets a runnable that will be run when the first illumination frame reaches the panel.
     * The runnable is reset to null after it is executed once.
     */
    void setOnIlluminatedRunnable(Runnable runnable) {
        mOnIlluminatedRunnable = runnable;
    }

    @Override
    public void dozeTimeTick() {
        if (mUdfpsAnimation instanceof DozeReceiver) {
            ((DozeReceiver) mUdfpsAnimation).dozeTimeTick();
        }
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
    public void onAlphaChanged(float alpha) {
        mNotificationPanelAlpha = (int) (alpha * 255);
        postInvalidate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mSensorRect.set(0, 0, 2 * mSensorProps.sensorRadius, 2 * mSensorProps.sensorRadius);
        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.onSensorRectUpdated(new RectF(mSensorRect));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.v(TAG, "onAttachedToWindow");

        // Retrieve the colors each time, since it depends on day/night mode
        updateColor();
    }

    private void updateColor() {
        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.updateColor();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.v(TAG, "onDetachedFromWindow");
    }

    /**
     * Immediately draws the provided drawable on this SurfaceView's surface.
     */
    private void drawImmediately(@NonNull SimpleDrawable drawable) {
        Canvas canvas = null;
        try {
            canvas = mHolder.lockCanvas();
            drawable.draw(canvas);
        } finally {
            // Make sure the surface is never left in a bad state.
            if (canvas != null) {
                mHolder.unlockCanvasAndPost(canvas);
            }
        }
    }

    /**
     * This onDraw will not execute if setWillNotDraw(true) is called.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mIlluminationRequested) {
            if (!TextUtils.isEmpty(mDebugMessage)) {
                canvas.drawText(mDebugMessage, 0, 160, mDebugTextPaint);
            }
            if (mUdfpsAnimation != null) {
                final int alpha = shouldPauseAuth() ? 255 - mNotificationPanelAlpha : 255;
                mUdfpsAnimation.setAlpha(alpha);
                mUdfpsAnimation.draw(canvas);
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
    private boolean shouldPauseAuth() {
        return (mNotificationShadeExpanded && mStatusBarState != KEYGUARD)
                || mStatusBarState == SHADE_LOCKED
                || mStatusBarState == FULLSCREEN_USER_SWITCHER;
    }

    boolean isIlluminationRequested() {
        return mIlluminationRequested;
    }

    void startIllumination() {
        mIlluminationRequested = true;

        // Disable onDraw to prevent overriding the illumination dot with the regular UI.
        setWillNotDraw(true);

        if (mHbmCallback != null && mHolder.getSurface().isValid()) {
            mHbmCallback.enableHbm(mHolder.getSurface());
        }
        drawImmediately(mIlluminationDotDrawable);

        if (mOnIlluminatedRunnable != null) {
            // No framework API can reliably tell when a frame reaches the panel. A timeout is the
            // safest solution. The frame should be displayed within 3 refresh cycles, which on a
            // 60 Hz panel equates to 50 milliseconds.
            postDelayed(mOnIlluminatedRunnable, 50 /* delayMillis */);
            mOnIlluminatedRunnable = null;
        }
    }

    void stopIllumination() {
        mIlluminationRequested = false;

        if (mHbmCallback != null && mHolder.getSurface().isValid()) {
            mHbmCallback.disableHbm(mHolder.getSurface());
        }
        // It may be necessary to clear the surface for the HBM changes to apply.
        drawImmediately(mClearSurfaceDrawable);

        // Enable onDraw to allow the regular UI to be drawn.
        setWillNotDraw(false);
        invalidate();
    }

    void onEnrollmentProgress(int remaining) {
        if (mUdfpsAnimation instanceof UdfpsAnimationEnroll) {
            ((UdfpsAnimationEnroll) mUdfpsAnimation).onEnrollmentProgress(remaining);
        }
    }

    void onEnrollmentHelp() {
        if (mUdfpsAnimation instanceof UdfpsAnimationEnroll) {
            ((UdfpsAnimationEnroll) mUdfpsAnimation).onEnrollmentHelp();
        }
    }
}
