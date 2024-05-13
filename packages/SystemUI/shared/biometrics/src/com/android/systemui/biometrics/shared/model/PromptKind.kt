/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.biometrics.shared.model

sealed interface PromptKind {
    object None : PromptKind

    data class Biometric(
        /** The available modalities for the authentication on the prompt. */
        val activeModalities: BiometricModalities = BiometricModalities(),
        // TODO(b/330908557): Use this value to decide whether to show two pane layout, instead of
        // simply depending on rotations.
        val showTwoPane: Boolean = false,
    ) : PromptKind

    object Pin : PromptKind
    object Pattern : PromptKind
    object Password : PromptKind

    fun isBiometric() = this is Biometric
    fun isCredential() = (this is Pin) or (this is Pattern) or (this is Password)
}
