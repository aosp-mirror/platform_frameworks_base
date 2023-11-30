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
 */

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardIndicationAreaViewModelTest : SysuiTestCase() {

    @Mock private lateinit var burnInHelperWrapper: BurnInHelperWrapper
    @Mock private lateinit var shortcutsCombinedViewModel: KeyguardQuickAffordancesCombinedViewModel

    private lateinit var underTest: KeyguardIndicationAreaViewModel
    private lateinit var repository: FakeKeyguardRepository

    private val startButtonFlow =
        MutableStateFlow<KeyguardQuickAffordanceViewModel>(
            KeyguardQuickAffordanceViewModel(
                slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId()
            )
        )
    private val endButtonFlow =
        MutableStateFlow<KeyguardQuickAffordanceViewModel>(
            KeyguardQuickAffordanceViewModel(
                slotId = KeyguardQuickAffordancePosition.BOTTOM_END.toSlotId()
            )
        )
    private val alphaFlow = MutableStateFlow<Float>(1f)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(burnInHelperWrapper.burnInOffset(anyInt(), any()))
            .thenReturn(RETURNED_BURN_IN_OFFSET)

        val withDeps = KeyguardInteractorFactory.create()
        val keyguardInteractor = withDeps.keyguardInteractor
        repository = withDeps.repository

        val bottomAreaViewModel: KeyguardBottomAreaViewModel = mock()
        whenever(bottomAreaViewModel.startButton).thenReturn(startButtonFlow)
        whenever(bottomAreaViewModel.endButton).thenReturn(endButtonFlow)
        whenever(bottomAreaViewModel.alpha).thenReturn(alphaFlow)
        underTest =
            KeyguardIndicationAreaViewModel(
                keyguardInteractor = keyguardInteractor,
                bottomAreaInteractor = KeyguardBottomAreaInteractor(repository = repository),
                keyguardBottomAreaViewModel = bottomAreaViewModel,
                burnInHelperWrapper = burnInHelperWrapper,
                shortcutsCombinedViewModel = shortcutsCombinedViewModel,
            )
    }

    @Test
    fun alpha() = runTest {
        val value = collectLastValue(underTest.alpha)

        assertThat(value()).isEqualTo(1f)
        alphaFlow.value = 0.1f
        assertThat(value()).isEqualTo(0.1f)
        alphaFlow.value = 0.5f
        assertThat(value()).isEqualTo(0.5f)
        alphaFlow.value = 0.2f
        assertThat(value()).isEqualTo(0.2f)
        alphaFlow.value = 0f
        assertThat(value()).isEqualTo(0f)
    }

    @Test
    fun isIndicationAreaPadded() = runTest {
        repository.setKeyguardShowing(true)
        val value = collectLastValue(underTest.isIndicationAreaPadded)

        assertThat(value()).isFalse()
        startButtonFlow.value = startButtonFlow.value.copy(isVisible = true)
        assertThat(value()).isTrue()
        endButtonFlow.value = endButtonFlow.value.copy(isVisible = true)
        assertThat(value()).isTrue()
        startButtonFlow.value = startButtonFlow.value.copy(isVisible = false)
        assertThat(value()).isTrue()
        endButtonFlow.value = endButtonFlow.value.copy(isVisible = false)
        assertThat(value()).isFalse()
    }

    @Test
    fun indicationAreaTranslationX() = runTest {
        val value = collectLastValue(underTest.indicationAreaTranslationX)

        assertThat(value()).isEqualTo(0f)
        repository.setClockPosition(100, 100)
        assertThat(value()).isEqualTo(100f)
        repository.setClockPosition(200, 100)
        assertThat(value()).isEqualTo(200f)
        repository.setClockPosition(200, 200)
        assertThat(value()).isEqualTo(200f)
        repository.setClockPosition(300, 100)
        assertThat(value()).isEqualTo(300f)
    }

    @Test
    fun indicationAreaTranslationY() = runTest {
        val value = collectLastValue(underTest.indicationAreaTranslationY(DEFAULT_BURN_IN_OFFSET))

        // Negative 0 - apparently there's a difference in floating point arithmetic - FML
        assertThat(value()).isEqualTo(-0f)
        val expected1 = setDozeAmountAndCalculateExpectedTranslationY(0.1f)
        assertThat(value()).isEqualTo(expected1)
        val expected2 = setDozeAmountAndCalculateExpectedTranslationY(0.2f)
        assertThat(value()).isEqualTo(expected2)
        val expected3 = setDozeAmountAndCalculateExpectedTranslationY(0.5f)
        assertThat(value()).isEqualTo(expected3)
        val expected4 = setDozeAmountAndCalculateExpectedTranslationY(1f)
        assertThat(value()).isEqualTo(expected4)
    }

    private fun setDozeAmountAndCalculateExpectedTranslationY(dozeAmount: Float): Float {
        repository.setDozeAmount(dozeAmount)
        return dozeAmount * (RETURNED_BURN_IN_OFFSET - DEFAULT_BURN_IN_OFFSET)
    }

    companion object {
        private const val DEFAULT_BURN_IN_OFFSET = 5
        private const val RETURNED_BURN_IN_OFFSET = 3
    }
}
