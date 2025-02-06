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

import com.android.app.animation.Interpolators
import com.android.internal.R.color as colors
import com.android.systemui.Flags
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.ColorId.NUM_PAD_BACKGROUND
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.ColorId.NUM_PAD_BACKGROUND_PRESSED
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.ColorId.NUM_PAD_BUTTON
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.ColorId.NUM_PAD_KEY
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.ColorId.NUM_PAD_PRESSED
import com.android.systemui.res.R

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

    object Color {
        @JvmField val actionButtonText = colors.materialColorOnSecondaryContainer
        @JvmField val actionButtonBg = colors.materialColorSecondaryContainer
    }
}

private fun <T> c(old: T, new: T): T {
    return if (Flags.bouncerUiRevamp2()) {
        new
    } else {
        old
    }
}

object PinBouncerConstants {
    @JvmField
    val pinShapes = c(old = R.array.bouncer_pin_shapes, new = R.array.updated_bouncer_pin_shapes)
    @JvmField
    val pinDotAvd = c(old = R.drawable.pin_dot_avd, new = R.drawable.bouncer_shape_outline)
    @JvmField
    val pinDeleteAvd = c(old = R.drawable.pin_dot_delete_avd, new = R.drawable.bouncer_shape_delete)

    object Color {
        @JvmField val hintDot = colors.materialColorOnSurfaceVariant
        @JvmField val shape = colors.materialColorPrimary
        @JvmField val digit = c(old = NUM_PAD_KEY, new = colors.materialColorOnSurface)
        @JvmField
        val digitPressed = c(old = NUM_PAD_PRESSED, new = colors.materialColorOnPrimaryContainer)
        @JvmField
        val digitBg = c(old = NUM_PAD_BACKGROUND, colors.materialColorSurfaceContainerHigh)
        @JvmField
        val bgPressed = c(old = NUM_PAD_BACKGROUND_PRESSED, colors.materialColorPrimaryContainer)
        @JvmField
        val actionWithAutoConfirm = c(old = NUM_PAD_KEY, new = colors.materialColorOnSurface)
        @JvmField val action = c(old = NUM_PAD_BUTTON, new = colors.materialColorOnSecondary)
        @JvmField
        val actionBg = c(old = colors.materialColorSecondaryFixedDim, colors.materialColorSecondary)
    }

    object Animation {
        @JvmField val expansionDuration = c(old = 100, new = 33)
        @JvmField val expansionColorDuration = c(old = 50, new = expansionDuration)
        @JvmField
        val expansionInterpolator = c(old = Interpolators.LINEAR, new = Interpolators.LINEAR)!!

        @JvmField val contractionDuration = c(old = 417, new = 300)
        @JvmField val contractionStartDelay = c(old = 33, new = 0)
        @JvmField
        val contractionRadiusInterpolator =
            c(old = Interpolators.FAST_OUT_SLOW_IN, new = Interpolators.STANDARD)!!
        @JvmField
        val contractionColorInterpolator =
            c(old = Interpolators.LINEAR, new = Interpolators.STANDARD)!!
    }
}
