/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.emergency

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.text.TextUtils
import android.util.Log
import dagger.Module
import dagger.Provides

import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R

/** Module for providing emergency gesture objects. */
@Module
object EmergencyGestureModule {

    val TAG: String = "EmergencyGestureModule"

    @Provides
    fun emergencyGestureIntentFactory(
        packageManager: PackageManager,
        @Main resources: Resources,
    ): EmergencyGestureIntentFactory {
        return object : EmergencyGestureIntentFactory {
            override fun invoke(action: String): Intent? {
                return getEmergencyActionIntent(packageManager, resources, action)
            }
        }
    }

    /**
     * Return the "best" Emergency action intent for a given action
     */
    private fun getEmergencyActionIntent(
        packageManager: PackageManager,
        @Main resources: Resources,
        action: String,
    ): Intent? {
        val emergencyIntent = Intent(action)
        val emergencyActivities = packageManager.queryIntentActivities(emergencyIntent,
                PackageManager.MATCH_SYSTEM_ONLY)
        val resolveInfo: ResolveInfo? = getTopEmergencySosInfo(emergencyActivities, resources)
        if (resolveInfo == null) {
            Log.wtf(TAG, "Couldn't find an app to process the emergency intent.")
            return null
        }
        emergencyIntent.setComponent(ComponentName(resolveInfo.activityInfo.packageName,
                resolveInfo.activityInfo.name))
        emergencyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return emergencyIntent
    }

    /**
     * Select and return the "best" ResolveInfo for Emergency SOS Activity.
     */
    private fun getTopEmergencySosInfo(
        emergencyActivities: List<ResolveInfo>,
        @Main resources: Resources,
    ): ResolveInfo? {
        // No matched activity.
        if (emergencyActivities.isEmpty()) {
            return null
        }

        // Of multiple matched Activities, give preference to the pre-set package name.
        val preferredAppPackageName =
                resources.getString(R.string.config_preferredEmergencySosPackage)

        // If there is no preferred app, then return first match.
        if (TextUtils.isEmpty(preferredAppPackageName)) {
            return emergencyActivities.get(0)
        }

        for (emergencyInfo: ResolveInfo in emergencyActivities) {
            // If activity is from the preferred app, use it.
            if (TextUtils.equals(emergencyInfo.activityInfo.packageName, preferredAppPackageName)) {
                return emergencyInfo
            }
        }
        // No matching activity: return first match
        return emergencyActivities.get(0)
    }

    /**
     * Creates an intent to launch the Emergency action. If no handler is present, returns `null`.
     */
    public interface EmergencyGestureIntentFactory {
        operator fun invoke(action: String): Intent?
    }
}
