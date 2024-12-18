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

package com.android.systemui.shade.domain.startable

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.displayStateRepository
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionListener
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.stack.notificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.scrimController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.verify
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class ShadeStartableTest(flags: FlagsParameterization) : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val shadeInteractor by lazy { kosmos.shadeInteractor }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val shadeExpansionStateManager by lazy { kosmos.shadeExpansionStateManager }
    private val fakeConfigurationRepository by lazy { kosmos.fakeConfigurationRepository }
    private val fakeSceneDataSource by lazy { kosmos.fakeSceneDataSource }

    private val underTest: ShadeStartable = kosmos.shadeStartable

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return parameterizeSceneContainerFlag()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun hydrateShadeMode_dualShadeDisabled() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            val shadeMode by collectLastValue(shadeInteractor.shadeMode)

            underTest.start()
            assertThat(shadeMode).isEqualTo(ShadeMode.Single)

            overrideResource(R.bool.config_use_split_notification_shade, true)
            fakeConfigurationRepository.onAnyConfigurationChange()
            assertThat(shadeMode).isEqualTo(ShadeMode.Split)

            overrideResource(R.bool.config_use_split_notification_shade, false)
            fakeConfigurationRepository.onAnyConfigurationChange()
            assertThat(shadeMode).isEqualTo(ShadeMode.Single)
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun hydrateShadeMode_dualShadeEnabled() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            val shadeMode by collectLastValue(shadeInteractor.shadeMode)

            underTest.start()
            assertThat(shadeMode).isEqualTo(ShadeMode.Dual)

            overrideResource(R.bool.config_use_split_notification_shade, true)
            fakeConfigurationRepository.onAnyConfigurationChange()
            assertThat(shadeMode).isEqualTo(ShadeMode.Dual)

            overrideResource(R.bool.config_use_split_notification_shade, false)
            fakeConfigurationRepository.onAnyConfigurationChange()
            assertThat(shadeMode).isEqualTo(ShadeMode.Dual)
        }

    @Test
    @EnableSceneContainer
    fun hydrateShadeExpansionStateManager() =
        testScope.runTest {
            val expansionListener = mock<ShadeExpansionListener>()
            var latestChangeEvent: ShadeExpansionChangeEvent? = null
            whenever(expansionListener.onPanelExpansionChanged(any())).thenAnswer {
                latestChangeEvent = it.arguments[0] as ShadeExpansionChangeEvent
                Unit
            }
            shadeExpansionStateManager.addExpansionListener(expansionListener)

            underTest.start()

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            runCurrent()
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.Gone)
                )
            sceneInteractor.setTransitionState(transitionState)

            changeScene(Scenes.Gone, transitionState)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            assertThat(latestChangeEvent)
                .isEqualTo(
                    ShadeExpansionChangeEvent(
                        fraction = 0f,
                        expanded = false,
                        tracking = false,
                    )
                )

            changeScene(Scenes.Shade, transitionState) { progress ->
                assertThat(latestChangeEvent?.fraction).isEqualTo(progress)
            }
        }

    @Test
    @EnableSceneContainer
    fun hydrateFullWidth() =
        testScope.runTest {
            underTest.start()

            kosmos.displayStateRepository.setIsLargeScreen(true)
            runCurrent()
            verify(kosmos.notificationStackScrollLayoutController).setIsFullWidth(false)
            assertThat(kosmos.scrimController.clipQsScrim).isFalse()
        }

    private fun TestScope.changeScene(
        toScene: SceneKey,
        transitionState: MutableStateFlow<ObservableTransitionState>,
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
