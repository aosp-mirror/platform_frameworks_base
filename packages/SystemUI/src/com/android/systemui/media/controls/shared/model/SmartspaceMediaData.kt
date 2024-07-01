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
 * limitations under the License.
 */

package com.android.systemui.media.controls.shared.model

import android.app.smartspace.SmartspaceAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.text.TextUtils
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.InstanceId

@VisibleForTesting const val KEY_SMARTSPACE_APP_NAME = "KEY_SMARTSPACE_APP_NAME"

/** State of a Smartspace media recommendations view. */
data class SmartspaceMediaData(
    /** Unique id of a Smartspace media target. */
    val targetId: String = "INVALID",
    /** Indicates if the status is active. */
    val isActive: Boolean = false,
    /** Package name of the media recommendations' provider-app. */
    val packageName: String = "INVALID",
    /** Action to perform when the card is tapped. Also contains the target's extra info. */
    val cardAction: SmartspaceAction? = null,
    /** List of media recommendations. */
    val recommendations: List<SmartspaceAction> = emptyList(),
    /** Intent for the user's initiated dismissal. */
    val dismissIntent: Intent? = null,
    /** The timestamp in milliseconds that the card was generated */
    val headphoneConnectionTimeMillis: Long = 0L,
    /** Instance ID for [MediaUiEventLogger] */
    val instanceId: InstanceId? = null,
    /** The timestamp in milliseconds indicating when the card should be removed */
    val expiryTimeMs: Long = 0L,
    /** If recommendation card was visible to user, used for logging. */
    var isImpressed: Boolean = false,
) {
    /**
     * Indicates if all the data is valid.
     *
     * TODO(b/230333302): Make MediaControlPanel more flexible so that we can display fewer than
     *
     * ```
     *     [NUM_REQUIRED_RECOMMENDATIONS].
     * ```
     */
    fun isValid() = getValidRecommendations().size >= NUM_REQUIRED_RECOMMENDATIONS

    /** Returns the list of [recommendations] that have valid data. */
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
                "Fallback to full app name"
        )
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, /* flags= */ 0)
            packageManager.getApplicationLabel(applicationInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun getUid(context: Context): Int {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0 /* flags */).uid
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Fail to get media recommendation's app info", e)
            Process.INVALID_UID
        }
    }
}

/** Key to indicate whether this card should be used to re-show recent media */
const val EXTRA_KEY_TRIGGER_RESUME = "SHOULD_TRIGGER_RESUME"
/** Key for extras [SmartspaceMediaData.cardAction] indicating why the card was sent */
const val EXTRA_KEY_TRIGGER_SOURCE = "MEDIA_RECOMMENDATION_TRIGGER_SOURCE"
/** Value for [EXTRA_KEY_TRIGGER_SOURCE] when the card is sent on headphone connection */
const val EXTRA_VALUE_TRIGGER_HEADPHONE = "HEADPHONE_CONNECTION"
/** Value for key [EXTRA_KEY_TRIGGER_SOURCE] when the card is sent as a regular update */
const val EXTRA_VALUE_TRIGGER_PERIODIC = "PERIODIC_TRIGGER"

const val NUM_REQUIRED_RECOMMENDATIONS = 3
private val TAG = SmartspaceMediaData::class.simpleName!!
