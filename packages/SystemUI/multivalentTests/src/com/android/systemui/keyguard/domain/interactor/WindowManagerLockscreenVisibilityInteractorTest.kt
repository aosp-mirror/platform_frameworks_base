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
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    @EnableSceneContainer
    fun surfaceBehindVisibility_fromLockscreenToGone_trueThroughout() =
        testScope.runTest {
            val isSurfaceBehindVisible by collectLastValue(underTest.value.surfaceBehindVisibility)
            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)

            // Before the transition, we start on Lockscreen so the surface should start invisible.
            kosmos.setSceneTransition(ObservableTransitionState.Idle(Scenes.Lockscreen))
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(isSurfaceBehindVisible).isFalse()

            // Unlocked with fingerprint.
            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )

            // Start the transition to Gone, the surface should become immediately visible.
            kosmos.setSceneTransition(
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Lockscreen,
                    toScene = Scenes.Gone,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                    progress = flowOf(0.3f),
                    currentScene = flowOf(Scenes.Lockscreen),
                )
            )
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(isSurfaceBehindVisible).isTrue()

            // Towards the end of the transition, the surface should continue to be visible.
            kosmos.setSceneTransition(
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Lockscreen,
                    toScene = Scenes.Gone,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                    progress = flowOf(0.9f),
                    currentScene = flowOf(Scenes.Gone),
                )
            )
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(isSurfaceBehindVisible).isTrue()

            // After the transition, settles on Gone. Surface behind should stay visible now.
            kosmos.setSceneTransition(ObservableTransitionState.Idle(Scenes.Gone))
            kosmos.sceneInteractor.changeScene(Scenes.Gone, "")
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(isSurfaceBehindVisible).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun surfaceBehindVisibility_fromBouncerToGone_becomesTrue() =
        testScope.runTest {
            val isSurfaceBehindVisible by collectLastValue(underTest.value.surfaceBehindVisibility)
            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)

            // Before the transition, we start on Bouncer so the surface should start invisible.
            kosmos.setSceneTransition(ObservableTransitionState.Idle(Scenes.Bouncer))
            kosmos.sceneInteractor.changeScene(Scenes.Bouncer, "")
            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
            assertThat(isSurfaceBehindVisible).isFalse()

            // Unlocked with fingerprint.
            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )

            // Start the transition to Gone, the surface should remain invisible prior to hitting
            // the
            // threshold.
            kosmos.setSceneTransition(
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Bouncer,
                    toScene = Scenes.Gone,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                    progress =
                        flowOf(
                            FromPrimaryBouncerTransitionInteractor
                                .TO_GONE_SURFACE_BEHIND_VISIBLE_THRESHOLD
                        ),
                    currentScene = flowOf(Scenes.Bouncer),
                )
            )
            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
            assertThat(isSurfaceBehindVisible).isFalse()

            // Once the transition passes the threshold, the surface should become visible.
            kosmos.setSceneTransition(
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Bouncer,
                    toScene = Scenes.Gone,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                    progress =
                        flowOf(
                            FromPrimaryBouncerTransitionInteractor
                                .TO_GONE_SURFACE_BEHIND_VISIBLE_THRESHOLD + 0.01f
                        ),
                    currentScene = flowOf(Scenes.Gone),
                )
            )
            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
            assertThat(isSurfaceBehindVisible).isTrue()

            // After the transition, settles on Gone. Surface behind should stay visible now.
            kosmos.setSceneTransition(ObservableTransitionState.Idle(Scenes.Gone))
            kosmos.sceneInteractor.changeScene(Scenes.Gone, "")
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(isSurfaceBehindVisible).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun surfaceBehindVisibility_idleWhileUnlocked_alwaysTrue() =
        testScope.runTest {
            val isSurfaceBehindVisible by collectLastValue(underTest.value.surfaceBehindVisibility)
            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)

            // Unlocked with fingerprint.
            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            kosmos.setSceneTransition(ObservableTransitionState.Idle(Scenes.Gone))
            kosmos.sceneInteractor.changeScene(Scenes.Gone, "")
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            listOf(
                    Scenes.Shade,
                    Scenes.QuickSettings,
                    Scenes.Shade,
                    Scenes.Gone,
                )
                .forEach { scene ->
                    kosmos.setSceneTransition(ObservableTransitionState.Idle(scene))
                    kosmos.sceneInteractor.changeScene(scene, "")
                    assertThat(currentScene).isEqualTo(scene)
                    assertWithMessage("Unexpected visibility for scene \"${scene.debugName}\"")
                        .that(isSurfaceBehindVisible)
                        .isTrue()
                }
        }

    @Test
    @EnableSceneContainer
    fun surfaceBehindVisibility_idleWhileLocked_alwaysFalse() =
        testScope.runTest {
            val isSurfaceBehindVisible by collectLastValue(underTest.value.surfaceBehindVisibility)
            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            listOf(
                    Scenes.Shade,
                    Scenes.QuickSettings,
                    Scenes.Shade,
                    Scenes.Lockscreen,
                )
                .forEach { scene ->
                    kosmos.setSceneTransition(ObservableTransitionState.Idle(scene))
                    kosmos.sceneInteractor.changeScene(scene, "")
                    assertWithMessage("Unexpected visibility for scene \"${scene.debugName}\"")
                        .that(isSurfaceBehindVisible)
                        .isFalse()
                }
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
    fun lockscreenVisibility() =
        testScope.runTest {
            val isDeviceUnlocked by
                collectLastValue(
                    kosmos.deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked }
                )
            assertThat(isDeviceUnlocked).isFalse()

            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            val lockscreenVisibility by collectLastValue(underTest.value.lockscreenVisibility)
            assertThat(lockscreenVisibility).isTrue()

            kosmos.sceneInteractor.changeScene(Scenes.Bouncer, "")
            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
            assertThat(lockscreenVisibility).isTrue()

            kosmos.authenticationInteractor.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
            assertThat(isDeviceUnlocked).isTrue()
            kosmos.sceneInteractor.changeScene(Scenes.Gone, "")
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(lockscreenVisibility).isFalse()

            kosmos.sceneInteractor.changeScene(Scenes.Shade, "")
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(lockscreenVisibility).isFalse()

            kosmos.sceneInteractor.changeScene(Scenes.QuickSettings, "")
            assertThat(currentScene).isEqualTo(Scenes.QuickSettings)
            assertThat(lockscreenVisibility).isFalse()

            kosmos.sceneInteractor.changeScene(Scenes.Shade, "")
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(lockscreenVisibility).isFalse()

            kosmos.sceneInteractor.changeScene(Scenes.Gone, "")
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(lockscreenVisibility).isFalse()

            kosmos.sceneInteractor.changeScene(Scenes.Lockscreen, "")
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
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
