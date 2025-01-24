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
import android.hardware.input.InputManager
import android.hardware.input.KeyGlyphMap
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
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
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_SHIFT_ON
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.android.systemui.Flags.shortcutHelperKeyGlyph
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyboard.shortcut.data.model.shortcutInfo
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperKeys
import com.android.systemui.res.R
import javax.inject.Inject

class SystemShortcutsSource
@Inject
constructor(@Main private val resources: Resources, private val inputManager: InputManager) :
    KeyboardShortcutGroupsSource {

    override suspend fun shortcutGroups(deviceId: Int) =
        listOf(
            KeyboardShortcutGroup(
                resources.getString(R.string.shortcut_helper_category_system_controls),
                systemControlsShortcuts() + hardwareShortcuts(deviceId),
            ),
            KeyboardShortcutGroup(
                resources.getString(R.string.shortcut_helper_category_system_apps),
                systemAppsShortcuts(),
            ),
        )

    private fun hardwareShortcuts(deviceId: Int): List<KeyboardShortcutInfo> =
        if (shortcutHelperKeyGlyph()) {
            val keyGlyphMap = inputManager.getKeyGlyphMap(deviceId)
            if (keyGlyphMap != null) {
                functionRowKeys(keyGlyphMap) + keyCombinationShortcuts(keyGlyphMap)
            } else {
                // Not add function row keys if it is not supported by keyboard
                emptyList()
            }
        } else {
            defaultFunctionRowKeys()
        }

    private fun defaultFunctionRowKeys(): List<KeyboardShortcutInfo> =
        listOf(
            shortcutInfo(resources.getString(R.string.group_system_access_home_screen)) {
                command(modifiers = 0, KEYCODE_HOME)
            },
            shortcutInfo(resources.getString(R.string.group_system_go_back)) {
                command(modifiers = 0, KEYCODE_BACK)
            },
            shortcutInfo(resources.getString(R.string.group_system_overview_open_apps)) {
                command(modifiers = 0, KEYCODE_RECENT_APPS)
            },
        )

    private fun functionRowKeys(keyGlyphMap: KeyGlyphMap): List<KeyboardShortcutInfo> {
        val functionRowKeys = mutableListOf<KeyboardShortcutInfo>()
        keyGlyphMap.functionRowKeys.forEach { keyCode ->
            val labelResId = ShortcutHelperKeys.keyLabelResIds[keyCode]
            if (labelResId != null) {
                functionRowKeys.add(
                    shortcutInfo(resources.getString(labelResId)) {
                        command(modifiers = 0, keyCode)
                    }
                )
            }
        }
        return functionRowKeys
    }

    private fun keyCombinationShortcuts(keyGlyphMap: KeyGlyphMap): List<KeyboardShortcutInfo> {
        val shortcuts = mutableListOf<KeyboardShortcutInfo>()
        keyGlyphMap.hardwareShortcuts.forEach { (keyCombination, keyCode) ->
            val labelResId = ShortcutHelperKeys.keyLabelResIds[keyCode]
            if (labelResId != null) {
                val info =
                    shortcutInfo(resources.getString(labelResId)) {
                        command(keyCombination.modifierState, keyCombination.keycode)
                    }
                shortcuts.add(info)
            }
        }
        return shortcuts
    }

    private fun systemControlsShortcuts() =
        listOf(
            // Access list of all apps and search (i.e. Search/Launcher):
            //  - Meta
            shortcutInfo(resources.getString(R.string.group_system_access_all_apps_search)) {
                command(META_META_ON)
            },
            // Access home screen:
            //  - Meta + H
            shortcutInfo(resources.getString(R.string.group_system_access_home_screen)) {
                command(META_META_ON, KEYCODE_H)
            },
            // Overview of open apps:
            //  - Meta + Tab
            shortcutInfo(resources.getString(R.string.group_system_overview_open_apps)) {
                command(META_META_ON, KEYCODE_TAB)
            },
            // Cycle through recent apps (forward):
            //  - Alt + Tab
            shortcutInfo(resources.getString(R.string.group_system_cycle_forward)) {
                command(META_ALT_ON, KEYCODE_TAB)
            },
            // Cycle through recent apps (back):
            //  - Shift + Alt + Tab
            shortcutInfo(resources.getString(R.string.group_system_cycle_back)) {
                command(META_SHIFT_ON or META_ALT_ON, KEYCODE_TAB)
            },
            // Back: go back to previous state (back button)
            //  - Meta + Escape OR
            //  - Meta + Left arrow
            shortcutInfo(resources.getString(R.string.group_system_go_back)) {
                command(META_META_ON, KEYCODE_ESCAPE)
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
