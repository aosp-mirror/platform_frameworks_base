/*
 * Copyright 2023 The Android Open Source Project
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

import android.view.Display
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.model.SysUiState
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.SceneTestUtils.Companion.toDataLayer
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(JUnit4::class)
class SceneContainerStartableTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val sceneInteractor = utils.sceneInteractor()
    private val featureFlags = utils.featureFlags
    private val authenticationRepository = utils.authenticationRepository()
    private val authenticationInteractor =
        utils.authenticationInteractor(
            repository = authenticationRepository,
        )
    private val keyguardRepository = utils.keyguardRepository()
    private val keyguardInteractor =
        utils.keyguardInteractor(
            repository = keyguardRepository,
        )
    private val sysUiState: SysUiState = mock()

    private val underTest =
        SceneContainerStartable(
            applicationScope = testScope.backgroundScope,
            sceneInteractor = sceneInteractor,
            authenticationInteractor = authenticationInteractor,
            keyguardInteractor = keyguardInteractor,
            featureFlags = featureFlags,
            sysUiState = sysUiState,
            displayId = Display.DEFAULT_DISPLAY,
            sceneLogger = mock(),
        )

    @Test
    fun hydrateVisibility() =
        testScope.runTest {
            val currentDesiredSceneKey by
                collectLastValue(sceneInteractor.desiredScene.map { it.key })
            val isVisible by collectLastValue(sceneInteractor.isVisible)
            val transitionStateFlow =
                prepareState(
                    isDeviceUnlocked = true,
                    initialSceneKey = SceneKey.Gone,
                )
            assertThat(currentDesiredSceneKey).isEqualTo(SceneKey.Gone)
            assertThat(isVisible).isTrue()

            underTest.start()
            assertThat(isVisible).isFalse()

            sceneInteractor.changeScene(SceneModel(SceneKey.Shade), "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = SceneKey.Gone,
                    toScene = SceneKey.Shade,
                    progress = flowOf(0.5f),
                )
            assertThat(isVisible).isTrue()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Shade), "reason")
            transitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Shade)
            assertThat(isVisible).isTrue()

            sceneInteractor.changeScene(SceneModel(SceneKey.Gone), "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = SceneKey.Shade,
                    toScene = SceneKey.Gone,
                    progress = flowOf(0.5f),
                )
            assertThat(isVisible).isTrue()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Gone), "reason")
            transitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Gone)
            assertThat(isVisible).isFalse()
        }

    @Test
    fun switchToLockscreenWhenDeviceLocks() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isDeviceUnlocked = true,
                initialSceneKey = SceneKey.Gone,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
            underTest.start()

            authenticationRepository.setUnlocked(false)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun switchFromBouncerToGoneWhenDeviceUnlocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = SceneKey.Bouncer,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Bouncer)
            underTest.start()

            authenticationRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun switchFromLockscreenToGoneWhenDeviceUnlocksWithBypassOn() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isBypassEnabled = true,
                initialSceneKey = SceneKey.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            authenticationRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun stayOnLockscreenWhenDeviceUnlocksWithBypassOff() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isBypassEnabled = false,
                initialSceneKey = SceneKey.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            authenticationRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun switchToLockscreenWhenDeviceSleepsLocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = SceneKey.Shade,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Shade)
            underTest.start()

            keyguardRepository.setWakefulnessModel(STARTING_TO_SLEEP)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun hydrateSystemUiState() =
        testScope.runTest {
            val transitionStateFlow = prepareState()
            underTest.start()
            runCurrent()
            clearInvocations(sysUiState)

            listOf(
                    SceneKey.Gone,
                    SceneKey.Lockscreen,
                    SceneKey.Bouncer,
                    SceneKey.Shade,
                    SceneKey.QuickSettings,
                )
                .forEachIndexed { index, sceneKey ->
                    sceneInteractor.changeScene(SceneModel(sceneKey), "reason")
                    runCurrent()
                    verify(sysUiState, times(index)).commitUpdate(Display.DEFAULT_DISPLAY)

                    sceneInteractor.onSceneChanged(SceneModel(sceneKey), "reason")
                    runCurrent()
                    verify(sysUiState, times(index)).commitUpdate(Display.DEFAULT_DISPLAY)

                    transitionStateFlow.value = ObservableTransitionState.Idle(sceneKey)
                    runCurrent()
                    verify(sysUiState, times(index + 1)).commitUpdate(Display.DEFAULT_DISPLAY)
                }
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodNone() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            keyguardRepository.setWakefulnessModel(STARTING_TO_WAKE)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun stayOnLockscreenWhenDeviceStartsToWakeUp_authMethodSwipe() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Swipe,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            keyguardRepository.setWakefulnessModel(STARTING_TO_WAKE)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun doesNotSwitchToGoneWhenDeviceStartsToWakeUp_authMethodSecure() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            keyguardRepository.setWakefulnessModel(STARTING_TO_WAKE)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodSecure_deviceUnlocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            authenticationRepository.setUnlocked(true)
            runCurrent()
            keyguardRepository.setWakefulnessModel(STARTING_TO_WAKE)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    private fun prepareState(
        isDeviceUnlocked: Boolean = false,
        isBypassEnabled: Boolean = false,
        initialSceneKey: SceneKey? = null,
        authenticationMethod: AuthenticationMethodModel? = null,
    ): MutableStateFlow<ObservableTransitionState> {
        featureFlags.set(Flags.SCENE_CONTAINER, true)
        authenticationRepository.setUnlocked(isDeviceUnlocked)
        keyguardRepository.setBypassEnabled(isBypassEnabled)
        val transitionStateFlow =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Idle(SceneKey.Lockscreen)
            )
        sceneInteractor.setTransitionState(transitionStateFlow)
        initialSceneKey?.let {
            transitionStateFlow.value = ObservableTransitionState.Idle(it)
            sceneInteractor.changeScene(SceneModel(it), "reason")
            sceneInteractor.onSceneChanged(SceneModel(it), "reason")
        }
        authenticationMethod?.let {
            authenticationRepository.setAuthenticationMethod(authenticationMethod.toDataLayer())
            authenticationRepository.setLockscreenEnabled(
                authenticationMethod != AuthenticationMethodModel.None
            )
        }
        return transitionStateFlow
    }

    companion object {
        private val STARTING_TO_SLEEP =
            WakefulnessModel(
                state = WakefulnessState.STARTING_TO_SLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.POWER_BUTTON
            )
        private val STARTING_TO_WAKE =
            WakefulnessModel(
                state = WakefulnessState.STARTING_TO_WAKE,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.POWER_BUTTON
            )
    }
}
