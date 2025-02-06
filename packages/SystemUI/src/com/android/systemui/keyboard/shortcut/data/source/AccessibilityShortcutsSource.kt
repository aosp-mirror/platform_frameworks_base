/*
 * Copyright (C) 2025 The Android Open Source Project
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
import android.hardware.input.InputSettings
import android.view.KeyEvent.KEYCODE_3
import android.view.KeyEvent.KEYCODE_4
import android.view.KeyEvent.KEYCODE_5
import android.view.KeyEvent.KEYCODE_6
import android.view.KeyEvent.KEYCODE_M
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_T
import android.view.KeyEvent.KEYCODE_V
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.android.hardware.input.Flags.enableTalkbackAndMagnifierKeyGestures
import com.android.hardware.input.Flags.enableVoiceAccessKeyGestures
import com.android.hardware.input.Flags.keyboardA11yShortcutControl
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyboard.shortcut.data.model.shortcutInfo
import com.android.systemui.res.R
import javax.inject.Inject

class AccessibilityShortcutsSource @Inject constructor(@Main private val resources: Resources) :
    KeyboardShortcutGroupsSource {
    override suspend fun shortcutGroups(deviceId: Int): List<KeyboardShortcutGroup> =
        listOf(
            KeyboardShortcutGroup(
                /* label= */ resources.getString(R.string.shortcutHelper_category_accessibility),
                accessibilityShortcuts(),
            )
        )

    private fun accessibilityShortcuts(): List<KeyboardShortcutInfo> {
        val shortcuts = mutableListOf<KeyboardShortcutInfo>()

        if (keyboardA11yShortcutControl()) {
            if (InputSettings.isAccessibilityBounceKeysFeatureEnabled()) {
                shortcuts.add(
                    // Toggle bounce keys:
                    //  - Meta + Alt + 3
                    shortcutInfo(
                        resources.getString(R.string.group_accessibility_toggle_bounce_keys)
                    ) {
                        command(META_META_ON or META_ALT_ON, KEYCODE_3)
                    }
                )
            }
            if (InputSettings.isAccessibilityMouseKeysFeatureFlagEnabled()) {
                shortcuts.add(
                    // Toggle mouse keys:
                    //  - Meta + Alt + 4
                    shortcutInfo(
                        resources.getString(R.string.group_accessibility_toggle_mouse_keys)
                    ) {
                        command(META_META_ON or META_ALT_ON, KEYCODE_4)
                    }
                )
            }
            if (InputSettings.isAccessibilityStickyKeysFeatureEnabled()) {
                shortcuts.add(
                    // Toggle sticky keys:
                    //  - Meta + Alt + 5
                    shortcutInfo(
                        resources.getString(R.string.group_accessibility_toggle_sticky_keys)
                    ) {
                        command(META_META_ON or META_ALT_ON, KEYCODE_5)
                    }
                )
            }
            if (InputSettings.isAccessibilitySlowKeysFeatureFlagEnabled()) {
                shortcuts.add(
                    // Toggle slow keys:
                    //  - Meta + Alt + 6
                    shortcutInfo(
                        resources.getString(R.string.group_accessibility_toggle_slow_keys)
                    ) {
                        command(META_META_ON or META_ALT_ON, KEYCODE_6)
                    }
                )
            }
        }

        if (enableVoiceAccessKeyGestures()) {
            shortcuts.add(
                // Toggle voice access:
                //  - Meta + Alt + V
                shortcutInfo(
                    resources.getString(R.string.group_accessibility_toggle_voice_access)
                ) {
                    command(META_META_ON or META_ALT_ON, KEYCODE_V)
                }
            )
        }

        if (enableTalkbackAndMagnifierKeyGestures()) {
            shortcuts.add(
                // Toggle talkback:
                //  - Meta + Alt + T
                shortcutInfo(resources.getString(R.string.group_accessibility_toggle_talkback)) {
                    command(META_META_ON or META_ALT_ON, KEYCODE_T)
                }
            )
            shortcuts.add(
                // Toggle magnification:
                //  - Meta + Alt + M
                shortcutInfo(
                    resources.getString(R.string.group_accessibility_toggle_magnification)
                ) {
                    command(META_META_ON or META_ALT_ON, KEYCODE_M)
                }
            )
            shortcuts.add(
                // Activate Select to Speak:
                //  - Meta + Alt + S
                shortcutInfo(
                    resources.getString(R.string.group_accessibility_activate_select_to_speak)
                ) {
                    command(META_META_ON or META_ALT_ON, KEYCODE_S)
                }
            )
        }

        return shortcuts
    }
}
