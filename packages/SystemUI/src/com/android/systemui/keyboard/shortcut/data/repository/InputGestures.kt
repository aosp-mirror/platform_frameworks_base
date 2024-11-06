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

import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_BACK
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_HOME
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING
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

object InputGestures {
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
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR to AppCategories,
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR to AppCategories,
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER to AppCategories,
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS to AppCategories,
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL to AppCategories,
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS to AppCategories,
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING to AppCategories,
        )

    // TODO move all string to to resources use the same resources as the original shortcuts
    // - that way when the strings are translated there are no discrepancies
    val gestureToInternalKeyboardShortcutGroupLabelMap =
        mapOf(
            // System Category
            KEY_GESTURE_TYPE_HOME to "System controls",
            KEY_GESTURE_TYPE_RECENT_APPS to "System controls",
            KEY_GESTURE_TYPE_BACK to "System controls",
            KEY_GESTURE_TYPE_TAKE_SCREENSHOT to "System controls",
            KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER to "System controls",
            KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL to "System controls",
            KEY_GESTURE_TYPE_LOCK_SCREEN to "System controls",
            KEY_GESTURE_TYPE_ALL_APPS to "System controls",
            KEY_GESTURE_TYPE_OPEN_NOTES to "System apps",
            KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS to "System apps",
            KEY_GESTURE_TYPE_LAUNCH_ASSISTANT to "System apps",
            KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT to "System apps",

            // Multitasking Category
            KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER to "Recent apps",
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT to "Split screen",
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT to "Split screen",
            KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION to "Split screen",
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT to "Split screen",
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT to "Split screen",

            // App Category
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR to "Applications",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR to "Applications",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER to "Applications",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS to "Applications",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL to "Applications",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS to "Applications",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING to "Applications",
        )

    val gestureToInternalKeyboardShortcutInfoLabelMap =
        mapOf(
            // System Category
            KEY_GESTURE_TYPE_HOME to "Go to home screen",
            KEY_GESTURE_TYPE_RECENT_APPS to "View recent apps",
            KEY_GESTURE_TYPE_BACK to "Go back",
            KEY_GESTURE_TYPE_TAKE_SCREENSHOT to "Take screenshot",
            KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER to "Show shortcuts",
            KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL to "View notifications",
            KEY_GESTURE_TYPE_LOCK_SCREEN to "Lock screen",
            KEY_GESTURE_TYPE_ALL_APPS to "Open apps list",
            KEY_GESTURE_TYPE_OPEN_NOTES to "Take a note",
            KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS to "Open settings",
            KEY_GESTURE_TYPE_LAUNCH_ASSISTANT to "Open assistant",
            KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT to "Open assistant",

            // Multitasking Category
            KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER to "Cycle forward through recent apps",
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT to
                "Use split screen with current app on the left",
            KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT to
                "Use split screen with current app on the right",
            KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION to "Switch from split screen to full screen",
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT to
                "Switch to app on left or above while using split screen",
            KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT to
                "Switch to app on right or below while using split screen",

            // App Category
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR to "Calculator",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR to "Calendar",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER to "Chrome",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS to "Contacts",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL to "Gmail",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS to "Maps",
            KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING to "Messages",
        )
}
