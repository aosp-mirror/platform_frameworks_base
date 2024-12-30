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

package com.android.systemui.qs.shared

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Provides the cached package name of the default Settings application.
 *
 * This repository retrieves and stores the package name to avoid repeated lookups. The package name
 * is retrieved in a background thread when the `init()` method is called.
 */
@SysUISingleton
@Suppress("ShadeDisplayAwareContextChecker")
class QSSettingsPackageRepository
@Inject
constructor(
    private val context: Context,
    @Background private val backgroundScope: CoroutineScope,
    private val userRepository: UserRepository,
) {
    private var settingsPackageName: String? = null

    /**
     * Initializes the repository by determining and caching the package name of the Settings app.
     */
    fun init() {
        backgroundScope.launch {
            val mainUserId = userRepository.mainUserId
            val mainUserContext =
                context.createContextAsUser(UserHandle.of(mainUserId), /* flags */ 0)
            val pm = mainUserContext.packageManager
            settingsPackageName =
                pm.queryIntentActivities(
                        Intent(Settings.ACTION_SETTINGS),
                        PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY,
                    )
                    .firstOrNull()
                    ?.activityInfo
                    ?.packageName ?: DEFAULT_SETTINGS_PACKAGE_NAME
        }
    }

    /**
     * Returns the cached package name of the Settings app.
     *
     * If the package name has not been initialized yet, this method will return the default
     * Settings package name.
     *
     * @return The package name of the Settings app.
     */
    fun getSettingsPackageName(): String {
        return settingsPackageName ?: DEFAULT_SETTINGS_PACKAGE_NAME
    }

    companion object {
        private const val DEFAULT_SETTINGS_PACKAGE_NAME = "com.android.settings"
    }
}
