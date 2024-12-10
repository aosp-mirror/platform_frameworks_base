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

import android.content.Context
import android.content.res.Resources
import android.view.KeyEvent.KEYCODE_D
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_LEFT_BRACKET
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyboardShortcutGroup
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyboard.shortcut.data.model.shortcutInfo
import com.android.systemui.res.R
import com.android.window.flags.Flags.enableMoveToNextDisplayShortcut
import com.android.window.flags.Flags.enableTaskResizingKeyboardShortcuts
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import javax.inject.Inject

class MultitaskingShortcutsSource
@Inject
constructor(@Main private val resources: Resources, @Application private val context: Context) :
    KeyboardShortcutGroupsSource {

    override suspend fun shortcutGroups(deviceId: Int) =
        listOf(
            KeyboardShortcutGroup(
                resources.getString(R.string.shortcutHelper_category_split_screen),
                splitScreenShortcuts(),
            )
        )

    private fun splitScreenShortcuts() = buildList {
        //  Enter Split screen with current app to RHS:
        //   - Meta + Ctrl + Right arrow
        add(
            shortcutInfo(resources.getString(R.string.system_multitasking_rhs)) {
                command(META_META_ON or META_CTRL_ON, KEYCODE_DPAD_RIGHT)
            }
        )
        //  Enter Split screen with current app to LHS:
        //   - Meta + Ctrl + Left arrow
        add(
            shortcutInfo(resources.getString(R.string.system_multitasking_lhs)) {
                command(META_META_ON or META_CTRL_ON, KEYCODE_DPAD_LEFT)
            }
        )
        //  Switch from Split screen to full screen:
        //   - Meta + Ctrl + Up arrow
        add(
            shortcutInfo(resources.getString(R.string.system_multitasking_full_screen)) {
                command(META_META_ON or META_CTRL_ON, KEYCODE_DPAD_UP)
            }
        )
        if (enableMoveToNextDisplayShortcut()) {
            // Move a window to the next display:
            //  - Meta + Ctrl + D
            add(
                shortcutInfo(
                    resources.getString(R.string.system_multitasking_move_to_next_display)
                ) {
                    command(META_META_ON or META_CTRL_ON, KEYCODE_D)
                }
            )
        }
        if (
            DesktopModeStatus.canEnterDesktopMode(context) && enableTaskResizingKeyboardShortcuts()
        ) {
            // Snap a freeform window to the left
            //  - Meta + Left bracket
            add(
                shortcutInfo(resources.getString(R.string.system_desktop_mode_snap_left_window)) {
                    command(META_META_ON, KEYCODE_LEFT_BRACKET)
                }
            )
            // Snap a freeform window to the right
            //  - Meta + Right bracket
            add(
                shortcutInfo(resources.getString(R.string.system_desktop_mode_snap_right_window)) {
                    command(META_META_ON, KEYCODE_RIGHT_BRACKET)
                }
            )
            // Toggle maximize a freeform window
            //  - Meta + Equals
            add(
                shortcutInfo(
                    resources.getString(R.string.system_desktop_mode_toggle_maximize_window)
                ) {
                    command(META_META_ON, KEYCODE_EQUALS)
                }
            )
            // Minimize a freeform window
            //  - Meta + Minus
            add(
                shortcutInfo(resources.getString(R.string.system_desktop_mode_minimize_window)) {
                    command(META_META_ON, KEYCODE_MINUS)
                }
            )
        }
    }
}
