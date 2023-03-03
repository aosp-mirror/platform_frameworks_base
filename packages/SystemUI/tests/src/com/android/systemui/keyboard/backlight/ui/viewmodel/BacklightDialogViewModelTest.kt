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

package com.android.systemui.keyboard.backlight.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.backlight.domain.interactor.KeyboardBacklightInteractor
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.keyboard.shared.model.BacklightModel
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class BacklightDialogViewModelTest : SysuiTestCase() {

    private val keyboardRepository = FakeKeyboardRepository()
    private lateinit var underTest: BacklightDialogViewModel
    @Mock private lateinit var accessibilityManagerWrapper: AccessibilityManagerWrapper
    private val timeoutMillis = 3000L

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(accessibilityManagerWrapper.getRecommendedTimeoutMillis(any(), any()))
            .thenReturn(timeoutMillis.toInt())
        underTest =
            BacklightDialogViewModel(
                KeyboardBacklightInteractor(keyboardRepository),
                accessibilityManagerWrapper
            )
        keyboardRepository.setKeyboardConnected(true)
    }

    @Test
    fun emitsViewModel_whenBacklightChanged() = runTest {
        keyboardRepository.setBacklight(BacklightModel(1, 5))

        assertThat(underTest.dialogContent.first()).isEqualTo(BacklightDialogContentViewModel(1, 5))
    }

    @Test
    fun emitsNull_afterTimeout() = runTest {
        val latest by collectLastValue(underTest.dialogContent)
        keyboardRepository.setBacklight(BacklightModel(1, 5))

        assertThat(latest).isEqualTo(BacklightDialogContentViewModel(1, 5))
        advanceTimeBy(timeoutMillis + 1)
        assertThat(latest).isNull()
    }

    @Test
    fun emitsNull_after5secDelay_fromLastBacklightChange() = runTest {
        val latest by collectLastValue(underTest.dialogContent)
        keyboardRepository.setKeyboardConnected(true)

        keyboardRepository.setBacklight(BacklightModel(1, 5))
        assertThat(latest).isEqualTo(BacklightDialogContentViewModel(1, 5))

        advanceTimeBy(timeoutMillis * 2 / 3)
        // timeout yet to pass, no new emission
        keyboardRepository.setBacklight(BacklightModel(2, 5))
        assertThat(latest).isEqualTo(BacklightDialogContentViewModel(2, 5))

        advanceTimeBy(timeoutMillis * 2 / 3)
        // timeout refreshed because of last `setBacklight`, still content present
        assertThat(latest).isEqualTo(BacklightDialogContentViewModel(2, 5))

        advanceTimeBy(timeoutMillis * 2 / 3)
        // finally timeout reached and null emitted
        assertThat(latest).isNull()
    }
}
