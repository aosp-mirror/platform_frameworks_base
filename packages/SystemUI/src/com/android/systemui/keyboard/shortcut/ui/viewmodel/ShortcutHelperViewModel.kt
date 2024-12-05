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
import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalSplit
import com.android.compose.ui.graphics.painter.DrawablePainter
import com.android.systemui.Flags.keyboardShortcutHelperShortcutCustomizer
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperCategoriesInteractor
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperStateInteractor
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.CurrentApp
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.ui.model.IconSource
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCategoryUi
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutsUiState
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
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
import javax.inject.Inject

class ShortcutHelperViewModel
@Inject
constructor(
    private val context: Context,
    private val roleManager: RoleManager,
    private val userTracker: UserTracker,
    @Background private val backgroundScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val stateInteractor: ShortcutHelperStateInteractor,
    categoriesInteractor: ShortcutHelperCategoriesInteractor,
) {

    private val searchQuery = MutableStateFlow("")
    private val userContext = userTracker.createCurrentUserContext(userTracker.userContext)

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
                    val shortcutCategoriesUi = convertCategoriesModelToUiModel(filteredCategories)
                    ShortcutsUiState.Active(
                        searchQuery = query,
                        shortcutCategories = shortcutCategoriesUi,
                        defaultSelectedCategory = getDefaultSelectedCategory(filteredCategories),
                        isShortcutCustomizerFlagEnabled = keyboardShortcutHelperShortcutCustomizer(),
                        shouldShowResetButton = shouldShowResetButton(shortcutCategoriesUi)
                    )
                }
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Lazily,
                initialValue = ShortcutsUiState.Inactive,
            )

    private fun shouldShowResetButton(categoriesUi: List<ShortcutCategoryUi>): Boolean {
        return categoriesUi.any { it.containsCustomShortcuts }
    }

    private fun convertCategoriesModelToUiModel(
        categories: List<ShortcutCategory>
    ): List<ShortcutCategoryUi> {
        return categories.map { category ->
            ShortcutCategoryUi(
                label = getShortcutCategoryLabel(category.type),
                iconSource = getShortcutCategoryIcon(category.type),
                shortcutCategory = category,
            )
        }
    }

    private fun getShortcutCategoryIcon(type: ShortcutCategoryType): IconSource {
        return when (type) {
            ShortcutCategoryType.System -> IconSource(imageVector = Icons.Default.Tv)
            ShortcutCategoryType.MultiTasking ->
                IconSource(imageVector = Icons.Default.VerticalSplit)
            ShortcutCategoryType.InputMethodEditor ->
                IconSource(imageVector = Icons.Default.Keyboard)
            ShortcutCategoryType.AppCategories -> IconSource(imageVector = Icons.Default.Apps)
            is CurrentApp -> {
                try {
                    val iconDrawable =
                        userContext.packageManager.getApplicationIcon(type.packageName)
                    IconSource(painter = DrawablePainter(drawable = iconDrawable))
                } catch (e: NameNotFoundException) {
                    Log.w(
                        "ShortcutHelperViewModel",
                        "Package not found when retrieving icon for ${type.packageName}",
                    )
                    IconSource(imageVector = Icons.Default.Android)
                }
            }
        }
    }

    private fun getShortcutCategoryLabel(type: ShortcutCategoryType): String =
        when (type) {
            ShortcutCategoryType.System ->
                context.getString(R.string.shortcut_helper_category_system)
            ShortcutCategoryType.MultiTasking ->
                context.getString(R.string.shortcut_helper_category_multitasking)
            ShortcutCategoryType.InputMethodEditor ->
                context.getString(R.string.shortcut_helper_category_input)
            ShortcutCategoryType.AppCategories ->
                context.getString(R.string.shortcut_helper_category_app_shortcuts)
            is CurrentApp -> getApplicationLabelForCurrentApp(type)
        }

    private fun getApplicationLabelForCurrentApp(type: CurrentApp): String {
        try {
            val packageManagerForUser = userContext.packageManager
            val currentAppInfo =
                packageManagerForUser.getApplicationInfo(type.packageName, /* flags= */ 0)
            return packageManagerForUser.getApplicationLabel(currentAppInfo).toString()
        } catch (e: NameNotFoundException) {
            Log.w(
                "ShortcutHelperViewModel",
                "Package Not found when retrieving Label for ${type.packageName}",
            )
            return "Current App"
        }
    }

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
        categories: List<ShortcutCategory>,
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
        query: String,
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
