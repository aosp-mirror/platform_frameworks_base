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
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.FakeLightRevealScrimRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.LightRevealScrim
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class LightRevealScrimInteractorTest : SysuiTestCase() {
    private val fakeKeyguardTransitionRepository = FakeKeyguardTransitionRepository()
    private val fakeLightRevealScrimRepository = FakeLightRevealScrimRepository()

    private val keyguardTransitionInteractor =
        KeyguardTransitionInteractor(fakeKeyguardTransitionRepository)

    private lateinit var underTest: LightRevealScrimInteractor

    private val reveal1 =
        object : LightRevealEffect {
            override fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim) {}
        }

    private val reveal2 =
        object : LightRevealEffect {
            override fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim) {}
        }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            LightRevealScrimInteractor(
                fakeKeyguardTransitionRepository,
                keyguardTransitionInteractor,
                fakeLightRevealScrimRepository
            )
    }

    @Test
    fun `lightRevealEffect - does not change during keyguard transition`() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<LightRevealEffect>()
            val job = underTest.lightRevealEffect.onEach(values::add).launchIn(this)

            fakeLightRevealScrimRepository.setRevealEffect(reveal1)

            // The reveal effect shouldn't emit anything until a keyguard transition starts.
            assertEquals(values.size, 0)

            // Once it starts, it should emit reveal1.
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(transitionState = TransitionState.STARTED)
            )
            assertEquals(values, listOf(reveal1))

            // Until the next transition starts, reveal2 should not be emitted.
            fakeLightRevealScrimRepository.setRevealEffect(reveal2)
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(transitionState = TransitionState.RUNNING)
            )
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(transitionState = TransitionState.FINISHED)
            )
            assertEquals(values, listOf(reveal1))
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(transitionState = TransitionState.STARTED)
            )
            assertEquals(values, listOf(reveal1, reveal2))

            job.cancel()
        }

    @Test
    fun `revealAmount - inverted when appropriate`() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()
            val job = underTest.revealAmount.onEach(values::add).launchIn(this)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = 0.3f
                )
            )

            assertEquals(values, listOf(0.3f))

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 0.3f
                )
            )

            assertEquals(values, listOf(0.3f, 0.7f))

            job.cancel()
        }

    @Test
    fun `revealAmount - ignores transitions that do not affect reveal amount`() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()
            val job = underTest.revealAmount.onEach(values::add).launchIn(this)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(from = KeyguardState.DOZING, to = KeyguardState.AOD, value = 0.3f)
            )

            assertEquals(values, emptyList<Float>())

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(from = KeyguardState.AOD, to = KeyguardState.DOZING, value = 0.3f)
            )

            assertEquals(values, emptyList<Float>())

            job.cancel()
        }
}
