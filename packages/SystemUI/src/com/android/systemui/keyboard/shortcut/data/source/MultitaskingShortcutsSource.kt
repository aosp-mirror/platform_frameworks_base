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
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_SHIFT_ON
import android.view.KeyboardShortcutGroup
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyboard.shortcut.data.model.shortcutInfo
import com.android.systemui.res.R
import javax.inject.Inject

class MultitaskingShortcutsSource @Inject constructor(@Main private val resources: Resources) :
    KeyboardShortcutGroupsSource {

    override suspend fun shortcutGroups(deviceId: Int) =
        listOf(
            KeyboardShortcutGroup(
                resources.getString(R.string.shortcutHelper_category_recent_apps),
                recentsShortcuts()
            ),
            KeyboardShortcutGroup(
                resources.getString(R.string.shortcutHelper_category_split_screen),
                splitScreenShortcuts()
            )
        )

    private fun splitScreenShortcuts() =
        listOf(
            //  Enter Split screen with current app to RHS:
            //   - Meta + Ctrl + Right arrow
            shortcutInfo(resources.getString(R.string.system_multitasking_rhs)) {
                command(META_META_ON or META_CTRL_ON, KEYCODE_DPAD_RIGHT)
            },
            //  Enter Split screen with current app to LHS:
            //   - Meta + Ctrl + Left arrow
            shortcutInfo(resources.getString(R.string.system_multitasking_lhs)) {
                command(META_META_ON or META_CTRL_ON, KEYCODE_DPAD_LEFT)
            },
            //  Switch from Split screen to full screen:
            //   - Meta + Ctrl + Up arrow
            shortcutInfo(resources.getString(R.string.system_multitasking_full_screen)) {
                command(META_META_ON or META_CTRL_ON, KEYCODE_DPAD_UP)
            },
            //  Change split screen focus to RHS:
            //   - Meta + Alt + Right arrow
            shortcutInfo(resources.getString(R.string.system_multitasking_splitscreen_focus_rhs)) {
                command(META_META_ON or META_ALT_ON, KEYCODE_DPAD_RIGHT)
            },
            //  Change split screen focus to LHS:
            //   - Meta + Alt + Left arrow
            shortcutInfo(resources.getString(R.string.system_multitasking_splitscreen_focus_rhs)) {
                command(META_META_ON or META_ALT_ON, KEYCODE_DPAD_LEFT)
            },
        )

    private fun recentsShortcuts() =
        listOf(
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
        )
}
