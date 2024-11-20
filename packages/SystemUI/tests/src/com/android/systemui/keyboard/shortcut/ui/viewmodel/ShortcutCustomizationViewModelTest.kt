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

import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.hardware.input.fakeInputManager
import android.os.SystemClock
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import androidx.compose.ui.input.key.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shortcutCustomizationViewModelFactory
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.userTracker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutCustomizationViewModelTest : SysuiTestCase() {

    private val mockUserContext: Context = mock()
    private val kosmos =
        Kosmos().also {
            it.testCase = this
            it.userTracker = FakeUserTracker(onCreateCurrentUserContext = { mockUserContext })
        }
    private val testScope = kosmos.testScope
    private val inputManager = kosmos.fakeInputManager.inputManager
    private val helper = kosmos.shortcutHelperTestHelper
    private val viewModel = kosmos.shortcutCustomizationViewModelFactory.create()

    @Before
    fun setup() {
        helper.showFromActivity()
        whenever(mockUserContext.getSystemService(INPUT_SERVICE)).thenReturn(inputManager)
    }

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
            viewModel.onDialogDismissed()
            assertThat(uiState).isEqualTo(ShortcutCustomizationUiState.Inactive)
        }
    }

    @Test
    fun uiState_pressedKeys_emptyByDefault() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            assertThat((uiState as ShortcutCustomizationUiState.AddShortcutDialog).pressedKeys)
                .isEmpty()
        }
    }

    @Test
    fun onKeyPressed_handlesKeyEvents_whereActionKeyIsAlsoPressed() {
        testScope.runTest {
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            val isHandled = viewModel.onKeyPressed(keyDownEventWithActionKeyPressed)

            assertThat(isHandled).isTrue()
        }
    }

    @Test
    fun onKeyPressed_doesNotHandleKeyEvents_whenActionKeyIsNotAlsoPressed() {
        testScope.runTest {
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            val isHandled = viewModel.onKeyPressed(keyDownEventWithoutActionKeyPressed)

            assertThat(isHandled).isFalse()
        }
    }

    @Test
    fun onKeyPressed_convertsKeyEventsAndUpdatesUiStatesPressedKey() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            viewModel.onKeyPressed(keyDownEventWithActionKeyPressed)
            viewModel.onKeyPressed(keyUpEventWithActionKeyPressed)

            // Note that Action Key is excluded as it's already displayed on the UI
            assertThat((uiState as ShortcutCustomizationUiState.AddShortcutDialog).pressedKeys)
                .containsExactly(ShortcutKey.Text("Ctrl"), ShortcutKey.Text("A"))
        }
    }

    @Test
    fun uiState_pressedKeys_resetsToEmptyListAfterDialogIsDismissedAndReopened() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            viewModel.onKeyPressed(keyDownEventWithActionKeyPressed)
            viewModel.onKeyPressed(keyUpEventWithActionKeyPressed)

            // Note that Action Key is excluded as it's already displayed on the UI
            assertThat((uiState as ShortcutCustomizationUiState.AddShortcutDialog).pressedKeys)
                .containsExactly(ShortcutKey.Text("Ctrl"), ShortcutKey.Text("A"))

            // Close the dialog and show it again
            viewModel.onDialogDismissed()
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            assertThat((uiState as ShortcutCustomizationUiState.AddShortcutDialog).pressedKeys)
                .isEmpty()
        }
    }

    private val keyDownEventWithoutActionKeyPressed =
        KeyEvent(
            android.view.KeyEvent(
                /* downTime = */ SystemClock.uptimeMillis(),
                /* eventTime = */ SystemClock.uptimeMillis(),
                /* action = */ ACTION_DOWN,
                /* code = */ KEYCODE_A,
                /* repeat = */ 0,
                /* metaState = */ META_CTRL_ON,
            )
        )

    private val keyDownEventWithActionKeyPressed =
        KeyEvent(
            android.view.KeyEvent(
                /* downTime = */ SystemClock.uptimeMillis(),
                /* eventTime = */ SystemClock.uptimeMillis(),
                /* action = */ ACTION_DOWN,
                /* code = */ KEYCODE_A,
                /* repeat = */ 0,
                /* metaState = */ META_CTRL_ON or META_META_ON,
            )
        )

    private val keyUpEventWithActionKeyPressed =
        KeyEvent(
            android.view.KeyEvent(
                /* downTime = */ SystemClock.uptimeMillis(),
                /* eventTime = */ SystemClock.uptimeMillis(),
                /* action = */ ACTION_DOWN,
                /* code = */ KEYCODE_A,
                /* repeat = */ 0,
                /* metaState = */ 0,
            )
        )

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
            defaultCustomShortcutModifierKey =
                ShortcutKey.Icon.ResIdIcon(R.drawable.ic_ksh_key_meta),
            isDialogShowing = false,
        )
}
