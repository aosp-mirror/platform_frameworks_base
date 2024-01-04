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

package com.android.systemui.volume.panel.ui.viewmodel

import android.content.res.Configuration
import android.content.res.Configuration.Orientation

/**
 * State of the Volume Panel itself.
 *
 * @property orientation is current Volume Panel orientation.
 */
data class VolumePanelState(
    @Orientation val orientation: Int,
    val isVisible: Boolean,
) {
    init {
        require(
            orientation == Configuration.ORIENTATION_PORTRAIT ||
                orientation == Configuration.ORIENTATION_LANDSCAPE ||
                orientation == Configuration.ORIENTATION_UNDEFINED ||
                orientation == Configuration.ORIENTATION_SQUARE
        ) {
            "Unknown orientation: $orientation"
        }
    }
}
