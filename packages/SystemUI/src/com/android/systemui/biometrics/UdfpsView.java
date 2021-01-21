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
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
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
    @NonNull private final Drawable mFingerprintDrawable;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;

    // AOD anti-burn-in offsets
    private float mInterpolatedDarkAmount;
    private float mBurnInOffsetX;
    private float mBurnInOffsetY;

    private int mShowReason;
    private boolean mShowScrimAndDot;
    private boolean mIsHbmSupported;
    @Nullable private String mDebugMessage;

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
        mSensorPaint.setShadowLayer(SENSOR_SHADOW_RADIUS, 0, 0, Color.BLACK);
        mSensorPaint.setStyle(Paint.Style.FILL);

        mDebugTextPaint = new Paint();
        mDebugTextPaint.setAntiAlias(true);
        mDebugTextPaint.setColor(Color.BLUE);
        mDebugTextPaint.setTextSize(DEBUG_TEXT_SIZE_PX);

        mFingerprintDrawable = getResources().getDrawable(R.drawable.ic_fingerprint, null);

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

    /**
     * @param reason See {@link android.hardware.fingerprint.IUdfpsOverlayController}
     */
    void setShowReason(int reason) {
        mShowReason = reason;
    }

    @Override
    public void dozeTimeTick() {
        updateAodPositionAndColor();
    }

    @Override
    public void onDozeAmountChanged(float linear, float eased) {
        mInterpolatedDarkAmount = eased;
        updateAodPositionAndColor();
    }

    private void updateAodPositionAndColor() {
        mBurnInOffsetX = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                        - mMaxBurnInOffsetX,
                mInterpolatedDarkAmount);
        mBurnInOffsetY = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                        - 0.5f * mMaxBurnInOffsetY,
                mInterpolatedDarkAmount);
        updateColor();
        postInvalidate();
    }

    // The "h" and "w" are the display's height and width relative to its current rotation.
    private void updateSensorRect(int h, int w) {
        // mSensorProps coordinates assume portrait mode.
        mSensorRect.set(mSensorProps.sensorLocationX - mSensorProps.sensorRadius,
                mSensorProps.sensorLocationY - mSensorProps.sensorRadius,
                mSensorProps.sensorLocationX + mSensorProps.sensorRadius,
                mSensorProps.sensorLocationY + mSensorProps.sensorRadius);

        // Transform mSensorRect if the device is in landscape mode.
        switch (mContext.getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                mSensorRect.set(mSensorRect.top, h - mSensorRect.right, mSensorRect.bottom,
                        h - mSensorRect.left);
                break;
            case Surface.ROTATION_270:
                mSensorRect.set(w - mSensorRect.bottom, mSensorRect.left, w - mSensorRect.top,
                        mSensorRect.right);
                break;
            default:
                // Do nothing to stay in portrait mode.
        }

        int margin =  (int) (mSensorRect.bottom - mSensorRect.top) / 5;
        mFingerprintDrawable.setBounds(
                (int) mSensorRect.left + margin,
                (int) mSensorRect.top + margin,
                (int) mSensorRect.right - margin,
                (int) mSensorRect.bottom - margin);
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
        if (mShowReason == IUdfpsOverlayController.REASON_AUTH) {
            final int lockScreenIconColor = Utils.getColorAttrDefaultColor(mContext,
                    com.android.systemui.R.attr.wallpaperTextColor);
            final int ambientDisplayIconColor = Color.WHITE;
            mFingerprintDrawable.setTint(ColorUtils.blendARGB(lockScreenIconColor,
                    ambientDisplayIconColor, mInterpolatedDarkAmount));
        } else if (mShowReason == IUdfpsOverlayController.REASON_ENROLL) {
            mFingerprintDrawable.setTint(mContext.getColor(R.color.udfps_enroll_icon));
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

        // Translation should affect everything but the scrim.
        canvas.save();
        canvas.translate(mBurnInOffsetX, mBurnInOffsetY);
        if (!TextUtils.isEmpty(mDebugMessage)) {
            canvas.drawText(mDebugMessage, 0, 160, mDebugTextPaint);
        }

        if (mShowScrimAndDot) {
            // draw dot (white circle)
            canvas.drawOval(mSensorRect, mSensorPaint);
        } else {
            final boolean isNightMode = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_YES) != 0;
            if (mShowReason == IUdfpsOverlayController.REASON_ENROLL && !isNightMode) {
                canvas.drawOval(mSensorRect, mSensorPaint);
            }
            // draw fingerprint icon
            mFingerprintDrawable.draw(canvas);
        }

        canvas.restore();

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
                && y < (cy + ry * mSensorTouchAreaCoefficient);
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
}
