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
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Spy

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LightRevealScrimInteractorTest : SysuiTestCase() {
    private val fakeKeyguardTransitionRepository = FakeKeyguardTransitionRepository()

    @Spy private val fakeLightRevealScrimRepository = FakeLightRevealScrimRepository()

    private val testScope = TestScope()

    private val keyguardTransitionInteractor =
        KeyguardTransitionInteractorFactory.create(
                scope = testScope.backgroundScope,
                repository = fakeKeyguardTransitionRepository,
            )
            .keyguardTransitionInteractor

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
                keyguardTransitionInteractor,
                fakeLightRevealScrimRepository,
                testScope.backgroundScope,
                mock(),
                mock()
            )
    }

    @Test
    fun lightRevealEffect_doesNotChangeDuringKeyguardTransition() =
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
    fun lightRevealEffect_startsAnimationOnlyForDifferentStateTargets() =
        testScope.runTest {
            runCurrent()
            reset(fakeLightRevealScrimRepository)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.OFF,
                    to = KeyguardState.OFF
                )
            )
            runCurrent()
            verify(fakeLightRevealScrimRepository, never()).startRevealAmountAnimator(anyBoolean())

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.DOZING,
                    to = KeyguardState.LOCKSCREEN
                )
            )
            runCurrent()
            verify(fakeLightRevealScrimRepository).startRevealAmountAnimator(true)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.DOZING
                )
            )
            runCurrent()
            verify(fakeLightRevealScrimRepository).startRevealAmountAnimator(false)
        }
}
