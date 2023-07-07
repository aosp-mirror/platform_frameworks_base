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
 *
 */

package com.android.systemui.controls.panels

import android.content.Context
import android.content.SharedPreferences
import com.android.systemui.R
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import javax.inject.Inject

class AuthorizedPanelsRepositoryImpl
@Inject
constructor(
    private val context: Context,
    private val userFileManager: UserFileManager,
    private val userTracker: UserTracker,
    private val featureFlags: FeatureFlags,
) : AuthorizedPanelsRepository {

    override fun getAuthorizedPanels(): Set<String> {
        return getAuthorizedPanelsInternal(instantiateSharedPrefs())
    }

    override fun getPreferredPackages(): Set<String> =
        context.resources.getStringArray(R.array.config_controlsPreferredPackages).toSet()

    override fun addAuthorizedPanels(packageNames: Set<String>) {
        addAuthorizedPanelsInternal(instantiateSharedPrefs(), packageNames)
    }

    override fun removeAuthorizedPanels(packageNames: Set<String>) {
        with(instantiateSharedPrefs()) {
            val currentSet = getAuthorizedPanelsInternal(this)
            edit().putStringSet(KEY, currentSet - packageNames).apply()
        }
    }

    private fun getAuthorizedPanelsInternal(sharedPreferences: SharedPreferences): Set<String> {
        return sharedPreferences.getStringSet(KEY, emptySet())!!
    }

    private fun addAuthorizedPanelsInternal(
        sharedPreferences: SharedPreferences,
        packageNames: Set<String>
    ) {
        val currentSet = getAuthorizedPanelsInternal(sharedPreferences)
        sharedPreferences.edit().putStringSet(KEY, currentSet + packageNames).apply()
    }

    private fun instantiateSharedPrefs(): SharedPreferences {
        val sharedPref =
            userFileManager.getSharedPreferences(
                DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                Context.MODE_PRIVATE,
                userTracker.userId,
            )

        // We should add default packages in two cases:
        // 1) We've never run this
        // 2) APP_PANELS_REMOVE_APPS_ALLOWED got disabled after user removed all apps
        val needToSetup =
            if (featureFlags.isEnabled(Flags.APP_PANELS_REMOVE_APPS_ALLOWED)) {
                sharedPref.getStringSet(KEY, null) == null
            } else {
                // There might be an empty set that need to be overridden after the feature has been
                // turned off after being turned on
                sharedPref.getStringSet(KEY, null).isNullOrEmpty()
            }
        if (needToSetup) {
            sharedPref.edit().putStringSet(KEY, getPreferredPackages()).apply()
        }
        return sharedPref
    }

    companion object {
        private const val KEY = "authorized_panels"
    }
}
