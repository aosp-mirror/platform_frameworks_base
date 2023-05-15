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

/** Keys of all known scenes. */
sealed class SceneKey(
    private val loggingName: String,
) {
    /**
     * The bouncer is the scene that displays authentication challenges like PIN, password, or
     * pattern.
     */
    object Bouncer : SceneKey("bouncer")

    /**
     * "Gone" is not a real scene but rather the absence of scenes when we want to skip showing any
     * content from the scene framework.
     */
    object Gone : SceneKey("gone")

    /** The lock screen is the scene that shows when the device is locked. */
    object LockScreen : SceneKey("lockscreen")

    /**
     * The shade is the scene whose primary purpose is to show a scrollable list of notifications.
     */
    object Shade : SceneKey("shade")

    /** The quick settings scene shows the quick setting tiles. */
    object QuickSettings : SceneKey("quick_settings")

    override fun toString(): String {
        return loggingName
    }
}
