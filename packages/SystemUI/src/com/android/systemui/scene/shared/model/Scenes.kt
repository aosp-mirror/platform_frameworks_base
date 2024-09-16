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

package com.android.systemui.scene.shared.model

import com.android.compose.animation.scene.SceneKey

/**
 * Keys of all known scenes.
 *
 * PLEASE KEEP THE KEYS SORTED ALPHABETICALLY.
 */
object Scenes {
    /**
     * The bouncer is the scene that displays authentication challenges like PIN, password, or
     * pattern.
     */
    @JvmField val Bouncer = SceneKey("bouncer")

    /** The communal scene shows the glanceable hub when device is locked and docked. */
    @JvmField val Communal = SceneKey("communal")

    /**
     * "Gone" is not a real scene but rather the absence of scenes when we want to skip showing any
     * content from the scene framework.
     */
    @JvmField val Gone = SceneKey("gone")

    /** The lockscreen is the scene that shows when the device is locked. */
    @JvmField val Lockscreen = SceneKey("lockscreen")

    /**
     * The quick settings scene shows the quick setting tiles.
     *
     * This scene is used for single/accordion configuration (swipe down once to reveal the shade,
     * swipe down again the to expand quick settings).
     *
     * For the "split" shade configuration (on large screens or unfolded foldables, where
     * notifications and quick settings are shown side-by-side in their own columns), the [Shade]
     * scene is used.
     *
     * For the dual shade configuration, where there are two separate shades: one for notifications
     * and one for quick settings, the overlays `NotificationsShade` and `QuickSettingsShade` are
     * used respectively.
     */
    @JvmField val QuickSettings = SceneKey("quick_settings")

    /**
     * The shade is the scene that shows a scrollable list of notifications and the minimized
     * version of quick settings (AKA "quick quick settings" or "QQS").
     *
     * This scene is used for single/accordion configuration (swipe down once to reveal the shade,
     * swipe down again the to expand quick settings) and for the "split" shade configuration (on
     * large screens or unfolded foldables, where notifications and quick settings are shown
     * side-by-side in their own columns). For the dual shade configuration, where there are two
     * separate shades: one for notifications and one for quick settings, the overlays
     * `NotificationsShade` and `QuickSettingsShade` are used respectively.
     */
    @JvmField val Shade = SceneKey("shade")
}

/**
 * Keys of all known scene families. A scene family is an alias that is resolved to a specific scene
 * from [Scenes] dynamically.
 *
 * PLEASE KEEP THE KEYS SORTED ALPHABETICALLY.
 */
object SceneFamilies {
    /**
     * The "base" scene, from the user's perspective. This is generally Gone or Lockscreen,
     * depending on whether the device is unlocked and has been entered.
     */
    @JvmField val Home = SceneKey(debugName = "scene_family_home")
}
