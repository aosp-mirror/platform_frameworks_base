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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.biometricUnlockInteractor
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.domain.interactor.brightnessMirrorShowingInteractor
import com.android.systemui.statusbar.domain.interactor.keyguardOcclusionInteractor
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.ScrimState
import com.android.systemui.statusbar.phone.centralSurfaces
import com.android.systemui.statusbar.phone.dozeServiceHost
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.reflect.full.memberProperties
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.Parameter
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@EnableSceneContainer
class ScrimStartableTest : SysuiTestCase() {

    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun testSpecs(): List<TestSpec> {
            return listOf(
                TestSpec(
                    id = 0,
                    expectedState = ScrimState.KEYGUARD,
                    Preconditions(
                        isOnKeyguard = true,
                        isAlternateBouncerVisible = true,
                        isTransitioningAwayFromKeyguard = true,
                    ),
                ),
                TestSpec(
                    id = 1,
                    expectedState = null,
                    Preconditions(
                        isOnKeyguard = true,
                        isAlternateBouncerVisible = true,
                        isTransitioningToShade = true,
                    ),
                ),
                TestSpec(
                    id = 2,
                    expectedState = ScrimState.BOUNCER,
                    Preconditions(
                        isOnKeyguard = true,
                        isCurrentSceneBouncer = true,
                    ),
                ),
                TestSpec(
                    id = 3,
                    expectedState = ScrimState.BOUNCER_SCRIMMED,
                    Preconditions(
                        isOnKeyguard = true,
                        isCurrentSceneBouncer = true,
                        isBouncerScrimmingNeeded = true,
                    ),
                ),
                TestSpec(
                    id = 4,
                    expectedState = ScrimState.BRIGHTNESS_MIRROR,
                    Preconditions(
                        isOnKeyguard = true,
                        isBrightnessMirrorVisible = true,
                    ),
                ),
                TestSpec(
                    id = 5,
                    expectedState = ScrimState.BRIGHTNESS_MIRROR,
                    Preconditions(
                        isOnKeyguard = true,
                        isCurrentSceneBouncer = true,
                        isBiometricWakeAndUnlock = true,
                        isBrightnessMirrorVisible = true,
                    ),
                ),
                TestSpec(
                    id = 6,
                    expectedState = ScrimState.SHADE_LOCKED,
                    Preconditions(
                        isOnKeyguard = true,
                        isCurrentSceneShade = true,
                    ),
                ),
                TestSpec(
                    id = 7,
                    expectedState = ScrimState.PULSING,
                    Preconditions(
                        isOnKeyguard = true,
                        isDozing = true,
                        isPulsing = true,
                    ),
                ),
                TestSpec(
                    id = 8,
                    expectedState = ScrimState.OFF,
                    Preconditions(
                        isOnKeyguard = true,
                        hasPendingScreenOffCallback = true,
                    ),
                ),
                TestSpec(
                    id = 9,
                    expectedState = ScrimState.AOD,
                    Preconditions(
                        isOnKeyguard = true,
                        isDozing = true,
                    ),
                ),
                TestSpec(
                    id = 10,
                    expectedState = ScrimState.GLANCEABLE_HUB,
                    Preconditions(
                        isIdleOnCommunal = true,
                    ),
                ),
                TestSpec(
                    id = 11,
                    expectedState = ScrimState.GLANCEABLE_HUB_OVER_DREAM,
                    Preconditions(isIdleOnCommunal = true, isDreaming = true),
                ),
                TestSpec(
                    id = 12,
                    expectedState = ScrimState.UNLOCKED,
                    Preconditions(
                        isDeviceEntered = true,
                    ),
                ),
                TestSpec(
                    id = 13,
                    expectedState = ScrimState.UNLOCKED,
                    Preconditions(
                        isOnKeyguard = true,
                        isBiometricWakeAndUnlock = true,
                    ),
                ),
                TestSpec(
                    id = 14,
                    expectedState = ScrimState.KEYGUARD,
                    Preconditions(),
                ),
                TestSpec(
                    id = 15,
                    expectedState = ScrimState.DREAMING,
                    Preconditions(
                        isOnKeyguard = true,
                        isOccluded = true,
                        isDreaming = true,
                    ),
                ),
                TestSpec(
                    id = 16,
                    expectedState = ScrimState.UNLOCKED,
                    Preconditions(
                        isOnKeyguard = true,
                        isOccluded = true,
                    ),
                ),
            )
        }

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            val seenIds = mutableSetOf<Int>()
            testSpecs().forEach { testSpec ->
                assertWithMessage("Duplicate TestSpec id=${testSpec.id}")
                    .that(seenIds)
                    .doesNotContain(testSpec.id)
                seenIds.add(testSpec.id)
            }
        }
    }

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest = kosmos.scrimStartable

    @JvmField @Parameter(0) var testSpec: TestSpec? = null

    @Before
    fun setUp() {
        kosmos.dozeServiceHost.initialize(
            /* centralSurfaces= */ kosmos.centralSurfaces,
            /* statusBarKeyguardViewManager= */ kosmos.statusBarKeyguardViewManager,
            /* notificationShadeWindowViewController= */ mock(),
            /* ambientIndicationContainer= */ mock(),
        )
        underTest.start()
    }

    @Test
    fun test() =
        testScope.runTest {
            val observedState by collectLastValue(underTest.scrimState)
            val preconditions = checkNotNull(testSpec).preconditions
            preconditions.assertValid()

            setUpWith(preconditions)

            runCurrent()

            assertThat(observedState).isEqualTo(checkNotNull(testSpec).expectedState)
        }

    /** Sets up the state to match what's specified in the given [preconditions]. */
    private fun TestScope.setUpWith(
        preconditions: Preconditions,
    ) {
        kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(
            preconditions.isAlternateBouncerVisible
        )

        if (preconditions.isDeviceEntered) {
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            whenIdle(on = Scenes.Gone)
        } else {
            whenIdle(on = Scenes.Lockscreen)
        }
        runCurrent()

        when {
            preconditions.isTransitioningToShade ->
                whenTransitioning(
                    from = Scenes.Lockscreen,
                    to = Scenes.Shade,
                )
            preconditions.isTransitioningAwayFromKeyguard ->
                whenTransitioning(
                    from = Scenes.Lockscreen,
                    to = Scenes.Gone,
                )
            preconditions.isCurrentSceneShade -> whenIdle(on = Scenes.Shade)
            preconditions.isCurrentSceneBouncer -> whenIdle(on = Scenes.Bouncer)
            preconditions.isIdleOnCommunal -> whenIdle(on = Scenes.Communal)
        }

        kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
            showWhenLockedActivityOnTop = preconditions.isOccluded,
            taskInfo = if (preconditions.isOccluded) mock() else null,
        )

        if (preconditions.isBiometricWakeAndUnlock) {
            kosmos.biometricUnlockInteractor.setBiometricUnlockState(
                BiometricUnlockController.MODE_WAKE_AND_UNLOCK,
                BiometricUnlockSource.FINGERPRINT_SENSOR,
            )
        }

        kosmos.brightnessMirrorShowingInteractor.setMirrorShowing(
            preconditions.isBrightnessMirrorVisible
        )

        if (preconditions.hasPendingScreenOffCallback) {
            kosmos.dozeServiceHost.prepareForGentleSleep {}
        } else {
            kosmos.dozeServiceHost.cancelGentleSleep()
        }

        kosmos.fakeKeyguardRepository.setIsDozing(preconditions.isDozing)
        if (preconditions.isPulsing) {
            kosmos.fakeKeyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(to = DozeStateModel.DOZE_PULSING)
            )
        }
        kosmos.fakeKeyguardRepository.setDreaming(preconditions.isDreaming)

        whenever(kosmos.statusBarKeyguardViewManager.primaryBouncerNeedsScrimming())
            .thenReturn(preconditions.isBouncerScrimmingNeeded)

        runCurrent()
    }

    /** Sets up an idle state on the given [on] scene. */
    private fun whenIdle(on: SceneKey) {
        kosmos.setSceneTransition(ObservableTransitionState.Idle(on))
        kosmos.sceneInteractor.changeScene(on, "")
    }

    /** Sets up a transitioning state between the [given] and [to] scenes. */
    private fun whenTransitioning(from: SceneKey, to: SceneKey, progress: Float = 0.5f) {
        val currentScene = if (progress > 0.5f) to else from
        kosmos.setSceneTransition(
            ObservableTransitionState.Transition(
                fromScene = from,
                toScene = to,
                progress = flowOf(progress),
                currentScene = flowOf(currentScene),
                isInitiatedByUserInput = true,
                isUserInputOngoing = flowOf(false),
            )
        )
        kosmos.sceneInteractor.changeScene(currentScene, "")
    }

    data class Preconditions(
        /** Whether bouncer or lockscreen scene is in the nav stack. */
        val isOnKeyguard: Boolean = false,
        val isAlternateBouncerVisible: Boolean = false,
        /** Whether any non-shade nor QS scene is transitioning to a shade or QS scene. */
        val isTransitioningToShade: Boolean = false,
        val isOccluded: Boolean = false,
        val isCurrentSceneBouncer: Boolean = false,
        val isBiometricWakeAndUnlock: Boolean = false,
        /** Whether there's an active transition from lockscreen or bouncer to gone. */
        val isTransitioningAwayFromKeyguard: Boolean = false,
        val isBrightnessMirrorVisible: Boolean = false,
        /** Whether the current scene is a shade or QS scene. */
        val isCurrentSceneShade: Boolean = false,
        val isDeviceEntered: Boolean = false,
        val isPulsing: Boolean = false,
        val hasPendingScreenOffCallback: Boolean = false,
        val isDozing: Boolean = false,
        val isIdleOnCommunal: Boolean = false,
        val isDreaming: Boolean = false,
        val isBouncerScrimmingNeeded: Boolean = false,
    ) {
        override fun toString(): String {
            // Only include values overridden to true:
            return buildString {
                append("(")
                append(
                    Preconditions::class
                        .memberProperties
                        .filter { it.get(this@Preconditions) == true }
                        .joinToString(", ") { "${it.name}=true" }
                )
                append(")")
            }
        }

        fun assertValid() {
            assertWithMessage("isOccluded cannot be true without isOnKeyguard also being true")
                .that(!isOccluded || isOnKeyguard)
                .isTrue()

            assertWithMessage(
                    "isCurrentSceneBouncer cannot be true without isOnKeyguard also being true"
                )
                .that(!isCurrentSceneBouncer || isOnKeyguard)
                .isTrue()

            assertWithMessage(
                    "isTransitioningAwayFromKeyguard cannot be true without isOnKeyguard being true"
                )
                .that(!isTransitioningAwayFromKeyguard || isOnKeyguard)
                .isTrue()

            assertWithMessage(
                    "isCurrentSceneBouncer cannot be true at the same time as isCurrentSceneShade"
                )
                .that(!isCurrentSceneBouncer || !isCurrentSceneShade)
                .isTrue()

            assertWithMessage(
                    "isCurrentSceneBouncer cannot be true at the same time as isIdleOnCommunal"
                )
                .that(!isCurrentSceneBouncer || !isIdleOnCommunal)
                .isTrue()

            assertWithMessage(
                    "isCurrentSceneShade cannot be true at the same time as isIdleOnCommunal"
                )
                .that(!isCurrentSceneShade || !isIdleOnCommunal)
                .isTrue()

            assertWithMessage("isDeviceEntered cannot be true at the same time as isOnKeyguard")
                .that(!isDeviceEntered || !isOnKeyguard)
                .isTrue()

            assertWithMessage(
                    "isDeviceEntered cannot be true at the same time as isCurrentSceneBouncer"
                )
                .that(!isDeviceEntered || !isCurrentSceneBouncer)
                .isTrue()

            assertWithMessage(
                    "isDeviceEntered cannot be true at the same time as isAlternateBouncerVisible"
                )
                .that(!isDeviceEntered || !isAlternateBouncerVisible)
                .isTrue()

            assertWithMessage("isPulsing cannot be true if both isDozing is false")
                .that(!isPulsing || isDozing)
                .isTrue()
        }
    }

    data class TestSpec(
        val id: Int,
        val expectedState: ScrimState?,
        val preconditions: Preconditions,
    ) {
        override fun toString(): String {
            return "id=$id, expected=$expectedState, preconditions=$preconditions"
        }
    }
}
