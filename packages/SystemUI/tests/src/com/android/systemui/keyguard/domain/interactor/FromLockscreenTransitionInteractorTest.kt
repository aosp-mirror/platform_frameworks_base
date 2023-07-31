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
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.shade.data.repository.FakeShadeRepository
import dagger.Lazy
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import junit.framework.Assert.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FromLockscreenTransitionInteractorTest : KeyguardTransitionInteractorTestCase() {
    private lateinit var underTest: FromLockscreenTransitionInteractor

    // Override the fromLockscreenTransitionInteractor provider from the superclass so our underTest
    // interactor is provided to any classes that need it.
    override var fromLockscreenTransitionInteractorLazy: Lazy<FromLockscreenTransitionInteractor>? =
        Lazy {
            underTest
        }

    @Before
    override fun setUp() {
        super.setUp()

        underTest =
            FromLockscreenTransitionInteractor(
                transitionRepository = super.transitionRepository,
                transitionInteractor = super.transitionInteractor,
                scope = super.testScope.backgroundScope,
                keyguardInteractor = super.keyguardInteractor,
                flags = FakeFeatureFlags(),
                shadeRepository = FakeShadeRepository(),
            )
    }

    @Test
    fun testSurfaceBehindVisibility_nonNullOnlyForRelevantTransitions() =
        testScope.runTest {
            val values by collectValues(underTest.surfaceBehindVisibility)
            runCurrent()

            // Transition-specific surface visibility should be null ("don't care") initially.
            assertEquals(
                listOf(
                    null,
                ),
                values
            )

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
                    null, // LOCKSCREEN -> AOD does not have any specific surface visibility.
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
                    null,
                    true, // Surface is made visible immediately during LOCKSCREEN -> GONE
                ),
                values
            )
        }

    @Test
    fun testSurfaceBehindModel() =
        testScope.runTest {
            val values by collectValues(underTest.surfaceBehindModel)
            runCurrent()

            assertEquals(
                values,
                listOf(
                    null, // We should start null ("don't care").
                )
            )

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
                    null, // LOCKSCREEN -> AOD does not have specific view params.
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

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    value = 0.01f,
                )
            )
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    value = 0.99f,
                )
            )
            runCurrent()

            assertEquals(3, values.size)
            val model1percent = values[1]
            val model99percent = values[2]

            try {
                // We should initially have an alpha of 0f when unlocking, so the surface is not
                // visible
                // while lockscreen UI animates out.
                assertEquals(0f, model1percent!!.alpha)

                // By the end it should probably be visible.
                assertTrue(model99percent!!.alpha > 0f)
            } catch (e: NullPointerException) {
                fail("surfaceBehindModel was unexpectedly null.")
            }
        }
}
