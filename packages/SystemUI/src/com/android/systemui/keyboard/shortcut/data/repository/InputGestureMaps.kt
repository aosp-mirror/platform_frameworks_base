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
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK
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
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_MINIMIZE_FREEFORM_WINDOW
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_SNAP_LEFT_FREEFORM_WINDOW
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_SNAP_RIGHT_FREEFORM_WINDOW
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_BOUNCE_KEYS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MAGNIFICATION
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MAXIMIZE_FREEFORM_WINDOW
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MOUSE_KEYS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_SLOW_KEYS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_STICKY_KEYS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_TALKBACK
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_VOICE_ACCESS
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.Accessibility
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
            KEY_GESTURE_TYPE_SNAP_LEFT_FREEFORM_WINDOW to MultiTasking,
            KEY_GESTURE_TYPE_SNAP_RIGHT_FREEFORM_WINDOW to MultiTasking,
            KEY_GESTURE_TYPE_MINIMIZE_FREEFORM_WINDOW to MultiTasking,
            KEY_GESTURE_TYPE_TOGGLE_MAXIMIZE_FREEFORM_WINDOW to MultiTasking,
            KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY to MultiTasking,

            // App Category
            KEY_GESTURE_TYPE_LAUNCH_APPLICATION to AppCategories,

            // Accessibility Category
            KEY_GESTURE_TYPE_TOGGLE_BOUNCE_KEYS to Accessibility,
            KEY_GESTURE_TYPE_TOGGLE_MOUSE_KEYS to Accessibility,
            KEY_GESTURE_TYPE_TOGGLE_STICKY_KEYS to Accessibility,
            KEY_GESTURE_TYPE_TOGGLE_SLOW_KEYS to Accessibility,
            KEY_GESTURE_TYPE_TOGGLE_VOICE_ACCESS to Accessibility,
            KEY_GESTURE_TYPE_TOGGLE_TALKBACK to Accessibility,
            KEY_GESTURE_TYPE_TOGGLE_MAGNIFICATION to Accessibility,
            KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK to Accessibility,
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
            KEY_GESTURE_TYPE_SNAP_LEFT_FREEFORM_WINDOW to
                R.string.shortcutHelper_category_split_screen,
            KEY_GESTURE_TYPE_SNAP_RIGHT_FREEFORM_WINDOW to
                R.string.shortcutHelper_category_split_screen,
            KEY_GESTURE_TYPE_MINIMIZE_FREEFORM_WINDOW to
                R.string.shortcutHelper_category_split_screen,
            KEY_GESTURE_TYPE_TOGGLE_MAXIMIZE_FREEFORM_WINDOW to
                R.string.shortcutHelper_category_split_screen,
            KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY to R.string.shortcutHelper_category_split_screen,

            // App Category
            KEY_GESTURE_TYPE_LAUNCH_APPLICATION to R.string.keyboard_shortcut_group_applications,

            // Accessibility Category
            KEY_GESTURE_TYPE_TOGGLE_BOUNCE_KEYS to R.string.shortcutHelper_category_accessibility,
            KEY_GESTURE_TYPE_TOGGLE_MOUSE_KEYS to R.string.shortcutHelper_category_accessibility,
            KEY_GESTURE_TYPE_TOGGLE_STICKY_KEYS to R.string.shortcutHelper_category_accessibility,
            KEY_GESTURE_TYPE_TOGGLE_SLOW_KEYS to R.string.shortcutHelper_category_accessibility,
            KEY_GESTURE_TYPE_TOGGLE_VOICE_ACCESS to R.string.shortcutHelper_category_accessibility,
            KEY_GESTURE_TYPE_TOGGLE_TALKBACK to R.string.shortcutHelper_category_accessibility,
            KEY_GESTURE_TYPE_TOGGLE_MAGNIFICATION to R.string.shortcutHelper_category_accessibility,
            KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK to
                R.string.shortcutHelper_category_accessibility,
        )

    /**
     * App Category shortcut labels are mapped dynamically based on intent see
     * [InputGestureDataAdapter.fetchShortcutLabelByAppLaunchData]
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
            KEY_GESTURE_TYPE_SNAP_LEFT_FREEFORM_WINDOW to
                R.string.system_desktop_mode_snap_left_window,
            KEY_GESTURE_TYPE_SNAP_RIGHT_FREEFORM_WINDOW to
                R.string.system_desktop_mode_snap_right_window,
            KEY_GESTURE_TYPE_MINIMIZE_FREEFORM_WINDOW to
                R.string.system_desktop_mode_minimize_window,
            KEY_GESTURE_TYPE_TOGGLE_MAXIMIZE_FREEFORM_WINDOW to
                R.string.system_desktop_mode_toggle_maximize_window,
            KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY to
                R.string.system_multitasking_move_to_next_display,

            // Accessibility Category
            KEY_GESTURE_TYPE_TOGGLE_BOUNCE_KEYS to R.string.group_accessibility_toggle_bounce_keys,
            KEY_GESTURE_TYPE_TOGGLE_MOUSE_KEYS to R.string.group_accessibility_toggle_mouse_keys,
            KEY_GESTURE_TYPE_TOGGLE_STICKY_KEYS to R.string.group_accessibility_toggle_sticky_keys,
            KEY_GESTURE_TYPE_TOGGLE_SLOW_KEYS to R.string.group_accessibility_toggle_slow_keys,
            KEY_GESTURE_TYPE_TOGGLE_VOICE_ACCESS to
                R.string.group_accessibility_toggle_voice_access,
            KEY_GESTURE_TYPE_TOGGLE_TALKBACK to R.string.group_accessibility_toggle_talkback,
            KEY_GESTURE_TYPE_TOGGLE_MAGNIFICATION to
                R.string.group_accessibility_toggle_magnification,
            KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK to
                R.string.group_accessibility_activate_select_to_speak,
        )

    val shortcutLabelToKeyGestureTypeMap: Map<String, Int>
        get() =
            gestureToInternalKeyboardShortcutInfoLabelResIdMap.entries.associateBy({
                context.getString(it.value)
            }) {
                it.key
            }
}
