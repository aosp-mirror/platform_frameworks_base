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

package com.android.systemui.scene.shared.model

import com.android.compose.animation.scene.OverlayKey

/**
 * Keys of all known overlays.
 *
 * PLEASE KEEP THE KEYS SORTED ALPHABETICALLY.
 */
object Overlays {
    /**
     * The notifications shade overlay primarily shows a scrollable list of notifications.
     *
     * It's used only in the dual shade configuration, where there are two separate shades: one for
     * notifications (this overlay) and another for [QuickSettingsShade].
     *
     * It's not used in the single/accordion configuration (swipe down once to reveal the shade,
     * swipe down again the to expand quick settings) or in the "split" shade configuration (on
     * large screens or unfolded foldables, where notifications and quick settings are shown
     * side-by-side in their own columns).
     */
    @JvmField val NotificationsShade = OverlayKey("notifications_shade")

    /**
     * The quick settings shade overlay shows the quick settings tiles UI.
     *
     * It's used only in the dual shade configuration, where there are two separate shades: one for
     * quick settings (this overlay) and another for [NotificationsShade].
     *
     * It's not used in the single/accordion configuration (swipe down once to reveal the shade,
     * swipe down again the to expand quick settings) or in the "split" shade configuration (on
     * large screens or unfolded foldables, where notifications and quick settings are shown
     * side-by-side in their own columns).
     */
    @JvmField val QuickSettingsShade = OverlayKey("quick_settings_shade")
}
