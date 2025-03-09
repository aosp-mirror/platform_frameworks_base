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
import android.view.KeyEvent.KEYCODE_EMOJI_PICKER
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_ON
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.WindowManager
import android.view.WindowManager.KeyboardShortcutsReceiver
import com.android.systemui.Flags.shortcutHelperKeyGlyph
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyboard.shortcut.data.model.shortcutInfo
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine

class InputShortcutsSource
@Inject
constructor(
    @Main private val resources: Resources,
    private val windowManager: WindowManager,
    private val inputManager: InputManager,
) : KeyboardShortcutGroupsSource {
    override suspend fun shortcutGroups(deviceId: Int): List<KeyboardShortcutGroup> =
        getInputLanguageShortcutGroup(deviceId) + getImeShortcutGroup(deviceId)

    private fun getInputLanguageShortcutGroup(deviceId: Int) =
        listOf(
            KeyboardShortcutGroup(
                resources.getString(R.string.shortcut_helper_category_input),
                inputLanguageShortcuts() + hardwareShortcuts(deviceId),
            )
        )

    private fun inputLanguageShortcuts() =
        listOf(
            /* Switch input language (next language): Ctrl + Space */
            shortcutInfo(resources.getString(R.string.input_switch_input_language_next)) {
                command(META_CTRL_ON, KEYCODE_SPACE)
            },
            /* Switch previous language (next language): Ctrl + Shift + Space */
            shortcutInfo(resources.getString(R.string.input_switch_input_language_previous)) {
                command(META_CTRL_ON or META_SHIFT_ON, KEYCODE_SPACE)
            },
        )

    private fun hardwareShortcuts(deviceId: Int): List<KeyboardShortcutInfo> {
        if (shortcutHelperKeyGlyph()) {
            val keyGlyphMap = inputManager.getKeyGlyphMap(deviceId)
            if (keyGlyphMap != null && keyGlyphMap.functionRowKeys.contains(KEYCODE_EMOJI_PICKER)) {
                return listOf(
                    shortcutInfo(resources.getString(R.string.input_access_emoji)) {
                        command(modifiers = 0, KEYCODE_EMOJI_PICKER)
                    }
                )
            }
        }
        return emptyList()
    }

    private suspend fun getImeShortcutGroup(deviceId: Int): List<KeyboardShortcutGroup> =
        suspendCancellableCoroutine { continuation ->
            val shortcutsReceiver = KeyboardShortcutsReceiver {
                continuation.resumeWith(Result.success(it ?: emptyList()))
            }
            windowManager.requestImeKeyboardShortcuts(shortcutsReceiver, deviceId)
        }
}
