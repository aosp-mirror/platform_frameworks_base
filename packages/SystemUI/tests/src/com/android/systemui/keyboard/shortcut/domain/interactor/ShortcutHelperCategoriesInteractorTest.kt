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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.shortcutHelperCategoriesInteractor
import com.android.systemui.keyboard.shortcut.shortcutHelperMultiTaskingShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperSystemShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutHelperCategoriesInteractorTest : SysuiTestCase() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val kosmos = testKosmos().also { it.testDispatcher = UnconfinedTestDispatcher() }
    private val testScope = kosmos.testScope
    private val interactor = kosmos.shortcutHelperCategoriesInteractor
    private val helper = kosmos.shortcutHelperTestHelper
    private val systemShortcutsSource = kosmos.shortcutHelperSystemShortcutsSource
    private val multitaskingShortcutsSource = kosmos.shortcutHelperMultiTaskingShortcutsSource

    @Test
    fun categories_emptyByDefault() =
        testScope.runTest {
            val categories by collectLastValue(interactor.shortcutCategories)

            assertThat(categories).isEmpty()
        }

    @Test
    fun categories_stateActive_emitsAllCategoriesInOrder() =
        testScope.runTest {
            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    systemShortcutsSource.systemShortcutsCategory(),
                    multitaskingShortcutsSource.multitaskingShortcutCategory()
                )
                .inOrder()
        }

    @Test
    fun categories_stateInactiveAfterActive_emitsEmpty() =
        testScope.runTest {
            val categories by collectLastValue(interactor.shortcutCategories)
            helper.showFromActivity()
            helper.hideFromActivity()

            assertThat(categories).isEmpty()
        }
}
