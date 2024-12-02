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

package com.android.systemui.keyboard.shortcut.shared.model

sealed interface ShortcutCustomizationRequestInfo {

    sealed interface SingleShortcutCustomization: ShortcutCustomizationRequestInfo {
        val label: String
        val categoryType: ShortcutCategoryType
        val subCategoryLabel: String
        val shortcutCommand: ShortcutCommand

        data class Add(
            override val label: String = "",
            override val categoryType: ShortcutCategoryType = ShortcutCategoryType.System,
            override val subCategoryLabel: String = "",
            override val shortcutCommand: ShortcutCommand = ShortcutCommand(),
        ) : SingleShortcutCustomization

        data class Delete(
            override val label: String = "",
            override val categoryType: ShortcutCategoryType = ShortcutCategoryType.System,
            override val subCategoryLabel: String = "",
            override val shortcutCommand: ShortcutCommand = ShortcutCommand(),
        ) : SingleShortcutCustomization
    }

    data object Reset : ShortcutCustomizationRequestInfo
}
