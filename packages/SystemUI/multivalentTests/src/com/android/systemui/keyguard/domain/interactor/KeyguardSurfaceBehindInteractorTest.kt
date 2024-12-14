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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardSurfaceBehindModel
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.util.mockTopActivityClassName
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.Transition
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.activityManagerWrapper
import com.android.systemui.statusbar.notification.domain.interactor.notificationLaunchAnimationInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.assertValuesMatch
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyguardSurfaceBehindInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private lateinit var testScope: TestScope
    private lateinit var underTest: KeyguardSurfaceBehindInteractor
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository
    private lateinit var inWindowUnlockInteractor: InWindowLauncherUnlockAnimationInteractor
    private lateinit var activityManagerWrapper: ActivityManagerWrapper

    private val LAUNCHER_ACTIVITY_NAME = "launcher"

    @Before
    fun setUp() {
        testScope = kosmos.testScope
        underTest = kosmos.keyguardSurfaceBehindInteractor
        transitionRepository = kosmos.fakeKeyguardTransitionRepository
        inWindowUnlockInteractor = kosmos.inWindowLauncherUnlockAnimationInteractor
        activityManagerWrapper = kosmos.activityManagerWrapper

        inWindowUnlockInteractor.setLauncherActivityClass(LAUNCHER_ACTIVITY_NAME)

        // Default to having something other than Launcher on top.
        activityManagerWrapper.mockTopActivityClassName("not_launcher")
    }

    @Test
    @DisableSceneContainer
    fun testSurfaceBehindModel_toAppSurface() =
        testScope.runTest {
            val values by collectValues(underTest.viewParams)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    // We're initialized in LOCKSCREEN.
                    KeyguardSurfaceBehindModel(alpha = 0f),
                )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()

            values.assertValuesMatch(
                { it == KeyguardSurfaceBehindModel(alpha = 0f) },
                // Once we start a transition to GONE, we should fade in and translate up. The exact
                // start value depends on screen density, so just look for != 0.
                {
                    it.animateFromAlpha == 0f &&
                        it.alpha == 1f &&
                        it.animateFromTranslationY != 0f &&
                        it.translationY == 0f
                }
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.RUNNING,
                )
            )
            runCurrent()

            values.assertValuesMatch(
                { it == KeyguardSurfaceBehindModel(alpha = 0f) },
                // There should be no change as we're RUNNING.
                {
                    it.animateFromAlpha == 0f &&
                        it.alpha == 1f &&
                        it.animateFromTranslationY != 0f &&
                        it.translationY == 0f
                }
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.FINISHED,
                )
            )
            runCurrent()

            values.assertValuesMatch(
                { it == KeyguardSurfaceBehindModel(alpha = 0f) },
                {
                    it.animateFromAlpha == 0f &&
                        it.alpha == 1f &&
                        it.animateFromTranslationY != 0f &&
                        it.translationY == 0f
                },
                // Once the current state is GONE, we should default to alpha = 1f.
                { it == KeyguardSurfaceBehindModel(alpha = 1f) }
            )
        }

    @Test
    @DisableSceneContainer
    fun testSurfaceBehindModel_toLauncher() =
        testScope.runTest {
            val values by collectValues(underTest.viewParams)
            activityManagerWrapper.mockTopActivityClassName(LAUNCHER_ACTIVITY_NAME)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    // We're initialized in LOCKSCREEN.
                    KeyguardSurfaceBehindModel(alpha = 0f),
                )
                .inOrder()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()

            assertThat(values)
                .containsExactly(
                    KeyguardSurfaceBehindModel(alpha = 0f),
                    // We should instantly set alpha = 1, with no animations, when Launcher is
                    // behind
                    // the keyguard since we're playing in-window animations.
                    KeyguardSurfaceBehindModel(alpha = 1f),
                )
                .inOrder()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.RUNNING,
                )
            )
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.FINISHED,
                )
            )
            runCurrent()

            assertThat(values)
                .containsExactly(
                    KeyguardSurfaceBehindModel(alpha = 0f),
                    // Should have remained at alpha = 1f through the entire animation.
                    KeyguardSurfaceBehindModel(alpha = 1f),
                )
                .inOrder()
        }

    @Test
    @DisableSceneContainer
    fun testSurfaceBehindModel_fromNotificationLaunch() =
        testScope.runTest {
            val values by collectValues(underTest.viewParams)
            runCurrent()

            kosmos.notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(true)
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.5f,
                )
            )
            runCurrent()

            values.assertValuesMatch(
                // We should be at alpha = 0f during the animation.
                { it == KeyguardSurfaceBehindModel(alpha = 0f) },
            )
        }

    @Test
    @EnableSceneContainer
    fun testSurfaceBehindModel_toAppSurface_scene_container() =
        testScope.runTest {
            val values by collectValues(underTest.viewParams)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    // We're initialized in LOCKSCREEN.
                    KeyguardSurfaceBehindModel(alpha = 0f),
                )

            kosmos.setSceneTransition(Transition(Scenes.Lockscreen, Scenes.Gone))

            values.assertValuesMatch(
                { it == KeyguardSurfaceBehindModel(alpha = 0f) },
                // Once we start a transition to GONE, we should fade in and translate up. The exact
                // start value depends on screen density, so just look for != 0.
                {
                    it.animateFromAlpha == 0f &&
                        it.alpha == 1f &&
                        it.animateFromTranslationY != 0f &&
                        it.translationY == 0f
                }
            )

            kosmos.setSceneTransition(Idle(Scenes.Gone))

            values.assertValuesMatch(
                { it == KeyguardSurfaceBehindModel(alpha = 0f) },
                {
                    it.animateFromAlpha == 0f &&
                        it.alpha == 1f &&
                        it.animateFromTranslationY != 0f &&
                        it.translationY == 0f
                },
                // Once the current state is GONE, we should default to alpha = 1f.
                { it == KeyguardSurfaceBehindModel(alpha = 1f) }
            )
        }

    @Test
    @EnableSceneContainer
    fun testSurfaceBehindModel_toLauncher_scene_container() =
        testScope.runTest {
            val values by collectValues(underTest.viewParams)
            activityManagerWrapper.mockTopActivityClassName(LAUNCHER_ACTIVITY_NAME)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    // We're initialized in LOCKSCREEN.
                    KeyguardSurfaceBehindModel(alpha = 0f),
                )
                .inOrder()

            kosmos.setSceneTransition(Transition(Scenes.Lockscreen, Scenes.Gone))

            assertThat(values)
                .containsExactly(
                    KeyguardSurfaceBehindModel(alpha = 0f),
                    // We should instantly set alpha = 1, with no animations, when Launcher is
                    // behind
                    // the keyguard since we're playing in-window animations.
                    KeyguardSurfaceBehindModel(alpha = 1f),
                )
                .inOrder()

            kosmos.setSceneTransition(Idle(Scenes.Gone))

            assertThat(values)
                .containsExactly(
                    KeyguardSurfaceBehindModel(alpha = 0f),
                    // Should have remained at alpha = 1f through the entire animation.
                    KeyguardSurfaceBehindModel(alpha = 1f),
                )
                .inOrder()
        }

    @Test
    @EnableSceneContainer
    fun testSurfaceBehindModel_fromNotificationLaunch_scene_container() =
        testScope.runTest {
            val values by collectValues(underTest.viewParams)
            runCurrent()

            kosmos.notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(true)
            runCurrent()

            kosmos.setSceneTransition(Transition(Scenes.Lockscreen, Scenes.Gone))

            values.assertValuesMatch(
                // We should be at alpha = 0f during the animation.
                { it == KeyguardSurfaceBehindModel(alpha = 0f) },
            )
        }

    @Test
    @DisableSceneContainer
    fun notificationLaunchFromLockscreen_isAnimatingSurfaceTrue() =
        testScope.runTest {
            val isAnimatingSurface by collectLastValue(underTest.isAnimatingSurface)
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED,
                )
            )
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.FINISHED,
                )
            )
            kosmos.notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(true)
            runCurrent()
            assertThat(isAnimatingSurface).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun notificationLaunchFromGone_isAnimatingSurfaceFalse() =
        testScope.runTest {
            val isAnimatingSurface by collectLastValue(underTest.isAnimatingSurface)
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.STARTED,
                )
            )
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.FINISHED,
                )
            )
            kosmos.notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(true)
            runCurrent()
            assertThat(isAnimatingSurface).isFalse()
        }

    @Test
    @DisableSceneContainer
    fun notificationLaunchFalse_isAnimatingSurfaceFalse() =
        testScope.runTest {
            val isAnimatingSurface by collectLastValue(underTest.isAnimatingSurface)
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED,
                )
            )
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.FINISHED,
                )
            )
            kosmos.notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(false)
            runCurrent()
            assertThat(isAnimatingSurface).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun notificationLaunchFromLockscreen_isAnimatingSurfaceTrue_scene_container() =
        testScope.runTest {
            val isAnimatingSurface by collectLastValue(underTest.isAnimatingSurface)

            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))
            kosmos.notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(true)
            runCurrent()

            assertThat(isAnimatingSurface).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun notificationLaunchFromGone_isAnimatingSurfaceFalse_scene_container() =
        testScope.runTest {
            val isAnimatingSurface by collectLastValue(underTest.isAnimatingSurface)

            kosmos.setSceneTransition(Idle(Scenes.Gone))
            kosmos.notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(true)
            runCurrent()
            assertThat(isAnimatingSurface).isFalse()

            kosmos.setSceneTransition(Transition(from = Scenes.Gone, to = Scenes.Lockscreen))
            assertThat(isAnimatingSurface).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun notificationLaunchFalse_isAnimatingSurfaceFalse_scene_container() =
        testScope.runTest {
            val isAnimatingSurface by collectLastValue(underTest.isAnimatingSurface)

            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))
            kosmos.notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(false)
            runCurrent()
            assertThat(isAnimatingSurface).isFalse()
        }
}
