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

package com.android.systemui.shade.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.deviceentry.shared.model.DeviceUnlockSource
import com.android.systemui.deviceentry.shared.model.DeviceUnlockStatus
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PanelExpansionInteractorImplTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val deviceUnlockedInteractor by lazy { kosmos.deviceUnlockedInteractor }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val shadeAnimationInteractor by lazy { kosmos.shadeAnimationInteractor }
    private val transitionState =
        MutableStateFlow<ObservableTransitionState>(
            ObservableTransitionState.Idle(Scenes.Lockscreen)
        )
    private val fakeSceneDataSource = kosmos.fakeSceneDataSource

    private lateinit var underTest: PanelExpansionInteractorImpl

    @Before
    fun setUp() {
        sceneInteractor.setTransitionState(transitionState)
    }

    @Test
    @EnableSceneContainer
    fun legacyPanelExpansion_whenIdle_whenLocked() =
        testScope.runTest {
            underTest = kosmos.panelExpansionInteractorImpl
            val panelExpansion by collectLastValue(underTest.legacyPanelExpansion)

            changeScene(Scenes.Lockscreen) { assertThat(panelExpansion).isEqualTo(1f) }
            assertThat(panelExpansion).isEqualTo(1f)

            changeScene(Scenes.Bouncer) { assertThat(panelExpansion).isEqualTo(1f) }
            assertThat(panelExpansion).isEqualTo(1f)

            changeScene(Scenes.Shade) { assertThat(panelExpansion).isEqualTo(1f) }
            assertThat(panelExpansion).isEqualTo(1f)

            changeScene(Scenes.QuickSettings) { assertThat(panelExpansion).isEqualTo(1f) }
            assertThat(panelExpansion).isEqualTo(1f)

            changeScene(Scenes.Communal) { assertThat(panelExpansion).isEqualTo(1f) }
            assertThat(panelExpansion).isEqualTo(1f)
        }

    @Test
    @EnableSceneContainer
    fun legacyPanelExpansion_whenIdle_whenUnlocked() =
        testScope.runTest {
            underTest = kosmos.panelExpansionInteractorImpl
            val unlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            assertThat(unlockStatus)
                .isEqualTo(DeviceUnlockStatus(true, DeviceUnlockSource.Fingerprint))

            val panelExpansion by collectLastValue(underTest.legacyPanelExpansion)

            changeScene(Scenes.Gone) { assertThat(panelExpansion).isEqualTo(0f) }
            assertThat(panelExpansion).isEqualTo(0f)

            changeScene(Scenes.Shade) { progress -> assertThat(panelExpansion).isEqualTo(progress) }
            assertThat(panelExpansion).isEqualTo(1f)

            changeScene(Scenes.QuickSettings) {
                // Shade's already expanded, so moving to QS should also be 1f.
                assertThat(panelExpansion).isEqualTo(1f)
            }
            assertThat(panelExpansion).isEqualTo(1f)

            changeScene(Scenes.Communal) { assertThat(panelExpansion).isEqualTo(1f) }
            assertThat(panelExpansion).isEqualTo(1f)
        }

    @Test
    @EnableSceneContainer
    fun shouldHideStatusBarIconsWhenExpanded_goneScene() =
        testScope.runTest {
            underTest = kosmos.panelExpansionInteractorImpl
            shadeAnimationInteractor.setIsLaunchingActivity(false)
            changeScene(Scenes.Gone)

            assertThat(underTest.shouldHideStatusBarIconsWhenExpanded()).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun shouldHideStatusBarIconsWhenExpanded_lockscreenScene() =
        testScope.runTest {
            underTest = kosmos.panelExpansionInteractorImpl
            shadeAnimationInteractor.setIsLaunchingActivity(false)
            changeScene(Scenes.Lockscreen)

            assertThat(underTest.shouldHideStatusBarIconsWhenExpanded()).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun shouldHideStatusBarIconsWhenExpanded_activityLaunch() =
        testScope.runTest {
            underTest = kosmos.panelExpansionInteractorImpl
            changeScene(Scenes.Gone)
            shadeAnimationInteractor.setIsLaunchingActivity(true)

            assertThat(underTest.shouldHideStatusBarIconsWhenExpanded()).isFalse()
        }

    private fun TestScope.changeScene(
        toScene: SceneKey,
        assertDuringProgress: ((progress: Float) -> Unit) = {},
    ) {
        val currentScene by collectLastValue(sceneInteractor.currentScene)
        val progressFlow = MutableStateFlow(0f)
        transitionState.value =
            ObservableTransitionState.Transition(
                fromScene = checkNotNull(currentScene),
                toScene = toScene,
                currentScene = flowOf(checkNotNull(currentScene)),
                progress = progressFlow,
                isInitiatedByUserInput = true,
                isUserInputOngoing = flowOf(true),
            )
        runCurrent()
        assertDuringProgress(progressFlow.value)

        progressFlow.value = 0.2f
        runCurrent()
        assertDuringProgress(progressFlow.value)

        progressFlow.value = 0.6f
        runCurrent()
        assertDuringProgress(progressFlow.value)

        progressFlow.value = 1f
        runCurrent()
        assertDuringProgress(progressFlow.value)

        transitionState.value = ObservableTransitionState.Idle(toScene)
        fakeSceneDataSource.changeScene(toScene)
        runCurrent()
        assertDuringProgress(progressFlow.value)

        assertThat(currentScene).isEqualTo(toScene)
    }
}
