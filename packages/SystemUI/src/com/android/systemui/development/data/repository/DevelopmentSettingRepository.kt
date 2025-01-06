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

package com.android.systemui.development.data.repository

import android.content.pm.UserInfo
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@SysUISingleton
class DevelopmentSettingRepository
@Inject
constructor(
    private val globalSettings: GlobalSettings,
    private val userManager: UserManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    private val settingFlow = globalSettings.observerFlow(SETTING)

    /**
     * Indicates whether development settings is enabled for this user. The conditions are:
     * * Setting is enabled (defaults to true in eng builds)
     * * User is an admin
     * * User is not restricted from Debugging features.
     */
    fun isDevelopmentSettingEnabled(userInfo: UserInfo): Flow<Boolean> {
        return settingFlow
            .emitOnStart()
            .map { checkDevelopmentSettingEnabled(userInfo) }
            .flowOn(backgroundDispatcher)
    }

    private suspend fun checkDevelopmentSettingEnabled(userInfo: UserInfo): Boolean {
        val hasUserRestriction =
            withContext(backgroundDispatcher) {
                userManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_DEBUGGING_FEATURES,
                    userInfo.userHandle,
                )
            }
        val isSettingEnabled =
            withContext(backgroundDispatcher) {
                globalSettings.getInt(SETTING, DEFAULT_ENABLED) != 0
            }
        val isAdmin = userInfo.isAdmin
        return isAdmin && !hasUserRestriction && isSettingEnabled
    }

    private companion object {
        val DEFAULT_ENABLED = if (Build.TYPE == "eng") 1 else 0

        const val SETTING = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
    }
}
