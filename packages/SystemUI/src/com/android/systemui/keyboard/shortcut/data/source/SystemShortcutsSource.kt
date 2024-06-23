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
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_H
import android.view.KeyEvent.KEYCODE_I
import android.view.KeyEvent.KEYCODE_L
import android.view.KeyEvent.KEYCODE_N
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import com.android.systemui.keyboard.shortcut.shared.model.shortcut
import com.android.systemui.res.R
import javax.inject.Inject

class SystemShortcutsSource @Inject constructor(private val resources: Resources) {

    fun generalShortcuts() =
        listOf(
            // Access list of all apps and search (i.e. Search/Launcher):
            //  - Meta
            shortcut(resources.getString(R.string.group_system_access_all_apps_search)) {
                command(META_META_ON)
            },
            // Access home screen:
            //  - Meta + H
            //  - Meta + Enter
            shortcut(resources.getString(R.string.group_system_access_home_screen)) {
                command(META_META_ON, KEYCODE_H)
                command(META_META_ON, KEYCODE_ENTER)
            },
            // Overview of open apps:
            //  - Meta + Tab
            shortcut(resources.getString(R.string.group_system_overview_open_apps)) {
                command(META_META_ON, KEYCODE_TAB)
            },
            // Back: go back to previous state (back button)
            //  - Meta + Escape OR
            //  - Meta + Backspace OR
            //  - Meta + Left arrow
            shortcut(resources.getString(R.string.group_system_go_back)) {
                command(META_META_ON, KEYCODE_ESCAPE)
                command(META_META_ON, KEYCODE_DEL)
                command(META_META_ON, KEYCODE_DPAD_LEFT)
            },
            // Take a full screenshot:
            //  - Meta + Ctrl + S
            shortcut(resources.getString(R.string.group_system_full_screenshot)) {
                command(META_META_ON, META_CTRL_ON, KEYCODE_S)
            },
            // Access list of system / apps shortcuts:
            //  - Meta + /
            shortcut(resources.getString(R.string.group_system_access_system_app_shortcuts)) {
                command(META_META_ON, KEYCODE_SLASH)
            },
            // Access notification shade:
            //  - Meta + N
            shortcut(resources.getString(R.string.group_system_access_notification_shade)) {
                command(META_META_ON, KEYCODE_N)
            },
            // Lock screen:
            //  - Meta + L
            shortcut(resources.getString(R.string.group_system_lock_screen)) {
                command(META_META_ON, KEYCODE_L)
            },
        )

    fun systemAppsShortcuts() =
        listOf(
            // Pull up Notes app for quick memo:
            //  - Meta + Ctrl + N
            shortcut(resources.getString(R.string.group_system_quick_memo)) {
                command(META_META_ON, META_CTRL_ON, KEYCODE_N)
            },
            // Access system settings:
            //  - Meta + I
            shortcut(resources.getString(R.string.group_system_access_system_settings)) {
                command(META_META_ON, KEYCODE_I)
            },
            // Access Assistant:
            //  - Meta + A
            shortcut(resources.getString(R.string.group_system_access_google_assistant)) {
                command(META_META_ON, KEYCODE_A)
            },
        )
}
