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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shortcutCustomizationViewModelFactory
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutCustomizationViewModelTest : SysuiTestCase() {

    private val kosmos = Kosmos().also { it.testCase = this }
    private val testScope = kosmos.testScope
    private val viewModel = kosmos.shortcutCustomizationViewModelFactory.create()

    @Test
    fun uiState_inactiveByDefault() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            assertThat(uiState).isEqualTo(ShortcutCustomizationUiState.Inactive)
        }
    }

    @Test
    fun uiState_correctlyUpdatedWhenAddShortcutCustomizationIsRequested() {
        testScope.runTest {
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            assertThat(uiState).isEqualTo(expectedStandardAddShortcutUiState)
        }
    }

    @Test
    fun uiState_consumedOnAddDialogShown() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            viewModel.onAddShortcutDialogShown()

            assertThat((uiState as ShortcutCustomizationUiState.AddShortcutDialog).isDialogShowing)
                .isTrue()
        }
    }

    @Test
    fun uiState_inactiveAfterDialogIsDismissed() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            viewModel.onAddShortcutDialogShown()
            viewModel.onAddShortcutDialogDismissed()
            assertThat(uiState).isEqualTo(ShortcutCustomizationUiState.Inactive)
        }
    }

    private val standardAddShortcutRequest =
        ShortcutCustomizationRequestInfo.Add(
            label = "Standard shortcut",
            categoryType = ShortcutCategoryType.System,
            subCategoryLabel = "Standard subcategory",
        )

    private val expectedStandardAddShortcutUiState =
        ShortcutCustomizationUiState.AddShortcutDialog(
            shortcutLabel = "Standard shortcut",
            shouldShowErrorMessage = false,
            isValidKeyCombination = false,
            defaultCustomShortcutModifierKey =
                ShortcutKey.Icon.ResIdIcon(R.drawable.ic_ksh_key_meta),
            isDialogShowing = false,
        )
}
