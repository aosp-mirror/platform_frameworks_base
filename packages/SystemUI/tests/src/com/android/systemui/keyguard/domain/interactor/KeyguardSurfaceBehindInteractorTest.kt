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
import com.android.systemui.keyguard.data.repository.FakeKeyguardSurfaceBehindRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardSurfaceBehindModel
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations.initMocks

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyguardSurfaceBehindInteractorTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardSurfaceBehindInteractor
    private lateinit var repository: FakeKeyguardSurfaceBehindRepository

    @Mock
    private lateinit var fromLockscreenTransitionInteractor: FromLockscreenTransitionInteractor
    @Mock
    private lateinit var fromPrimaryBouncerTransitionInteractor:
        FromPrimaryBouncerTransitionInteractor

    private val lockscreenSurfaceBehindModel = KeyguardSurfaceBehindModel(alpha = 0.33f)
    private val primaryBouncerSurfaceBehindModel = KeyguardSurfaceBehindModel(alpha = 0.66f)

    private val testScope = TestScope()

    private lateinit var transitionRepository: FakeKeyguardTransitionRepository
    private lateinit var transitionInteractor: KeyguardTransitionInteractor

    @Before
    fun setUp() {
        initMocks(this)

        whenever(fromLockscreenTransitionInteractor.surfaceBehindModel)
            .thenReturn(flowOf(lockscreenSurfaceBehindModel))
        whenever(fromPrimaryBouncerTransitionInteractor.surfaceBehindModel)
            .thenReturn(flowOf(primaryBouncerSurfaceBehindModel))

        transitionRepository = FakeKeyguardTransitionRepository()

        transitionInteractor =
            KeyguardTransitionInteractorFactory.create(
                    scope = testScope.backgroundScope,
                    repository = transitionRepository,
                )
                .keyguardTransitionInteractor

        repository = FakeKeyguardSurfaceBehindRepository()
        underTest =
            KeyguardSurfaceBehindInteractor(
                repository = repository,
                fromLockscreenInteractor = fromLockscreenTransitionInteractor,
                fromPrimaryBouncerInteractor = fromPrimaryBouncerTransitionInteractor,
                transitionInteractor = transitionInteractor,
            )
    }

    @Test
    fun viewParamsSwitchToCorrectFlow() =
        testScope.runTest {
            val values by collectValues(underTest.viewParams)

            // Start on the LOCKSCREEN.
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

            // We're on LOCKSCREEN; we should be using the default params.
            assertEquals(1, values.size)
            assertTrue(values[0].alpha == 0f)

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )

            runCurrent()

            // We're going from LOCKSCREEN -> GONE, we should be using the lockscreen interactor's
            // surface behind model.
            assertEquals(2, values.size)
            assertEquals(values[1], lockscreenSurfaceBehindModel)

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                )
            )

            runCurrent()

            // We're going from PRIMARY_BOUNCER -> GONE, we should be using the bouncer interactor's
            // surface behind model.
            assertEquals(3, values.size)
            assertEquals(values[2], primaryBouncerSurfaceBehindModel)

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                )
            )

            runCurrent()

            // Once PRIMARY_BOUNCER -> GONE finishes, we should be using default params, which is
            // alpha=1f when we're GONE.
            assertEquals(4, values.size)
            assertEquals(1f, values[3].alpha)
        }
}
