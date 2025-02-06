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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@SmallTest
class KeyguardIndicationAreaViewModelTest() : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var underTest: KeyguardIndicationAreaViewModel
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val communalSceneRepository = kosmos.fakeCommunalSceneRepository

    private val startButtonFlow =
        MutableStateFlow(
            KeyguardQuickAffordanceViewModel(
                slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId()
            )
        )
    private val endButtonFlow =
        MutableStateFlow(
            KeyguardQuickAffordanceViewModel(
                slotId = KeyguardQuickAffordancePosition.BOTTOM_END.toSlotId()
            )
        )

    @Before
    fun setUp() {
        val burnInInteractor =
            mock<BurnInInteractor> {
                on { burnIn(anyInt(), anyInt()) } doReturn flowOf(BurnInModel())
            }
        val burnInHelperWrapper =
            mock<BurnInHelperWrapper> {
                on { burnInOffset(anyInt(), any()) } doReturn RETURNED_BURN_IN_OFFSET
            }
        val shortcutsCombinedViewModel =
            mock<KeyguardQuickAffordancesCombinedViewModel> {
                on { startButton } doReturn startButtonFlow
                on { endButton } doReturn endButtonFlow
            }
        underTest =
            KeyguardIndicationAreaViewModel(
                keyguardInteractor = kosmos.keyguardInteractor,
                burnInHelperWrapper = burnInHelperWrapper,
                burnInInteractor = burnInInteractor,
                shortcutsCombinedViewModel = shortcutsCombinedViewModel,
                configurationInteractor = kosmos.configurationInteractor,
                keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor,
                backgroundDispatcher = kosmos.testDispatcher,
                communalSceneInteractor = kosmos.communalSceneInteractor,
                mainDispatcher = kosmos.testDispatcher,
            )
    }

    @Test
    fun visibilityWhenCommunalNotShowing() =
        testScope.runTest {
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            val visible by collectLastValue(underTest.visible)

            assertThat(visible).isTrue()
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            assertThat(visible).isFalse()
        }

    @Test
    fun visibilityWhenCommunalShowing() =
        testScope.runTest {
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            communalSceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )

            val visible by collectLastValue(underTest.visible)

            assertThat(visible).isTrue()
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            assertThat(visible).isTrue()

            communalSceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Blank))
            )
            assertThat(visible).isFalse()
        }

    private fun setDozeAmountAndCalculateExpectedTranslationY(dozeAmount: Float): Float {
        keyguardRepository.setDozeAmount(dozeAmount)
        return dozeAmount * (RETURNED_BURN_IN_OFFSET - DEFAULT_BURN_IN_OFFSET)
    }

    companion object {
        private const val DEFAULT_BURN_IN_OFFSET = 5
        private const val RETURNED_BURN_IN_OFFSET = 3
    }
}
