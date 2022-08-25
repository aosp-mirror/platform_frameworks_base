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

package com.android.settingslib.spaprivileged.model.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class AppsRepository(context: Context) {
    private val packageManager = context.packageManager

    fun loadApps(userInfoFlow: Flow<UserInfo>): Flow<List<ApplicationInfo>> = userInfoFlow
        .map { loadApps(it) }
        .flowOn(Dispatchers.Default)

    private suspend fun loadApps(userInfo: UserInfo): List<ApplicationInfo> {
        return coroutineScope {
            val hiddenSystemModulesDeferred = async {
                packageManager.getInstalledModules(0)
                    .filter { it.isHidden }
                    .map { it.packageName }
                    .toSet()
            }
            val flags = PackageManager.ApplicationInfoFlags.of(
                ((if (userInfo.isAdmin) PackageManager.MATCH_ANY_USER else 0) or
                    PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS).toLong()
            )
            val installedApplicationsAsUser =
                packageManager.getInstalledApplicationsAsUser(flags, userInfo.id)

            val hiddenSystemModules = hiddenSystemModulesDeferred.await()
            installedApplicationsAsUser.filter { app ->
                app.isInAppList(hiddenSystemModules)
            }
        }
    }

    fun showSystemPredicate(
        userIdFlow: Flow<Int>,
        showSystemFlow: Flow<Boolean>,
    ): Flow<(app: ApplicationInfo) -> Boolean> =
        userIdFlow.combine(showSystemFlow) { userId, showSystem ->
            showSystemPredicate(userId, showSystem)
        }

    private suspend fun showSystemPredicate(
        userId: Int,
        showSystem: Boolean,
    ): (app: ApplicationInfo) -> Boolean {
        if (showSystem) return { true }
        val homeOrLauncherPackages = loadHomeOrLauncherPackages(userId)
        return { app ->
            app.isUpdatedSystemApp || !app.isSystemApp || app.packageName in homeOrLauncherPackages
        }
    }

    private suspend fun loadHomeOrLauncherPackages(userId: Int): Set<String> {
        val launchIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        // If we do not specify MATCH_DIRECT_BOOT_AWARE or MATCH_DIRECT_BOOT_UNAWARE, system will
        // derive and update the flags according to the user's lock state. When the user is locked,
        // components with ComponentInfo#directBootAware == false will be filtered. We should
        // explicitly include both direct boot aware and unaware component here.
        val flags = PackageManager.ResolveInfoFlags.of(
            (PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DIRECT_BOOT_AWARE or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong()
        )
        return coroutineScope {
            val launcherActivities = async {
                packageManager.queryIntentActivitiesAsUser(launchIntent, flags, userId)
            }
            val homeActivities = ArrayList<ResolveInfo>()
            packageManager.getHomeActivities(homeActivities)
            (launcherActivities.await() + homeActivities)
                .map { it.activityInfo.packageName }
                .toSet()
        }
    }

    companion object {
        private fun ApplicationInfo.isInAppList(hiddenSystemModules: Set<String>) =
            when {
                packageName in hiddenSystemModules -> false
                enabled -> true
                enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> true
                else -> false
            }
    }
}
