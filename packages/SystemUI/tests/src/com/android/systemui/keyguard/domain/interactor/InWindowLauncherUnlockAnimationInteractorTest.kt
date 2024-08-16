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
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.inWindowLauncherUnlockAnimationRepository
import com.android.systemui.keyguard.data.repository.keyguardSurfaceBehindRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.util.mockTopActivityClassName
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.activityManagerWrapper
import com.android.systemui.testKosmos
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class InWindowLauncherUnlockAnimationInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest =
        InWindowLauncherUnlockAnimationInteractor(
            kosmos.inWindowLauncherUnlockAnimationRepository,
            kosmos.applicationCoroutineScope,
            kosmos.keyguardTransitionInteractor,
            { kosmos.keyguardSurfaceBehindRepository },
            kosmos.activityManagerWrapper,
        )
    private val testScope = kosmos.testScope
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository
    @Mock private lateinit var activityManagerWrapper: ActivityManagerWrapper

    private val launcherClassName = "launcher"

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        transitionRepository = kosmos.fakeKeyguardTransitionRepository

        activityManagerWrapper = kosmos.activityManagerWrapper
        activityManagerWrapper.mockTopActivityClassName(launcherClassName)
    }

    @Test
    fun testTransitioningToGoneWithInWindowAnimation_trueIfTopActivityIsLauncher_andTransitioningToGone() =
        testScope.runTest {
            val values by collectValues(underTest.transitioningToGoneWithInWindowAnimation)
            runCurrent()

            assertEquals(
                listOf(
                    false, // False by default.
                ),
                values
            )

            // Put launcher on top
            kosmos.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
                launcherClassName
            )
            activityManagerWrapper.mockTopActivityClassName(launcherClassName)
            runCurrent()

            // Should still be false since we're not transitioning to GONE.
            assertEquals(
                listOf(
                    false, // False by default.
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
                    false,
                    true, // -> GONE + launcher is behind
                ),
                values
            )

            activityManagerWrapper.mockTopActivityClassName("not_launcher")
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true, // Top activity should be sampled, if it changes midway it should not
                    // matter.
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
                    false,
                    true,
                    false, // False once we're not transitioning anymore.
                ),
                values
            )
        }

    @Test
    fun testTransitioningToGoneWithInWindowAnimation_falseIfTopActivityIsLauncherPartwayThrough() =
        testScope.runTest {
            val values by collectValues(underTest.transitioningToGoneWithInWindowAnimation)
            runCurrent()

            assertEquals(
                listOf(
                    false, // False by default.
                ),
                values
            )

            // Put not launcher on top
            kosmos.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
                launcherClassName
            )
            activityManagerWrapper.mockTopActivityClassName("not_launcher")
            runCurrent()

            assertEquals(
                listOf(
                    false,
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
                    false,
                ),
                values
            )

            activityManagerWrapper.mockTopActivityClassName(launcherClassName)
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
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
                    false,
                ),
                values
            )
        }

    @Test
    fun testTransitioningToGoneWithInWindowAnimation_falseIfTopActivityIsLauncherWhileNotTransitioningToGone() =
        testScope.runTest {
            val values by collectValues(underTest.transitioningToGoneWithInWindowAnimation)
            runCurrent()

            assertEquals(
                listOf(
                    false, // False by default.
                ),
                values
            )

            // Put launcher on top
            kosmos.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
                launcherClassName
            )
            activityManagerWrapper.mockTopActivityClassName(launcherClassName)
            runCurrent()

            assertEquals(
                listOf(
                    false,
                ),
                values
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
                    false,
                ),
                values
            )
        }

    @Test
    fun testShouldStartInWindowAnimation_trueOnceSurfaceAvailable_falseWhenTransitionEnds() =
        testScope.runTest {
            val values by collectValues(underTest.shouldStartInWindowAnimation)
            runCurrent()

            assertEquals(
                listOf(
                    false, // False by default.
                ),
                values
            )

            // Put Launcher on top and begin transitioning to GONE.
            kosmos.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
                launcherClassName
            )
            activityManagerWrapper.mockTopActivityClassName(launcherClassName)
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
                ),
                values
            )

            kosmos.keyguardSurfaceBehindRepository.setSurfaceRemoteAnimationTargetAvailable(true)
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true, // The surface is now available, so we should start the animation.
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
                    false,
                    true,
                    false,
                ),
                values
            )
        }

    @Test
    fun testShouldStartInWindowAnimation_neverTrueIfSurfaceNotAvailable() =
        testScope.runTest {
            val values by collectValues(underTest.shouldStartInWindowAnimation)
            runCurrent()

            assertEquals(
                listOf(
                    false, // False by default.
                ),
                values
            )

            // Put Launcher on top and begin transitioning to GONE.
            kosmos.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
                launcherClassName
            )
            activityManagerWrapper.mockTopActivityClassName(launcherClassName)
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
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
                    false,
                ),
                values
            )
        }

    @Test
    fun testShouldStartInWindowAnimation_falseIfSurfaceAvailable_afterTransitionInterrupted() =
        testScope.runTest {
            val values by collectValues(underTest.shouldStartInWindowAnimation)
            runCurrent()

            assertEquals(
                listOf(
                    false, // False by default.
                ),
                values
            )

            // Put Launcher on top and begin transitioning to GONE.
            kosmos.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
                launcherClassName
            )
            activityManagerWrapper.mockTopActivityClassName(launcherClassName)
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.CANCELED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                )
            )
            kosmos.keyguardSurfaceBehindRepository.setSurfaceRemoteAnimationTargetAvailable(true)
            runCurrent()

            assertEquals(
                listOf(
                    false,
                ),
                values
            )
        }
}
