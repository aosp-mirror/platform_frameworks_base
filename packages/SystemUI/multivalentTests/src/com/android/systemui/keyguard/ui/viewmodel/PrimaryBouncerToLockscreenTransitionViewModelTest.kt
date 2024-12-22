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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class PrimaryBouncerToLockscreenTransitionViewModelTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    val biometricSettingsRepository = kosmos.biometricSettingsRepository

    val underTest = kosmos.primaryBouncerToLockscreenTransitionViewModel

    @Test
    fun lockscreenAlphaStartsFromViewStateAccessorAlpha() =
        testScope.runTest {
            val viewState = ViewStateAccessor(alpha = { 0.5f })
            val alpha by collectLastValue(underTest.lockscreenAlpha(viewState))

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))

            keyguardTransitionRepository.sendTransitionStep(step(0f))
            assertThat(alpha).isEqualTo(0.5f)

            keyguardTransitionRepository.sendTransitionStep(step(0.5f))
            assertThat(alpha).isIn(Range.open(0.5f, 1f))

            keyguardTransitionRepository.sendTransitionStep(step(1f))
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    fun deviceEntryParentViewAlpha() =
        testScope.runTest {
            val deviceEntryParentViewAlpha by collectLastValue(underTest.deviceEntryParentViewAlpha)

            // immediately 1f
            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(0.4f))
            assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(.85f))
            assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(1f))
            assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)
        }

    @Test
    fun deviceEntryBackgroundViewAlpha_udfpsEnrolled_show() =
        testScope.runTest {
            fingerprintPropertyRepository.supportsUdfps()
            val bgViewAlpha by collectLastValue(underTest.deviceEntryBackgroundViewAlpha)
            runCurrent()

            // immediately 1f
            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(bgViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(0.1f))
            assertThat(bgViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(.3f))
            assertThat(bgViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(.5f))
            assertThat(bgViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(bgViewAlpha).isEqualTo(1f)
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.PRIMARY_BOUNCER,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
            ownerName = "PrimaryBouncerToLockscreenTransitionViewModelTest"
        )
    }
}
