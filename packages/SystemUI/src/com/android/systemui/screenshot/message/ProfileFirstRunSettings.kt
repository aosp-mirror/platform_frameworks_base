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

package com.android.systemui.screenshot.message

import android.content.Context
import javax.inject.Inject

/**
 * An interfaces for the settings related to the profile first run experience, storing a bit
 * indicating whether the user has already dismissed the message for the given profile.
 */
interface ProfileFirstRunSettings {
    /** @return true if the user has already dismissed the first run message for this profile. */
    fun messageAlreadyDismissed(profileType: ProfileMessageController.FirstRunProfile): Boolean
    /**
     * Update storage to reflect the fact that the user has dismissed a first run message for the
     * given profile.
     */
    fun onMessageDismissed(profileType: ProfileMessageController.FirstRunProfile)
}

class ProfileFirstRunSettingsImpl @Inject constructor(private val context: Context) :
    ProfileFirstRunSettings {

    override fun messageAlreadyDismissed(
        profileType: ProfileMessageController.FirstRunProfile
    ): Boolean {
        val preferenceKey = preferenceKey(profileType)
        return sharedPreference().getBoolean(preferenceKey, false)
    }

    override fun onMessageDismissed(profileType: ProfileMessageController.FirstRunProfile) {
        val preferenceKey = preferenceKey(profileType)
        val editor = sharedPreference().edit()
        editor.putBoolean(preferenceKey, true)
        editor.apply()
    }

    private fun preferenceKey(profileType: ProfileMessageController.FirstRunProfile): String {
        return when (profileType) {
            ProfileMessageController.FirstRunProfile.WORK -> WORK_PREFERENCE_KEY
            ProfileMessageController.FirstRunProfile.PRIVATE -> PRIVATE_PREFERENCE_KEY
        }
    }

    private fun sharedPreference() =
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

    companion object {
        const val SHARED_PREFERENCES_NAME = "com.android.systemui.screenshot"
        const val WORK_PREFERENCE_KEY = "work_profile_first_run"
        const val PRIVATE_PREFERENCE_KEY = "private_profile_first_run"
    }
}
