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
 *
 */

package com.android.systemui.keyguard.shared.model

import androidx.annotation.DrawableRes

/**
 * Representation of a quick affordance for use to build "picker", "selector", or "settings"
 * experiences.
 */
data class KeyguardQuickAffordancePickerRepresentation(
    val id: String,
    val name: String,
    @DrawableRes val iconResourceId: Int,

    /** Whether this quick affordance is enabled. */
    val isEnabled: Boolean = true,

    /** If not enabled, the list of user-visible steps to re-enable it. */
    val instructions: List<String>? = null,

    /**
     * If not enabled, an optional label for a button that takes the user to a destination where
     * they can re-enable it.
     */
    val actionText: String? = null,

    /**
     * If not enabled, an optional component name (package and action) for a button that takes the
     * user to a destination where they can re-enable it.
     */
    val actionComponentName: String? = null,
)
