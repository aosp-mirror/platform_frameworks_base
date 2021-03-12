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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * Abstract base class for drawable displayed when the finger is not touching the
 * sensor area.
 */
public abstract class UdfpsDrawable extends Drawable {
    @NonNull protected final Context mContext;
    @NonNull protected final Drawable mFingerprintDrawable;
    private boolean mIlluminationShowing;

    int mAlpha = 255; // 0 - 255
    public UdfpsDrawable(@NonNull Context context) {
        mContext = context;
        mFingerprintDrawable = context.getResources().getDrawable(R.drawable.ic_fingerprint, null);
        mFingerprintDrawable.mutate();
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
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        mFingerprintDrawable.setAlpha(mAlpha);
    }

    boolean isIlluminationShowing() {
        return mIlluminationShowing;
    }

    void setIlluminationShowing(boolean showing) {
        mIlluminationShowing = showing;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return 0;
    }
}
