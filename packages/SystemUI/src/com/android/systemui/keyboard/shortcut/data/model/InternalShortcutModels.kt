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

package com.android.systemui.keyboard.shortcut.data.model

import android.graphics.drawable.Icon
import android.hardware.input.InputGestureData
import android.view.KeyboardShortcutGroup
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutCategoriesUtils
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType

/**
 * Internal Keyboard Shortcut models to use with [ShortcutCategoriesUtils.fetchShortcutCategory]
 * when converting API models to Shortcut Helper Model [ShortcutCategory]. These Internal Models
 * bridge the Gap between [InputGestureData] from custom shortcuts API and [KeyboardShortcutGroup]
 * from default shortcuts API allowing us to have a single Utility Class that converts API models to
 * Shortcut Helper models
 *
 * @param label Equivalent to shortcut helper's subcategory label
 * @param items Keyboard Shortcuts received from API
 * @param packageName package name of current app shortcut if available.
 */
data class InternalKeyboardShortcutGroup(
    val label: String,
    val items: List<InternalKeyboardShortcutInfo>,
    val packageName: String? = null,
)

/**
 * @param label Shortcut label
 * @param keycode Key to trigger shortcut
 * @param modifiers Mask of shortcut modifiers
 * @param baseCharacter Key to trigger shortcut if is a character
 * @param icon Shortcut icon if available - often used for app launch shortcuts
 * @param isCustomShortcut If Shortcut is user customized or system defined.
 */
data class InternalKeyboardShortcutInfo(
    val label: String,
    val keycode: Int,
    val modifiers: Int,
    val baseCharacter: Char = Char.MIN_VALUE,
    val icon: Icon? = null,
    val isCustomShortcut: Boolean = false,
)

data class InternalGroupsSource(
    val groups: List<InternalKeyboardShortcutGroup>,
    val type: ShortcutCategoryType,
)