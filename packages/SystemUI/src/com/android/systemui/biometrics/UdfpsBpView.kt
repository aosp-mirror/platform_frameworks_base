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
package com.android.systemui.biometrics

import android.content.Context
import android.util.AttributeSet

/**
 * Class that coordinates non-HBM animations during BiometricPrompt.
 *
 * Currently doesn't draw anything.
 *
 * Note that [AuthBiometricFingerprintViewController] also shows UDFPS animations. At some point we should
 * de-dupe this if necessary.
 */
class UdfpsBpView(context: Context, attrs: AttributeSet?) : UdfpsAnimationView(context, attrs) {

    // Drawable isn't ever added to the view, so we don't currently show anything
    private val fingerprintDrawable: UdfpsFpDrawable = UdfpsFpDrawable(context)

    override fun getDrawable(): UdfpsDrawable = fingerprintDrawable
}
