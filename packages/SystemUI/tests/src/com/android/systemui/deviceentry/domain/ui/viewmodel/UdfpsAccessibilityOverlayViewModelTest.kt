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

package com.android.systemui.deviceentry.domain.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.fakeAccessibilityRepository
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.data.ui.viewmodel.deviceEntryUdfpsAccessibilityOverlayViewModel
import com.android.systemui.flags.Flags.FULL_SCREEN_USER_SWITCHER
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.viewmodel.fakeDeviceEntryIconViewModelTransition
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class UdfpsAccessibilityOverlayViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val deviceEntryIconTransition = kosmos.fakeDeviceEntryIconViewModelTransition
    private val testScope = kosmos.testScope
    private val biometricSettingsRepository = kosmos.fakeBiometricSettingsRepository
    private val accessibilityRepository = kosmos.fakeAccessibilityRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    private val deviceEntryFingerprintAuthRepository = kosmos.deviceEntryFingerprintAuthRepository
    private val deviceEntryRepository = kosmos.fakeDeviceEntryRepository
    private val shadeRepository = kosmos.fakeShadeRepository
    private val underTest = kosmos.deviceEntryUdfpsAccessibilityOverlayViewModel

    @Test
    fun visible() =
        testScope.runTest {
            val visible by collectLastValue(underTest.visible)
            setupVisibleStateOnLockscreen()
            assertThat(visible).isTrue()
        }

    @Test
    fun touchExplorationNotEnabled_overlayNotVisible() =
        testScope.runTest {
            val visible by collectLastValue(underTest.visible)
            setupVisibleStateOnLockscreen()
            accessibilityRepository.isTouchExplorationEnabled.value = false
            assertThat(visible).isFalse()
        }

    @Test
    fun deviceEntryFgIconViewModelAod_overlayNotVisible() =
        testScope.runTest {
            val visible by collectLastValue(underTest.visible)
            setupVisibleStateOnLockscreen()

            // AOD
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                this,
            )
            runCurrent()
            assertThat(visible).isFalse()
        }
    fun fpNotRunning_overlayNotVisible() =
        testScope.runTest {
            val visible by collectLastValue(underTest.visible)
            setupVisibleStateOnLockscreen()
            deviceEntryFingerprintAuthRepository.setIsRunning(false)
            assertThat(visible).isFalse()
        }

    @Test
    fun deviceEntryViewAlpha0_overlayNotVisible() =
        testScope.runTest {
            val visible by collectLastValue(underTest.visible)
            setupVisibleStateOnLockscreen()
            deviceEntryIconTransition.setDeviceEntryParentViewAlpha(0f)
            assertThat(visible).isFalse()
        }

    private suspend fun setupVisibleStateOnLockscreen() {
        // A11y enabled
        accessibilityRepository.isTouchExplorationEnabled.value = true

        // Transition alpha is 1f
        deviceEntryIconTransition.setDeviceEntryParentViewAlpha(1f)

        // Listening for UDFPS
        fingerprintPropertyRepository.supportsUdfps()
        biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
        deviceEntryFingerprintAuthRepository.setIsRunning(true)
        deviceEntryRepository.setUnlocked(false)

        // Lockscreen
        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                value = 0f,
                transitionState = TransitionState.STARTED,
            )
        )
        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                value = 1f,
                transitionState = TransitionState.FINISHED,
            )
        )

        // Shade not expanded
        shadeRepository.qsExpansion.value = 0f
        shadeRepository.lockscreenShadeExpansion.value = 0f
    }
}
