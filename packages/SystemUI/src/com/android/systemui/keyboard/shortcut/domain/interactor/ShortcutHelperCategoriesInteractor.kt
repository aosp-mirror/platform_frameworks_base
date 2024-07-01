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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperCategoriesRepository
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@SysUISingleton
class ShortcutHelperCategoriesInteractor
@Inject
constructor(
    categoriesRepository: ShortcutHelperCategoriesRepository,
) {

    private val systemsShortcutCategory = categoriesRepository.systemShortcutsCategory
    private val multitaskingShortcutsCategory = categoriesRepository.multitaskingShortcutsCategory
    private val imeShortcutsCategory =
        categoriesRepository.imeShortcutsCategory.map { groupSubCategoriesInCategory(it) }

    val shortcutCategories: Flow<List<ShortcutCategory>> =
        combine(systemsShortcutCategory, multitaskingShortcutsCategory, imeShortcutsCategory) {
            shortcutCategories ->
            shortcutCategories.filterNotNull()
        }

    private fun groupSubCategoriesInCategory(
        shortcutCategory: ShortcutCategory?
    ): ShortcutCategory? {
        if (shortcutCategory == null) {
            return null
        }
        val subCategoriesWithGroupedShortcuts =
            shortcutCategory.subCategories.map {
                ShortcutSubCategory(
                    label = it.label,
                    shortcuts = groupShortcutsInSubcategory(it.shortcuts)
                )
            }
        return ShortcutCategory(
            type = shortcutCategory.type,
            subCategories = subCategoriesWithGroupedShortcuts
        )
    }

    private fun groupShortcutsInSubcategory(shortcuts: List<Shortcut>) =
        shortcuts
            .groupBy { it.label }
            .entries
            .map { (commonLabel, groupedShortcuts) ->
                Shortcut(label = commonLabel, commands = groupedShortcuts.flatMap { it.commands })
            }
}
