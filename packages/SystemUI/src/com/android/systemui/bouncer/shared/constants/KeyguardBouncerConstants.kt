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

package com.android.systemui.bouncer.shared.constants

import com.android.internal.R.color as colors

object KeyguardBouncerConstants {
    /**
     * Values for the bouncer expansion represented as the panel expansion. Panel expansion 1f =
     * panel fully showing = bouncer fully hidden Panel expansion 0f = panel fully hiding = bouncer
     * fully showing
     */
    const val EXPANSION_HIDDEN = 1f
    const val EXPANSION_VISIBLE = 0f
    const val ALPHA_EXPANSION_THRESHOLD = 0.95f

    /**
     * This value is used for denoting the PIN length at which we want to layout the view in which
     * PIN hinting is enabled
     */
    const val DEFAULT_PIN_LENGTH = 6

    object ColorId {
        private const val DEPRECATION_MSG =
            "Colors will not be used after bouncerUiRevamp2 flag is launched"

        @Deprecated(DEPRECATION_MSG)
        const val TITLE = com.android.internal.R.color.materialColorOnSurface

        @Deprecated(DEPRECATION_MSG)
        const val PIN_SHAPES = com.android.internal.R.color.materialColorOnSurfaceVariant

        @Deprecated(DEPRECATION_MSG)
        const val NUM_PAD_BACKGROUND =
            com.android.internal.R.color.materialColorSurfaceContainerHigh

        @Deprecated(DEPRECATION_MSG)
        const val NUM_PAD_BACKGROUND_PRESSED =
            com.android.internal.R.color.materialColorPrimaryFixed

        @Deprecated(DEPRECATION_MSG)
        const val NUM_PAD_PRESSED = com.android.internal.R.color.materialColorOnPrimaryFixed

        @Deprecated(DEPRECATION_MSG)
        const val NUM_PAD_KEY = com.android.internal.R.color.materialColorOnSurface

        @Deprecated(DEPRECATION_MSG)
        const val NUM_PAD_BUTTON = com.android.internal.R.color.materialColorOnSecondaryFixed

        @Deprecated(DEPRECATION_MSG)
        const val EMERGENCY_BUTTON = com.android.internal.R.color.materialColorTertiaryFixed
    }
}

object PinBouncerConstants {
    object Color {
        @JvmField val hintDot = colors.materialColorOnSurfaceVariant
        @JvmField val shape = colors.materialColorPrimary
    }
}
