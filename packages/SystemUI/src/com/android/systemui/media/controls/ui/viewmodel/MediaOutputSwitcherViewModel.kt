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

package com.android.systemui.media.controls.ui.viewmodel

import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon

/** Models UI state of output switcher chip. */
data class MediaOutputSwitcherViewModel(
    val isTapEnabled: Boolean,
    val deviceString: CharSequence,
    val deviceIcon: Icon,
    val isCurrentBroadcastApp: Boolean,
    val isIntentValid: Boolean,
    val alpha: Float,
    val isVisible: Boolean,
    val onClicked: (Expandable) -> Unit,
) {
    fun contentEquals(other: MediaOutputSwitcherViewModel?): Boolean {
        return (other?.let {
            isTapEnabled == other.isTapEnabled &&
                deviceString == other.deviceString &&
                isCurrentBroadcastApp == other.isCurrentBroadcastApp &&
                isIntentValid == other.isIntentValid &&
                alpha == other.alpha &&
                isVisible == other.isVisible
        } ?: false)
    }
}
