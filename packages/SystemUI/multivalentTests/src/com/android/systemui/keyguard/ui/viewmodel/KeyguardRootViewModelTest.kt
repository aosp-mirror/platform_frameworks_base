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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.Flags.FLAG_NEW_AOD_TRANSITION
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.communalRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.statusbar.phone.screenOffAnimationController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardRootViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT, false) }
        }
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val keyguardInteractor = kosmos.keyguardInteractor
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val communalRepository = kosmos.communalRepository
    private val screenOffAnimationController = kosmos.screenOffAnimationController
    private val deviceEntryRepository = kosmos.fakeDeviceEntryRepository
    private val notificationsKeyguardInteractor = kosmos.notificationsKeyguardInteractor
    private val dozeParameters = kosmos.dozeParameters
    private val shadeRepository = kosmos.fakeShadeRepository
    private val underTest by lazy { kosmos.keyguardRootViewModel }

    private val viewState = ViewStateAccessor()

    @Before
    fun setUp() {
        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)
        mSetFlagsRule.enableFlags(FLAG_NEW_AOD_TRANSITION)
        mSetFlagsRule.disableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
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
    fun iconContainer_isNotVisible_onKeyguard_dontShowWhenGoneToAodTransitionRunning() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.AOD,
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
            notificationsKeyguardInteractor.setPulseExpanding(true)
            deviceEntryRepository.setBypassEnabled(false)
            runCurrent()

            assertThat(isVisible?.value).isEqualTo(false)
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassEnabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            notificationsKeyguardInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(true)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isTrue()
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled_aodDisabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            notificationsKeyguardInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(false)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled_displayNeedsBlanking() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            notificationsKeyguardInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(true)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            notificationsKeyguardInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(false)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isTrue()
        }

    @Test
    fun isIconContainerVisible_stopAnimation() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            notificationsKeyguardInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(false)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.isAnimating).isEqualTo(true)
            isVisible?.stopAnimating()
            runCurrent()

            assertThat(isVisible?.isAnimating).isEqualTo(false)
        }

    @Test
    fun topClippingBounds() =
        testScope.runTest {
            val topClippingBounds by collectLastValue(underTest.topClippingBounds)
            assertThat(topClippingBounds).isNull()

            keyguardRepository.topClippingBounds.value = 50
            assertThat(topClippingBounds).isEqualTo(50)

            keyguardRepository.topClippingBounds.value = 1000
            assertThat(topClippingBounds).isEqualTo(1000)
        }

    @Test
    fun alpha_idleOnHub_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            // Default value check
            assertThat(alpha).isEqualTo(1f)

            // Hub transition state is idle with hub open.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            runCurrent()

            // Run at least 1 transition to make sure value remains at 0
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            // Alpha property remains 0 regardless.
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun alpha_transitionToHub_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope,
            )

            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun alpha_transitionFromHubToLockscreen_isOne() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            // Transition to the glanceable hub and back.
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope,
            )
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            assertThat(alpha).isEqualTo(1.0f)
        }

    @Test
    fun alpha_emitsOnShadeExpansion() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            shadeRepository.setQsExpansion(0f)
            assertThat(alpha).isEqualTo(1f)

            shadeRepository.setQsExpansion(0.5f)
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun alpha_idleOnOccluded_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))
            assertThat(alpha).isEqualTo(1f)

            // Go to OCCLUDED state
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope = testScope,
            )
            assertThat(alpha).isEqualTo(0f)

            // Try pulling down shade and ensure the value doesn't change
            shadeRepository.setQsExpansion(0.5f)
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun alpha_idleOnGone_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))
            assertThat(alpha).isEqualTo(1f)

            // Go to GONE state
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope = testScope,
            )
            assertThat(alpha).isEqualTo(0f)

            // Try pulling down shade and ensure the value doesn't change
            shadeRepository.setQsExpansion(0.5f)
            assertThat(alpha).isEqualTo(0f)
        }
}
