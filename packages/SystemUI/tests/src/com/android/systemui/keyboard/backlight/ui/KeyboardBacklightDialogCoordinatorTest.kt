/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyboard.backlight.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyboard.backlight.domain.interactor.KeyboardBacklightInteractor
import com.android.systemui.keyboard.backlight.ui.view.KeyboardBacklightDialog
import com.android.systemui.keyboard.backlight.ui.viewmodel.BacklightDialogViewModel
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.keyboard.shared.model.BacklightModel
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardBacklightDialogCoordinatorTest : SysuiTestCase() {

    @Mock private lateinit var accessibilityManagerWrapper: AccessibilityManagerWrapper
    @Mock private lateinit var dialog: KeyboardBacklightDialog

    private val keyboardRepository = FakeKeyboardRepository()
    private lateinit var underTest: KeyboardBacklightDialogCoordinator
    private val timeoutMillis = 3000L
    private val testScope = TestScope(StandardTestDispatcher())

    private val createDialog = { value: Int, maxValue: Int ->
        dialogCreationValue = value
        dialogCreationMaxValue = maxValue
        dialog
    }
    private var dialogCreationValue = -1
    private var dialogCreationMaxValue = -1

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(accessibilityManagerWrapper.getRecommendedTimeoutMillis(any(), any()))
            .thenReturn(timeoutMillis.toInt())
        val viewModel =
            BacklightDialogViewModel(
                KeyboardBacklightInteractor(keyboardRepository),
                accessibilityManagerWrapper
            )
        underTest =
            KeyboardBacklightDialogCoordinator(testScope.backgroundScope, viewModel, createDialog)
        underTest.startListening()
        keyboardRepository.setIsAnyKeyboardConnected(true)
    }

    @Test
    fun showsDialog_afterBacklightChange() =
        testScope.runTest {
            setBacklightValue(1)

            verify(dialog).show()
        }

    @Test
    fun updatesDialog_withLatestValues_afterBacklightChange() =
        testScope.runTest {
            setBacklightValue(value = 1, maxValue = 5)
            setBacklightValue(value = 2, maxValue = 5)

            verify(dialog).updateState(2, 5)
        }

    @Test
    fun showsDialog_withDataFromBacklightChange() =
        testScope.runTest {
            setBacklightValue(value = 4, maxValue = 5)

            Truth.assertThat(dialogCreationValue).isEqualTo(4)
            Truth.assertThat(dialogCreationMaxValue).isEqualTo(5)
        }

    @Test
    fun dismissesDialog_afterTimeout() =
        testScope.runTest {
            setBacklightValue(1)

            advanceTimeBy(timeoutMillis + 1)

            verify(dialog).dismiss()
        }

    @Test
    fun dismissesDialog_onlyAfterTimeout_fromLastBacklightChange() =
        testScope.runTest {
            setBacklightValue(1)
            advanceTimeBy(timeoutMillis * 2 / 3)
            // majority of timeout passed

            // this should restart timeout
            setBacklightValue(2)
            advanceTimeBy(timeoutMillis * 2 / 3)
            verify(dialog, never()).dismiss()

            advanceTimeBy(timeoutMillis * 2 / 3)
            // finally timeout reached and dialog was dismissed
            verify(dialog, times(1)).dismiss()
        }

    @Test
    fun showsDialog_ifItWasAlreadyShownAndDismissedBySomethingElse() =
        testScope.runTest {
            setBacklightValue(1)
            // let's pretend dialog is dismissed e.g. by user tapping on the screen
            whenever(dialog.isShowing).thenReturn(false)

            // no advancing time, we're still in timeout period
            setBacklightValue(2)

            verify(dialog, times(2)).show()
        }

    private fun TestScope.setBacklightValue(value: Int, maxValue: Int = MAX_BACKLIGHT) {
        keyboardRepository.setBacklight(BacklightModel(value, maxValue))
        runCurrent()
    }

    private companion object {
        const val MAX_BACKLIGHT = 5
    }
}
