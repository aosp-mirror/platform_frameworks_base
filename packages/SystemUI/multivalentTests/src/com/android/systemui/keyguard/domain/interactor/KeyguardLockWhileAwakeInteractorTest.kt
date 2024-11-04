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

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyguardLockWhileAwakeInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var underTest: KeyguardLockWhileAwakeInteractor

    @Before
    fun setup() {
        underTest = kosmos.keyguardLockWhileAwakeInteractor
    }

    @Test
    fun emitsMultipleTimeoutEvents() =
        testScope.runTest {
            val values by collectValues(underTest.lockWhileAwakeEvents)

            underTest.onKeyguardServiceDoKeyguardTimeout(options = null)
            runCurrent()

            assertThat(values)
                .containsExactly(LockWhileAwakeReason.KEYGUARD_TIMEOUT_WHILE_SCREEN_ON)

            advanceTimeBy(1000)
            underTest.onKeyguardServiceDoKeyguardTimeout(options = null)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    LockWhileAwakeReason.KEYGUARD_TIMEOUT_WHILE_SCREEN_ON,
                    LockWhileAwakeReason.KEYGUARD_TIMEOUT_WHILE_SCREEN_ON,
                )
        }

    @Test
    fun emitsWhenKeyguardEnabled_onlyIfShowingWhenDisabled() =
        testScope.runTest {
            val values by collectValues(underTest.lockWhileAwakeEvents)

            kosmos.biometricSettingsRepository.setIsUserInLockdown(false)
            runCurrent()

            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()

            assertEquals(0, values.size)

            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(true)
            runCurrent()

            assertThat(values).containsExactly(LockWhileAwakeReason.KEYGUARD_REENABLED)

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope = testScope,
            )
            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(true)
            runCurrent()

            assertThat(values).containsExactly(LockWhileAwakeReason.KEYGUARD_REENABLED)
        }
}
