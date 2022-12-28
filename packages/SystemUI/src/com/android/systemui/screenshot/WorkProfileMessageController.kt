/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.android.systemui.R
import javax.inject.Inject

/**
 * Handles all the non-UI portions of the work profile first run:
 * - Track whether the user has already dismissed it.
 * - Load the proper icon and app name.
 */
class WorkProfileMessageController
@Inject
constructor(
    private val context: Context,
    private val userManager: UserManager,
    private val packageManager: PackageManager,
) {

    /**
     * Determine if a message should be shown to the user, send message details to messageDisplay if
     * appropriate.
     */
    fun onScreenshotTaken(userHandle: UserHandle, messageDisplay: WorkProfileMessageDisplay) {
        if (userManager.isManagedProfile(userHandle.identifier) && !messageAlreadyDismissed()) {
            var badgedIcon: Drawable? = null
            var label: CharSequence? = null
            val fileManager = fileManagerComponentName()
            try {
                val info =
                    packageManager.getActivityInfo(
                        fileManager,
                        PackageManager.ComponentInfoFlags.of(0)
                    )
                val icon = packageManager.getActivityIcon(fileManager)
                badgedIcon = packageManager.getUserBadgedIcon(icon, userHandle)
                label = info.loadLabel(packageManager)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Component $fileManager not found")
            }

            // If label wasn't loaded, use a default
            val badgedLabel =
                packageManager.getUserBadgedLabel(label ?: defaultFileAppName(), userHandle)

            messageDisplay.showWorkProfileMessage(badgedLabel, badgedIcon) { onMessageDismissed() }
        }
    }

    private fun messageAlreadyDismissed(): Boolean {
        return sharedPreference().getBoolean(PREFERENCE_KEY, false)
    }

    private fun onMessageDismissed() {
        val editor = sharedPreference().edit()
        editor.putBoolean(PREFERENCE_KEY, true)
        editor.apply()
    }

    private fun sharedPreference() =
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun fileManagerComponentName() =
        ComponentName.unflattenFromString(
            context.getString(R.string.config_sceenshotWorkProfileFilesApp)
        )

    private fun defaultFileAppName() = context.getString(R.string.screenshot_default_files_app_name)

    /** UI that can show work profile messages (ScreenshotView in practice) */
    interface WorkProfileMessageDisplay {
        /**
         * Show the given message and icon, calling onDismiss if the user explicitly dismisses the
         * message.
         */
        fun showWorkProfileMessage(text: CharSequence, icon: Drawable?, onDismiss: Runnable)
    }

    companion object {
        const val TAG = "WorkProfileMessageCtrl"
        const val SHARED_PREFERENCES_NAME = "com.android.systemui.screenshot"
        const val PREFERENCE_KEY = "work_profile_first_run"
    }
}
