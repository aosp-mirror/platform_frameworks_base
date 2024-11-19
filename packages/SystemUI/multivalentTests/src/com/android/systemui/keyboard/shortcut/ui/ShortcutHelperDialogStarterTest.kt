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

package com.android.systemui.keyboard.shortcut.ui

import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.hardware.input.fakeInputManager
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyboard.shortcut.data.source.FakeKeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts
import com.android.systemui.keyboard.shortcut.shortcutCustomizationDialogStarterFactory
import com.android.systemui.keyboard.shortcut.shortcutHelperAppCategoriesShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperCurrentAppShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperInputShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperMultiTaskingShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperSystemShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.keyboard.shortcut.shortcutHelperViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.statusbar.phone.systemUIDialogFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutHelperDialogStarterTest : SysuiTestCase() {

    private val fakeSystemSource = FakeKeyboardShortcutGroupsSource()
    private val fakeMultiTaskingSource = FakeKeyboardShortcutGroupsSource()
    private val mockUserContext: Context = mock()
    private val kosmos =
        Kosmos().also {
            it.testCase = this
            it.testDispatcher = UnconfinedTestDispatcher()
            it.shortcutHelperSystemShortcutsSource = fakeSystemSource
            it.shortcutHelperMultiTaskingShortcutsSource = fakeMultiTaskingSource
            it.shortcutHelperAppCategoriesShortcutsSource = FakeKeyboardShortcutGroupsSource()
            it.shortcutHelperInputShortcutsSource = FakeKeyboardShortcutGroupsSource()
            it.shortcutHelperCurrentAppShortcutsSource = FakeKeyboardShortcutGroupsSource()
            it.userTracker = FakeUserTracker(onCreateCurrentUserContext = { mockUserContext })
        }

    private val inputManager = kosmos.fakeInputManager.inputManager
    private val testScope = kosmos.testScope
    private val testHelper = kosmos.shortcutHelperTestHelper
    private val dialogFactory = kosmos.systemUIDialogFactory
    private val coroutineScope = kosmos.applicationCoroutineScope
    private val viewModel = kosmos.shortcutHelperViewModel

    private val starter: ShortcutHelperDialogStarter =
        with(kosmos) {
            ShortcutHelperDialogStarter(
                coroutineScope,
                viewModel,
                shortcutCustomizationDialogStarterFactory,
                dialogFactory,
                activityStarter,
            )
        }

    @Before
    fun setUp() {
        fakeSystemSource.setGroups(TestShortcuts.systemGroups)
        fakeMultiTaskingSource.setGroups(TestShortcuts.multitaskingGroups)
        whenever(mockUserContext.getSystemService(INPUT_SERVICE)).thenReturn(inputManager)
    }

    @Test
    fun start_doesNotShowDialogByDefault() =
        testScope.runTest {
            starter.start()

            assertThat(starter.dialog).isNull()
        }

    @Test
    @UiThreadTest
    fun start_onToggle_showsDialog() =
        testScope.runTest {
            starter.start()

            testHelper.toggle(deviceId = 456)

            assertThat(starter.dialog?.isShowing).isTrue()
        }

    @Test
    fun start_onToggle_noShortcuts_doesNotStartActivity() =
        testScope.runTest {
            fakeSystemSource.setGroups(emptyList())
            fakeMultiTaskingSource.setGroups(emptyList())

            starter.start()

            testHelper.toggle(deviceId = 456)

            assertThat(starter.dialog).isNull()
        }

    @Test
    @UiThreadTest
    fun start_onRequestShowShortcuts_startsActivity() =
        testScope.runTest {
            starter.start()

            testHelper.showFromActivity()

            assertThat(starter.dialog?.isShowing).isTrue()
        }

    @Test
    fun start_onRequestShowShortcuts_noShortcuts_doesNotStartActivity() =
        testScope.runTest {
            fakeSystemSource.setGroups(emptyList())
            fakeMultiTaskingSource.setGroups(emptyList())
            starter.start()

            testHelper.showFromActivity()

            assertThat(starter.dialog).isNull()
        }

    @Test
    @UiThreadTest
    fun start_onRequestShowShortcuts_multipleTimes_startsActivityOnlyOnce() =
        testScope.runTest {
            starter.start()

            testHelper.showFromActivity()
            testHelper.showFromActivity()
            testHelper.showFromActivity()

            assertThat(starter.dialog?.isShowing).isTrue()
        }

    @Test
    @UiThreadTest
    fun start_onRequestShowShortcuts_multipleTimes_startsActivityOnlyWhenNotStarted() =
        testScope.runTest {
            starter.start()

            assertThat(starter.dialog).isNull()
            // No-op. Already hidden.
            testHelper.hideFromActivity()
            assertThat(starter.dialog).isNull()
            // No-op. Already hidden.
            testHelper.hideForSystem()
            assertThat(starter.dialog).isNull()
            // Show 1st time.
            testHelper.toggle(deviceId = 987)
            assertThat(starter.dialog).isNotNull()
            assertThat(starter.dialog?.isShowing).isTrue()
            // No-op. Already shown.
            testHelper.showFromActivity()
            assertThat(starter.dialog?.isShowing).isTrue()
            // Hidden.
            testHelper.hideFromActivity()
            assertThat(starter.dialog?.isShowing).isFalse()
            // No-op. Already hidden.
            testHelper.hideForSystem()
            assertThat(starter.dialog?.isShowing).isFalse()
            // Show 2nd time.
            testHelper.toggle(deviceId = 456)
            assertThat(starter.dialog?.isShowing).isTrue()
            // No-op. Already shown.
            testHelper.showFromActivity()
            assertThat(starter.dialog?.isShowing).isTrue()
        }
}
