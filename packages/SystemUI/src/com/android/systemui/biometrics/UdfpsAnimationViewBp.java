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
import android.util.AttributeSet;

import androidx.annotation.Nullable;

/**
 * Class that coordinates non-HBM animations during BiometricPrompt.
 *
 * Note that {@link AuthBiometricUdfpsView} also shows UDFPS animations. At some point we should
 * de-dupe this if necessary. This will probably happen once the top-level TODO in UdfpsController
 * is completed (inflate operation-specific views, instead of inflating generic udfps_view and
 * adding operation-specific animations to it).
 */
public class UdfpsAnimationViewBp extends UdfpsAnimationView {
    public UdfpsAnimationViewBp(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Nullable
    @Override
    protected UdfpsAnimation getUdfpsAnimation() {
        return null;
    }
}
