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
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * UDFPS animations that should be shown when enrolling.
 */
public class UdfpsAnimationEnroll extends UdfpsAnimation {
    private static final String TAG = "UdfpsAnimationEnroll";

    @Nullable private RectF mSensorRect;
    @NonNull private final Paint mSensorPaint;

    UdfpsAnimationEnroll(@NonNull Context context) {
        super(context);

        mSensorPaint = new Paint(0 /* flags */);
        mSensorPaint.setAntiAlias(true);
        mSensorPaint.setColor(Color.WHITE);
        mSensorPaint.setShadowLayer(UdfpsView.SENSOR_SHADOW_RADIUS, 0, 0, Color.BLACK);
        mSensorPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    void updateColor() {
        mFingerprintDrawable.setTint(mContext.getColor(R.color.udfps_enroll_icon));
    }

    @Override
    public void onSensorRectUpdated(@NonNull RectF sensorRect) {
        super.onSensorRectUpdated(sensorRect);
        mSensorRect = sensorRect;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final boolean isNightMode = (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_YES) != 0;
        if (!isNightMode) {
            if (mSensorRect != null) {
                canvas.drawOval(mSensorRect, mSensorPaint);
            }
        }
        mFingerprintDrawable.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {

    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return 0;
    }
}
