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

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class WindowManagerLockscreenVisibilityInteractorTest : SysuiTestCase() {
    private val lockscreenSurfaceVisibilityFlow = MutableStateFlow<Boolean?>(false)
    private val primaryBouncerSurfaceVisibilityFlow = MutableStateFlow<Boolean?>(false)
    private val surfaceBehindIsAnimatingFlow = MutableStateFlow(false)

    private val kosmos =
        testKosmos().apply {
            fromLockscreenTransitionInteractor = mock<FromLockscreenTransitionInteractor>()
            fromPrimaryBouncerTransitionInteractor = mock<FromPrimaryBouncerTransitionInteractor>()
            keyguardSurfaceBehindInteractor = mock<KeyguardSurfaceBehindInteractor>()

            whenever(fromLockscreenTransitionInteractor.surfaceBehindVisibility)
                .thenReturn(lockscreenSurfaceVisibilityFlow)
            whenever(fromPrimaryBouncerTransitionInteractor.surfaceBehindVisibility)
                .thenReturn(primaryBouncerSurfaceVisibilityFlow)
            whenever(keyguardSurfaceBehindInteractor.isAnimatingSurface)
                .thenReturn(surfaceBehindIsAnimatingFlow)
        }

    private val underTest = kosmos.windowManagerLockscreenVisibilityInteractor
    private val testScope = kosmos.testScope
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository

    @Test
    fun surfaceBehindVisibility_switchesToCorrectFlow() =
        testScope.runTest {
            val values by collectValues(underTest.surfaceBehindVisibility)

            // Start on LOCKSCREEN.
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
            )

            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
            )

            runCurrent()

            assertEquals(
                listOf(
                    false, // We should start with the surface invisible on LOCKSCREEN.
                ),
                values
            )

            val lockscreenSpecificSurfaceVisibility = true
            lockscreenSurfaceVisibilityFlow.emit(lockscreenSpecificSurfaceVisibility)
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )

            runCurrent()

            // We started a transition from LOCKSCREEN, we should be using the value emitted by the
            // lockscreenSurfaceVisibilityFlow.
            assertEquals(
                listOf(
                    false,
                    lockscreenSpecificSurfaceVisibility,
                ),
                values
            )

            // Go back to LOCKSCREEN, since we won't emit 'true' twice in a row.
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                )
            )
            runCurrent()
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    lockscreenSpecificSurfaceVisibility,
                    false, // FINISHED (LOCKSCREEN)
                ),
                values
            )

            val bouncerSpecificVisibility = true
            primaryBouncerSurfaceVisibilityFlow.emit(bouncerSpecificVisibility)
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                )
            )

            runCurrent()

            // We started a transition from PRIMARY_BOUNCER, we should be using the value emitted by
            // the
            // primaryBouncerSurfaceVisibilityFlow.
            assertEquals(
                listOf(
                    false,
                    lockscreenSpecificSurfaceVisibility,
                    false,
                    bouncerSpecificVisibility,
                ),
                values
            )
        }

    @Test
    fun testUsingGoingAwayAnimation_duringTransitionToGone() =
        testScope.runTest {
            val values by collectValues(underTest.usingKeyguardGoingAwayAnimation)

            // Start on LOCKSCREEN.
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
            )
            runCurrent()
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    false, // Not using the animation when we're just sitting on LOCKSCREEN.
                ),
                values
            )

            surfaceBehindIsAnimatingFlow.emit(true)
            runCurrent()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true, // Still true when we're FINISHED -> GONE, since we're still animating.
                ),
                values
            )

            surfaceBehindIsAnimatingFlow.emit(false)
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true,
                    false, // False once the animation ends.
                ),
                values
            )
        }

    @Test
    fun testNotUsingGoingAwayAnimation_evenWhenAnimating_ifStateIsNotGone() =
        testScope.runTest {
            val values by collectValues(underTest.usingKeyguardGoingAwayAnimation)

            // Start on LOCKSCREEN.
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
            )
            runCurrent()
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    false, // Not using the animation when we're just sitting on LOCKSCREEN.
                ),
                values
            )

            surfaceBehindIsAnimatingFlow.emit(true)
            runCurrent()
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true, // We're happily animating while transitioning to gone.
                ),
                values
            )

            // Oh no, we're still surfaceBehindAnimating=true, but no longer transitioning to GONE.
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true,
                    false, // Despite the animator still running, this should be false.
                ),
                values
            )

            surfaceBehindIsAnimatingFlow.emit(false)
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true,
                    false, // The animator ending should have no effect.
                ),
                values
            )
        }

    @Test
    fun lockscreenVisibility_visibleWhenGone() =
        testScope.runTest {
            val values by collectValues(underTest.lockscreenVisibility)

            // Start on LOCKSCREEN.
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
            )
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    true, // Unsurprisingly, we should start with the lockscreen visible on
                    // LOCKSCREEN.
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    true, // Lockscreen remains visible while we're transitioning to GONE.
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    true,
                    false, // Once we're fully GONE, the lockscreen should not be visible.
                ),
                values
            )
        }
}
