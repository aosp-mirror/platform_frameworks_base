/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.hardware.biometrics.BiometricAuthenticator
import android.os.Bundle
import android.view.View

/** TODO(b/251476085): Temporary interface while legacy biometric prompt is around. */
@Deprecated("temporary adapter while migrating biometric prompt - do not expand")
interface AuthBiometricViewAdapter {
    val legacyIconController: AuthIconController?

    fun onDialogAnimatedIn(fingerprintWasStarted: Boolean)

    fun onAuthenticationSucceeded(@BiometricAuthenticator.Modality modality: Int)

    fun onAuthenticationFailed(
        @BiometricAuthenticator.Modality modality: Int,
        failureReason: String
    )

    fun onError(@BiometricAuthenticator.Modality modality: Int, error: String)

    fun onHelp(@BiometricAuthenticator.Modality modality: Int, help: String)

    fun startTransitionToCredentialUI(isError: Boolean)

    fun requestLayout()

    fun onSaveState(bundle: Bundle?)

    fun restoreState(bundle: Bundle?)

    fun onOrientationChanged()

    fun cancelAnimation()

    fun isCoex(): Boolean

    fun asView(): View
}
