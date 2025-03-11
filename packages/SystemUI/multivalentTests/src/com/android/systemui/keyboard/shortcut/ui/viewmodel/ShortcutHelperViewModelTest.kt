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
import android.app.role.mockRoleManager
import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.hardware.input.InputGestureData
import android.hardware.input.fakeInputManager
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyboard.shortcut.data.source.FakeKeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allAppsInputGestureData
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.CurrentApp
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MultiTasking
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.System
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.shared.model.shortcut
import com.android.systemui.keyboard.shortcut.shortcutHelperAppCategoriesShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperCurrentAppShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperInputShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperMultiTaskingShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperSystemShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.keyboard.shortcut.shortcutHelperViewModel
import com.android.systemui.keyboard.shortcut.ui.model.IconSource
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCategoryUi
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutsUiState
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.sysUiState
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SHORTCUT_HELPER_SHOWING
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutHelperViewModelTest : SysuiTestCase() {

    private val fakeSystemSource = FakeKeyboardShortcutGroupsSource()
    private val fakeMultiTaskingSource = FakeKeyboardShortcutGroupsSource()
    private val fakeCurrentAppsSource = FakeKeyboardShortcutGroupsSource()
    private val mockPackageManager: PackageManager = mock()
    private val mockUserContext: Context = mock()
    private val mockApplicationInfo: ApplicationInfo = mock()

    private val kosmos =
        Kosmos().also {
            it.testCase = this
            it.testDispatcher = UnconfinedTestDispatcher()
            it.shortcutHelperSystemShortcutsSource = fakeSystemSource
            it.shortcutHelperMultiTaskingShortcutsSource = fakeMultiTaskingSource
            it.shortcutHelperAppCategoriesShortcutsSource = FakeKeyboardShortcutGroupsSource()
            it.shortcutHelperInputShortcutsSource = FakeKeyboardShortcutGroupsSource()
            it.shortcutHelperCurrentAppShortcutsSource = fakeCurrentAppsSource
            it.userTracker = FakeUserTracker(onCreateCurrentUserContext = { mockUserContext })
        }

    private val testScope = kosmos.testScope
    private val testHelper = kosmos.shortcutHelperTestHelper
    private val sysUiState = kosmos.sysUiState
    private val fakeUserTracker = kosmos.fakeUserTracker
    private val mockRoleManager = kosmos.mockRoleManager
    private val inputManager = kosmos.fakeInputManager.inputManager
    private val viewModel = kosmos.shortcutHelperViewModel


    @Before
    fun setUp() {
        fakeSystemSource.setGroups(TestShortcuts.systemGroups)
        fakeMultiTaskingSource.setGroups(TestShortcuts.multitaskingGroups)
        fakeCurrentAppsSource.setGroups(TestShortcuts.currentAppGroups)
        whenever(mockPackageManager.getApplicationInfo(anyString(), eq(0))).thenReturn(mockApplicationInfo)
        whenever(mockPackageManager.getApplicationLabel(mockApplicationInfo)).thenReturn("Current App")
        whenever(mockPackageManager.getApplicationIcon(anyString())).thenThrow(NameNotFoundException())
        whenever(mockUserContext.packageManager).thenReturn(mockPackageManager)
        whenever(mockUserContext.getSystemService(INPUT_SERVICE)).thenReturn(inputManager)
    }

    @Test
    fun shouldShow_falseByDefault() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            assertThat(shouldShow).isFalse()
        }

    @Test
    fun shouldShow_trueAfterShowRequested() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.showFromActivity()

            assertThat(shouldShow).isTrue()
        }

    @Test
    fun shouldShow_trueAfterToggleRequested() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.toggle(deviceId = 123)

            assertThat(shouldShow).isTrue()
        }

    @Test
    fun shouldShow_falseAfterToggleTwice() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.toggle(deviceId = 123)
            testHelper.toggle(deviceId = 123)

            assertThat(shouldShow).isFalse()
        }

    @Test
    fun shouldShow_falseAfterViewClosed() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.toggle(deviceId = 567)
            viewModel.onViewClosed()

            assertThat(shouldShow).isFalse()
        }

    @Test
    fun shouldShow_falseAfterCloseSystemDialogs() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.showFromActivity()
            testHelper.hideThroughCloseSystemDialogs()

            assertThat(shouldShow).isFalse()
        }

    @Test
    fun shouldShow_doesNotEmitDuplicateValues() =
        testScope.runTest {
            val shouldShowValues by collectValues(viewModel.shouldShow)

            testHelper.hideForSystem()
            testHelper.toggle(deviceId = 987)
            testHelper.showFromActivity()
            viewModel.onViewClosed()
            testHelper.hideFromActivity()
            testHelper.hideForSystem()
            testHelper.toggle(deviceId = 456)
            testHelper.showFromActivity()

            assertThat(shouldShowValues).containsExactly(false, true, false, true).inOrder()
        }

    @Test
    fun shouldShow_emitsLatestValueToNewSubscribers() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.showFromActivity()

            val shouldShowNew by collectLastValue(viewModel.shouldShow)
            assertThat(shouldShowNew).isEqualTo(shouldShow)
        }

    @Test
    fun sysUiStateFlag_disabledByDefault() =
        testScope.runTest {
            assertThat(sysUiState.isFlagEnabled(SYSUI_STATE_SHORTCUT_HELPER_SHOWING)).isFalse()
        }

    @Test
    fun sysUiStateFlag_trueAfterViewOpened() =
        testScope.runTest {
            viewModel.onViewOpened()

            assertThat(sysUiState.isFlagEnabled(SYSUI_STATE_SHORTCUT_HELPER_SHOWING)).isTrue()
        }

    @Test
    fun sysUiStateFlag_falseAfterViewClosed() =
        testScope.runTest {
            viewModel.onViewOpened()
            viewModel.onViewClosed()

            assertThat(sysUiState.isFlagEnabled(SYSUI_STATE_SHORTCUT_HELPER_SHOWING)).isFalse()
        }

    @Test
    fun shortcutsUiState_inactiveByDefault() =
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            assertThat(uiState).isEqualTo(ShortcutsUiState.Inactive)
        }

    @Test
    fun shortcutsUiState_featureActive_emitsActive() =
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()

            assertThat(uiState).isInstanceOf(ShortcutsUiState.Active::class.java)
        }

    @Test
    fun shortcutsUiState_noCurrentAppCategory_defaultSelectedCategoryIsSystem() =
        testScope.runTest {
            fakeCurrentAppsSource.setGroups(emptyList())

            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.defaultSelectedCategory).isEqualTo(System)
        }

    @Test
    fun shortcutsUiState_currentAppCategoryPresent_currentAppIsDefaultSelected() =
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.defaultSelectedCategory)
                .isEqualTo(CurrentApp(TestShortcuts.currentAppPackageName))
        }

    @Test
    fun shortcutsUiState_currentAppIsLauncher_defaultSelectedCategoryIsSystem() =
        testScope.runTest {
            whenever(
                mockRoleManager.getRoleHoldersAsUser(
                    RoleManager.ROLE_HOME,
                    fakeUserTracker.userHandle,
                )
            )
                .thenReturn(listOf(TestShortcuts.currentAppPackageName))
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.defaultSelectedCategory).isEqualTo(System)
        }

    @Test
    fun shortcutsUiState_userTypedQuery_filtersMatchingShortcutLabels() =
        testScope.runTest {
            fakeSystemSource.setGroups(
                groupWithShortcutLabels("first Foo shortcut1", "first bar shortcut1"),
                groupWithShortcutLabels(
                    "second foO shortcut2",
                    "second bar shortcut2",
                    groupLabel = SECOND_SIMPLE_GROUP_LABEL,
                ),
            )
            fakeMultiTaskingSource.setGroups(
                groupWithShortcutLabels("third FoO shortcut1", "third bar shortcut1")
            )
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()
            viewModel.onSearchQueryChanged("foo")

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.shortcutCategories)
                .containsExactly(
                    ShortcutCategoryUi(
                        label = "System",
                        iconSource = IconSource(imageVector = Icons.Default.Tv),
                        shortcutCategory =
                        ShortcutCategory(
                            System,
                            subCategoryWithShortcutLabels("first Foo shortcut1"),
                            subCategoryWithShortcutLabels(
                                "second foO shortcut2",
                                subCategoryLabel = SECOND_SIMPLE_GROUP_LABEL,
                            ),
                        ),
                    ),
                    ShortcutCategoryUi(
                        label = "Multitasking",
                        iconSource = IconSource(imageVector = Icons.Default.VerticalSplit),
                        shortcutCategory =
                        ShortcutCategory(
                            MultiTasking,
                            subCategoryWithShortcutLabels("third FoO shortcut1"),
                        ),
                    ),
                )
        }

    @Test
    fun shortcutsUiState_userTypedQuery_noMatch_returnsEmptyList() =
        testScope.runTest {
            fakeSystemSource.setGroups(
                groupWithShortcutLabels("first Foo shortcut1", "first bar shortcut1"),
                groupWithShortcutLabels("second foO shortcut2", "second bar shortcut2"),
            )
            fakeMultiTaskingSource.setGroups(
                groupWithShortcutLabels("third FoO shortcut1", "third bar shortcut1")
            )
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()
            viewModel.onSearchQueryChanged("unmatched query")

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.shortcutCategories).isEmpty()
        }

    @Test
    fun shortcutsUiState_userTypedQuery_noMatch_returnsNullDefaultSelectedCategory() =
        testScope.runTest {
            fakeSystemSource.setGroups(
                groupWithShortcutLabels("first Foo shortcut1", "first bar shortcut1"),
                groupWithShortcutLabels("second foO shortcut2", "second bar shortcut2"),
            )
            fakeMultiTaskingSource.setGroups(
                groupWithShortcutLabels("third FoO shortcut1", "third bar shortcut1")
            )
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()
            viewModel.onSearchQueryChanged("unmatched query")

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.defaultSelectedCategory).isNull()
        }

    @Test
    fun shortcutsUiState_userTypedQuery_changesDefaultSelectedCategoryToFirstMatchingCategory() =
        testScope.runTest {
            fakeSystemSource.setGroups(groupWithShortcutLabels("first shortcut"))
            fakeMultiTaskingSource.setGroups(groupWithShortcutLabels("second shortcut"))
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()
            viewModel.onSearchQueryChanged("second")

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.defaultSelectedCategory).isEqualTo(MultiTasking)
        }

    @Test
    fun shortcutsUiState_userTypedQuery_multipleCategoriesMatch_currentAppIsDefaultSelected() =
        testScope.runTest {
            fakeSystemSource.setGroups(groupWithShortcutLabels("first shortcut"))
            fakeMultiTaskingSource.setGroups(groupWithShortcutLabels("second shortcut"))
            fakeCurrentAppsSource.setGroups(groupWithShortcutLabels("third shortcut"))
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()
            viewModel.onSearchQueryChanged("shortcut")

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.defaultSelectedCategory).isInstanceOf(CurrentApp::class.java)
        }

    @Test
    fun shortcutsUiState_shouldShowResetButton_isFalseWhenThereAreNoCustomShortcuts() =
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.shouldShowResetButton).isFalse()
        }

    @Test
    fun shortcutsUiState_shouldShowResetButton_isTrueWhenThereAreCustomShortcuts() =
        testScope.runTest {
            whenever(
                inputManager.getCustomInputGestures(/* filter= */ InputGestureData.Filter.KEY)
            ).thenReturn(listOf(allAppsInputGestureData))
            val uiState by collectLastValue(viewModel.shortcutsUiState)

            testHelper.showFromActivity()

            val activeUiState = uiState as ShortcutsUiState.Active
            assertThat(activeUiState.shouldShowResetButton).isTrue()
        }

    private fun groupWithShortcutLabels(
        vararg shortcutLabels: String,
        groupLabel: String = FIRST_SIMPLE_GROUP_LABEL,
    ) =
        KeyboardShortcutGroup(groupLabel, shortcutLabels.map { simpleShortcutInfo(it) }).apply {
            packageName = "test.package.name"
        }

    private fun simpleShortcutInfo(label: String) =
        KeyboardShortcutInfo(label, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)

    private fun subCategoryWithShortcutLabels(
        vararg shortcutLabels: String,
        subCategoryLabel: String = FIRST_SIMPLE_GROUP_LABEL,
    ) =
        ShortcutSubCategory(
            label = subCategoryLabel,
            shortcuts = shortcutLabels.map { simpleShortcut(it) },
        )

    private fun simpleShortcut(label: String) =
        shortcut(label) {
            command {
                key("Ctrl")
                key("A")
            }
            contentDescription { "$label, Press key Ctrl plus A" }
        }

    companion object {
        private const val FIRST_SIMPLE_GROUP_LABEL = "simple group 1"
        private const val SECOND_SIMPLE_GROUP_LABEL = "simple group 2"
    }
}
