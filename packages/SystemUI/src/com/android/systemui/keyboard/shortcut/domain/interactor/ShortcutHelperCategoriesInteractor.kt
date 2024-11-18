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

import com.android.systemui.Flags.keyboardShortcutHelperShortcutCustomizer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutCategoriesRepository
import com.android.systemui.keyboard.shortcut.qualifiers.CustomShortcutCategories
import com.android.systemui.keyboard.shortcut.qualifiers.DefaultShortcutCategories
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

@SysUISingleton
class ShortcutHelperCategoriesInteractor
@Inject
constructor(
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
                Shortcut(
                    label = commonLabel,
                    icon = groupedShortcuts.firstOrNull()?.icon,
                    commands = groupedShortcuts.flatMap { it.commands },
                )
            }
}
