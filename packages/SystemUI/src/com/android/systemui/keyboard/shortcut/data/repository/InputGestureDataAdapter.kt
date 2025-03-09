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

package com.android.systemui.keyboard.shortcut.data.repository

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_MAIN
import android.content.Intent.CATEGORY_LAUNCHER
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
import android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE
import android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.drawable.Icon
import android.hardware.input.AppLaunchData
import android.hardware.input.AppLaunchData.CategoryData
import android.hardware.input.AppLaunchData.ComponentData
import android.hardware.input.AppLaunchData.RoleData
import android.hardware.input.InputGestureData
import android.hardware.input.InputGestureData.KeyTrigger
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION
import android.hardware.input.KeyGestureEvent.KeyGestureType
import android.util.Log
import com.android.internal.app.ResolverActivity
import com.android.systemui.keyboard.shortcut.data.model.InternalGroupsSource
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutGroup
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import javax.inject.Inject


class InputGestureDataAdapter
@Inject
constructor(
    private val userTracker: UserTracker,
    private val inputGestureMaps: InputGestureMaps,
    private val context: Context
) {
    private val userContext: Context
        get() = userTracker.createCurrentUserContext(userTracker.userContext)

    fun toInternalGroupSources(
        inputGestures: List<InputGestureData>
    ): List<InternalGroupsSource> {
        val ungroupedInternalGroupSources =
            inputGestures.mapNotNull { gestureData ->
                val keyTrigger = gestureData.trigger as KeyTrigger
                val keyGestureType = gestureData.action.keyGestureType()
                val appLaunchData: AppLaunchData? = gestureData.action.appLaunchData()
                fetchGroupLabelByGestureType(keyGestureType)?.let { groupLabel ->
                    toInternalKeyboardShortcutInfo(
                        keyGestureType,
                        keyTrigger,
                        appLaunchData
                    )?.let { internalKeyboardShortcutInfo ->
                        val group =
                            InternalKeyboardShortcutGroup(
                                label = groupLabel,
                                items = listOf(internalKeyboardShortcutInfo),
                            )

                        fetchShortcutCategoryTypeByGestureType(keyGestureType)?.let {
                            InternalGroupsSource(groups = listOf(group), type = it)
                        }
                    }
                }
            }

        return ungroupedInternalGroupSources
    }

    fun getKeyGestureTypeFromShortcutLabel(label: String): Int? {
        return inputGestureMaps.shortcutLabelToKeyGestureTypeMap[label]
    }

    private fun toInternalKeyboardShortcutInfo(
        keyGestureType: Int,
        keyTrigger: KeyTrigger,
        appLaunchData: AppLaunchData?,
    ): InternalKeyboardShortcutInfo? {
        fetchShortcutLabelByGestureType(keyGestureType, appLaunchData)?.let {
            return InternalKeyboardShortcutInfo(
                label = it,
                keycode = keyTrigger.keycode,
                modifiers = keyTrigger.modifierState,
                isCustomShortcut = true,
                icon = appLaunchData?.let { fetchShortcutIconByAppLaunchData(appLaunchData) }
            )
        }
        return null
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun fetchShortcutIconByAppLaunchData(
        appLaunchData: AppLaunchData
    ): Icon? {
        val intent = fetchIntentFromAppLaunchData(appLaunchData) ?: return null
        val resolvedActivity = resolveSingleMatchingActivityFrom(intent)

        return if (resolvedActivity == null) {
            null
        } else {
            Icon.createWithResource(context, resolvedActivity.iconResource)
        }
    }

    private fun fetchGroupLabelByGestureType(@KeyGestureType keyGestureType: Int): String? {
        inputGestureMaps.gestureToInternalKeyboardShortcutGroupLabelResIdMap[keyGestureType]?.let {
            return context.getString(it)
        } ?: return null
    }

    private fun fetchShortcutLabelByGestureType(
        @KeyGestureType keyGestureType: Int,
        appLaunchData: AppLaunchData?
    ): String? {
        inputGestureMaps.gestureToInternalKeyboardShortcutInfoLabelResIdMap[keyGestureType]?.let {
            return context.getString(it)
        }

        if (keyGestureType == KEY_GESTURE_TYPE_LAUNCH_APPLICATION) {
            return fetchShortcutLabelByAppLaunchData(appLaunchData!!)
        }

        return null
    }

    private fun fetchShortcutLabelByAppLaunchData(appLaunchData: AppLaunchData): String? {
        val intent = fetchIntentFromAppLaunchData(appLaunchData) ?: return null
        val resolvedActivity = resolveSingleMatchingActivityFrom(intent)

        return if (resolvedActivity == null) {
            getIntentCategoryLabel(intent.selector?.categories?.iterator()?.next())
        } else resolvedActivity.loadLabel(userContext.packageManager).toString()

    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun resolveSingleMatchingActivityFrom(intent: Intent): ActivityInfo? {
        val packageManager = userContext.packageManager
        val resolvedActivity = intent.resolveActivityInfo(
            packageManager,
            /* flags= */ MATCH_DEFAULT_ONLY
        ) ?: return null

        val matchesMultipleActivities =
            ResolverActivity::class.qualifiedName.equals(resolvedActivity.name)

        return if (matchesMultipleActivities) {
            return null
        } else resolvedActivity
    }

    private fun getIntentCategoryLabel(category: String?): String? {
        val categoryLabelRes = when (category.toString()) {
            Intent.CATEGORY_APP_BROWSER -> R.string.keyboard_shortcut_group_applications_browser
            Intent.CATEGORY_APP_CONTACTS -> R.string.keyboard_shortcut_group_applications_contacts
            Intent.CATEGORY_APP_EMAIL -> R.string.keyboard_shortcut_group_applications_email
            Intent.CATEGORY_APP_CALENDAR -> R.string.keyboard_shortcut_group_applications_calendar
            Intent.CATEGORY_APP_MAPS -> R.string.keyboard_shortcut_group_applications_maps
            Intent.CATEGORY_APP_MUSIC -> R.string.keyboard_shortcut_group_applications_music
            Intent.CATEGORY_APP_MESSAGING -> R.string.keyboard_shortcut_group_applications_sms
            Intent.CATEGORY_APP_CALCULATOR -> R.string.keyboard_shortcut_group_applications_calculator
            else -> {
                Log.w(TAG, ("No label for app category $category"))
                null
            }
        }

        return if (categoryLabelRes == null){
            return null
        } else {
            context.getString(categoryLabelRes)
        }
    }

    private fun fetchIntentFromAppLaunchData(appLaunchData: AppLaunchData): Intent? {
        return when (appLaunchData) {
            is CategoryData -> Intent.makeMainSelectorActivity(
                /* selectorAction= */ ACTION_MAIN,
                /* selectorCategory= */ appLaunchData.category
            )

            is RoleData -> getRoleLaunchIntent(appLaunchData.role)
            is ComponentData -> resolveComponentNameIntent(
                packageName = appLaunchData.packageName,
                className = appLaunchData.className
            )

            else -> null
        }
    }

    private fun resolveComponentNameIntent(packageName: String, className: String): Intent? {
        buildIntentFromComponentName(ComponentName(packageName, className))?.let { return it }
        buildIntentFromComponentName(ComponentName(
            userContext.packageManager.canonicalToCurrentPackageNames(arrayOf(packageName))[0],
            className
        ))?.let { return it }
        return null
    }

    private fun buildIntentFromComponentName(componentName: ComponentName): Intent? {
        try{
            val flags =
                MATCH_DIRECT_BOOT_UNAWARE or MATCH_DIRECT_BOOT_AWARE or MATCH_UNINSTALLED_PACKAGES
            // attempt to retrieve activity info to see if a NameNotFoundException is thrown.
            userContext.packageManager.getActivityInfo(componentName, flags)
        } catch (e: NameNotFoundException) {
            Log.w(
                TAG,
                "Unable to find activity info for componentName: $componentName"
            )
            return null
        }

        return Intent(ACTION_MAIN).apply {
            addCategory(CATEGORY_LAUNCHER)
            component = componentName
        }
    }

    @SuppressLint("NonInjectedService")
    private fun getRoleLaunchIntent(role: String): Intent? {
        val packageManager = userContext.packageManager
        val roleManager = userContext.getSystemService(RoleManager::class.java)!!
        if (roleManager.isRoleAvailable(role)) {
            roleManager.getDefaultApplication(role)?.let { rolePackage ->
                packageManager.getLaunchIntentForPackage(rolePackage)?.let { return it }
                    ?: Log.w(TAG, "No launch intent for role $role")
            } ?: Log.w(TAG, "No default application for role $role, user= ${userContext.user}")
        } else {
            Log.w(TAG, "Role $role is not available.")
        }
        return null
    }

    private fun fetchShortcutCategoryTypeByGestureType(
        @KeyGestureType keyGestureType: Int
    ): ShortcutCategoryType? {
        return inputGestureMaps.gestureToShortcutCategoryTypeMap[keyGestureType]
    }

    private companion object {
        private const val TAG = "InputGestureDataUtils"
    }
}