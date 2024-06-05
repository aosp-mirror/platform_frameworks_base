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

package com.android.systemui.communal

import android.platform.test.annotations.EnableFlags
import android.service.dream.dreamManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalDreamStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: CommunalDreamStartable

    private val dreamManager by lazy { kosmos.dreamManager }
    private val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    private val powerRepository by lazy { kosmos.fakePowerRepository }

    @Before
    fun setUp() {
        underTest =
            CommunalDreamStartable(
                    powerInteractor = kosmos.powerInteractor,
                    keyguardInteractor = kosmos.keyguardInteractor,
                    keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor,
                    communalInteractor = kosmos.communalInteractor,
                    dreamManager = dreamManager,
                    bgScope = kosmos.applicationCoroutineScope,
                )
                .apply { start() }
    }

    @Test
    fun startDreamWhenTransitioningToHub() =
        testScope.runTest {
            keyguardRepository.setDreaming(false)
            powerRepository.setScreenPowerState(ScreenPowerState.SCREEN_ON)
            whenever(dreamManager.canStartDreaming(/* isScreenOn = */ true)).thenReturn(true)
            runCurrent()

            verify(dreamManager, never()).startDream()

            transition(from = KeyguardState.DREAMING, to = KeyguardState.GLANCEABLE_HUB)

            verify(dreamManager).startDream()
        }

    @Test
    @EnableFlags(Flags.FLAG_RESTART_DREAM_ON_UNOCCLUDE)
    fun restartDreamingWhenTransitioningFromDreamingToOccludedToDreaming() =
        testScope.runTest {
            keyguardRepository.setDreaming(false)
            powerRepository.setScreenPowerState(ScreenPowerState.SCREEN_ON)
            whenever(dreamManager.canStartDreaming(/* isScreenOn = */ true)).thenReturn(true)
            runCurrent()

            verify(dreamManager, never()).startDream()

            kosmos.fakeKeyguardRepository.setKeyguardOccluded(true)
            kosmos.fakeKeyguardRepository.setDreaming(true)
            runCurrent()

            transition(from = KeyguardState.DREAMING, to = KeyguardState.OCCLUDED)
            kosmos.fakeKeyguardRepository.setKeyguardOccluded(false)
            kosmos.fakeKeyguardRepository.setDreaming(false)
            runCurrent()

            transition(from = KeyguardState.OCCLUDED, to = KeyguardState.DREAMING)
            runCurrent()

            verify(dreamManager).startDream()
        }

    @Test
    fun shouldNotStartDreamWhenIneligibleToDream() =
        testScope.runTest {
            keyguardRepository.setDreaming(false)
            powerRepository.setScreenPowerState(ScreenPowerState.SCREEN_ON)
            // Not eligible to dream
            whenever(dreamManager.canStartDreaming(/* isScreenOn = */ true)).thenReturn(false)
            transition(from = KeyguardState.DREAMING, to = KeyguardState.GLANCEABLE_HUB)

            verify(dreamManager, never()).startDream()
        }

    @Test
    fun shouldNotStartDreamIfAlreadyDreaming() =
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            powerRepository.setScreenPowerState(ScreenPowerState.SCREEN_ON)
            whenever(dreamManager.canStartDreaming(/* isScreenOn = */ true)).thenReturn(true)
            transition(from = KeyguardState.DREAMING, to = KeyguardState.GLANCEABLE_HUB)

            verify(dreamManager, never()).startDream()
        }

    @Test
    fun shouldNotStartDreamForInvalidTransition() =
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            powerRepository.setScreenPowerState(ScreenPowerState.SCREEN_ON)
            whenever(dreamManager.canStartDreaming(/* isScreenOn = */ true)).thenReturn(true)

            // Verify we do not trigger dreaming for any other state besides glanceable hub
            for (state in KeyguardState.entries) {
                if (state == KeyguardState.GLANCEABLE_HUB) continue
                transition(from = KeyguardState.GLANCEABLE_HUB, to = state)
                verify(dreamManager, never()).startDream()
            }
        }

    private suspend fun TestScope.transition(from: KeyguardState, to: KeyguardState) {
        kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
            from = from,
            to = to,
            testScope = this
        )
        runCurrent()
    }
}
