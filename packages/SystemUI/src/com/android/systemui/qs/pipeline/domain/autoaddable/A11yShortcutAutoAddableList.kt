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

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.view.accessibility.Flags
import com.android.internal.accessibility.AccessibilityShortcutController
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.ColorCorrectionTile
import com.android.systemui.qs.tiles.ColorInversionTile
import com.android.systemui.qs.tiles.HearingDevicesTile
import com.android.systemui.qs.tiles.OneHandedModeTile
import com.android.systemui.qs.tiles.ReduceBrightColorsTile

object A11yShortcutAutoAddableList {

    /**
     * Generate a collection of [A11yShortcutAutoAddable] for the framework tiles related to
     * accessibility features with shortcut options
     */
    fun getA11yShortcutAutoAddables(factory: A11yShortcutAutoAddable.Factory): Set<AutoAddable> {
        return if (Flags.a11yQsShortcut()) {
            setOf(
                factory.create(
                    TileSpec.create(ColorCorrectionTile.TILE_SPEC),
                    AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME
                ),
                factory.create(
                    TileSpec.create(ColorInversionTile.TILE_SPEC),
                    AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME
                ),
                factory.create(
                    TileSpec.create(OneHandedModeTile.TILE_SPEC),
                    AccessibilityShortcutController.ONE_HANDED_COMPONENT_NAME
                ),
                factory.create(
                    TileSpec.create(ReduceBrightColorsTile.TILE_SPEC),
                    AccessibilityShortcutController.REDUCE_BRIGHT_COLORS_COMPONENT_NAME
                ),
                factory.create(
                    TileSpec.create(HearingDevicesTile.TILE_SPEC),
                    AccessibilityShortcutController.ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME
                )
            )
        } else {
            emptySet()
        }
    }
}
