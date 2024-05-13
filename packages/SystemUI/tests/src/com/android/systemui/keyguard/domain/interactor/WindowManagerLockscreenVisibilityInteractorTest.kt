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
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
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

    private val underTest = lazy { kosmos.windowManagerLockscreenVisibilityInteractor }
    private val testScope = kosmos.testScope
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        // lazy value needs to be called here otherwise flow collection misbehaves
        underTest.value
        kosmos.sceneContainerRepository.setTransitionState(sceneTransitions)
    }

    @Test
    @DisableSceneContainer
    fun surfaceBehindVisibility_switchesToCorrectFlow() =
        testScope.runTest {
            val values by collectValues(underTest.value.surfaceBehindVisibility)

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
    @DisableSceneContainer
    fun testUsingGoingAwayAnimation_duringTransitionToGone() =
        testScope.runTest {
            val values by collectValues(underTest.value.usingKeyguardGoingAwayAnimation)

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
    @DisableSceneContainer
    fun testNotUsingGoingAwayAnimation_evenWhenAnimating_ifStateIsNotGone() =
        testScope.runTest {
            val values by collectValues(underTest.value.usingKeyguardGoingAwayAnimation)

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
                    transitionState = TransitionState.CANCELED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()
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
    @DisableSceneContainer
    fun lockscreenVisibility_visibleWhenGone() =
        testScope.runTest {
            val values by collectValues(underTest.value.lockscreenVisibility)

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

    @Test
    @DisableSceneContainer
    fun testLockscreenVisibility_usesFromState_ifCanceled() =
        testScope.runTest {
            val values by collectValues(underTest.value.lockscreenVisibility)

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )

            runCurrent()

            assertEquals(
                listOf(
                    // Initially should be true, as we start in LOCKSCREEN.
                    true,
                    // Then, false, since we finish in GONE.
                    false,
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    true,
                    // Should remain false as we transition from GONE.
                    false,
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.CANCELED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
            )

            runCurrent()

            assertEquals(
                listOf(
                    true,
                    false,
                    // If we cancel and then go from LS -> GONE, we should immediately flip to the
                    // visibility of the from state (LS).
                    true,
                ),
                values
            )

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
                    true,
                    false,
                    true,
                ),
                values
            )
        }

    /**
     * Tests the special case for insecure camera launch. CANCELING a transition from GONE and then
     * STARTING a transition back to GONE should never show the lockscreen, even though the current
     * state during the AOD/isAsleep -> GONE transition is AOD (where lockscreen visibility = true).
     */
    @Test
    @DisableSceneContainer
    fun testLockscreenVisibility_falseDuringTransitionToGone_fromCanceledGone() =
        testScope.runTest {
            val values by collectValues(underTest.value.lockscreenVisibility)

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )

            runCurrent()
            assertEquals(
                listOf(
                    true,
                    // Not visible since we're GONE.
                    false,
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            runCurrent()
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.CANCELED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            runCurrent()
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.AOD,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.GONE,
                )
            )

            runCurrent()
            assertEquals(
                listOf(
                    true,
                    // Remains not visible from GONE -> AOD (canceled) -> AOD since we never
                    // FINISHED in AOD, and special-case handling for the insecure camera launch
                    // ensures that we use the lockscreen visibility for GONE (false) if we're
                    // STARTED to GONE after a CANCELED from GONE.
                    false,
                ),
                values
            )

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            assertEquals(
                listOf(
                    true,
                    false,
                    // Make sure there's no stuck overrides or something - we should make lockscreen
                    // visible again once we're finished in LOCKSCREEN.
                    true,
                ),
                values
            )
        }

    @Test
    @DisableSceneContainer
    fun testLockscreenVisibility_trueDuringTransitionToGone_fromNotCanceledGone() =
        testScope.runTest {
            val values by collectValues(underTest.value.lockscreenVisibility)

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )

            runCurrent()
            assertEquals(
                listOf(
                    true,
                    // Not visible when finished in GONE.
                    false,
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            runCurrent()
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    true,
                    // Still not visible during GONE -> AOD.
                    false,
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    true,
                    false,
                    // Visible now that we're FINISHED in AOD.
                    true
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.AOD,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    true,
                    false,
                    // Remains visible from AOD during transition.
                    true
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.GONE,
                )
            )

            runCurrent()
            assertEquals(
                listOf(
                    true,
                    false,
                    true,
                    // Until we're finished in GONE again.
                    false
                ),
                values
            )
        }

    @Test
    @EnableSceneContainer
    fun sceneContainer_lockscreenVisibility_visibleWhenNotGone() =
        testScope.runTest {
            val lockscreenVisibility by collectLastValue(underTest.value.lockscreenVisibility)

            sceneTransitions.value = lsToGone
            assertThat(lockscreenVisibility).isTrue()

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Gone)
            assertThat(lockscreenVisibility).isFalse()

            sceneTransitions.value = goneToLs
            assertThat(lockscreenVisibility).isFalse()

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Lockscreen)
            assertThat(lockscreenVisibility).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun sceneContainer_lockscreenVisibility_notVisibleWhenReturningToGone() =
        testScope.runTest {
            val lockscreenVisibility by collectLastValue(underTest.value.lockscreenVisibility)

            sceneTransitions.value = goneToLs
            assertThat(lockscreenVisibility).isFalse()

            sceneTransitions.value = lsToGone
            assertThat(lockscreenVisibility).isFalse()

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Gone)
            assertThat(lockscreenVisibility).isFalse()

            sceneTransitions.value = goneToLs
            assertThat(lockscreenVisibility).isFalse()

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Lockscreen)
            assertThat(lockscreenVisibility).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun sceneContainer_usingGoingAwayAnimation_duringTransitionToGone() =
        testScope.runTest {
            val usingKeyguardGoingAwayAnimation by
                collectLastValue(underTest.value.usingKeyguardGoingAwayAnimation)

            sceneTransitions.value = lsToGone
            assertThat(usingKeyguardGoingAwayAnimation).isTrue()

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Gone)
            assertThat(usingKeyguardGoingAwayAnimation).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun sceneContainer_usingGoingAwayAnimation_surfaceBehindIsAnimating() =
        testScope.runTest {
            val usingKeyguardGoingAwayAnimation by
                collectLastValue(underTest.value.usingKeyguardGoingAwayAnimation)

            sceneTransitions.value = lsToGone
            surfaceBehindIsAnimatingFlow.emit(true)
            assertThat(usingKeyguardGoingAwayAnimation).isTrue()

            sceneTransitions.value = ObservableTransitionState.Idle(Scenes.Gone)
            assertThat(usingKeyguardGoingAwayAnimation).isTrue()

            sceneTransitions.value = goneToLs
            assertThat(usingKeyguardGoingAwayAnimation).isTrue()

            surfaceBehindIsAnimatingFlow.emit(false)
            assertThat(usingKeyguardGoingAwayAnimation).isFalse()
        }

    companion object {
        private val progress = MutableStateFlow(0f)

        private val sceneTransitions =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Idle(Scenes.Lockscreen)
            )

        private val lsToGone =
            ObservableTransitionState.Transition(
                Scenes.Lockscreen,
                Scenes.Gone,
                flowOf(Scenes.Lockscreen),
                progress,
                false,
                flowOf(false)
            )

        private val goneToLs =
            ObservableTransitionState.Transition(
                Scenes.Gone,
                Scenes.Lockscreen,
                flowOf(Scenes.Lockscreen),
                progress,
                false,
                flowOf(false)
            )
    }
}
