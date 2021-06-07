/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.android.systemui.biometrics.UdfpsHbmTypes.HbmType;

/**
 * Under-display fingerprint sensor Surface View. The surface should be used for HBM-specific things
 * only. All other animations should be done on the other view.
 */
public class UdfpsSurfaceView extends SurfaceView implements UdfpsIlluminator {
    private static final String TAG = "UdfpsSurfaceView";
    private static final String SETTING_HBM_TYPE =
            "com.android.systemui.biometrics.UdfpsSurfaceView.hbmType";
    private static final @HbmType int DEFAULT_HBM_TYPE = UdfpsHbmTypes.GLOBAL_HBM;

    /**
     * This is used instead of {@link android.graphics.drawable.Drawable}, because the latter has
     * several abstract methods that are not used here but require implementation.
     */
    private interface SimpleDrawable {
        void draw(Canvas canvas);
    }

    @NonNull private final SurfaceHolder mHolder;
    @NonNull private final Paint mSensorPaint;
    @NonNull private final SimpleDrawable mIlluminationDotDrawable;
    private final @HbmType int mHbmType;

    @NonNull private RectF mSensorRect;
    @Nullable private UdfpsHbmProvider mHbmProvider;

    public UdfpsSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Make this SurfaceView draw on top of everything else in this window. This allows us to
        // 1) Always show the HBM circle on top of everything else, and
        // 2) Properly composite this view with any other animations in the same window no matter
        //    what contents are added in which order to this view hierarchy.
        setZOrderOnTop(true);

        mHolder = getHolder();
        mHolder.setFormat(PixelFormat.RGBA_8888);

        mSensorRect = new RectF();
        mSensorPaint = new Paint(0 /* flags */);
        mSensorPaint.setAntiAlias(true);
        mSensorPaint.setARGB(255, 255, 255, 255);
        mSensorPaint.setStyle(Paint.Style.FILL);

        mIlluminationDotDrawable = canvas -> {
            canvas.drawOval(mSensorRect, mSensorPaint);
        };

        if (Build.IS_ENG || Build.IS_USERDEBUG) {
            mHbmType = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    SETTING_HBM_TYPE, DEFAULT_HBM_TYPE, UserHandle.USER_CURRENT);
        } else {
            mHbmType = DEFAULT_HBM_TYPE;
        }
    }

    @Override
    public void setHbmProvider(@Nullable UdfpsHbmProvider hbmProvider) {
        mHbmProvider = hbmProvider;
    }

    @Override
    public void startIllumination(@Nullable Runnable onIlluminatedRunnable) {
        if (mHbmProvider != null) {
            final Surface surface =
                    (mHbmType == UdfpsHbmTypes.GLOBAL_HBM) ? mHolder.getSurface() : null;

            final Runnable onHbmEnabled = () -> {
                if (mHbmType == UdfpsHbmTypes.GLOBAL_HBM) {
                    drawImmediately(mIlluminationDotDrawable);
                }
                if (onIlluminatedRunnable != null) {
                    // No framework API can reliably tell when a frame reaches the panel. A timeout
                    // is the safest solution. The frame should be displayed within 3 refresh
                    // cycles, which on a 60 Hz panel equates to 50 milliseconds.
                    postDelayed(onIlluminatedRunnable, 50 /* delayMillis */);
                } else {
                    Log.w(TAG, "startIllumination | onIlluminatedRunnable is null");
                }
            };

            mHbmProvider.enableHbm(mHbmType, surface, onHbmEnabled);
        } else {
            Log.e(TAG, "startIllumination | mHbmProvider is null");
        }
    }

    @Override
    public void stopIllumination() {
        if (mHbmProvider != null) {
            final Runnable onHbmDisabled =
                    (mHbmType == UdfpsHbmTypes.GLOBAL_HBM) ? this::invalidate : null;
            mHbmProvider.disableHbm(onHbmDisabled);
        } else {
            Log.e(TAG, "stopIllumination | mHbmProvider is null");
        }
    }

    void onSensorRectUpdated(@NonNull RectF sensorRect) {
        mSensorRect = sensorRect;
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
}
