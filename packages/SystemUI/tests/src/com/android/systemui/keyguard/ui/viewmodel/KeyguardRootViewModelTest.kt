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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import android.view.View
import androidx.test.filters.SmallTest
import com.android.SysUITestComponent
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.collectLastValue
import com.android.runCurrent
import com.android.runTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.data.repository.FakeDeviceEntryRepository
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.plugins.ClockController
import com.android.systemui.statusbar.notification.data.repository.FakeNotificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.reset
import org.mockito.Mockito.withSettings
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

        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)

        val featureFlags =
            FakeFeatureFlagsClassic().apply {
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
                deviceEntryInteractor =
                    mock { whenever(isBypassEnabled).thenReturn(MutableStateFlow(false)) },
                dozeParameters = mock(),
                featureFlags,
                keyguardInteractor,
                keyguardTransitionInteractor,
                notificationsKeyguardInteractor =
                    mock {
                        whenever(areNotificationsFullyHidden).thenReturn(emptyFlow())
                        whenever(isPulseExpanding).thenReturn(emptyFlow())
                    },
                burnInInteractor,
                goneToAodTransitionViewModel,
                aodToLockscreenTransitionViewModel,
                screenOffAnimationController = mock(),
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

            underTest.statusViewTop = 100

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
    fun translationAndScaleFromBurnFullyDozingStaysOutOfTopInset() =
        testScope.runTest {
            val translationX by collectLastValue(underTest.translationX)
            val translationY by collectLastValue(underTest.translationY)
            val scale by collectLastValue(underTest.scale)

            underTest.statusViewTop = 100
            underTest.topInset = 80

            // Set to dozing (on AOD)
            dozeAmountTransitionStep.emit(TransitionStep(value = 1f))
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

@SmallTest
class KeyguardRootViewModelTestWithFakes : SysuiTestCase() {

    @Component(modules = [SysUITestModule::class])
    @SysUISingleton
    interface TestComponent : SysUITestComponent<KeyguardRootViewModel> {
        val deviceEntryRepository: FakeDeviceEntryRepository
        val notifsKeyguardRepository: FakeNotificationsKeyguardViewStateRepository
        val repository: FakeKeyguardRepository
        val transitionRepository: FakeKeyguardTransitionRepository

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }

    private val clockController: ClockController =
        mock(withSettings().defaultAnswer(RETURNS_DEEP_STUBS))
    private val dozeParams: DozeParameters = mock()
    private val screenOffAnimController: ScreenOffAnimationController = mock()

    private fun runTest(block: suspend TestComponent.() -> Unit): Unit =
        DaggerKeyguardRootViewModelTestWithFakes_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule {
                        setDefault(Flags.NEW_AOD_TRANSITION)
                        set(Flags.FACE_AUTH_REFACTOR, true)
                    },
                mocks =
                    TestMocksModule(
                        dozeParameters = dozeParams,
                        screenOffAnimationController = screenOffAnimController,
                    ),
            )
            .runTest {
                reset(clockController)
                underTest.clockControllerProvider = Provider { clockController }
                block()
            }

    @Test
    fun iconContainer_isNotVisible_notOnKeyguard_dontShowAodIconsWhenShade() = runTest {
        val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
        runCurrent()
        transitionRepository.sendTransitionSteps(
            from = KeyguardState.OFF,
            to = KeyguardState.GONE,
            testScope,
        )
        whenever(screenOffAnimController.shouldShowAodIconsWhenShade()).thenReturn(false)
        runCurrent()

        assertThat(isVisible?.value).isFalse()
        assertThat(isVisible?.isAnimating).isFalse()
    }

    @Test
    fun iconContainer_isVisible_bypassEnabled() = runTest {
        val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
        runCurrent()
        deviceEntryRepository.setBypassEnabled(true)
        runCurrent()

        assertThat(isVisible?.value).isTrue()
    }

    @Test
    fun iconContainer_isNotVisible_pulseExpanding_notBypassing() = runTest {
        val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
        runCurrent()
        notifsKeyguardRepository.setPulseExpanding(true)
        deviceEntryRepository.setBypassEnabled(false)
        runCurrent()

        assertThat(isVisible?.value).isEqualTo(false)
    }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassEnabled() = runTest {
        val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
        runCurrent()
        notifsKeyguardRepository.setPulseExpanding(false)
        deviceEntryRepository.setBypassEnabled(true)
        notifsKeyguardRepository.setNotificationsFullyHidden(true)
        runCurrent()

        assertThat(isVisible?.value).isTrue()
        assertThat(isVisible?.isAnimating).isTrue()
    }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled_aodDisabled() = runTest {
        val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
        runCurrent()
        notifsKeyguardRepository.setPulseExpanding(false)
        deviceEntryRepository.setBypassEnabled(false)
        whenever(dozeParams.alwaysOn).thenReturn(false)
        notifsKeyguardRepository.setNotificationsFullyHidden(true)
        runCurrent()

        assertThat(isVisible?.value).isTrue()
        assertThat(isVisible?.isAnimating).isFalse()
    }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled_displayNeedsBlanking() = runTest {
        val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
        runCurrent()
        notifsKeyguardRepository.setPulseExpanding(false)
        deviceEntryRepository.setBypassEnabled(false)
        whenever(dozeParams.alwaysOn).thenReturn(true)
        whenever(dozeParams.displayNeedsBlanking).thenReturn(true)
        notifsKeyguardRepository.setNotificationsFullyHidden(true)
        runCurrent()

        assertThat(isVisible?.value).isTrue()
        assertThat(isVisible?.isAnimating).isFalse()
    }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled() = runTest {
        val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
        runCurrent()
        notifsKeyguardRepository.setPulseExpanding(false)
        deviceEntryRepository.setBypassEnabled(false)
        whenever(dozeParams.alwaysOn).thenReturn(true)
        whenever(dozeParams.displayNeedsBlanking).thenReturn(false)
        notifsKeyguardRepository.setNotificationsFullyHidden(true)
        runCurrent()

        assertThat(isVisible?.value).isTrue()
        assertThat(isVisible?.isAnimating).isTrue()
    }

    @Test
    fun isIconContainerVisible_stopAnimation() = runTest {
        val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
        runCurrent()
        notifsKeyguardRepository.setPulseExpanding(false)
        deviceEntryRepository.setBypassEnabled(false)
        whenever(dozeParams.alwaysOn).thenReturn(true)
        whenever(dozeParams.displayNeedsBlanking).thenReturn(false)
        notifsKeyguardRepository.setNotificationsFullyHidden(true)
        runCurrent()

        assertThat(isVisible?.isAnimating).isEqualTo(true)
        isVisible?.stopAnimating()
        runCurrent()

        assertThat(isVisible?.isAnimating).isEqualTo(false)
    }
}
