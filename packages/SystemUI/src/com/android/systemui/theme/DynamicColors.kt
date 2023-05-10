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
import com.android.systemui.monet.dynamiccolor.DynamicColor
import com.android.systemui.monet.dynamiccolor.MaterialDynamicColors as MDC

class DynamicColors {
    companion object {
        @JvmField
        val ALL_DYNAMIC_COLORS_MAPPED: List<Pair<String, DynamicColor>> =
            arrayListOf(
                Pair.create("primary_container", MDC.primaryContainer()),
                Pair.create("on_primary_container", MDC.onPrimaryContainer()),
                Pair.create("primary", MDC.primary()),
                Pair.create("on_primary", MDC.onPrimary()),
                Pair.create("secondary_container", MDC.secondaryContainer()),
                Pair.create("on_secondary_container", MDC.onSecondaryContainer()),
                Pair.create("secondary", MDC.secondary()),
                Pair.create("on_secondary", MDC.onSecondary()),
                Pair.create("tertiary_container", MDC.tertiaryContainer()),
                Pair.create("on_tertiary_container", MDC.onTertiaryContainer()),
                Pair.create("tertiary", MDC.tertiary()),
                Pair.create("on_tertiary", MDC.onTertiary()),
                Pair.create("background", MDC.background()),
                Pair.create("on_background", MDC.onBackground()),
                Pair.create("surface", MDC.surface()),
                Pair.create("on_surface", MDC.onSurface()),
                Pair.create("surface_container_low", MDC.surfaceContainerLow()),
                Pair.create("surface_container_lowest", MDC.surfaceContainerLowest()),
                Pair.create("surface_container", MDC.surfaceContainer()),
                Pair.create("surface_container_high", MDC.surfaceContainerHigh()),
                Pair.create("surface_container_highest", MDC.surfaceContainerHighest()),
                Pair.create("surface_bright", MDC.surfaceBright()),
                Pair.create("surface_dim", MDC.surfaceDim()),
                Pair.create("surface_variant", MDC.surfaceVariant()),
                Pair.create("on_surface_variant", MDC.onSurfaceVariant()),
                Pair.create("outline", MDC.outline()),
                Pair.create("outline_variant", MDC.outlineVariant()),
                Pair.create("error", MDC.error()),
                Pair.create("on_error", MDC.onError()),
                Pair.create("error_container", MDC.errorContainer()),
                Pair.create("on_error_container", MDC.onErrorContainer()),
                Pair.create("control_activated", MDC.controlActivated()),
                Pair.create("control_normal", MDC.controlNormal()),
                Pair.create("control_highlight", MDC.controlHighlight()),
                Pair.create("text_primary_inverse", MDC.textPrimaryInverse()),
                Pair.create(
                    "text_secondary_and_tertiary_inverse",
                    MDC.textSecondaryAndTertiaryInverse()
                ),
                Pair.create(
                    "text_primary_inverse_disable_only",
                    MDC.textPrimaryInverseDisableOnly()
                ),
                Pair.create(
                    "text_secondary_and_tertiary_inverse_disabled",
                    MDC.textSecondaryAndTertiaryInverseDisabled()
                ),
                Pair.create("text_hint_inverse", MDC.textHintInverse()),
                Pair.create("palette_key_color_primary", MDC.primaryPaletteKeyColor()),
                Pair.create("palette_key_color_secondary", MDC.secondaryPaletteKeyColor()),
                Pair.create("palette_key_color_tertiary", MDC.tertiaryPaletteKeyColor()),
                Pair.create("palette_key_color_neutral", MDC.neutralPaletteKeyColor()),
                Pair.create(
                    "palette_key_color_neutral_variant",
                    MDC.neutralVariantPaletteKeyColor()
                ),
            )

        @JvmField
        val FIXED_COLORS_MAPPED: List<Pair<String, DynamicColor>> =
            arrayListOf(
                Pair.create("primary_fixed", MDC.primaryFixed()),
                Pair.create("primary_fixed_dim", MDC.primaryFixedDim()),
                Pair.create("on_primary_fixed", MDC.onPrimaryFixed()),
                Pair.create("on_primary_fixed_variant", MDC.onPrimaryFixedVariant()),
                Pair.create("secondary_fixed", MDC.secondaryFixed()),
                Pair.create("secondary_fixed_dim", MDC.secondaryFixedDim()),
                Pair.create("on_secondary_fixed", MDC.onSecondaryFixed()),
                Pair.create("on_secondary_fixed_variant", MDC.onSecondaryFixedVariant()),
                Pair.create("tertiary_fixed", MDC.tertiaryFixed()),
                Pair.create("tertiary_fixed_dim", MDC.tertiaryFixedDim()),
                Pair.create("on_tertiary_fixed", MDC.onTertiaryFixed()),
                Pair.create("on_tertiary_fixed_variant", MDC.onTertiaryFixedVariant()),
            )
    }
}
