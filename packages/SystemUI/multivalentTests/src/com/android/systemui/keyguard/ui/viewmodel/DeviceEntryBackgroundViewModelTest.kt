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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryBackgroundViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest: DeviceEntryBackgroundViewModel by lazy {
        kosmos.deviceEntryBackgroundViewModel
    }

    @Test
    fun lockscreenToDozingTransitionChangesBackgroundViewAlphaToZero() =
        testScope.runTest {
            kosmos.fingerprintPropertyRepository.supportsUdfps()
            val alpha by collectLastValue(underTest.alpha)

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                listOf(dozingToLockscreen(0f, STARTED), dozingToLockscreen(0.1f)),
                testScope,
            )
            runCurrent()
            assertThat(alpha).isEqualTo(1.0f)

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                listOf(lockscreenToDozing(0f, STARTED)),
                testScope,
            )
            runCurrent()

            assertThat(alpha).isEqualTo(0.0f)
        }

    private fun lockscreenToDozing(value: Float, state: TransitionState = RUNNING): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.DOZING,
            value = value,
            transitionState = state,
            ownerName = "DeviceEntryBackgroundViewModelTest",
        )
    }

    private fun dozingToLockscreen(value: Float, state: TransitionState = RUNNING): TransitionStep {
        return TransitionStep(
            from = KeyguardState.DOZING,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
            ownerName = "DeviceEntryBackgroundViewModelTest",
        )
    }
}
