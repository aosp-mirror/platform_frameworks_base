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

package com.android.systemui.shade.domain.interactor

import android.content.pm.UserInfo
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.SysUITestComponent
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.collectLastValue
import com.android.runCurrent
import com.android.runTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

@SmallTest
class ShadeInteractorSceneContainerImplTest : SysuiTestCase() {

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<ShadeInteractorSceneContainerImpl> {

        val configurationRepository: FakeConfigurationRepository
        val keyguardRepository: FakeKeyguardRepository
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val powerRepository: FakePowerRepository
        val sceneInteractor: SceneInteractor
        val shadeRepository: FakeShadeRepository
        val userRepository: FakeUserRepository

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }

    private val dozeParameters: DozeParameters = mock()

    private val testComponent: TestComponent =
        DaggerShadeInteractorSceneContainerImplTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule {
                        set(Flags.FACE_AUTH_REFACTOR, false)
                        set(Flags.FULL_SCREEN_USER_SWITCHER, true)
                    },
                mocks =
                    TestMocksModule(
                        dozeParameters = dozeParameters,
                    ),
            )

    @Before
    fun setUp() {
        runBlocking {
            val userInfos =
                listOf(
                    UserInfo(
                        /* id= */ 0,
                        /* name= */ "zero",
                        /* iconPath= */ "",
                        /* flags= */ UserInfo.FLAG_PRIMARY or
                            UserInfo.FLAG_ADMIN or
                            UserInfo.FLAG_FULL,
                        UserManager.USER_TYPE_FULL_SYSTEM,
                    ),
                )
            testComponent.apply {
                userRepository.setUserInfos(userInfos)
                userRepository.setSelectedUserInfo(userInfos[0])
            }
        }
    }

    @Ignore("b/309825977")
    @Test
    fun qsExpansionWhenInSplitShadeAndQsExpanded() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.qsExpansion)

            // WHEN split shade is enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onAnyConfigurationChange()
            val progress = MutableStateFlow(.3f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.QuickSettings,
                        toScene = SceneKey.Shade,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN legacy shade expansion is passed through
            Truth.assertThat(actual).isEqualTo(.3f)
        }

    @Ignore("b/309825977")
    @Test
    fun qsExpansionWhenNotInSplitShadeAndQsExpanded() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.qsExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, false)
            val progress = MutableStateFlow(.3f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.QuickSettings,
                        toScene = SceneKey.Shade,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN shade expansion is zero
            Truth.assertThat(actual).isEqualTo(.7f)
        }

    @Test
    fun qsFullscreen_falseWhenTransitioning() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isQsFullscreen)

            // WHEN scene transition active
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            val progress = MutableStateFlow(.3f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.QuickSettings,
                        toScene = SceneKey.Shade,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN QS is not fullscreen
            Truth.assertThat(actual).isFalse()
        }

    @Test
    fun qsFullscreen_falseWhenIdleNotQS() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isQsFullscreen)

            // WHEN Idle but not on QuickSettings scene
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(SceneKey.Shade)
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN QS is not fullscreen
            Truth.assertThat(actual).isFalse()
        }

    @Test
    fun qsFullscreen_trueWhenIdleQS() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isQsFullscreen)

            // WHEN Idle on QuickSettings scene
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(SceneKey.QuickSettings)
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN QS is fullscreen
            Truth.assertThat(actual).isTrue()
        }

    @Test
    fun lockscreenShadeExpansion_idle_onScene() =
        testComponent.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val key = SceneKey.Shade
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, key)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is idle on the scene
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 1
            Truth.assertThat(expansionAmount).isEqualTo(1f)
        }

    @Test
    fun lockscreenShadeExpansion_idle_onDifferentScene() =
        testComponent.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, SceneKey.Shade)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is idle on a different scene
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(SceneKey.Lockscreen)
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)
        }

    @Test
    fun lockscreenShadeExpansion_transitioning_toScene() =
        testComponent.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, key)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = key,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN expansion matches the progress
            Truth.assertThat(expansionAmount).isEqualTo(.4f)

            // WHEN transition completes
            progress.value = 1f

            // THEN expansion is 1
            Truth.assertThat(expansionAmount).isEqualTo(1f)
        }

    @Test
    fun lockscreenShadeExpansion_transitioning_fromScene() =
        testComponent.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, key)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = key,
                        toScene = SceneKey.Lockscreen,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 1
            Truth.assertThat(expansionAmount).isEqualTo(1f)

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN expansion reflects the progress
            Truth.assertThat(expansionAmount).isEqualTo(.6f)

            // WHEN transition completes
            progress.value = 1f

            // THEN expansion is 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)
        }

    @Test
    fun lockscreenShadeExpansion_transitioning_toAndFromDifferentScenes() =
        testComponent.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, SceneKey.QuickSettings)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is starting to between different scenes
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = SceneKey.Shade,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)

            // WHEN transition state is partially complete
            progress.value = .4f

            // THEN expansion is still 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)

            // WHEN transition completes
            progress.value = 1f

            // THEN expansion is still 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)
        }

    @Test
    fun userInteracting_idle() =
        testComponent.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.Shade
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is idle
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()
        }

    @Test
    fun userInteracting_transitioning_toScene_programmatic() =
        testComponent.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = key,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()
        }

    @Test
    fun userInteracting_transitioning_toScene_userInputDriven() =
        testComponent.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = key,
                        progress = progress,
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()
        }

    @Test
    fun userInteracting_transitioning_fromScene_programmatic() =
        testComponent.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = key,
                        toScene = SceneKey.Lockscreen,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()
        }

    @Test
    fun userInteracting_transitioning_fromScene_userInputDriven() =
        testComponent.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = key,
                        toScene = SceneKey.Lockscreen,
                        progress = progress,
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()
        }

    @Test
    fun userInteracting_transitioning_toAndFromDifferentScenes() =
        testComponent.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, SceneKey.Shade)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to between different scenes
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = SceneKey.QuickSettings,
                        progress = MutableStateFlow(0f),
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()
        }
}
