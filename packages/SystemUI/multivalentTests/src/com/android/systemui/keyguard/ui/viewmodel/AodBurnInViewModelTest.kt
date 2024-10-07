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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.burnInInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.ClockSize
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
import org.junit.Ignore
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
    @Mock
    private lateinit var lockscreenToAodTransitionViewModel: LockscreenToAodTransitionViewModel
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var clockController: ClockController

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val keyguardClockRepository = kosmos.fakeKeyguardClockRepository
    private lateinit var underTest: AodBurnInViewModel
    // assign a smaller value to minViewY to avoid overflow
    private var burnInParameters = BurnInParameters(minViewY = Int.MAX_VALUE / 2)
    private val burnInFlow = MutableStateFlow(BurnInModel())

    @Before
    @DisableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    @DisableSceneContainer
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(burnInInteractor.burnIn(anyInt(), anyInt())).thenReturn(burnInFlow)
        kosmos.burnInInteractor = burnInInteractor
        whenever(goneToAodTransitionViewModel.enterFromTopTranslationY(anyInt()))
            .thenReturn(emptyFlow())
        whenever(goneToAodTransitionViewModel.enterFromSideTranslationX(anyInt()))
            .thenReturn(emptyFlow())
        whenever(lockscreenToAodTransitionViewModel.enterFromSideTranslationX(anyInt()))
            .thenReturn(emptyFlow())
        kosmos.goneToAodTransitionViewModel = goneToAodTransitionViewModel
        kosmos.lockscreenToAodTransitionViewModel = lockscreenToAodTransitionViewModel
        kosmos.fakeKeyguardClockRepository.setCurrentClock(clockController)

        underTest = kosmos.aodBurnInViewModel
        underTest.updateBurnInParams(burnInParameters)
    }

    @Test
    fun movement_initializedToDefaultValues() =
        testScope.runTest {
            val movement by collectLastValue(underTest.movement)
            assertThat(movement?.translationY).isEqualTo(0)
            assertThat(movement?.translationX).isEqualTo(0)
            assertThat(movement?.scale).isEqualTo(1f)
        }

    @Test
    fun translationAndScale_whenNotDozing() =
        testScope.runTest {
            val movement by collectLastValue(underTest.movement)

            // Set to not dozing (on lockscreen)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                ),
                validateStep = false,
            )

            // Trigger a change to the burn-in model
            burnInFlow.value = BurnInModel(translationX = 20, translationY = 30, scale = 0.5f)

            assertThat(movement?.translationX).isEqualTo(0)
            assertThat(movement?.translationY).isEqualTo(0)
            assertThat(movement?.scale).isEqualTo(1f)
            assertThat(movement?.scaleClockOnly).isEqualTo(true)
        }

    @Test
    fun translationX_aodToLockscreen() =
        testScope.runTest {
            underTest.updateBurnInParams(burnInParameters.copy(translationX = { -100f }))
            val movement by collectLastValue(underTest.movement)
            assertThat(movement?.translationX).isEqualTo(0)

            // Trigger a change to the burn-in model
            burnInFlow.value = BurnInModel(translationX = 20, translationY = 30, scale = 0.5f)

            // Set to not dozing (on lockscreen)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                ),
                validateStep = false,
            )
            // Set to not dozing (on lockscreen)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = 0f,
                    transitionState = TransitionState.RUNNING,
                ),
                validateStep = false,
            )
            assertThat(movement?.translationX).isEqualTo(-100)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                ),
                validateStep = false,
            )

            assertThat(movement?.translationX).isEqualTo(0)
            assertThat(movement?.translationY).isEqualTo(0)
            assertThat(movement?.scale).isEqualTo(1f)
            assertThat(movement?.scaleClockOnly).isEqualTo(true)
        }

    @Test
    fun translationAndScale_whenFullyDozing() =
        testScope.runTest {
            underTest.updateBurnInParams(burnInParameters.copy(minViewY = 100))
            val movement by collectLastValue(underTest.movement)

            // Set to dozing (on AOD)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                ),
                validateStep = false,
            )
            // Trigger a change to the burn-in model
            burnInFlow.value = BurnInModel(translationX = 20, translationY = 30, scale = 0.5f)

            assertThat(movement?.translationX).isEqualTo(20)
            assertThat(movement?.translationY).isEqualTo(30)
            assertThat(movement?.scale).isEqualTo(0.5f)
            assertThat(movement?.scaleClockOnly).isEqualTo(true)

            // Set to the beginning of GONE->AOD transition
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                ),
                validateStep = false,
            )
            assertThat(movement?.translationX).isEqualTo(0)
            assertThat(movement?.translationY).isEqualTo(0)
            assertThat(movement?.scale).isEqualTo(1f)
            assertThat(movement?.scaleClockOnly).isEqualTo(true)
        }

    @Test
    @DisableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    fun translationAndScale_whenFullyDozing_MigrationFlagOff_staysOutOfTopInset() =
        testScope.runTest {
            underTest.updateBurnInParams(burnInParameters.copy(minViewY = 100, topInset = 80))
            val movement by collectLastValue(underTest.movement)

            // Set to dozing (on AOD)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                ),
                validateStep = false,
            )

            // Trigger a change to the burn-in model
            burnInFlow.value = BurnInModel(translationX = 20, translationY = -30, scale = 0.5f)
            assertThat(movement?.translationX).isEqualTo(20)
            // -20 instead of -30, due to inset of 80
            assertThat(movement?.translationY).isEqualTo(-20)
            assertThat(movement?.scale).isEqualTo(0.5f)
            assertThat(movement?.scaleClockOnly).isEqualTo(true)

            // Set to the beginning of GONE->AOD transition
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                ),
                validateStep = false,
            )
            assertThat(movement?.translationX).isEqualTo(0)
            assertThat(movement?.translationY).isEqualTo(0)
            assertThat(movement?.scale).isEqualTo(1f)
            assertThat(movement?.scaleClockOnly).isEqualTo(true)
        }

    @Test
    @EnableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    fun translationAndScale_whenFullyDozing_MigrationFlagOn_staysOutOfTopInset() =
        testScope.runTest {
            underTest.updateBurnInParams(burnInParameters.copy(minViewY = 100, topInset = 80))
            val movement by collectLastValue(underTest.movement)

            // Set to dozing (on AOD)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                ),
                validateStep = false,
            )

            // Trigger a change to the burn-in model
            burnInFlow.value = BurnInModel(translationX = 20, translationY = -30, scale = 0.5f)
            assertThat(movement?.translationX).isEqualTo(20)
            // -20 instead of -30, due to inset of 80
            assertThat(movement?.translationY).isEqualTo(-20)
            assertThat(movement?.scale).isEqualTo(0.5f)
            assertThat(movement?.scaleClockOnly).isEqualTo(true)

            // Set to the beginning of GONE->AOD transition
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                ),
                validateStep = false,
            )
            assertThat(movement?.translationX).isEqualTo(0)
            assertThat(movement?.translationY).isEqualTo(0)
            assertThat(movement?.scale).isEqualTo(1f)
            assertThat(movement?.scaleClockOnly).isEqualTo(true)
        }

    @Test
    fun translationAndScale_useScaleOnly() =
        testScope.runTest {
            whenever(clockController.config.useAlternateSmartspaceAODTransition).thenReturn(true)

            val movement by collectLastValue(underTest.movement)

            // Set to dozing (on AOD)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                ),
                validateStep = false,
            )

            // Trigger a change to the burn-in model
            burnInFlow.value = BurnInModel(translationX = 20, translationY = 30, scale = 0.5f)

            assertThat(movement?.translationX).isEqualTo(20)
            assertThat(movement?.translationY).isEqualTo(30)
            assertThat(movement?.scale).isEqualTo(0.5f)
            assertThat(movement?.scaleClockOnly).isEqualTo(false)
        }

    @Test
    @DisableSceneContainer
    @EnableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    fun translationAndScale_sceneContainerOff_weatherLargeClock() =
        testBurnInViewModelForClocks(
            isSmallClock = false,
            isWeatherClock = true,
            expectedScaleOnly = false,
        )

    @Test
    @DisableSceneContainer
    @EnableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    fun translationAndScale_sceneContainerOff_weatherSmallClock() =
        testBurnInViewModelForClocks(
            isSmallClock = true,
            isWeatherClock = true,
            expectedScaleOnly = false,
        )

    @Test
    @DisableSceneContainer
    @EnableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    fun translationAndScale_sceneContainerOff_nonWeatherLargeClock() =
        testBurnInViewModelForClocks(
            isSmallClock = false,
            isWeatherClock = false,
            expectedScaleOnly = true,
        )

    @Test
    @DisableSceneContainer
    @EnableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    fun translationAndScale_sceneContainerOff_nonWeatherSmallClock() =
        testBurnInViewModelForClocks(
            isSmallClock = true,
            isWeatherClock = false,
            expectedScaleOnly = false,
        )

    @Test
    @EnableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    @EnableSceneContainer
    fun translationAndScale_sceneContainerOn_weatherLargeClock() =
        testBurnInViewModelForClocks(
            isSmallClock = false,
            isWeatherClock = true,
            expectedScaleOnly = false,
        )

    @Test
    @EnableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    @EnableSceneContainer
    fun translationAndScale_sceneContainerOn_weatherSmallClock() =
        testBurnInViewModelForClocks(
            isSmallClock = true,
            isWeatherClock = true,
            expectedScaleOnly = false,
        )

    @Test
    @EnableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    @EnableSceneContainer
    fun translationAndScale_sceneContainerOn_nonWeatherLargeClock() =
        testBurnInViewModelForClocks(
            isSmallClock = false,
            isWeatherClock = false,
            expectedScaleOnly = true,
        )

    @Test
    @EnableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    @EnableSceneContainer
    @Ignore("b/367659687")
    fun translationAndScale_sceneContainerOn_nonWeatherSmallClock() =
        testBurnInViewModelForClocks(
            isSmallClock = true,
            isWeatherClock = false,
            expectedScaleOnly = false,
        )

    private fun testBurnInViewModelForClocks(
        isSmallClock: Boolean,
        isWeatherClock: Boolean,
        expectedScaleOnly: Boolean,
    ) =
        testScope.runTest {
            if (isSmallClock) {
                keyguardClockRepository.setClockSize(ClockSize.SMALL)
                // we need the following step to update stateFlow value
                kosmos.testScope.collectLastValue(kosmos.keyguardClockViewModel.clockSize)
            }

            whenever(clockController.config.useAlternateSmartspaceAODTransition)
                .thenReturn(if (isWeatherClock) true else false)

            val movement by collectLastValue(underTest.movement)

            // Set to dozing (on AOD)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                ),
                validateStep = false,
            )

            // Trigger a change to the burn-in model
            burnInFlow.value = BurnInModel(translationX = 20, translationY = 30, scale = 0.5f)

            assertThat(movement?.translationX).isEqualTo(20)
            assertThat(movement?.translationY).isEqualTo(30)
            assertThat(movement?.scale).isEqualTo(0.5f)
            assertThat(movement?.scaleClockOnly).isEqualTo(expectedScaleOnly)
        }
}
