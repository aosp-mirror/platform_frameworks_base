/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.media.taptotransfer.common

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * A superclass chip state that will be subclassed by the sender chip and receiver chip.
 *
 * @property appPackageName the package name of the app playing the media. Will be used to fetch the
 *   app icon and app name.
 */
open class MediaTttChipState(
    internal val appPackageName: String?,
) {
    open fun getAppIcon(context: Context): Drawable? {
        appPackageName ?: return null
        return try {
            context.packageManager.getApplicationIcon(appPackageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Cannot find icon for package $appPackageName", e)
            null
        }
    }

    /** Returns the name of the app playing the media or null if we can't find it. */
    open fun getAppName(context: Context): String? {
        appPackageName ?: return null
        return try {
            context.packageManager.getApplicationInfo(
                appPackageName, PackageManager.ApplicationInfoFlags.of(0)
            ).loadLabel(context.packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Cannot find name for package $appPackageName", e)
            null
        }
    }

    /**
     * Returns the amount of time this chip should display on the screen before it times out and
     * disappears. [MediaTttChipControllerCommon] will ensure that the timeout resets each time we
     * receive a new state.
     */
    open fun getTimeoutMs(): Long = DEFAULT_TIMEOUT_MILLIS
}

private const val DEFAULT_TIMEOUT_MILLIS = 3000L
private val TAG = MediaTttChipState::class.simpleName!!
