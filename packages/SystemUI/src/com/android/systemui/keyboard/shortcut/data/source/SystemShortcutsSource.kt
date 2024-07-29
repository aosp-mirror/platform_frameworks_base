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

package com.android.systemui.keyboard.shortcut.data.source

import android.content.res.Resources
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_H
import android.view.KeyEvent.KEYCODE_HOME
import android.view.KeyEvent.KEYCODE_I
import android.view.KeyEvent.KEYCODE_L
import android.view.KeyEvent.KEYCODE_N
import android.view.KeyEvent.KEYCODE_RECENT_APPS
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyboardShortcutGroup
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyboard.shortcut.data.model.shortcutInfo
import com.android.systemui.res.R
import javax.inject.Inject

class SystemShortcutsSource @Inject constructor(@Main private val resources: Resources) :
    KeyboardShortcutGroupsSource {

    override suspend fun shortcutGroups(deviceId: Int) =
        listOf(
            KeyboardShortcutGroup(
                resources.getString(R.string.shortcut_helper_category_system_controls),
                systemControlsShortcuts()
            ),
            KeyboardShortcutGroup(
                resources.getString(R.string.shortcut_helper_category_system_apps),
                systemAppsShortcuts()
            )
        )

    private fun systemControlsShortcuts() =
        listOf(
            // Access list of all apps and search (i.e. Search/Launcher):
            //  - Meta
            shortcutInfo(resources.getString(R.string.group_system_access_all_apps_search)) {
                command(META_META_ON)
            },
            // Access home screen:
            //  - Home button
            //  - Meta + H
            //  - Meta + Enter
            shortcutInfo(resources.getString(R.string.group_system_access_home_screen)) {
                command(modifiers = 0, KEYCODE_HOME)
            },
            shortcutInfo(resources.getString(R.string.group_system_access_home_screen)) {
                command(META_META_ON, KEYCODE_H)
            },
            shortcutInfo(resources.getString(R.string.group_system_access_home_screen)) {
                command(META_META_ON, KEYCODE_ENTER)
            },
            // Overview of open apps:
            //  - Recent apps button
            //  - Meta + Tab
            shortcutInfo(resources.getString(R.string.group_system_overview_open_apps)) {
                command(modifiers = 0, KEYCODE_RECENT_APPS)
            },
            shortcutInfo(resources.getString(R.string.group_system_overview_open_apps)) {
                command(META_META_ON, KEYCODE_TAB)
            },
            // Back: go back to previous state (back button)
            //  - Back button
            //  - Meta + Escape OR
            //  - Meta + Backspace OR
            //  - Meta + Left arrow
            shortcutInfo(resources.getString(R.string.group_system_go_back)) {
                command(modifiers = 0, KEYCODE_BACK)
            },
            shortcutInfo(resources.getString(R.string.group_system_go_back)) {
                command(META_META_ON, KEYCODE_ESCAPE)
            },
            shortcutInfo(resources.getString(R.string.group_system_go_back)) {
                command(META_META_ON, KEYCODE_DEL)
            },
            shortcutInfo(resources.getString(R.string.group_system_go_back)) {
                command(META_META_ON, KEYCODE_DPAD_LEFT)
            },
            // Take a full screenshot:
            //  - Meta + Ctrl + S
            shortcutInfo(resources.getString(R.string.group_system_full_screenshot)) {
                command(META_META_ON or META_CTRL_ON, KEYCODE_S)
            },
            // Access list of system / apps shortcuts:
            //  - Meta + /
            shortcutInfo(resources.getString(R.string.group_system_access_system_app_shortcuts)) {
                command(META_META_ON, KEYCODE_SLASH)
            },
            // Access notification shade:
            //  - Meta + N
            shortcutInfo(resources.getString(R.string.group_system_access_notification_shade)) {
                command(META_META_ON, KEYCODE_N)
            },
            // Lock screen:
            //  - Meta + L
            shortcutInfo(resources.getString(R.string.group_system_lock_screen)) {
                command(META_META_ON, KEYCODE_L)
            },
        )

    private fun systemAppsShortcuts() =
        listOf(
            // Pull up Notes app for quick memo:
            //  - Meta + Ctrl + N
            shortcutInfo(resources.getString(R.string.group_system_quick_memo)) {
                command(META_META_ON or META_CTRL_ON, KEYCODE_N)
            },
            // Access system settings:
            //  - Meta + I
            shortcutInfo(resources.getString(R.string.group_system_access_system_settings)) {
                command(META_META_ON, KEYCODE_I)
            },
            // Access Assistant:
            //  - Meta + A
            shortcutInfo(resources.getString(R.string.group_system_access_google_assistant)) {
                command(META_META_ON, KEYCODE_A)
            },
        )
}
