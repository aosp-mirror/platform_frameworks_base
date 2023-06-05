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

/** The size of a biometric prompt. */
enum class PromptSize {
    /** Minimal UI, showing only biometric icon. */
    SMALL,
    /** Normal-sized biometric UI, showing title, icon, buttons, etc. */
    MEDIUM,
    /** Full-screen credential UI. */
    LARGE,
}

val PromptSize?.isSmall: Boolean
    get() = this != null && this == PromptSize.SMALL

val PromptSize?.isNotSmall: Boolean
    get() = this != null && this != PromptSize.SMALL

val PromptSize?.isNullOrNotSmall: Boolean
    get() = this == null || this != PromptSize.SMALL

val PromptSize?.isMedium: Boolean
    get() = this != null && this == PromptSize.MEDIUM

val PromptSize?.isLarge: Boolean
    get() = this != null && this == PromptSize.LARGE
