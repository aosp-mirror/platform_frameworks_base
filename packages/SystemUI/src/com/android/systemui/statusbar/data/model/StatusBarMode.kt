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

package com.android.systemui.statusbar.data.model

import com.android.systemui.shared.statusbar.phone.BarTransitions
import com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT
import com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT
import com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_OPAQUE
import com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT
import com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_TRANSPARENT
import com.android.systemui.shared.statusbar.phone.BarTransitions.TransitionMode

/**
 * The possible status bar modes.
 *
 * See the associated [BarTransitions] mode documentation for information about each of the modes
 * and how they're used.
 */
enum class StatusBarMode {
    /** Use a semi-transparent (aka translucent) background for the status bar. */
    SEMI_TRANSPARENT,
    /**
     * A mode where notification icons in the status bar are hidden and replaced by a dot (this mode
     * can be requested by apps). See
     * [com.android.systemui.statusbar.phone.LegacyLightsOutNotifController] and
     * [com.android.systemui.statusbar.phone.domain.interactor.LightsOutInteractor].
     */
    LIGHTS_OUT,
    /** Similar to [LIGHTS_OUT], but also with a transparent background for the status bar. */
    LIGHTS_OUT_TRANSPARENT,
    /** Use an opaque background for the status bar. */
    OPAQUE,
    /** Use a transparent background for the status bar. */
    TRANSPARENT;

    /** Converts a [StatusBarMode] to its [BarTransitions] integer. */
    @TransitionMode
    fun toTransitionModeInt(): Int {
        return when (this) {
            SEMI_TRANSPARENT -> MODE_SEMI_TRANSPARENT
            LIGHTS_OUT -> MODE_LIGHTS_OUT
            LIGHTS_OUT_TRANSPARENT -> MODE_LIGHTS_OUT_TRANSPARENT
            OPAQUE -> MODE_OPAQUE
            TRANSPARENT -> MODE_TRANSPARENT
        }
    }
}
