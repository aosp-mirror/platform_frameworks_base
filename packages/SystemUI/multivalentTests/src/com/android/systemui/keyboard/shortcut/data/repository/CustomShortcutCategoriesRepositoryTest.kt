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

package com.android.systemui.keyboard.shortcut.data.repository

import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.hardware.input.InputGestureData
import android.hardware.input.InputGestureData.createKeyTrigger
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS
import android.hardware.input.fakeInputManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CAPS_LOCK_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_FUNCTION_ON
import android.view.KeyEvent.META_META_LEFT_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_SHIFT_ON
import android.view.KeyEvent.META_SHIFT_RIGHT_ON
import android.view.KeyEvent.META_SYM_ON
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.hardware.input.Flags.FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES
import com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.customShortcutCategoriesRepository
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allCustomizableInputGesturesWithSimpleShortcutCombinations
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.customizableInputGestureWithUnknownKeyGestureType
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.expectedShortcutCategoriesWithSimpleShortcutCombination
import com.android.systemui.keyboard.shortcut.shared.model.KeyCombination
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class CustomShortcutCategoriesRepositoryTest : SysuiTestCase() {

    private val mockUserContext: Context = mock()
    private val kosmos =
        testKosmos().also {
            it.userTracker = FakeUserTracker(onCreateCurrentUserContext = { mockUserContext })
        }

    private val fakeInputManager = kosmos.fakeInputManager
    private val testScope = kosmos.testScope
    private val helper = kosmos.shortcutHelperTestHelper
    private val repo = kosmos.customShortcutCategoriesRepository

    @Before
    fun setup() {
        whenever(mockUserContext.getSystemService(INPUT_SERVICE))
            .thenReturn(fakeInputManager.inputManager)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_emitsCorrectlyConvertedShortcutCategories() {
        testScope.runTest {
            whenever(
                    fakeInputManager.inputManager.getCustomInputGestures(/* filter= */ anyOrNull())
                )
                .thenReturn(allCustomizableInputGesturesWithSimpleShortcutCombinations)

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            assertThat(categories)
                .containsExactlyElementsIn(expectedShortcutCategoriesWithSimpleShortcutCombination)
        }
    }

    @Test
    @DisableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_emitsEmptyListWhenFlagIsDisabled() {
        testScope.runTest {
            whenever(
                    fakeInputManager.inputManager.getCustomInputGestures(/* filter= */ anyOrNull())
                )
                .thenReturn(allCustomizableInputGesturesWithSimpleShortcutCombinations)

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            assertThat(categories).isEmpty()
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_ignoresUnknownKeyGestureTypes() {
        testScope.runTest {
            whenever(
                    fakeInputManager.inputManager.getCustomInputGestures(/* filter= */ anyOrNull())
                )
                .thenReturn(customizableInputGestureWithUnknownKeyGestureType)

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            assertThat(categories).isEmpty()
        }
    }

    @Test
    fun pressedKeys_isEmptyByDefault() {
        testScope.runTest {
            val pressedKeys by collectLastValue(repo.pressedKeys)
            assertThat(pressedKeys).isEmpty()

            helper.toggle(deviceId = 123)
            assertThat(pressedKeys).isEmpty()
        }
    }

    @Test
    fun pressedKeys_recognizesAllSupportedModifiers() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            val pressedKeys by collectLastValue(repo.pressedKeys)
            repo.updateUserKeyCombination(
                KeyCombination(modifiers = allSupportedModifiers, keyCode = null)
            )

            assertThat(pressedKeys)
                .containsExactly(
                    ShortcutKey.Icon.ResIdIcon(R.drawable.ic_ksh_key_meta),
                    ShortcutKey.Text("Ctrl"),
                    ShortcutKey.Text("Fn"),
                    ShortcutKey.Text("Shift"),
                    ShortcutKey.Text("Alt"),
                    ShortcutKey.Text("Sym"),
                )
        }
    }

    @Test
    fun pressedKeys_ignoresUnsupportedModifiers() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            val pressedKeys by collectLastValue(repo.pressedKeys)
            repo.updateUserKeyCombination(
                KeyCombination(modifiers = META_CAPS_LOCK_ON, keyCode = null)
            )

            assertThat(pressedKeys).isEmpty()
        }
    }

    @Test
    fun pressedKeys_assertCorrectConversion() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            val pressedKeys by collectLastValue(repo.pressedKeys)
            repo.updateUserKeyCombination(
                KeyCombination(modifiers = META_META_ON, keyCode = KEYCODE_SLASH)
            )

            assertThat(pressedKeys)
                .containsExactly(
                    ShortcutKey.Icon.ResIdIcon(R.drawable.ic_ksh_key_meta),
                    ShortcutKey.Text("/"),
                )
        }
    }

    @Test
    fun shortcutBeingCustomized_updatedOnCustomizationRequested() {
        testScope.runTest {
            repo.onCustomizationRequested(standardCustomizationRequestInfo)

            val shortcutBeingCustomized = repo.getShortcutBeingCustomized()

            assertThat(shortcutBeingCustomized).isEqualTo(standardCustomizationRequestInfo)
        }
    }

    @Test
    fun buildInputGestureDataForShortcutBeingCustomized_noShortcutBeingCustomized_returnsNull() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            repo.updateUserKeyCombination(standardKeyCombination)

            val inputGestureData = repo.buildInputGestureDataForShortcutBeingCustomized()

            assertThat(inputGestureData).isNull()
        }
    }

    @Test
    fun buildInputGestureDataForShortcutBeingCustomized_noKeyCombinationSelected_returnsNull() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            repo.onCustomizationRequested(standardCustomizationRequestInfo)

            val inputGestureData = repo.buildInputGestureDataForShortcutBeingCustomized()

            assertThat(inputGestureData).isNull()
        }
    }

    @Test
    fun buildInputGestureDataForShortcutBeingCustomized_successfullyBuildInputGestureData() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            repo.onCustomizationRequested(standardCustomizationRequestInfo)
            repo.updateUserKeyCombination(standardKeyCombination)
            val inputGestureData = repo.buildInputGestureDataForShortcutBeingCustomized()

            // using toString as we're testing for only structural equality not referential.
            // inputGestureData is a java class and isEqual Tests for referential equality
            // as well which would cause this assert to fail
            assertThat(inputGestureData.toString()).isEqualTo(standardInputGestureData.toString())
        }
    }

    private val standardCustomizationRequestInfo =
        ShortcutCustomizationRequestInfo.Add(
            label = "Open apps list",
            categoryType = ShortcutCategoryType.System,
            subCategoryLabel = "System controls",
        )

    private val standardKeyCombination =
        KeyCombination(
            modifiers = META_META_ON or META_SHIFT_ON or META_META_LEFT_ON or META_SHIFT_RIGHT_ON,
            keyCode = KEYCODE_A,
        )

    private val allSupportedModifiers =
        META_META_ON or
            META_CTRL_ON or
            META_FUNCTION_ON or
            META_SHIFT_ON or
            META_ALT_ON or
            META_SYM_ON

    private val standardInputGestureData =
        InputGestureData.Builder()
            .setKeyGestureType(KEY_GESTURE_TYPE_ALL_APPS)
            .setTrigger(
                createKeyTrigger(
                    /* keycode = */ standardKeyCombination.keyCode!!,
                    /* modifierState = */ standardKeyCombination.modifiers and allSupportedModifiers,
                )
            )
            .build()
}
