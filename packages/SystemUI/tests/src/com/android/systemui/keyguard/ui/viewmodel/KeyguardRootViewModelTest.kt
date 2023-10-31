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

package com.android.systemui.keyguard.ui.viewmodel

import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.plugins.ClockController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardRootViewModelTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardRootViewModel
    private lateinit var testScope: TestScope
    private lateinit var repository: FakeKeyguardRepository
    private lateinit var keyguardInteractor: KeyguardInteractor
    private lateinit var configurationRepository: FakeConfigurationRepository
    @Mock private lateinit var burnInInteractor: BurnInInteractor
    @Mock private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    @Mock private lateinit var goneToAodTransitionViewModel: GoneToAodTransitionViewModel
    @Mock
    private lateinit var aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var clockController: ClockController

    private val burnInFlow = MutableStateFlow(BurnInModel())
    private val goneToAodTransitionViewModelVisibility = MutableStateFlow(0)
    private val enterFromTopAnimationAlpha = MutableStateFlow(0f)
    private val goneToAodTransitionStep = MutableSharedFlow<TransitionStep>(replay = 1)
    private val dozeAmountTransitionStep = MutableSharedFlow<TransitionStep>(replay = 1)
    private val startedKeyguardState = MutableStateFlow(KeyguardState.GONE)

    @Before
    fun setUp() {
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        MockitoAnnotations.initMocks(this)

        val featureFlags =
            FakeFeatureFlags().apply {
                set(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA, true)
                set(Flags.FACE_AUTH_REFACTOR, true)
            }

        val withDeps = KeyguardInteractorFactory.create(featureFlags = featureFlags)
        keyguardInteractor = withDeps.keyguardInteractor
        repository = withDeps.repository
        configurationRepository = withDeps.configurationRepository

        whenever(goneToAodTransitionViewModel.enterFromTopTranslationY(anyInt()))
            .thenReturn(emptyFlow<Float>())
        whenever(goneToAodTransitionViewModel.enterFromTopAnimationAlpha)
            .thenReturn(enterFromTopAnimationAlpha)

        whenever(burnInInteractor.keyguardBurnIn).thenReturn(burnInFlow)

        whenever(keyguardTransitionInteractor.goneToAodTransition)
            .thenReturn(goneToAodTransitionStep)
        whenever(keyguardTransitionInteractor.dozeAmountTransition)
            .thenReturn(dozeAmountTransitionStep)
        whenever(keyguardTransitionInteractor.startedKeyguardState).thenReturn(startedKeyguardState)

        underTest =
            KeyguardRootViewModel(
                context,
                keyguardInteractor,
                burnInInteractor,
                goneToAodTransitionViewModel,
                aodToLockscreenTransitionViewModel,
                keyguardTransitionInteractor,
            )
        underTest.clockControllerProvider = Provider { clockController }
    }

    @Test
    fun alpha() =
        testScope.runTest {
            val value = collectLastValue(underTest.alpha)

            assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0.1f)
            assertThat(value()).isEqualTo(0.1f)
            repository.setKeyguardAlpha(0.5f)
            assertThat(value()).isEqualTo(0.5f)
            repository.setKeyguardAlpha(0.2f)
            assertThat(value()).isEqualTo(0.2f)
            repository.setKeyguardAlpha(0f)
            assertThat(value()).isEqualTo(0f)
        }

    @Test
    fun alpha_inPreviewMode_doesNotChange() =
        testScope.runTest {
            val value = collectLastValue(underTest.alpha)
            underTest.enablePreviewMode()

            assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0.1f)
            assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0.5f)
            assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0.2f)
            assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0f)
            assertThat(value()).isEqualTo(1f)
        }

    @Test
    fun translationAndScaleFromBurnInNotDozing() =
        testScope.runTest {
            val translationX by collectLastValue(underTest.translationX)
            val translationY by collectLastValue(underTest.translationY)
            val scale by collectLastValue(underTest.scale)

            // Set to not dozing (on lockscreen)
            dozeAmountTransitionStep.emit(TransitionStep(value = 0f))

            // Trigger a change to the burn-in model
            burnInFlow.value =
                BurnInModel(
                    translationX = 20,
                    translationY = 30,
                    scale = 0.5f,
                )

            assertThat(translationX).isEqualTo(0)
            assertThat(translationY).isEqualTo(0)
            assertThat(scale).isEqualTo(Pair(1f, true /* scaleClockOnly */))
        }

    @Test
    fun translationAndScaleFromBurnFullyDozing() =
        testScope.runTest {
            val translationX by collectLastValue(underTest.translationX)
            val translationY by collectLastValue(underTest.translationY)
            val scale by collectLastValue(underTest.scale)

            // Set to dozing (on AOD)
            dozeAmountTransitionStep.emit(TransitionStep(value = 1f))
            // Trigger a change to the burn-in model
            burnInFlow.value =
                BurnInModel(
                    translationX = 20,
                    translationY = 30,
                    scale = 0.5f,
                )
            assertThat(translationX).isEqualTo(20)
            assertThat(translationY).isEqualTo(30)
            assertThat(scale).isEqualTo(Pair(0.5f, true /* scaleClockOnly */))

            // Set to the beginning of GONE->AOD transition
            goneToAodTransitionStep.emit(TransitionStep(value = 0f))
            assertThat(translationX).isEqualTo(0)
            assertThat(translationY).isEqualTo(0)
            assertThat(scale).isEqualTo(Pair(1f, true /* scaleClockOnly */))
        }

    @Test
    fun translationAndScaleFromBurnInUseScaleOnly() =
        testScope.runTest {
            whenever(clockController.config.useAlternateSmartspaceAODTransition).thenReturn(true)

            val translationX by collectLastValue(underTest.translationX)
            val translationY by collectLastValue(underTest.translationY)
            val scale by collectLastValue(underTest.scale)

            // Set to dozing (on AOD)
            dozeAmountTransitionStep.emit(TransitionStep(value = 1f))

            // Trigger a change to the burn-in model
            burnInFlow.value =
                BurnInModel(
                    translationX = 20,
                    translationY = 30,
                    scale = 0.5f,
                )

            assertThat(translationX).isEqualTo(0)
            assertThat(translationY).isEqualTo(0)
            assertThat(scale).isEqualTo(Pair(0.5f, false /* scaleClockOnly */))
        }

    @Test
    fun burnInLayerVisibility() =
        testScope.runTest {
            val burnInLayerVisibility by collectLastValue(underTest.burnInLayerVisibility)

            startedKeyguardState.value = KeyguardState.OCCLUDED
            assertThat(burnInLayerVisibility).isNull()

            startedKeyguardState.value = KeyguardState.AOD
            assertThat(burnInLayerVisibility).isEqualTo(View.VISIBLE)
        }

    @Test
    fun burnInLayerAlpha() =
        testScope.runTest {
            val burnInLayerAlpha by collectLastValue(underTest.burnInLayerAlpha)

            enterFromTopAnimationAlpha.value = 0.2f
            assertThat(burnInLayerAlpha).isEqualTo(0.2f)

            enterFromTopAnimationAlpha.value = 1f
            assertThat(burnInLayerAlpha).isEqualTo(1f)
        }
}
