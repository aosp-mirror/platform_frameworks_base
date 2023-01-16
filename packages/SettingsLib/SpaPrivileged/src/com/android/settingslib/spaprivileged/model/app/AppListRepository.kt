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
import com.android.internal.R
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking

/**
 * The config used to load the App List.
 */
data class AppListConfig(
    val userId: Int,
    val showInstantApps: Boolean,
)

/**
 * The repository to load the App List data.
 */
internal interface AppListRepository {
    /** Loads the list of [ApplicationInfo]. */
    suspend fun loadApps(config: AppListConfig): List<ApplicationInfo>

    /** Gets the flow of predicate that could used to filter system app. */
    fun showSystemPredicate(
        userIdFlow: Flow<Int>,
        showSystemFlow: Flow<Boolean>,
    ): Flow<(app: ApplicationInfo) -> Boolean>

    /** Gets the system app package names. */
    fun getSystemPackageNamesBlocking(config: AppListConfig): Set<String>
}

/**
 * Util for app list repository.
 */
object AppListRepositoryUtil {
    /** Gets the system app package names. */
    @JvmStatic
    fun getSystemPackageNames(context: Context, config: AppListConfig): Set<String> {
        return AppListRepositoryImpl(context).getSystemPackageNamesBlocking(config)
    }
}

internal class AppListRepositoryImpl(private val context: Context) : AppListRepository {
    private val packageManager = context.packageManager

    override suspend fun loadApps(config: AppListConfig): List<ApplicationInfo> = coroutineScope {
        val hiddenSystemModulesDeferred = async {
            packageManager.getInstalledModules(0)
                .filter { it.isHidden }
                .map { it.packageName }
                .toSet()
        }
        val hideWhenDisabledPackagesDeferred = async {
            context.resources.getStringArray(R.array.config_hideWhenDisabled_packageNames)
        }
        val flags = PackageManager.ApplicationInfoFlags.of(
            (PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS).toLong()
        )
        val installedApplicationsAsUser =
            packageManager.getInstalledApplicationsAsUser(flags, config.userId)

        val hiddenSystemModules = hiddenSystemModulesDeferred.await()
        val hideWhenDisabledPackages = hideWhenDisabledPackagesDeferred.await()
        installedApplicationsAsUser.filter { app ->
            app.isInAppList(config.showInstantApps, hiddenSystemModules, hideWhenDisabledPackages)
        }
    }

    override fun showSystemPredicate(
        userIdFlow: Flow<Int>,
        showSystemFlow: Flow<Boolean>,
    ): Flow<(app: ApplicationInfo) -> Boolean> =
        userIdFlow.combine(showSystemFlow, ::showSystemPredicate)

    override fun getSystemPackageNamesBlocking(config: AppListConfig) = runBlocking {
        getSystemPackageNames(config)
    }

    private suspend fun getSystemPackageNames(config: AppListConfig): Set<String> =
            coroutineScope {
                val loadAppsDeferred = async { loadApps(config) }
                val homeOrLauncherPackages = loadHomeOrLauncherPackages(config.userId)
                val showSystemPredicate =
                        { app: ApplicationInfo -> isSystemApp(app, homeOrLauncherPackages) }
                loadAppsDeferred.await().filter(showSystemPredicate).map { it.packageName }.toSet()
            }

    private suspend fun showSystemPredicate(
        userId: Int,
        showSystem: Boolean,
    ): (app: ApplicationInfo) -> Boolean {
        if (showSystem) return { true }
        val homeOrLauncherPackages = loadHomeOrLauncherPackages(userId)
        return { app -> !isSystemApp(app, homeOrLauncherPackages) }
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

    private fun isSystemApp(app: ApplicationInfo, homeOrLauncherPackages: Set<String>): Boolean {
        return !app.isUpdatedSystemApp && app.isSystemApp &&
            !(app.packageName in homeOrLauncherPackages)
    }

    companion object {
        private fun ApplicationInfo.isInAppList(
            showInstantApps: Boolean,
            hiddenSystemModules: Set<String>,
            hideWhenDisabledPackages: Array<String>,
        ) = when {
            !showInstantApps && isInstantApp -> false
            packageName in hiddenSystemModules -> false
            packageName in hideWhenDisabledPackages -> enabled && !isDisabledUntilUsed
            enabled -> true
            else -> enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }
    }
}
