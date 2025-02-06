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
import android.hardware.input.InputGestureData
import android.hardware.input.InputGestureData.createKeyTrigger
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_OTHER
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_HOME
import android.hardware.input.fakeInputManager
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allAppsInputGestureData
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allAppsShortcutAddRequest
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allAppsShortcutDeleteRequest
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.keyDownEventWithActionKeyPressed
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.keyDownEventWithoutActionKeyPressed
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.keyUpEventWithActionKeyPressed
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.standardAddShortcutRequest
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shortcutCustomizationViewModelFactory
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState.AddShortcutDialog
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState.DeleteShortcutDialog
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState.ResetShortcutDialog
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
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
        testKosmos().useUnconfinedTestDispatcher().also {
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
        testScope.backgroundScope.launch { viewModel.activate() }
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

            assertThat(uiState)
                .isEqualTo(
                    AddShortcutDialog(
                        shortcutLabel = "Standard shortcut",
                        defaultCustomShortcutModifierKey =
                            ShortcutKey.Icon.ResIdIcon(R.drawable.ic_ksh_key_meta),
                    )
                )
        }
    }

    @Test
    fun uiState_correctlyUpdatedWhenResetShortcutCustomizationIsRequested() {
        testScope.runTest {
            viewModel.onShortcutCustomizationRequested(ShortcutCustomizationRequestInfo.Reset)
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            assertThat(uiState).isEqualTo(ResetShortcutDialog)
        }
    }

    @Test
    fun uiState_correctlyUpdatedWhenDeleteShortcutCustomizationIsRequested() {
        testScope.runTest {
            viewModel.onShortcutCustomizationRequested(allAppsShortcutDeleteRequest)
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            assertThat(uiState).isEqualTo(DeleteShortcutDialog)
        }
    }

    @Test
    fun uiState_inactiveAfterDialogIsDismissed() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            viewModel.onDialogDismissed()
            assertThat(uiState).isEqualTo(ShortcutCustomizationUiState.Inactive)
        }
    }

    @Test
    fun uiState_pressedKeys_emptyByDefault() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            assertThat((uiState as AddShortcutDialog).pressedKeys).isEmpty()
        }
    }

    @Test
    fun uiState_becomeInactiveAfterSuccessfullySettingShortcut() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            openAddShortcutDialogAndSetShortcut()

            assertThat(uiState).isEqualTo(ShortcutCustomizationUiState.Inactive)
        }
    }

    @Test
    fun uiState_errorMessage_isEmptyByDefault() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(allAppsShortcutAddRequest)

            assertThat((uiState as AddShortcutDialog).errorMessage).isEmpty()
        }
    }

    @Test
    fun uiState_errorMessage_onKeyPressed_isKeyCombinationInUse_whenKeyCombinationAlreadyExists() {
        testScope.runTest {
            inputManager.addCustomInputGesture(buildSimpleInputGestureWithMetaCtrlATrigger())
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            openAddShortcutDialogAndPressKeyCombination()

            assertThat((uiState as AddShortcutDialog).errorMessage)
                .isEqualTo(
                    context.getString(
                        R.string.shortcut_customizer_key_combination_in_use_error_message
                    )
                )
        }
    }

    @Test
    fun uiState_errorMessage_onKeyPressed_isEmpty_whenKeyCombinationIsAvailable() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            openAddShortcutDialogAndPressKeyCombination()

            assertThat((uiState as AddShortcutDialog).errorMessage).isEmpty()
        }
    }

    @Test
    fun uiState_errorMessage_onSetShortcut_isKeyCombinationInUse_whenKeyCombinationAlreadyExists() {
        testScope.runTest {
            inputManager.addCustomInputGesture(buildSimpleInputGestureWithMetaCtrlATrigger())
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            openAddShortcutDialogAndSetShortcut()

            assertThat((uiState as AddShortcutDialog).errorMessage)
                .isEqualTo(
                    context.getString(
                        R.string.shortcut_customizer_key_combination_in_use_error_message
                    )
                )
        }
    }

    @Test
    fun uiState_errorMessage_onSetShortcut_isKeyCombinationInUse_whenKeyCombinationIsReserved() {
        testScope.runTest {
            inputManager.addCustomInputGesture(buildSimpleInputGestureWithMetaCtrlATrigger())
            kosmos.fakeInputManager.addCustomInputGestureErrorCode =
                CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            openAddShortcutDialogAndSetShortcut()

            assertThat((uiState as AddShortcutDialog).errorMessage)
                .isEqualTo(
                    context.getString(
                        R.string.shortcut_customizer_key_combination_in_use_error_message
                    )
                )
        }
    }

    @Test
    fun uiState_errorMessage_onSetShortcut_isGenericError_whenErrorIsUnknown() {
        testScope.runTest {
            inputManager.addCustomInputGesture(buildSimpleInputGestureWithMetaCtrlATrigger())
            kosmos.fakeInputManager.addCustomInputGestureErrorCode =
                CUSTOM_INPUT_GESTURE_RESULT_ERROR_OTHER
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            openAddShortcutDialogAndSetShortcut()

            assertThat((uiState as AddShortcutDialog).errorMessage)
                .isEqualTo(context.getString(R.string.shortcut_customizer_generic_error_message))
        }
    }

    @Test
    fun uiState_becomesInactiveAfterSuccessfullyDeletingShortcut() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            inputManager.addCustomInputGesture(allAppsInputGestureData)

            openDeleteShortcutDialogAndDeleteShortcut()

            assertThat(uiState).isEqualTo(ShortcutCustomizationUiState.Inactive)
        }
    }

    @Test
    fun uiState_becomesInactiveAfterSuccessfullyResettingShortcuts() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)

            openResetShortcutDialogAndResetAllCustomShortcuts()

            assertThat(uiState).isEqualTo(ShortcutCustomizationUiState.Inactive)
        }
    }

    @Test
    fun onShortcutKeyCombinationSelected_handlesKeyEvents_whereActionKeyIsAlsoPressed() {
        testScope.runTest {
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            val isHandled =
                viewModel.onShortcutKeyCombinationSelected(keyDownEventWithActionKeyPressed)

            assertThat(isHandled).isTrue()
        }
    }

    @Test
    fun onShortcutKeyCombinationSelected_doesNotHandleKeyEvents_whenActionKeyIsNotAlsoPressed() {
        testScope.runTest {
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            val isHandled =
                viewModel.onShortcutKeyCombinationSelected(keyDownEventWithoutActionKeyPressed)

            assertThat(isHandled).isFalse()
        }
    }

    @Test
    fun onShortcutKeyCombinationSelected_convertsKeyEventsAndUpdatesUiStatesPressedKey() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            viewModel.onShortcutKeyCombinationSelected(keyDownEventWithActionKeyPressed)
            viewModel.onShortcutKeyCombinationSelected(keyUpEventWithActionKeyPressed)

            // Note that Action Key is excluded as it's already displayed on the UI
            assertThat((uiState as AddShortcutDialog).pressedKeys)
                .containsExactly(ShortcutKey.Text("Ctrl"), ShortcutKey.Text("A"))
        }
    }

    @Test
    fun uiState_pressedKeys_resetsToEmptyListAfterDialogIsDismissedAndReopened() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            viewModel.onShortcutKeyCombinationSelected(keyDownEventWithActionKeyPressed)
            viewModel.onShortcutKeyCombinationSelected(keyUpEventWithActionKeyPressed)

            // Note that Action Key is excluded as it's already displayed on the UI
            assertThat((uiState as AddShortcutDialog).pressedKeys)
                .containsExactly(ShortcutKey.Text("Ctrl"), ShortcutKey.Text("A"))

            // Close the dialog and show it again
            viewModel.onDialogDismissed()
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            assertThat((uiState as AddShortcutDialog).pressedKeys).isEmpty()
        }
    }

    @Test
    fun uiState_pressedKeys_resetsToEmpty_onClearSelectedShortcutKeyCombination() {
        testScope.runTest {
            val uiState by collectLastValue(viewModel.shortcutCustomizationUiState)
            viewModel.onShortcutCustomizationRequested(standardAddShortcutRequest)
            viewModel.onShortcutKeyCombinationSelected(keyDownEventWithActionKeyPressed)
            viewModel.onShortcutKeyCombinationSelected(keyUpEventWithActionKeyPressed)
            viewModel.clearSelectedKeyCombination()
            assertThat((uiState as AddShortcutDialog).pressedKeys).isEmpty()
        }
    }

    private suspend fun openAddShortcutDialogAndSetShortcut() {
        openAddShortcutDialogAndPressKeyCombination()
        viewModel.onSetShortcut()
    }

    private fun openAddShortcutDialogAndPressKeyCombination() {
        viewModel.onShortcutCustomizationRequested(allAppsShortcutAddRequest)
        viewModel.onShortcutKeyCombinationSelected(keyDownEventWithActionKeyPressed)
        viewModel.onShortcutKeyCombinationSelected(keyUpEventWithActionKeyPressed)
    }

    private suspend fun openDeleteShortcutDialogAndDeleteShortcut() {
        viewModel.onShortcutCustomizationRequested(allAppsShortcutDeleteRequest)
        viewModel.deleteShortcutCurrentlyBeingCustomized()
    }

    private suspend fun openResetShortcutDialogAndResetAllCustomShortcuts() {
        viewModel.onShortcutCustomizationRequested(ShortcutCustomizationRequestInfo.Reset)
        viewModel.resetAllCustomShortcuts()
    }

    private fun buildSimpleInputGestureWithMetaCtrlATrigger() =
        InputGestureData.Builder()
            .setKeyGestureType(KEY_GESTURE_TYPE_HOME)
            .setTrigger(createKeyTrigger(KEYCODE_A, META_CTRL_ON or META_META_ON))
            .build()
}
