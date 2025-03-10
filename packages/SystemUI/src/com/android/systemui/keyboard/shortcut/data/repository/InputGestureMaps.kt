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

import android.content.Context
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_BACK
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_HOME
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.AppCategories
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MultiTasking
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.System
import com.android.systemui.res.R
import javax.inject.Inject

class InputGestureMaps @Inject constructor(private val context: Context) {
    val gestureToShortcutCategoryTypeMap =
        mapOf(
            // System Category
            KEY_GESTURE_TYPE_HOME to System,
            KEY_GESTURE_TYPE_RECENT_APPS to System,
            KEY_GESTURE_TYPE_BACK to System,
            KEY_GESTURE_TYPE_TAKE_SCREENSHOT to System,
            KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER to System,
            KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL to System,
            KEY_GESTURE_TYPE_LOCK_SCREEN to System,
            KEY_GESTURE_TYPE_OPEN_NOTES to System,
            KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS to System,
            KEY_GESTURE_TYPE_LAUNCH_ASSISTANT to System,
            KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT to System,
            KEY_GESTURE_TYPE_ALL_APPS to System,

            // Multitasking Category
            KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER to MultiTasking,
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT to MultiTasking,
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT to MultiTasking,
            KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION to MultiTasking,
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT to MultiTasking,
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT to MultiTasking,

            // App Category
            KEY_GESTURE_TYPE_LAUNCH_APPLICATION to AppCategories,
        )

    val gestureToInternalKeyboardShortcutGroupLabelResIdMap =
        mapOf(
            // System Category
            KEY_GESTURE_TYPE_HOME to R.string.shortcut_helper_category_system_controls,
            KEY_GESTURE_TYPE_RECENT_APPS to R.string.shortcut_helper_category_system_controls,
            KEY_GESTURE_TYPE_BACK to R.string.shortcut_helper_category_system_controls,
            KEY_GESTURE_TYPE_TAKE_SCREENSHOT to R.string.shortcut_helper_category_system_controls,
            KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER to
                R.string.shortcut_helper_category_system_controls,
            KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL to
                R.string.shortcut_helper_category_system_controls,
            KEY_GESTURE_TYPE_LOCK_SCREEN to R.string.shortcut_helper_category_system_controls,
            KEY_GESTURE_TYPE_ALL_APPS to R.string.shortcut_helper_category_system_controls,
            KEY_GESTURE_TYPE_OPEN_NOTES to R.string.shortcut_helper_category_system_apps,
            KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS to
                R.string.shortcut_helper_category_system_apps,
            KEY_GESTURE_TYPE_LAUNCH_ASSISTANT to R.string.shortcut_helper_category_system_apps,
            KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT to
                R.string.shortcut_helper_category_system_apps,

            // Multitasking Category
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT to
                R.string.shortcutHelper_category_split_screen,
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT to
                R.string.shortcutHelper_category_split_screen,
            KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION to
                R.string.shortcutHelper_category_split_screen,
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT to
                R.string.shortcutHelper_category_split_screen,
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT to
                R.string.shortcutHelper_category_split_screen,

            // App Category
            KEY_GESTURE_TYPE_LAUNCH_APPLICATION to
                R.string.keyboard_shortcut_group_applications,
        )

    /**
     * App Category shortcut labels are mapped dynamically based on intent
     * see [InputGestureDataAdapter.fetchShortcutLabelByAppLaunchData]
     */
    val gestureToInternalKeyboardShortcutInfoLabelResIdMap =
        mapOf(
            // System Category
            KEY_GESTURE_TYPE_HOME to R.string.group_system_access_home_screen,
            KEY_GESTURE_TYPE_RECENT_APPS to R.string.group_system_overview_open_apps,
            KEY_GESTURE_TYPE_BACK to R.string.group_system_go_back,
            KEY_GESTURE_TYPE_TAKE_SCREENSHOT to R.string.group_system_full_screenshot,
            KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER to
                R.string.group_system_access_system_app_shortcuts,
            KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL to
                R.string.group_system_access_notification_shade,
            KEY_GESTURE_TYPE_LOCK_SCREEN to R.string.group_system_lock_screen,
            KEY_GESTURE_TYPE_ALL_APPS to R.string.group_system_access_all_apps_search,
            KEY_GESTURE_TYPE_OPEN_NOTES to R.string.group_system_quick_memo,
            KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS to R.string.group_system_access_system_settings,
            KEY_GESTURE_TYPE_LAUNCH_ASSISTANT to R.string.group_system_access_google_assistant,
            KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT to
                R.string.group_system_access_google_assistant,

            // Multitasking Category
            KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER to R.string.group_system_cycle_forward,
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT to R.string.system_multitasking_lhs,
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT to R.string.system_multitasking_rhs,
            KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION to R.string.system_multitasking_full_screen,
        )

    val shortcutLabelToKeyGestureTypeMap: Map<String, Int>
        get() =
            gestureToInternalKeyboardShortcutInfoLabelResIdMap.entries.associateBy({
                context.getString(it.value)
            }) {
                it.key
            }
}
