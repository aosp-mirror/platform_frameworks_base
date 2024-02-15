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

package com.android.systemui.communal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dock.DockManager
import com.android.systemui.dock.dockManager
import com.android.systemui.dock.fakeDockManager
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalSceneStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private lateinit var underTest: CommunalSceneStartable

    @Before
    fun setUp() =
        with(kosmos) {
            underTest =
                CommunalSceneStartable(
                        dockManager = dockManager,
                        communalInteractor = communalInteractor,
                        keyguardTransitionInteractor = keyguardTransitionInteractor,
                        applicationScope = applicationCoroutineScope,
                        bgScope = applicationCoroutineScope,
                    )
                    .apply { start() }
        }

    @Test
    fun keyguardGoesAway_forceBlankScene() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalInteractor.desiredScene)

                communalInteractor.onSceneChanged(CommunalSceneKey.Communal)
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                    testScope = this
                )

                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)
            }
        }

    @Test
    fun deviceDreaming_forceBlankScene() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalInteractor.desiredScene)

                communalInteractor.onSceneChanged(CommunalSceneKey.Communal)
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.DREAMING,
                    testScope = this
                )

                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)
            }
        }

    @Test
    fun deviceDocked_forceCommunalScene() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalInteractor.desiredScene)
                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)

                updateDocked(true)
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.DREAMING,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)
            }
        }

    @Test
    fun deviceDocked_doesNotForceCommunalIfTransitioningFromCommunal() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalInteractor.desiredScene)
                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)

                updateDocked(true)
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.LOCKSCREEN,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)
            }
        }

    @Test
    fun deviceAsleep_forceBlankSceneAfterTimeout() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalInteractor.desiredScene)
                communalInteractor.onSceneChanged(CommunalSceneKey.Communal)
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.OFF,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)

                advanceTimeBy(CommunalSceneStartable.AWAKE_DEBOUNCE_DELAY)

                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)
            }
        }

    @Test
    fun deviceAsleep_wakesUpBeforeTimeout_noChangeInScene() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalInteractor.desiredScene)
                communalInteractor.onSceneChanged(CommunalSceneKey.Communal)
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.OFF,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)
                advanceTimeBy(CommunalSceneStartable.AWAKE_DEBOUNCE_DELAY / 2)
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GLANCEABLE_HUB,
                    testScope = this
                )

                advanceTimeBy(CommunalSceneStartable.AWAKE_DEBOUNCE_DELAY)
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)
            }
        }

    @Test
    fun dockingOnLockscreen_forcesCommunal() =
        with(kosmos) {
            testScope.runTest {
                communalInteractor.onSceneChanged(CommunalSceneKey.Blank)
                val scene by collectLastValue(communalInteractor.desiredScene)

                // device is docked while on the lockscreen
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.LOCKSCREEN,
                    testScope = this
                )
                updateDocked(true)

                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)
                advanceTimeBy(CommunalSceneStartable.DOCK_DEBOUNCE_DELAY)
                assertThat(scene).isEqualTo(CommunalSceneKey.Communal)
            }
        }

    @Test
    fun dockingOnLockscreen_doesNotForceCommunalIfDreamStarts() =
        with(kosmos) {
            testScope.runTest {
                communalInteractor.onSceneChanged(CommunalSceneKey.Blank)
                val scene by collectLastValue(communalInteractor.desiredScene)

                // device is docked while on the lockscreen
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.LOCKSCREEN,
                    testScope = this
                )
                updateDocked(true)

                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)
                advanceTimeBy(CommunalSceneStartable.DOCK_DEBOUNCE_DELAY / 2)
                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)

                // dream starts shortly after docking
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.DREAMING,
                    testScope = this
                )
                advanceTimeBy(CommunalSceneStartable.DOCK_DEBOUNCE_DELAY)
                assertThat(scene).isEqualTo(CommunalSceneKey.Blank)
            }
        }

    private fun TestScope.updateDocked(docked: Boolean) =
        with(kosmos) {
            runCurrent()
            fakeDockManager.setIsDocked(docked)
            fakeDockManager.setDockEvent(DockManager.STATE_DOCKED)
            runCurrent()
        }
}
