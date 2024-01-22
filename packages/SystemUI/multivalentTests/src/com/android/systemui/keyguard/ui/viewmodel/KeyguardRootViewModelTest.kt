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
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.Flags.FLAG_NEW_AOD_TRANSITION
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardRootViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val screenOffAnimationController = kosmos.screenOffAnimationController
    private val deviceEntryRepository = kosmos.fakeDeviceEntryRepository
    private val notificationsKeyguardInteractor = kosmos.notificationsKeyguardInteractor
    private val dozeParameters = kosmos.dozeParameters
    private val underTest by lazy { kosmos.keyguardRootViewModel }

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
    fun alpha_glanceableHubOpen_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope,
            )

            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun alpha_glanceableHubClosed_isOne() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha)

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
}
