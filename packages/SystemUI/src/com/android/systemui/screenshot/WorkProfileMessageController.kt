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
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.R
import javax.inject.Inject

/**
 * Handles work profile first run, determining whether a first run UI should be shown and populating
 * that UI if needed.
 */
class WorkProfileMessageController
@Inject
constructor(
    private val context: Context,
    private val userManager: UserManager,
    private val packageManager: PackageManager,
) {

    /**
     * @return a populated WorkProfileFirstRunData object if a work profile first run message should
     *   be shown
     */
    fun onScreenshotTaken(userHandle: UserHandle?): WorkProfileFirstRunData? {
        if (userHandle == null) return null

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
            return WorkProfileFirstRunData(label ?: defaultFileAppName(), badgedIcon)
        }
        return null
    }

    /**
     * Use the provided WorkProfileFirstRunData to populate the work profile first run UI in the
     * given view.
     */
    fun populateView(view: ViewGroup, data: WorkProfileFirstRunData, animateOut: () -> Unit) {
        if (data.icon != null) {
            // Replace the default icon if one is provided.
            val imageView: ImageView = view.requireViewById<ImageView>(R.id.screenshot_message_icon)
            imageView.setImageDrawable(data.icon)
        }
        val messageContent = view.requireViewById<TextView>(R.id.screenshot_message_content)
        messageContent.text =
            view.context.getString(R.string.screenshot_work_profile_notification, data.appName)
        view.requireViewById<View>(R.id.message_dismiss_button).setOnClickListener {
            animateOut()
            onMessageDismissed()
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

    data class WorkProfileFirstRunData constructor(val appName: CharSequence, val icon: Drawable?)

    companion object {
        const val TAG = "WorkProfileMessageCtrl"
        const val SHARED_PREFERENCES_NAME = "com.android.systemui.screenshot"
        const val PREFERENCE_KEY = "work_profile_first_run"
    }
}
