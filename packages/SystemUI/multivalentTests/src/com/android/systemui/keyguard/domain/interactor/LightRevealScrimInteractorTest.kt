/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.systemui.keyguard.data.fakeLightRevealScrimRepository
import com.android.systemui.keyguard.data.repository.DEFAULT_REVEAL_EFFECT
import com.android.systemui.keyguard.data.repository.FakeLightRevealScrimRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.testKosmos
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LightRevealScrimInteractorTest : SysuiTestCase() {
    val kosmos =
        testKosmos().apply {
            this.fakeLightRevealScrimRepository = Mockito.spy(FakeLightRevealScrimRepository())
        }

    private val fakeLightRevealScrimRepository = kosmos.fakeLightRevealScrimRepository

    private val fakeKeyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository

    private val underTest = kosmos.lightRevealScrimInteractor

    private val reveal1 =
        object : LightRevealEffect {
            override fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim) {}
        }

    private val reveal2 =
        object : LightRevealEffect {
            override fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim) {}
        }

    @Test
    fun lightRevealEffect_doesNotChangeDuringKeyguardTransition() =
        kosmos.testScope.runTest {
            val values by collectValues(underTest.lightRevealEffect)
            runCurrent()
            assertEquals(listOf(DEFAULT_REVEAL_EFFECT), values)

            fakeLightRevealScrimRepository.setRevealEffect(reveal1)
            runCurrent()
            // The reveal effect shouldn't emit anything until a keyguard transition starts.
            assertEquals(listOf(DEFAULT_REVEAL_EFFECT), values)

            // Once it starts, it should emit reveal1.
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(to = KeyguardState.AOD, transitionState = TransitionState.STARTED)
            )
            runCurrent()
            assertEquals(listOf(DEFAULT_REVEAL_EFFECT, reveal1), values)

            // Until the next transition starts, reveal2 should not be emitted.
            fakeLightRevealScrimRepository.setRevealEffect(reveal2)
            runCurrent()
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.RUNNING
                )
            )
            runCurrent()
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(to = KeyguardState.AOD, transitionState = TransitionState.FINISHED)
            )
            runCurrent()
            assertEquals(listOf(DEFAULT_REVEAL_EFFECT, reveal1), values)
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED
                )
            )
            runCurrent()
            assertEquals(listOf(DEFAULT_REVEAL_EFFECT, reveal1, reveal2), values)
        }
}
