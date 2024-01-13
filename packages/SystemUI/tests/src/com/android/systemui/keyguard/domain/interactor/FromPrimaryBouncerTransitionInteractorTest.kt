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
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.mock
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
class FromPrimaryBouncerTransitionInteractorTest : KeyguardTransitionInteractorTestCase() {
    private lateinit var underTest: FromPrimaryBouncerTransitionInteractor

    private val mSelectedUserInteractor = SelectedUserInteractor(FakeUserRepository())

    // Override the fromPrimaryBouncerTransitionInteractor provider from the superclass so our
    // underTest interactor is provided to any classes that need it.
    override var fromPrimaryBouncerTransitionInteractorLazy:
        Lazy<FromPrimaryBouncerTransitionInteractor>? =
        Lazy {
            underTest
        }

    @Before
    override fun setUp() {
        super.setUp()

        underTest =
            FromPrimaryBouncerTransitionInteractor(
                transitionRepository = super.transitionRepository,
                transitionInteractor = super.transitionInteractor,
                scope = super.testScope.backgroundScope,
                bgDispatcher = super.testDispatcher,
                mainDispatcher = super.testDispatcher,
                keyguardInteractor = super.keyguardInteractor,
                flags = FakeFeatureFlags(),
                keyguardSecurityModel = mock(),
                powerInteractor = PowerInteractorFactory.create().powerInteractor,
                selectedUserInteractor = mSelectedUserInteractor
            )
    }

    @Test
    fun testSurfaceBehindVisibility() =
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
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.LOCKSCREEN,
                )
            )

            runCurrent()

            assertEquals(
                listOf(
                    null, // PRIMARY_BOUNCER -> LOCKSCREEN does not have any specific visibility.
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                )
            )

            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                    value = 0.01f,
                )
            )

            runCurrent()

            assertEquals(
                listOf(
                    null,
                    false, // Surface is only made visible once the bouncer UI animates out.
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                    value = 0.99f,
                )
            )

            runCurrent()

            assertEquals(
                listOf(
                    null,
                    false,
                    true, // Surface should eventually be visible.
                ),
                values
            )
        }

    @Test
    fun testSurfaceBehindModel() =
        testScope.runTest {
            val values by collectValues(underTest.surfaceBehindModel)

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.LOCKSCREEN,
                )
            )
            runCurrent()

            assertEquals(
                listOf(
                    null, // PRIMARY_BOUNCER -> LOCKSCREEN does not have specific view params.
                ),
                values
            )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                    value = 0.01f,
                )
            )
            runCurrent()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.PRIMARY_BOUNCER,
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
