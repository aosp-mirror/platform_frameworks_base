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

import android.content.Intent
import android.content.res.Resources
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.util.icons.AppCategoryIconProvider
import javax.inject.Inject

class AppCategoriesShortcutsSource
@Inject
constructor(
    private val appCategoryIconProvider: AppCategoryIconProvider,
    @Main private val resources: Resources,
) : KeyboardShortcutGroupsSource {

    override suspend fun shortcutGroups(deviceId: Int) =
        listOf(
            KeyboardShortcutGroup(
                /* label = */ resources.getString(R.string.keyboard_shortcut_group_applications),
                /* items = */ shortcuts()
            )
        )

    private suspend fun shortcuts(): List<KeyboardShortcutInfo> =
        listOfNotNull(
                assistantAppShortcutInfo(),
                appCategoryShortcutInfo(
                    Intent.CATEGORY_APP_BROWSER,
                    R.string.keyboard_shortcut_group_applications_browser,
                    KeyEvent.KEYCODE_B
                ),
                appCategoryShortcutInfo(
                    Intent.CATEGORY_APP_CONTACTS,
                    R.string.keyboard_shortcut_group_applications_contacts,
                    KeyEvent.KEYCODE_C
                ),
                appCategoryShortcutInfo(
                    Intent.CATEGORY_APP_EMAIL,
                    R.string.keyboard_shortcut_group_applications_email,
                    KeyEvent.KEYCODE_E
                ),
                appCategoryShortcutInfo(
                    Intent.CATEGORY_APP_CALENDAR,
                    R.string.keyboard_shortcut_group_applications_calendar,
                    KeyEvent.KEYCODE_K
                ),
                appCategoryShortcutInfo(
                    Intent.CATEGORY_APP_MAPS,
                    R.string.keyboard_shortcut_group_applications_maps,
                    KeyEvent.KEYCODE_M
                ),
                appCategoryShortcutInfo(
                    Intent.CATEGORY_APP_MUSIC,
                    R.string.keyboard_shortcut_group_applications_music,
                    KeyEvent.KEYCODE_P
                ),
                appCategoryShortcutInfo(
                    Intent.CATEGORY_APP_MESSAGING,
                    R.string.keyboard_shortcut_group_applications_sms,
                    KeyEvent.KEYCODE_S
                ),
                appCategoryShortcutInfo(
                    Intent.CATEGORY_APP_CALCULATOR,
                    R.string.keyboard_shortcut_group_applications_calculator,
                    KeyEvent.KEYCODE_U
                ),
            )
            .sortedBy { it.label!!.toString().lowercase() }

    private suspend fun assistantAppShortcutInfo(): KeyboardShortcutInfo? {
        val assistantIcon = appCategoryIconProvider.assistantAppIcon() ?: return null
        return KeyboardShortcutInfo(
            /* label = */ resources.getString(R.string.keyboard_shortcut_group_applications_assist),
            /* icon = */ assistantIcon,
            /* keycode = */ KeyEvent.KEYCODE_A,
            /* modifiers = */ KeyEvent.META_META_ON,
        )
    }

    private suspend fun appCategoryShortcutInfo(category: String, labelResId: Int, keycode: Int) =
        KeyboardShortcutInfo(
            /* label = */ resources.getString(labelResId),
            /* icon = */ appCategoryIconProvider.categoryAppIcon(category),
            /* keycode = */ keycode,
            /* modifiers = */ KeyEvent.META_META_ON,
        )
}
