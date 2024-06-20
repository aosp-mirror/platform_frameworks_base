/*
 * Copyright (C) 2024 The Android Open Source Project
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

/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.testKosmos
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FromOccludedTransitionInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            this.fakeKeyguardTransitionRepository = spy(FakeKeyguardTransitionRepository())
        }

    private val testScope = kosmos.testScope
    private val underTest = kosmos.fromOccludedTransitionInteractor

    private val powerInteractor = kosmos.powerInteractor
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository

    @Before
    fun setup() {
        underTest.start()

        // Transition to OCCLUDED and set up PowerInteractor and the occlusion repository.
        powerInteractor.setAwakeForTest()
        runBlocking {
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(onTop = true)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope
            )
            reset(transitionRepository)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testShowWhenLockedActivity_noLongerOnTop_transitionsToLockscreen() =
        testScope.runTest {
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(onTop = false)
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.LOCKSCREEN,
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testShowWhenLockedActivity_noLongerOnTop_transitionsToGlanceableHub_ifIdleOnCommunal() =
        testScope.runTest {
            kosmos.fakeCommunalSceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            runCurrent()

            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(onTop = false)
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.GLANCEABLE_HUB,
                )
        }
}
