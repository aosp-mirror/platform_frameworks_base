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
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.systemui.R;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.ScrimController;

/**
 * A full screen view with a configurable illumination dot and scrim.
 */
public class UdfpsView extends View implements DozeReceiver,
        StatusBarStateController.StateListener,  ScrimController.ScrimChangedListener{
    private static final String TAG = "UdfpsView";

    // Values in pixels.
    private static final float SENSOR_SHADOW_RADIUS = 2.0f;

    private static final int DEBUG_TEXT_SIZE_PX = 32;

    @NonNull private final Rect mScrimRect;
    @NonNull private final Paint mScrimPaint;
    @NonNull private final Paint mDebugTextPaint;

    @NonNull private final RectF mSensorRect;
    @NonNull private final Paint mSensorPaint;
    private final float mSensorTouchAreaCoefficient;

    // Stores rounded up values from mSensorRect. Necessary for APIs that only take Rect (not RecF).
    @NonNull private final Rect mTouchableRegion;
    // mInsetsListener is used to set the touchable region for our window. Our window covers the
    // whole screen, and by default its touchable region is the whole screen. We use
    // mInsetsListener to restrict the touchable region and allow the touches outside of the sensor
    // to propagate to the rest of the UI.
    @NonNull private final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsListener;
    @Nullable private UdfpsAnimation mUdfpsAnimation;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;

    private boolean mShowScrimAndDot;
    private boolean mIsHbmSupported;
    @Nullable private String mDebugMessage;
    private int mStatusBarState;
    private boolean mNotificationShadeExpanded;
    private int mNotificationPanelAlpha;

    // Runnable that will be run after the illumination dot and scrim are shown.
    // The runnable is reset to null after it's executed once.
    @Nullable private Runnable mRunAfterShowingScrimAndDot;

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


        mScrimRect = new Rect();
        mScrimPaint = new Paint(0 /* flags */);
        mScrimPaint.setColor(Color.BLACK);

        mSensorRect = new RectF();
        mSensorPaint = new Paint(0 /* flags */);
        mSensorPaint.setAntiAlias(true);
        mSensorPaint.setColor(Color.WHITE);
        mSensorPaint.setShadowLayer(SENSOR_SHADOW_RADIUS, 0, 0, Color.BLACK);
        mSensorPaint.setStyle(Paint.Style.FILL);

        mDebugTextPaint = new Paint();
        mDebugTextPaint.setAntiAlias(true);
        mDebugTextPaint.setColor(Color.BLUE);
        mDebugTextPaint.setTextSize(DEBUG_TEXT_SIZE_PX);

        mTouchableRegion = new Rect();
        // When the device is rotated, it's important that mTouchableRegion is updated before
        // this listener is called. This listener is usually called shortly after onLayout.
        mInsetsListener = internalInsetsInfo -> {
            internalInsetsInfo.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            internalInsetsInfo.touchableRegion.set(mTouchableRegion);
        };

        mShowScrimAndDot = false;
    }

    void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    void setUdfpsAnimation(@Nullable UdfpsAnimation animation) {
        mUdfpsAnimation = animation;
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

    // The "h" and "w" are the display's height and width relative to its current rotation.
    protected void updateSensorRect(int h, int w) {
        // mSensorProps coordinates assume portrait mode.
        mSensorRect.set(mSensorProps.sensorLocationX - mSensorProps.sensorRadius,
                mSensorProps.sensorLocationY - mSensorProps.sensorRadius,
                mSensorProps.sensorLocationX + mSensorProps.sensorRadius,
                mSensorProps.sensorLocationY + mSensorProps.sensorRadius);

        // Transform mSensorRect if the device is in landscape mode.
        switch (mContext.getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                //noinspection SuspiciousNameCombination
                mSensorRect.set(mSensorRect.top, h - mSensorRect.right, mSensorRect.bottom,
                        h - mSensorRect.left);
                break;
            case Surface.ROTATION_270:
                //noinspection SuspiciousNameCombination
                mSensorRect.set(w - mSensorRect.bottom, mSensorRect.left, w - mSensorRect.top,
                        mSensorRect.right);
                break;
            default:
                // Do nothing to stay in portrait mode.
        }

        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.onSensorRectUpdated(new RectF(mSensorRect));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Always re-compute the layout regardless of whether "changed" is true. It is usually false
        // when the device goes from landscape to seascape and vice versa, but mSensorRect and
        // its dependencies need to be recalculated to stay at the same physical location on the
        // screen.
        final int w = getLayoutParams().width;
        final int h = getLayoutParams().height;
        mScrimRect.set(0 /* left */, 0 /* top */, w, h);
        updateSensorRect(h, w);
        // Update mTouchableRegion with the rounded up values from mSensorRect. After "onLayout"
        // is finished, mTouchableRegion will be used by mInsetsListener to compute the touch
        // insets.
        mSensorRect.roundOut(mTouchableRegion);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.v(TAG, "onAttachedToWindow");

        // Retrieve the colors each time, since it depends on day/night mode
        updateColor();

        getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsListener);
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
        getViewTreeObserver().removeOnComputeInternalInsetsListener(mInsetsListener);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mShowScrimAndDot && mIsHbmSupported) {
            // Only draw the scrim if HBM is supported.
            canvas.drawRect(mScrimRect, mScrimPaint);
        }

        if (!TextUtils.isEmpty(mDebugMessage)) {
            canvas.drawText(mDebugMessage, 0, 160, mDebugTextPaint);
        }

        if (mShowScrimAndDot) {
            // draw dot (white circle)
            canvas.drawOval(mSensorRect, mSensorPaint);
        } else {
            if (mUdfpsAnimation != null) {
                final int alpha = shouldPauseAuth() ? 255 - mNotificationPanelAlpha : 255;
                mUdfpsAnimation.setAlpha(alpha);
                mUdfpsAnimation.draw(canvas);
            }
        }

        if (mShowScrimAndDot && mRunAfterShowingScrimAndDot != null) {
            post(mRunAfterShowingScrimAndDot);
            mRunAfterShowingScrimAndDot = null;
        }
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

    void setRunAfterShowingScrimAndDot(Runnable runnable) {
        mRunAfterShowingScrimAndDot = runnable;
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

    void setScrimAlpha(int alpha) {
        mScrimPaint.setAlpha(alpha);
    }

    boolean isShowScrimAndDot() {
        return mShowScrimAndDot;
    }

    void showScrimAndDot() {
        mShowScrimAndDot = true;
        invalidate();
    }

    void hideScrimAndDot() {
        mShowScrimAndDot = false;
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
