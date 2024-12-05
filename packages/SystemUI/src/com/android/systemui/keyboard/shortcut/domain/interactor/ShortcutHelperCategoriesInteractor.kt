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

package com.android.systemui.keyboard.shortcut.domain.interactor

import android.content.Context
import android.view.KeyEvent.META_META_ON
import com.android.systemui.Flags.keyboardShortcutHelperShortcutCustomizer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutCategoriesRepository
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperKeys
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperKeys.metaModifierIconResId
import com.android.systemui.keyboard.shortcut.qualifiers.CustomShortcutCategories
import com.android.systemui.keyboard.shortcut.qualifiers.DefaultShortcutCategories
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.res.R
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

@SysUISingleton
class ShortcutHelperCategoriesInteractor
@Inject
constructor(
    @Application private val context: Context,
    @DefaultShortcutCategories defaultCategoriesRepository: ShortcutCategoriesRepository,
    @CustomShortcutCategories customCategoriesRepositoryLazy: Lazy<ShortcutCategoriesRepository>,
) {
    val shortcutCategories: Flow<List<ShortcutCategory>> =
        defaultCategoriesRepository.categories.combine(
            if (keyboardShortcutHelperShortcutCustomizer()) {
                customCategoriesRepositoryLazy.get().categories
            } else {
                flowOf(emptyList())
            }
        ) { defaultShortcutCategories, customShortcutCategories ->
            groupCategories(defaultShortcutCategories + customShortcutCategories)
        }

    private fun groupCategories(
        shortcutCategories: List<ShortcutCategory>
    ): List<ShortcutCategory> {
        return shortcutCategories
            .groupBy { it.type }
            .entries
            .map { (categoryType, groupedCategories) ->
                ShortcutCategory(
                    type = categoryType,
                    subCategories =
                        groupSubCategories(groupedCategories.flatMap { it.subCategories }),
                )
            }
    }

    private fun groupSubCategories(
        subCategories: List<ShortcutSubCategory>
    ): List<ShortcutSubCategory> {
        return subCategories
            .groupBy { it.label }
            .entries
            .map { (label, groupedSubcategories) ->
                ShortcutSubCategory(
                    label = label,
                    shortcuts =
                        groupShortcutsInSubcategory(groupedSubcategories.flatMap { it.shortcuts }),
                )
            }
    }

    private fun groupShortcutsInSubcategory(shortcuts: List<Shortcut>) =
        shortcuts
            .groupBy { it.label }
            .entries
            .map { (commonLabel, groupedShortcuts) ->
                groupedShortcuts[0].copy(
                    commands = groupedShortcuts.flatMap { it.commands }.sortedBy { it.keys.size },
                    contentDescription =
                        toContentDescription(commonLabel, groupedShortcuts.flatMap { it.commands }),
                )
            }

    private fun toContentDescription(label: String, commands: List<ShortcutCommand>): String {
        val pressKey = context.getString(R.string.shortcut_helper_add_shortcut_dialog_placeholder)
        val andConjunction =
            context.getString(R.string.shortcut_helper_key_combinations_and_conjunction)
        val orConjunction =
            context.getString(R.string.shortcut_helper_key_combinations_or_separator)
        val forwardSlash =
            context.getString(R.string.shortcut_helper_key_combinations_forward_slash)
        return buildString {
            append("$label, $pressKey")
            commands.forEachIndexed { i, shortcutCommand ->
                if (i > 0) {
                    append(", $orConjunction")
                }
                shortcutCommand.keys.forEachIndexed { j, shortcutKey ->
                    if (j > 0) {
                        append(" $andConjunction")
                    }
                    if (shortcutKey is ShortcutKey.Text) {
                        // Special handling for "/" as TalkBack will not read punctuation by
                        // default.
                        if (shortcutKey.value.equals("/")) {
                            append(" $forwardSlash")
                        } else {
                            append(" ${shortcutKey.value}")
                        }
                    } else if (shortcutKey is ShortcutKey.Icon.ResIdIcon) {
                        val keyLabel =
                            if (shortcutKey.drawableResId == metaModifierIconResId) {
                                ShortcutHelperKeys.modifierLabels[META_META_ON]
                            } else {
                                val keyCode =
                                    ShortcutHelperKeys.keyIcons.entries
                                        .firstOrNull { it.value == shortcutKey.drawableResId }
                                        ?.key
                                ShortcutHelperKeys.specialKeyLabels[keyCode]
                            }
                        if (keyLabel != null) {
                            append(" ${keyLabel.invoke(context)}")
                        }
                    } // No-Op when shortcutKey is ShortcutKey.Icon.DrawableIcon
                }
            }
        }
    }
}
