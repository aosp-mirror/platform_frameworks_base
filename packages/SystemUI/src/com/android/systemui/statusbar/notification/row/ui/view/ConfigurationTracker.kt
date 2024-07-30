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

package com.android.systemui.statusbar.notification.row.ui.view

import android.content.pm.ActivityInfo.CONFIG_ASSETS_PATHS
import android.content.pm.ActivityInfo.CONFIG_DENSITY
import android.content.pm.ActivityInfo.CONFIG_FONT_SCALE
import android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION
import android.content.pm.ActivityInfo.CONFIG_LOCALE
import android.content.pm.ActivityInfo.CONFIG_UI_MODE
import android.content.res.Configuration
import android.content.res.Resources

/**
 * Tracks the active configuration when constructed and returns (when queried) whether the
 * configuration has unhandled changes.
 */
class ConfigurationTracker(
    private val resources: Resources,
    private val unhandledConfigChanges: Int
) {
    private val initialConfig = Configuration(resources.configuration)

    constructor(
        resources: Resources,
        handlesDensityFontScale: Boolean = false,
        handlesTheme: Boolean = false,
        handlesLocaleAndLayout: Boolean = true,
    ) : this(
        resources,
        unhandledConfigChanges =
            (if (handlesDensityFontScale) 0 else CONFIG_DENSITY or CONFIG_FONT_SCALE) or
                (if (handlesTheme) 0 else CONFIG_ASSETS_PATHS or CONFIG_UI_MODE) or
                (if (handlesLocaleAndLayout) 0 else CONFIG_LOCALE or CONFIG_LAYOUT_DIRECTION)
    )

    /**
     * Whether the current configuration has unhandled changes relative to the initial configuration
     */
    fun hasUnhandledConfigChange(): Boolean =
        initialConfig.diff(resources.configuration) and unhandledConfigChanges != 0
}
