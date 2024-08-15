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
import com.android.systemui.keyboard.shortcut.data.source.FakeKeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.InputMethodEditor
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MultiTasking
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.System
import com.android.systemui.keyboard.shortcut.shortcutHelperAppCategoriesShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperCategoriesInteractor
import com.android.systemui.keyboard.shortcut.shortcutHelperCurrentAppShortcutsSource
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutHelperCategoriesInteractorTest : SysuiTestCase() {

    private val systemShortcutsSource = FakeKeyboardShortcutGroupsSource()
    private val multitaskingShortcutsSource = FakeKeyboardShortcutGroupsSource()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val kosmos =
        testKosmos().also {
            it.testDispatcher = UnconfinedTestDispatcher()
            it.shortcutHelperSystemShortcutsSource = systemShortcutsSource
            it.shortcutHelperMultiTaskingShortcutsSource = multitaskingShortcutsSource
            it.shortcutHelperAppCategoriesShortcutsSource = FakeKeyboardShortcutGroupsSource()
            it.shortcutHelperCurrentAppShortcutsSource = FakeKeyboardShortcutGroupsSource()
        }

    private val testScope = kosmos.testScope
    private val interactor = kosmos.shortcutHelperCategoriesInteractor
    private val helper = kosmos.shortcutHelperTestHelper

    @Before
    fun setShortcuts() {
        helper.setImeShortcuts(TestShortcuts.imeGroups)
        systemShortcutsSource.setGroups(TestShortcuts.systemGroups)
        multitaskingShortcutsSource.setGroups(TestShortcuts.multitaskingGroups)
    }

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
                    TestShortcuts.systemCategory,
                    TestShortcuts.multitaskingCategory,
                    TestShortcuts.imeCategory,
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

    @Test
    fun categories_stateActive_imeShortcutsWithDuplicateLabels_emitsGroupedShortcuts() =
        testScope.runTest {
            helper.setImeShortcuts(TestShortcuts.groupsWithDuplicateShortcutLabels)

            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    TestShortcuts.systemCategory,
                    TestShortcuts.multitaskingCategory,
                    ShortcutCategory(
                        type = InputMethodEditor,
                        subCategories =
                            TestShortcuts.imeSubCategoriesWithGroupedDuplicatedShortcutLabels
                    ),
                )
                .inOrder()
        }

    @Test
    fun categories_stateActive_systemShortcutsWithDuplicateLabels_emitsGroupedShortcuts() =
        testScope.runTest {
            systemShortcutsSource.setGroups(TestShortcuts.groupsWithDuplicateShortcutLabels)

            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    ShortcutCategory(
                        type = System,
                        subCategories =
                            TestShortcuts.subCategoriesWithGroupedDuplicatedShortcutLabels
                    ),
                    TestShortcuts.multitaskingCategory,
                    TestShortcuts.imeCategory,
                )
                .inOrder()
        }

    @Test
    fun categories_stateActive_multiTaskingShortcutsWithDuplicateLabels_emitsGroupedShortcuts() =
        testScope.runTest {
            multitaskingShortcutsSource.setGroups(TestShortcuts.groupsWithDuplicateShortcutLabels)

            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    TestShortcuts.systemCategory,
                    ShortcutCategory(
                        type = MultiTasking,
                        subCategories =
                            TestShortcuts.subCategoriesWithGroupedDuplicatedShortcutLabels
                    ),
                    TestShortcuts.imeCategory,
                )
                .inOrder()
        }

    @Test
    fun categories_stateActive_imeShortcutsWithUnsupportedModifiers_discardUnsupported() =
        testScope.runTest {
            helper.setImeShortcuts(TestShortcuts.groupsWithUnsupportedModifier)
            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    TestShortcuts.systemCategory,
                    TestShortcuts.multitaskingCategory,
                    ShortcutCategory(
                        type = InputMethodEditor,
                        subCategories =
                            TestShortcuts.imeSubCategoriesWithUnsupportedModifiersRemoved
                    ),
                )
                .inOrder()
        }

    @Test
    fun categories_stateActive_systemShortcutsWithUnsupportedModifiers_discardUnsupported() =
        testScope.runTest {
            systemShortcutsSource.setGroups(TestShortcuts.groupsWithUnsupportedModifier)
            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    ShortcutCategory(
                        type = System,
                        subCategories = TestShortcuts.subCategoriesWithUnsupportedModifiersRemoved
                    ),
                    TestShortcuts.multitaskingCategory,
                    TestShortcuts.imeCategory,
                )
                .inOrder()
        }

    @Test
    fun categories_stateActive_multitaskingShortcutsWithUnsupportedModifiers_discardUnsupported() =
        testScope.runTest {
            multitaskingShortcutsSource.setGroups(TestShortcuts.groupsWithUnsupportedModifier)
            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    TestShortcuts.systemCategory,
                    ShortcutCategory(
                        type = MultiTasking,
                        subCategories = TestShortcuts.subCategoriesWithUnsupportedModifiersRemoved
                    ),
                    TestShortcuts.imeCategory,
                )
                .inOrder()
        }

    @Test
    fun categories_stateActive_systemShortcutsWithOnlyUnsupportedModifiers_discardsCategory() =
        testScope.runTest {
            systemShortcutsSource.setGroups(TestShortcuts.groupsWithOnlyUnsupportedModifiers)
            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    TestShortcuts.multitaskingCategory,
                    TestShortcuts.imeCategory,
                )
                .inOrder()
        }

    @Test
    fun categories_stateActive_multitaskingShortcutsWitOnlyUnsupportedModifiers_discardsCategory() =
        testScope.runTest {
            multitaskingShortcutsSource.setGroups(TestShortcuts.groupsWithOnlyUnsupportedModifiers)
            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    TestShortcuts.systemCategory,
                    TestShortcuts.imeCategory,
                )
                .inOrder()
        }
}
