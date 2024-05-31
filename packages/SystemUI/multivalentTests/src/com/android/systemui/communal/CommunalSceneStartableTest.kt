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

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dock.dockManager
import com.android.systemui.dock.fakeDockManager
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.notificationShadeWindowController
import com.android.systemui.statusbar.phone.centralSurfaces
import com.android.systemui.statusbar.phone.centralSurfacesOptional
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalSceneStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private lateinit var underTest: CommunalSceneStartable

    @Before
    fun setUp() {
        with(kosmos) {
            fakeSettings.putInt(Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_TIMEOUT)

            underTest =
                CommunalSceneStartable(
                        dockManager = dockManager,
                        communalInteractor = communalInteractor,
                        communalSceneInteractor = communalSceneInteractor,
                        keyguardTransitionInteractor = keyguardTransitionInteractor,
                        keyguardInteractor = keyguardInteractor,
                        systemSettings = fakeSettings,
                        notificationShadeWindowController = notificationShadeWindowController,
                        applicationScope = applicationCoroutineScope,
                        bgScope = applicationCoroutineScope,
                        mainDispatcher = testDispatcher,
                        centralSurfacesOpt = centralSurfacesOptional,
                    )
                    .apply { start() }

            // Make communal available so that communalInteractor.desiredScene accurately reflects
            // scene changes instead of just returning Blank.
            with(kosmos.testScope) {
                launch { setCommunalAvailable(true) }
                testScheduler.runCurrent()
            }
        }
    }

    @Test
    fun keyguardGoesAway_forceBlankScene() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalSceneInteractor.currentScene)

                communalSceneInteractor.changeScene(CommunalScenes.Communal)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                    testScope = this
                )

                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    @Ignore("Ignored until custom animations are implemented in b/322787129")
    @Test
    fun deviceDocked_forceCommunalScene() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalSceneInteractor.currentScene)
                assertThat(scene).isEqualTo(CommunalScenes.Blank)

                updateDocked(true)
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalScenes.Communal)
            }
        }

    @Test
    fun occluded_forceBlankScene() =
        with(kosmos) {
            testScope.runTest {
                whenever(centralSurfaces.isLaunchingActivityOverLockscreen).thenReturn(false)
                val scene by collectLastValue(communalSceneInteractor.currentScene)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                updateDocked(true)
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.OCCLUDED,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    @Test
    fun occluded_doesNotForceBlankSceneIfLaunchingActivityOverLockscreen() =
        with(kosmos) {
            testScope.runTest {
                whenever(centralSurfaces.isLaunchingActivityOverLockscreen).thenReturn(true)
                val scene by collectLastValue(communalSceneInteractor.currentScene)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                updateDocked(true)
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.OCCLUDED,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalScenes.Communal)
            }
        }

    @Test
    fun deviceDocked_doesNotForceCommunalIfTransitioningFromCommunal() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalSceneInteractor.currentScene)
                assertThat(scene).isEqualTo(CommunalScenes.Blank)

                updateDocked(true)
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.LOCKSCREEN,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    @Test
    fun deviceAsleep_forceBlankSceneAfterTimeout() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalSceneInteractor.currentScene)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.OFF,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                advanceTimeBy(CommunalSceneStartable.AWAKE_DEBOUNCE_DELAY)

                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    @Test
    fun deviceAsleep_wakesUpBeforeTimeout_noChangeInScene() =
        with(kosmos) {
            testScope.runTest {
                val scene by collectLastValue(communalSceneInteractor.currentScene)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.OFF,
                    testScope = this
                )
                assertThat(scene).isEqualTo(CommunalScenes.Communal)
                advanceTimeBy(CommunalSceneStartable.AWAKE_DEBOUNCE_DELAY / 2)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GLANCEABLE_HUB,
                    testScope = this
                )

                advanceTimeBy(CommunalSceneStartable.AWAKE_DEBOUNCE_DELAY)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)
            }
        }

    @Ignore("Ignored until custom animations are implemented in b/322787129")
    @Test
    fun dockingOnLockscreen_forcesCommunal() =
        with(kosmos) {
            testScope.runTest {
                communalSceneInteractor.changeScene(CommunalScenes.Blank)
                val scene by collectLastValue(communalSceneInteractor.currentScene)

                // device is docked while on the lockscreen
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.LOCKSCREEN,
                    testScope = this
                )
                updateDocked(true)

                assertThat(scene).isEqualTo(CommunalScenes.Blank)
                advanceTimeBy(CommunalSceneStartable.DOCK_DEBOUNCE_DELAY)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)
            }
        }

    @Ignore("Ignored until custom animations are implemented in b/322787129")
    @Test
    fun dockingOnLockscreen_doesNotForceCommunalIfDreamStarts() =
        with(kosmos) {
            testScope.runTest {
                communalSceneInteractor.changeScene(CommunalScenes.Blank)
                val scene by collectLastValue(communalSceneInteractor.currentScene)

                // device is docked while on the lockscreen
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.LOCKSCREEN,
                    testScope = this
                )
                updateDocked(true)

                assertThat(scene).isEqualTo(CommunalScenes.Blank)
                advanceTimeBy(CommunalSceneStartable.DOCK_DEBOUNCE_DELAY / 2)
                assertThat(scene).isEqualTo(CommunalScenes.Blank)

                // dream starts shortly after docking
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.DREAMING,
                    testScope = this
                )
                advanceTimeBy(CommunalSceneStartable.DOCK_DEBOUNCE_DELAY)
                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    @Test
    fun hubTimeout_whenDreaming_goesToBlank() =
        with(kosmos) {
            testScope.runTest {
                // Device is dreaming and on communal.
                updateDreaming(true)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)

                val scene by collectLastValue(communalSceneInteractor.currentScene)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                // Scene times out back to blank after the screen timeout.
                advanceTimeBy(SCREEN_TIMEOUT.milliseconds)
                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    @Test
    fun hubTimeout_notDreaming_staysOnCommunal() =
        with(kosmos) {
            testScope.runTest {
                // Device is not dreaming and on communal.
                updateDreaming(false)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)

                // Scene stays as Communal
                advanceTimeBy(SCREEN_TIMEOUT.milliseconds)
                val scene by collectLastValue(communalSceneInteractor.currentScene)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)
            }
        }

    @Test
    fun hubTimeout_dreamStopped_staysOnCommunal() =
        with(kosmos) {
            testScope.runTest {
                // Device is dreaming and on communal.
                updateDreaming(true)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)

                val scene by collectLastValue(communalSceneInteractor.currentScene)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                // Wait a bit, but not long enough to timeout.
                advanceTimeBy((SCREEN_TIMEOUT / 2).milliseconds)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                // Dream stops, timeout is cancelled and device stays on hub, because the regular
                // screen timeout will take effect at this point.
                updateDreaming(false)
                advanceTimeBy((SCREEN_TIMEOUT / 2).milliseconds)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)
            }
        }

    @Test
    fun hubTimeout_dreamStartedHalfway_goesToCommunal() =
        with(kosmos) {
            testScope.runTest {
                // Device is on communal, but not dreaming.
                updateDreaming(false)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)

                val scene by collectLastValue(communalSceneInteractor.currentScene)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                // Wait a bit, but not long enough to timeout, then start dreaming.
                advanceTimeBy((SCREEN_TIMEOUT / 2).milliseconds)
                updateDreaming(true)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                // Device times out after one screen timeout interval, dream doesn't reset timeout.
                advanceTimeBy((SCREEN_TIMEOUT / 2).milliseconds)
                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    @Test
    fun hubTimeout_dreamAfterInitialTimeout_goesToBlank() =
        with(kosmos) {
            testScope.runTest {
                // Device is on communal.
                communalSceneInteractor.changeScene(CommunalScenes.Communal)

                // Device stays on the hub after the timeout since we're not dreaming.
                advanceTimeBy(SCREEN_TIMEOUT.milliseconds * 2)
                val scene by collectLastValue(communalSceneInteractor.currentScene)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                // Start dreaming.
                updateDreaming(true)

                // Hub times out immediately.
                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    @Test
    fun hubTimeout_userActivityTriggered_resetsTimeout() =
        with(kosmos) {
            testScope.runTest {
                // Device is dreaming and on communal.
                updateDreaming(true)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)

                val scene by collectLastValue(communalSceneInteractor.currentScene)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                // Wait a bit, but not long enough to timeout.
                advanceTimeBy((SCREEN_TIMEOUT / 2).milliseconds)

                // Send user interaction to reset timeout.
                communalInteractor.signalUserInteraction()

                // If user activity didn't reset timeout, we would have gone back to Blank by now.
                advanceTimeBy((SCREEN_TIMEOUT / 2).milliseconds)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                // Timeout happens one interval after the user interaction.
                advanceTimeBy((SCREEN_TIMEOUT / 2).milliseconds)
                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    @Test
    fun hubTimeout_screenTimeoutChanged() =
        with(kosmos) {
            testScope.runTest {
                fakeSettings.putInt(Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_TIMEOUT * 2)

                // Device is dreaming and on communal.
                updateDreaming(true)
                communalSceneInteractor.changeScene(CommunalScenes.Communal)

                val scene by collectLastValue(communalSceneInteractor.currentScene)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                // Scene times out back to blank after the screen timeout.
                advanceTimeBy(SCREEN_TIMEOUT.milliseconds)
                assertThat(scene).isEqualTo(CommunalScenes.Communal)

                advanceTimeBy(SCREEN_TIMEOUT.milliseconds)
                assertThat(scene).isEqualTo(CommunalScenes.Blank)
            }
        }

    private fun TestScope.updateDocked(docked: Boolean) =
        with(kosmos) {
            runCurrent()
            fakeDockManager.setIsDocked(docked)
            // TODO(b/322787129): uncomment once custom animations are in place
            // fakeDockManager.setDockEvent(DockManager.STATE_DOCKED)
            runCurrent()
        }

    private fun TestScope.updateDreaming(dreaming: Boolean) =
        with(kosmos) {
            fakeKeyguardRepository.setDreaming(dreaming)
            runCurrent()
        }

    companion object {
        private const val SCREEN_TIMEOUT = 1000
    }
}
