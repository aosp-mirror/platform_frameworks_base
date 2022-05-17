/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.media

import android.app.smartspace.SmartspaceAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.TextUtils
import android.util.Log
import com.android.internal.logging.InstanceId
import com.android.systemui.media.MediaControlPanel.KEY_SMARTSPACE_APP_NAME

/** State of a Smartspace media recommendations view. */
data class SmartspaceMediaData(
    /**
     * Unique id of a Smartspace media target.
     */
    val targetId: String,
    /**
     * Indicates if the status is active.
     */
    val isActive: Boolean,
    /**
     * Package name of the media recommendations' provider-app.
     */
    val packageName: String,
    /**
     * Action to perform when the card is tapped. Also contains the target's extra info.
     */
    val cardAction: SmartspaceAction?,
    /**
     * List of media recommendations.
     */
    val recommendations: List<SmartspaceAction>,
    /**
     * Intent for the user's initiated dismissal.
     */
    val dismissIntent: Intent?,
    /**
     * The timestamp in milliseconds that headphone is connected.
     */
    val headphoneConnectionTimeMillis: Long,
    /**
     * Instance ID for [MediaUiEventLogger]
     */
    val instanceId: InstanceId
) {
    /**
     * Indicates if all the data is valid.
     *
     * TODO(b/230333302): Make MediaControlPanel more flexible so that we can display fewer than
     *     [NUM_REQUIRED_RECOMMENDATIONS].
     */
    fun isValid() = getValidRecommendations().size >= NUM_REQUIRED_RECOMMENDATIONS

    /**
     * Returns the list of [recommendations] that have valid data.
     */
    fun getValidRecommendations() = recommendations.filter { it.icon != null }

    /** Returns the upstream app name if available. */
    fun getAppName(context: Context): CharSequence? {
        val nameFromAction = cardAction?.intent?.extras?.getString(KEY_SMARTSPACE_APP_NAME)
        if (!TextUtils.isEmpty(nameFromAction)) {
            return nameFromAction
        }

        val packageManager = context.packageManager
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            val launchActivity = it.resolveActivityInfo(packageManager, 0)
            return launchActivity.loadLabel(packageManager)
        }

        Log.w(
            TAG,
            "Package $packageName does not have a main launcher activity. " +
                    "Fallback to full app name")
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName,  /* flags= */ 0)
            packageManager.getApplicationLabel(applicationInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}

const val NUM_REQUIRED_RECOMMENDATIONS = 3
private val TAG = SmartspaceMediaData::class.simpleName!!
