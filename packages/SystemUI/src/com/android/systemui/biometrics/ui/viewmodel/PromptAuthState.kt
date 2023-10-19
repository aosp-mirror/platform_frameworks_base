/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.biometrics.ui.viewmodel

import com.android.systemui.biometrics.shared.model.BiometricModality

/**
 * The authenticated state with the [authenticatedModality] (when [isAuthenticated]) with an
 * optional [delay] to keep the UI showing before dismissing when [needsUserConfirmation] is not
 * required.
 */
data class PromptAuthState(
    val isAuthenticated: Boolean,
    val authenticatedModality: BiometricModality = BiometricModality.None,
    val needsUserConfirmation: Boolean = false,
    val delay: Long = 0,
) {
    private var wasConfirmed = false

    /** If authentication was successful and the user has confirmed (or does not need to). */
    val isAuthenticatedAndConfirmed: Boolean
        get() = isAuthenticated && !needsUserConfirmation

    /** Same as [isAuthenticatedAndConfirmed] but only true if the user clicked a confirm button. */
    val isAuthenticatedAndExplicitlyConfirmed: Boolean
        get() = isAuthenticated && wasConfirmed

    /** If a successful authentication has not occurred. */
    val isNotAuthenticated: Boolean
        get() = !isAuthenticated

    /** If a authentication has succeeded and it was done by face (may need confirmation). */
    val isAuthenticatedByFace: Boolean
        get() = isAuthenticated && authenticatedModality == BiometricModality.Face

    /** If a authentication has succeeded and it was done by fingerprint (may need confirmation). */
    val isAuthenticatedByFingerprint: Boolean
        get() = isAuthenticated && authenticatedModality == BiometricModality.Fingerprint

    /**
     * Copies this state, but toggles [needsUserConfirmation] to false and ensures that
     * [isAuthenticatedAndExplicitlyConfirmed] is true.
     */
    fun asExplicitlyConfirmed(): PromptAuthState =
        PromptAuthState(
                isAuthenticated = isAuthenticated,
                authenticatedModality = authenticatedModality,
                needsUserConfirmation = false,
                delay = delay,
            )
            .apply { wasConfirmed = true }
}
