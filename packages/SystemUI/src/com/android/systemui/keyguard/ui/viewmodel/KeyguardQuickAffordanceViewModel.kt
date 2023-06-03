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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon

/** Models the UI state of a keyguard quick affordance button. */
data class KeyguardQuickAffordanceViewModel(
    val configKey: String? = null,
    val isVisible: Boolean = false,
    /** Whether to animate the transition of the quick affordance from invisible to visible. */
    val animateReveal: Boolean = false,
    val icon: Icon = Icon.Resource(res = 0, contentDescription = null),
    val onClicked: (OnClickedParameters) -> Unit = {},
    val isClickable: Boolean = false,
    val isActivated: Boolean = false,
    val isSelected: Boolean = false,
    val useLongPress: Boolean = false,
    val isDimmed: Boolean = false,
) {
    data class OnClickedParameters(
        val configKey: String,
        val expandable: Expandable?,
    )
}
