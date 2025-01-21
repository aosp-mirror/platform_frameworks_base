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
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.android.systemui.dagger.qualifiers.Main
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

    private fun accessibilityShortcuts() = listOf<KeyboardShortcutInfo>()
}
