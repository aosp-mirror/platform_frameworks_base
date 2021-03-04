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
import android.graphics.Canvas;
import android.graphics.ColorFilter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * UDFPS animations that should be shown when authenticating via FingerprintManager, excluding
 * keyguard.
 */
public class UdfpsAnimationFpmOther extends UdfpsAnimation {

    UdfpsAnimationFpmOther(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void updateColor() {

    }

    @Override
    protected void onDestroy() {

    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (isIlluminationShowing()) {
            return;
        }

        mFingerprintDrawable.draw(canvas);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return 0;
    }
}
