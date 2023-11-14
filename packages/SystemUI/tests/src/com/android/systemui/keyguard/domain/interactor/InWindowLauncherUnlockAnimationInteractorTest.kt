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
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.FakeKeyguardSurfaceBehindRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.InWindowLauncherUnlockAnimationRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.util.mockTopActivityClassName
import com.android.systemui.shared.system.ActivityManagerWrapper
import dagger.BindsInstance
import dagger.Component
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.test.TestScope
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
    private lateinit var underTest: InWindowLauncherUnlockAnimationInteractor

    private lateinit var testComponent: TestComponent
    private lateinit var testScope: TestScope
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository
    @Mock private lateinit var activityManagerWrapper: ActivityManagerWrapper

    private val launcherClassName = "launcher"

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testComponent =
            DaggerInWindowLauncherUnlockAnimationInteractorTest_TestComponent.factory()
                .create(
                    test = this,
                    mocks =
                        TestMocksModule(
                            activityManagerWrapper = activityManagerWrapper,
                        ),
                )
        underTest = testComponent.underTest
        testScope = testComponent.testScope
        transitionRepository = testComponent.transitionRepository

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
            testComponent.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
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
            testComponent.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
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
            testComponent.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
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
            testComponent.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
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

            testComponent.surfaceBehindRepository.setSurfaceRemoteAnimationTargetAvailable(true)
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
            testComponent.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
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
            testComponent.inWindowLauncherUnlockAnimationRepository.setLauncherActivityClass(
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
            testComponent.surfaceBehindRepository.setSurfaceRemoteAnimationTargetAvailable(true)
            runCurrent()

            assertEquals(
                listOf(
                    false,
                ),
                values
            )
        }

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
            ]
    )
    interface TestComponent {
        val underTest: InWindowLauncherUnlockAnimationInteractor
        val testScope: TestScope
        val transitionRepository: FakeKeyguardTransitionRepository
        val surfaceBehindRepository: FakeKeyguardSurfaceBehindRepository
        val inWindowLauncherUnlockAnimationRepository: InWindowLauncherUnlockAnimationRepository

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }
}
