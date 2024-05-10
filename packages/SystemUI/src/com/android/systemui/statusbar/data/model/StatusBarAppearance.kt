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

import com.android.internal.view.AppearanceRegion
import com.android.systemui.statusbar.phone.BoundsPair

/** Keeps track of various parameters coordinating the appearance of the status bar. */
data class StatusBarAppearance(
    /** The current mode of the status bar. */
    val mode: StatusBarMode,
    /** The current bounds of the status bar. */
    val bounds: BoundsPair,
    /**
     * A list of appearance regions for the appearance of the status bar background. Used to
     * determine the correct coloring of status bar icons to ensure contrast. See
     * [com.android.systemui.statusbar.phone.LightBarController].
     */
    val appearanceRegions: List<AppearanceRegion>,
    /**
     * The navigation bar color as set by
     * [com.android.systemui.statusbar.CommandQueue.onSystemBarAttributesChanged].
     *
     * TODO(b/277764509): This likely belongs in a "NavigationBarAppearance"-type class, not a
     *   status bar class.
     */
    val navbarColorManagedByIme: Boolean,
)
