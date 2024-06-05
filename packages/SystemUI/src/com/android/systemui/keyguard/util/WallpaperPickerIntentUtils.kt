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

package com.android.systemui.keyguard.util

import android.content.Context
import android.content.Intent
import com.android.systemui.res.R

/** Provides function(s) to get intent for launching the Wallpaper Picker app. */
object WallpaperPickerIntentUtils {

    fun getIntent(context: Context, launchSource: String): Intent {
        return Intent(Intent.ACTION_SET_WALLPAPER).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context
                .getString(R.string.config_wallpaperPickerPackage)
                .takeIf { it.isNotEmpty() }
                ?.let { packageName -> setPackage(packageName) }
            putExtra(WALLPAPER_LAUNCH_SOURCE, launchSource)
        }
    }

    private const val WALLPAPER_LAUNCH_SOURCE = "com.android.wallpaper.LAUNCH_SOURCE"
    const val LAUNCH_SOURCE_KEYGUARD = "app_launched_keyguard"
}
