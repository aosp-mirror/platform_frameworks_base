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

package com.android.systemui.keyboard.shortcut.ui.viewmodel

import android.app.role.RoleManager
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperCategoriesInteractor
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperStateInteractor
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.CurrentApp
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutsUiState
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class ShortcutHelperViewModel
@Inject
constructor(
    private val roleManager: RoleManager,
    private val userTracker: UserTracker,
    @Background private val backgroundScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val stateInteractor: ShortcutHelperStateInteractor,
    categoriesInteractor: ShortcutHelperCategoriesInteractor,
) {

    private val searchQuery = MutableStateFlow("")

    val shouldShow =
        categoriesInteractor.shortcutCategories
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    val shortcutsUiState =
        combine(searchQuery, categoriesInteractor.shortcutCategories) { query, categories ->
                if (categories.isEmpty()) {
                    ShortcutsUiState.Inactive
                } else {
                    /* temporarily hiding launcher shortcut categories until b/327141011
                     * is completed. */
                    val categoriesWithLauncherExcluded = excludeLauncherApp(categories)
                    val filteredCategories =
                        filterCategoriesBySearchQuery(query, categoriesWithLauncherExcluded)
                    ShortcutsUiState.Active(
                        searchQuery = query,
                        shortcutCategories = filteredCategories,
                        defaultSelectedCategory = getDefaultSelectedCategory(filteredCategories),
                    )
                }
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Lazily,
                initialValue = ShortcutsUiState.Inactive
            )

    private suspend fun excludeLauncherApp(
        categories: List<ShortcutCategory>
    ): List<ShortcutCategory> {
        val launcherAppCategory =
            categories.firstOrNull { it.type is CurrentApp && isLauncherApp(it.type.packageName) }
        return if (launcherAppCategory != null) {
            categories - launcherAppCategory
        } else {
            categories
        }
    }

    private suspend fun getDefaultSelectedCategory(
        categories: List<ShortcutCategory>
    ): ShortcutCategoryType? {
        val currentAppShortcuts =
            categories.firstOrNull { it.type is CurrentApp && !isLauncherApp(it.type.packageName) }
        return currentAppShortcuts?.type ?: categories.firstOrNull()?.type
    }

    private suspend fun isLauncherApp(packageName: String): Boolean {
        return withContext(backgroundDispatcher) {
            roleManager
                .getRoleHoldersAsUser(RoleManager.ROLE_HOME, userTracker.userHandle)
                .firstOrNull() == packageName
        }
    }

    private fun filterCategoriesBySearchQuery(
        query: String,
        categories: List<ShortcutCategory>
    ): List<ShortcutCategory> {
        val lowerCaseTrimmedQuery = query.trim().lowercase()
        if (lowerCaseTrimmedQuery.isEmpty()) {
            return categories
        }
        return categories
            .map { category ->
                category.copy(
                    subCategories =
                        filterSubCategoriesBySearchQuery(
                            subCategories = category.subCategories,
                            query = lowerCaseTrimmedQuery,
                        )
                )
            }
            .filter { it.subCategories.isNotEmpty() }
    }

    private fun filterSubCategoriesBySearchQuery(
        subCategories: List<ShortcutSubCategory>,
        query: String
    ) =
        subCategories
            .map { subCategory ->
                subCategory.copy(
                    shortcuts = filterShortcutsBySearchQuery(subCategory.shortcuts, query)
                )
            }
            .filter { it.shortcuts.isNotEmpty() }

    private fun filterShortcutsBySearchQuery(shortcuts: List<Shortcut>, query: String) =
        shortcuts.filter { shortcut -> shortcut.label.trim().lowercase().contains(query) }

    fun onViewClosed() {
        stateInteractor.onViewClosed()
    }

    fun onViewOpened() {
        stateInteractor.onViewOpened()
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }
}
