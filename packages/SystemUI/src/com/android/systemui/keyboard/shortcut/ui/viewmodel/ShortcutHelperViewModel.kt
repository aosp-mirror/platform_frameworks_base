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

import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperCategoriesInteractor
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperStateInteractor
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.CurrentApp
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutsUiState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ShortcutHelperViewModel
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val stateInteractor: ShortcutHelperStateInteractor,
    categoriesInteractor: ShortcutHelperCategoriesInteractor,
) {

    val shouldShow =
        categoriesInteractor.shortcutCategories
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    val shortcutsUiState =
        categoriesInteractor.shortcutCategories
            .map {
                if (it.isEmpty()) {
                    ShortcutsUiState.Inactive
                } else {
                    ShortcutsUiState.Active(
                        shortcutCategories = it,
                        defaultSelectedCategory = getDefaultSelectedCategory(it),
                    )
                }
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Lazily,
                initialValue = ShortcutsUiState.Inactive
            )

    private fun getDefaultSelectedCategory(
        categories: List<ShortcutCategory>
    ): ShortcutCategoryType {
        val currentAppShortcuts = categories.firstOrNull { it.type is CurrentApp }
        return currentAppShortcuts?.type ?: categories.first().type
    }

    fun onViewClosed() {
        stateInteractor.onViewClosed()
    }

    fun onViewOpened() {
        stateInteractor.onViewOpened()
    }
}
