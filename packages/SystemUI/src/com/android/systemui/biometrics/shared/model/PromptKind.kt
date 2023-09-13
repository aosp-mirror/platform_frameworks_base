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

package com.android.systemui.biometrics.shared.model

import com.android.systemui.biometrics.Utils

// TODO(b/251476085): this should eventually replace Utils.CredentialType
/** Credential options for biometric prompt. Shadows [Utils.CredentialType]. */
sealed interface PromptKind {
    data class Biometric(
        val activeModalities: BiometricModalities = BiometricModalities(),
    ) : PromptKind

    object Pin : PromptKind
    object Pattern : PromptKind
    object Password : PromptKind
}
