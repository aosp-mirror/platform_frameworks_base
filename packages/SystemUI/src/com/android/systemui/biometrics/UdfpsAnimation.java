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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * Abstract base class for animations that should be drawn when the finger is not touching the
 * sensor area.
 */
public abstract class UdfpsAnimation extends Drawable {
    protected abstract void updateColor();
    protected abstract void onDestroy();

    @NonNull protected final Context mContext;
    @NonNull protected final Drawable mFingerprintDrawable;
    @Nullable private View mView;
    private boolean mIlluminationShowing;

    public UdfpsAnimation(@NonNull Context context) {
        mContext = context;
        mFingerprintDrawable = context.getResources().getDrawable(R.drawable.ic_fingerprint, null);
        mFingerprintDrawable.mutate();
    }

    public void onSensorRectUpdated(@NonNull RectF sensorRect) {
        final int margin =  (int) sensorRect.height() / 5;

        final Rect bounds = new Rect((int) sensorRect.left + margin,
                (int) sensorRect.top + margin,
                (int) sensorRect.right - margin,
                (int) sensorRect.bottom - margin);
        updateFingerprintIconBounds(bounds);
    }

    protected void updateFingerprintIconBounds(@NonNull Rect bounds) {
        mFingerprintDrawable.setBounds(bounds);
    }

    @Override
    public void setAlpha(int alpha) {
        mFingerprintDrawable.setAlpha(alpha);
    }

    public void setAnimationView(UdfpsAnimationView view) {
        mView = view;
    }

    boolean isIlluminationShowing() {
        return mIlluminationShowing;
    }

    void setIlluminationShowing(boolean showing) {
        mIlluminationShowing = showing;
    }

    /**
     * @return The amount of padding that's needed on each side of the sensor, in pixels.
     */
    public int getPaddingX() {
        return 0;
    }

    /**
     * @return The amount of padding that's needed on each side of the sensor, in pixels.
     */
    public int getPaddingY() {
        return 0;
    }

    protected void postInvalidateView() {
        if (mView != null) {
            mView.postInvalidate();
        }
    }
}
