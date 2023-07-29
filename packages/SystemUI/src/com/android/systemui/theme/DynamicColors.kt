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

package com.android.systemui.theme

import android.util.Pair
import com.google.ux.material.libmonet.dynamiccolor.DynamicColor
import com.google.ux.material.libmonet.dynamiccolor.MaterialDynamicColors

class DynamicColors {
    companion object {
        @JvmStatic
        fun allDynamicColorsMapped(isExtendedFidelity: Boolean): List<Pair<String, DynamicColor>> {
            val mdc = MaterialDynamicColors(isExtendedFidelity)
            return arrayListOf(
                Pair.create("primary_container", mdc.primaryContainer()),
                Pair.create("on_primary_container", mdc.onPrimaryContainer()),
                Pair.create("primary", mdc.primary()),
                Pair.create("on_primary", mdc.onPrimary()),
                Pair.create("secondary_container", mdc.secondaryContainer()),
                Pair.create("on_secondary_container", mdc.onSecondaryContainer()),
                Pair.create("secondary", mdc.secondary()),
                Pair.create("on_secondary", mdc.onSecondary()),
                Pair.create("tertiary_container", mdc.tertiaryContainer()),
                Pair.create("on_tertiary_container", mdc.onTertiaryContainer()),
                Pair.create("tertiary", mdc.tertiary()),
                Pair.create("on_tertiary", mdc.onTertiary()),
                Pair.create("background", mdc.background()),
                Pair.create("on_background", mdc.onBackground()),
                Pair.create("surface", mdc.surface()),
                Pair.create("on_surface", mdc.onSurface()),
                Pair.create("surface_container_low", mdc.surfaceContainerLow()),
                Pair.create("surface_container_lowest", mdc.surfaceContainerLowest()),
                Pair.create("surface_container", mdc.surfaceContainer()),
                Pair.create("surface_container_high", mdc.surfaceContainerHigh()),
                Pair.create("surface_container_highest", mdc.surfaceContainerHighest()),
                Pair.create("surface_bright", mdc.surfaceBright()),
                Pair.create("surface_dim", mdc.surfaceDim()),
                Pair.create("surface_variant", mdc.surfaceVariant()),
                Pair.create("on_surface_variant", mdc.onSurfaceVariant()),
                Pair.create("outline", mdc.outline()),
                Pair.create("outline_variant", mdc.outlineVariant()),
                Pair.create("error", mdc.error()),
                Pair.create("on_error", mdc.onError()),
                Pair.create("error_container", mdc.errorContainer()),
                Pair.create("on_error_container", mdc.onErrorContainer()),
                Pair.create("control_activated", mdc.controlActivated()),
                Pair.create("control_normal", mdc.controlNormal()),
                Pair.create("control_highlight", mdc.controlHighlight()),
                Pair.create("text_primary_inverse", mdc.textPrimaryInverse()),
                Pair.create(
                    "text_secondary_and_tertiary_inverse",
                    mdc.textSecondaryAndTertiaryInverse()
                ),
                Pair.create(
                    "text_primary_inverse_disable_only",
                    mdc.textPrimaryInverseDisableOnly()
                ),
                Pair.create(
                    "text_secondary_and_tertiary_inverse_disabled",
                    mdc.textSecondaryAndTertiaryInverseDisabled()
                ),
                Pair.create("text_hint_inverse", mdc.textHintInverse()),
                Pair.create("palette_key_color_primary", mdc.primaryPaletteKeyColor()),
                Pair.create("palette_key_color_secondary", mdc.secondaryPaletteKeyColor()),
                Pair.create("palette_key_color_tertiary", mdc.tertiaryPaletteKeyColor()),
                Pair.create("palette_key_color_neutral", mdc.neutralPaletteKeyColor()),
                Pair.create(
                    "palette_key_color_neutral_variant",
                    mdc.neutralVariantPaletteKeyColor()
                ),
            )
        }

        @JvmStatic
        fun getFixedColorsMapped(isExtendedFidelity: Boolean): List<Pair<String, DynamicColor>> {
            val mdc = MaterialDynamicColors(isExtendedFidelity)
            return arrayListOf(
                Pair.create("primary_fixed", mdc.primaryFixed()),
                Pair.create("primary_fixed_dim", mdc.primaryFixedDim()),
                Pair.create("on_primary_fixed", mdc.onPrimaryFixed()),
                Pair.create("on_primary_fixed_variant", mdc.onPrimaryFixedVariant()),
                Pair.create("secondary_fixed", mdc.secondaryFixed()),
                Pair.create("secondary_fixed_dim", mdc.secondaryFixedDim()),
                Pair.create("on_secondary_fixed", mdc.onSecondaryFixed()),
                Pair.create("on_secondary_fixed_variant", mdc.onSecondaryFixedVariant()),
                Pair.create("tertiary_fixed", mdc.tertiaryFixed()),
                Pair.create("tertiary_fixed_dim", mdc.tertiaryFixedDim()),
                Pair.create("on_tertiary_fixed", mdc.onTertiaryFixed()),
                Pair.create("on_tertiary_fixed_variant", mdc.onTertiaryFixedVariant()),
            )
        }
    }
}
