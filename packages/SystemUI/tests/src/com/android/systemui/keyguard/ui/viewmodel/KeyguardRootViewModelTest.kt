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
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.Flags.FLAG_NEW_AOD_TRANSITION
import com.android.systemui.SysuiTestCase
import com.android.systemui.collectLastValue
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.featureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.statusbar.notification.data.repository.fakeNotificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.statusbar.phone.screenOffAnimationController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardRootViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeKeyguardRepository
    private val configurationRepository = kosmos.fakeConfigurationRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val screenOffAnimationController = kosmos.screenOffAnimationController
    private val deviceEntryRepository = kosmos.fakeDeviceEntryRepository
    private val notificationsKeyguardInteractor = kosmos.notificationsKeyguardInteractor
    private val fakeNotificationsKeyguardViewStateRepository =
        kosmos.fakeNotificationsKeyguardViewStateRepository
    private val dozeParameters = kosmos.dozeParameters
    private lateinit var underTest: KeyguardRootViewModel

    @Mock private lateinit var burnInInteractor: BurnInInteractor
    private val burnInFlow = MutableStateFlow(BurnInModel())

    @Mock private lateinit var goneToAodTransitionViewModel: GoneToAodTransitionViewModel
    private val enterFromTopAnimationAlpha = MutableStateFlow(0f)

    @Mock
    private lateinit var aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel
    @Mock
    private lateinit var occludedToLockscreenTransitionViewModel:
        OccludedToLockscreenTransitionViewModel
    private val occludedToLockscreenTranslationY = MutableStateFlow(0f)
    private val occludedToLockscreenAlpha = MutableStateFlow(0f)

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var clockController: ClockController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)
        mSetFlagsRule.enableFlags(FLAG_NEW_AOD_TRANSITION)
        mSetFlagsRule.disableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)

        whenever(goneToAodTransitionViewModel.enterFromTopTranslationY(anyInt()))
            .thenReturn(emptyFlow<Float>())
        whenever(goneToAodTransitionViewModel.enterFromTopAnimationAlpha)
            .thenReturn(enterFromTopAnimationAlpha)

        whenever(burnInInteractor.keyguardBurnIn).thenReturn(burnInFlow)

        whenever(occludedToLockscreenTransitionViewModel.lockscreenTranslationY)
            .thenReturn(occludedToLockscreenTranslationY)
        whenever(occludedToLockscreenTransitionViewModel.lockscreenAlpha)
            .thenReturn(occludedToLockscreenAlpha)

        underTest =
            KeyguardRootViewModel(
                configurationInteractor = kosmos.configurationInteractor,
                deviceEntryInteractor = kosmos.deviceEntryInteractor,
                dozeParameters = kosmos.dozeParameters,
                keyguardInteractor = kosmos.keyguardInteractor,
                keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor,
                notificationsKeyguardInteractor = kosmos.notificationsKeyguardInteractor,
                burnInInteractor = burnInInteractor,
                keyguardClockViewModel = kosmos.keyguardClockViewModel,
                goneToAodTransitionViewModel = goneToAodTransitionViewModel,
                aodToLockscreenTransitionViewModel = aodToLockscreenTransitionViewModel,
                occludedToLockscreenTransitionViewModel = occludedToLockscreenTransitionViewModel,
                screenOffAnimationController = screenOffAnimationController,
                // TODO(b/310989341): remove after change to aconfig
                featureFlags = kosmos.featureFlagsClassic
            )

        underTest.clockControllerProvider = Provider { clockController }
    }

    @Test
    fun alpha() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OFF,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
            )

            repository.setKeyguardAlpha(0.1f)
            assertThat(alpha).isEqualTo(0.1f)
            repository.setKeyguardAlpha(0.5f)
            assertThat(alpha).isEqualTo(0.5f)
            repository.setKeyguardAlpha(0.2f)
            assertThat(alpha).isEqualTo(0.2f)
            repository.setKeyguardAlpha(0f)
            assertThat(alpha).isEqualTo(0f)
            occludedToLockscreenAlpha.value = 0.8f
            assertThat(alpha).isEqualTo(0.8f)
        }

    @Test
    fun alphaWhenGoneEqualsZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope = testScope,
            )

            repository.setKeyguardAlpha(0.1f)
            assertThat(alpha).isEqualTo(0f)
            repository.setKeyguardAlpha(0.5f)
            assertThat(alpha).isEqualTo(0f)
            repository.setKeyguardAlpha(1f)
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun translationYInitialValueIsZero() =
        testScope.runTest {
            val translationY by collectLastValue(underTest.translationY)
            assertThat(translationY).isEqualTo(0)
        }

    @Test
    fun translationAndScaleFromBurnInNotDozing() =
        testScope.runTest {
            val translationX by collectLastValue(underTest.translationX)
            val translationY by collectLastValue(underTest.translationY)
            val scale by collectLastValue(underTest.scale)

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
            assertThat(scale).isEqualTo(Pair(0.5f, true /* scaleClockOnly */))

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
            assertThat(scale).isEqualTo(Pair(0.5f, true /* scaleClockOnly */))

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
            assertThat(scale).isEqualTo(Pair(0.5f, false /* scaleClockOnly */))
        }

    @Test
    fun burnInLayerVisibility() =
        testScope.runTest {
            val burnInLayerVisibility by collectLastValue(underTest.burnInLayerVisibility)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED
                ),
                validateStep = false,
            )
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

    @Test
    fun iconContainer_isNotVisible_notOnKeyguard_dontShowAodIconsWhenShade() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OFF,
                to = KeyguardState.GONE,
                testScope,
            )
            whenever(screenOffAnimationController.shouldShowAodIconsWhenShade()).thenReturn(false)
            runCurrent()

            assertThat(isVisible?.value).isFalse()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun iconContainer_isVisible_bypassEnabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            deviceEntryRepository.setBypassEnabled(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
        }

    @Test
    fun iconContainer_isNotVisible_pulseExpanding_notBypassing() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            fakeNotificationsKeyguardViewStateRepository.setPulseExpanding(true)
            deviceEntryRepository.setBypassEnabled(false)
            runCurrent()

            assertThat(isVisible?.value).isEqualTo(false)
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassEnabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            fakeNotificationsKeyguardViewStateRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(true)
            fakeNotificationsKeyguardViewStateRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isTrue()
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled_aodDisabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            fakeNotificationsKeyguardViewStateRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(false)
            fakeNotificationsKeyguardViewStateRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled_displayNeedsBlanking() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            fakeNotificationsKeyguardViewStateRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(true)
            fakeNotificationsKeyguardViewStateRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            fakeNotificationsKeyguardViewStateRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(false)
            fakeNotificationsKeyguardViewStateRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isTrue()
        }

    @Test
    fun isIconContainerVisible_stopAnimation() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            fakeNotificationsKeyguardViewStateRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(false)
            fakeNotificationsKeyguardViewStateRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.isAnimating).isEqualTo(true)
            isVisible?.stopAnimating()
            runCurrent()

            assertThat(isVisible?.isAnimating).isEqualTo(false)
        }
}
