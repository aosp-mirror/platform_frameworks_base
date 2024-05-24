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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.burnInInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class AodBurnInViewModelTest : SysuiTestCase() {

    @Mock private lateinit var burnInInteractor: BurnInInteractor
    @Mock private lateinit var goneToAodTransitionViewModel: GoneToAodTransitionViewModel
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var clockController: ClockController

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private lateinit var underTest: AodBurnInViewModel

    private var burnInParameters =
        BurnInParameters(
            clockControllerProvider = { clockController },
        )
    private val burnInFlow = MutableStateFlow(BurnInModel())

    @Before
    fun setUp() {
        mSetFlagsRule.disableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)

        MockitoAnnotations.initMocks(this)
        whenever(burnInInteractor.burnIn(anyInt(), anyInt())).thenReturn(burnInFlow)
        kosmos.burnInInteractor = burnInInteractor
        whenever(goneToAodTransitionViewModel.enterFromTopTranslationY(anyInt()))
            .thenReturn(emptyFlow())
        kosmos.goneToAodTransitionViewModel = goneToAodTransitionViewModel

        underTest = kosmos.aodBurnInViewModel
    }

    @Test
    fun translationY_initializedToZero() =
        testScope.runTest {
            val translationY by collectLastValue(underTest.translationY(burnInParameters))
            assertThat(translationY).isEqualTo(0)
        }

    @Test
    fun translationAndScale_whenNotDozing() =
        testScope.runTest {
            val translationX by collectLastValue(underTest.translationX(burnInParameters))
            val translationY by collectLastValue(underTest.translationY(burnInParameters))
            val scale by collectLastValue(underTest.scale(burnInParameters))

            // Set to not dozing (on lockscreen)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                ),
                validateStep = false,
            )

            // Trigger a change to the burn-in model
            burnInFlow.value =
                BurnInModel(
                    translationX = 20,
                    translationY = 30,
                    scale = 0.5f,
                )

            assertThat(translationX).isEqualTo(0)
            assertThat(translationY).isEqualTo(0)
            assertThat(scale)
                .isEqualTo(
                    BurnInScaleViewModel(
                        scale = 1f,
                        scaleClockOnly = true,
                    )
                )
        }

    @Test
    fun translationAndScale_whenFullyDozing() =
        testScope.runTest {
            burnInParameters = burnInParameters.copy(minViewY = 100)
            val translationX by collectLastValue(underTest.translationX(burnInParameters))
            val translationY by collectLastValue(underTest.translationY(burnInParameters))
            val scale by collectLastValue(underTest.scale(burnInParameters))

            // Set to dozing (on AOD)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                ),
                validateStep = false,
            )
            // Trigger a change to the burn-in model
            burnInFlow.value =
                BurnInModel(
                    translationX = 20,
                    translationY = 30,
                    scale = 0.5f,
                )

            assertThat(translationX).isEqualTo(20)
            assertThat(translationY).isEqualTo(30)
            assertThat(scale)
                .isEqualTo(
                    BurnInScaleViewModel(
                        scale = 0.5f,
                        scaleClockOnly = true,
                    )
                )

            // Set to the beginning of GONE->AOD transition
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED
                ),
                validateStep = false,
            )
            assertThat(translationX).isEqualTo(0)
            assertThat(translationY).isEqualTo(0)
            assertThat(scale)
                .isEqualTo(
                    BurnInScaleViewModel(
                        scale = 1f,
                        scaleClockOnly = true,
                    )
                )
        }

    @Test
    fun translationAndScale_whenFullyDozing_MigrationFlagOff_staysOutOfTopInset() =
        testScope.runTest {
            mSetFlagsRule.disableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)

            burnInParameters =
                burnInParameters.copy(
                    minViewY = 100,
                    topInset = 80,
                )
            val translationX by collectLastValue(underTest.translationX(burnInParameters))
            val translationY by collectLastValue(underTest.translationY(burnInParameters))
            val scale by collectLastValue(underTest.scale(burnInParameters))

            // Set to dozing (on AOD)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                ),
                validateStep = false,
            )

            // Trigger a change to the burn-in model
            burnInFlow.value =
                BurnInModel(
                    translationX = 20,
                    translationY = -30,
                    scale = 0.5f,
                )
            assertThat(translationX).isEqualTo(20)
            // -20 instead of -30, due to inset of 80
            assertThat(translationY).isEqualTo(-20)
            assertThat(scale)
                .isEqualTo(
                    BurnInScaleViewModel(
                        scale = 0.5f,
                        scaleClockOnly = true,
                    )
                )

            // Set to the beginning of GONE->AOD transition
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED
                ),
                validateStep = false,
            )
            assertThat(translationX).isEqualTo(0)
            assertThat(translationY).isEqualTo(0)
            assertThat(scale)
                .isEqualTo(
                    BurnInScaleViewModel(
                        scale = 1f,
                        scaleClockOnly = true,
                    )
                )
        }

    @Test
    fun translationAndScale_whenFullyDozing_MigrationFlagOn_staysOutOfTopInset() =
        testScope.runTest {
            mSetFlagsRule.enableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)

            burnInParameters =
                burnInParameters.copy(
                    minViewY = 100,
                    topInset = 80,
                )
            val translationX by collectLastValue(underTest.translationX(burnInParameters))
            val translationY by collectLastValue(underTest.translationY(burnInParameters))
            val scale by collectLastValue(underTest.scale(burnInParameters))

            // Set to dozing (on AOD)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                ),
                validateStep = false,
            )

            // Trigger a change to the burn-in model
            burnInFlow.value =
                BurnInModel(
                    translationX = 20,
                    translationY = -30,
                    scale = 0.5f,
                )
            assertThat(translationX).isEqualTo(20)
            // -20 instead of -30, due to inset of 80
            assertThat(translationY).isEqualTo(-20)
            assertThat(scale)
                .isEqualTo(
                    BurnInScaleViewModel(
                        scale = 0.5f,
                        scaleClockOnly = true,
                    )
                )

            // Set to the beginning of GONE->AOD transition
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED
                ),
                validateStep = false,
            )
            assertThat(translationX).isEqualTo(0)
            assertThat(translationY).isEqualTo(0)
            assertThat(scale)
                .isEqualTo(
                    BurnInScaleViewModel(
                        scale = 1f,
                        scaleClockOnly = true,
                    )
                )
        }

    @Test
    fun translationAndScale_useScaleOnly() =
        testScope.runTest {
            whenever(clockController.config.useAlternateSmartspaceAODTransition).thenReturn(true)

            val translationX by collectLastValue(underTest.translationX(burnInParameters))
            val translationY by collectLastValue(underTest.translationY(burnInParameters))
            val scale by collectLastValue(underTest.scale(burnInParameters))

            // Set to dozing (on AOD)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                ),
                validateStep = false,
            )

            // Trigger a change to the burn-in model
            burnInFlow.value =
                BurnInModel(
                    translationX = 20,
                    translationY = 30,
                    scale = 0.5f,
                )

            assertThat(translationX).isEqualTo(0)
            assertThat(translationY).isEqualTo(0)
            assertThat(scale).isEqualTo(BurnInScaleViewModel(scale = 0.5f, scaleClockOnly = false))
        }
}
