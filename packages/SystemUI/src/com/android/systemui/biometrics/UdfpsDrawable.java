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

import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.util.PathParser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * Abstract base class for drawable displayed when the finger is not touching the
 * sensor area.
 */
public abstract class UdfpsDrawable extends Drawable {
    static final float DEFAULT_STROKE_WIDTH = 3f;

    @NonNull final Context mContext;
    @NonNull final ShapeDrawable mFingerprintDrawable;
    private final Paint mPaint;
    private boolean mIlluminationShowing;

    int mAlpha = 255; // 0 - 255
    public UdfpsDrawable(@NonNull Context context) {
        mContext = context;
        final String fpPath = context.getResources().getString(R.string.config_udfpsIcon);
        mFingerprintDrawable = new ShapeDrawable(
                new PathShape(PathParser.createPathFromPathData(fpPath), 72, 72));
        mFingerprintDrawable.mutate();

        mPaint = mFingerprintDrawable.getPaint();
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        setStrokeWidth(DEFAULT_STROKE_WIDTH);
    }

    void setStrokeWidth(float strokeWidth) {
        mPaint.setStrokeWidth(strokeWidth);
        invalidateSelf();
    }

    /**
     * @param sensorRect the rect coordinates for the sensor area
     */
    public void onSensorRectUpdated(@NonNull RectF sensorRect) {
        final int margin =  (int) sensorRect.height() / 8;

        final Rect bounds = new Rect((int) sensorRect.left + margin,
                (int) sensorRect.top + margin,
                (int) sensorRect.right - margin,
                (int) sensorRect.bottom - margin);
        updateFingerprintIconBounds(bounds);
    }

    /**
     * Bounds for the fingerprint icon
     */
    protected void updateFingerprintIconBounds(@NonNull Rect bounds) {
        mFingerprintDrawable.setBounds(bounds);
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        mFingerprintDrawable.setAlpha(mAlpha);
        invalidateSelf();
    }

    boolean isIlluminationShowing() {
        return mIlluminationShowing;
    }

    void setIlluminationShowing(boolean showing) {
        if (mIlluminationShowing == showing) {
            return;
        }
        mIlluminationShowing = showing;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return 0;
    }
}
