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
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import com.android.systemui.R;

/**
 * Abstract base class for animations that should be drawn when the finger is not touching the
 * sensor area.
 */
public abstract class UdfpsAnimation extends Drawable {
    abstract void updateColor();

    @NonNull protected final Context mContext;
    @NonNull protected final Drawable mFingerprintDrawable;

    public UdfpsAnimation(@NonNull Context context) {
        mContext = context;
        mFingerprintDrawable = context.getResources().getDrawable(R.drawable.ic_fingerprint, null);
    }

    public void onSensorRectUpdated(@NonNull RectF sensorRect) {
        int margin =  (int) (sensorRect.bottom - sensorRect.top) / 5;
        mFingerprintDrawable.setBounds(
                (int) sensorRect.left + margin,
                (int) sensorRect.top + margin,
                (int) sensorRect.right - margin,
                (int) sensorRect.bottom - margin);
    }
}
