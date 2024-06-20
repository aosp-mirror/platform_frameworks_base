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
import android.os.UserHandle
import com.android.systemui.res.R
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.util.kotlin.SharedPreferencesExt.observe
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AuthorizedPanelsRepositoryImpl
@Inject
constructor(
    private val context: Context,
    private val userFileManager: UserFileManager,
    private val userTracker: UserTracker,
) : AuthorizedPanelsRepository {

    override fun observeAuthorizedPanels(user: UserHandle): Flow<Set<String>> {
        val prefs = instantiateSharedPrefs(user)
        return prefs.observe().emitOnStart().map { getAuthorizedPanelsInternal(prefs) }
    }

    override fun getAuthorizedPanels(): Set<String> {
        return getAuthorizedPanelsInternal(instantiateSharedPrefs(userTracker.userHandle))
    }

    override fun getPreferredPackages(): Set<String> =
        context.resources.getStringArray(R.array.config_controlsPreferredPackages).toSet()

    override fun addAuthorizedPanels(packageNames: Set<String>) {
        addAuthorizedPanelsInternal(instantiateSharedPrefs(userTracker.userHandle), packageNames)
    }

    override fun removeAuthorizedPanels(packageNames: Set<String>) {
        with(instantiateSharedPrefs(userTracker.userHandle)) {
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

    private fun instantiateSharedPrefs(user: UserHandle): SharedPreferences {
        val sharedPref =
            userFileManager.getSharedPreferences(
                DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                Context.MODE_PRIVATE,
                user.identifier,
            )

        // We should add default packages when we've never run this
        val needToSetup = sharedPref.getStringSet(KEY, null) == null
        if (needToSetup) {
            sharedPref.edit().putStringSet(KEY, getPreferredPackages()).apply()
        }
        return sharedPref
    }

    companion object {
        private const val KEY = "authorized_panels"
    }
}
