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

import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.TestMocksModule
import com.android.systemui.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

@SmallTest
class ShadeAnimationInteractorSceneContainerImplTest : SysuiTestCase() {

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<ShadeAnimationInteractorSceneContainerImpl> {
        val sceneInteractor: SceneInteractor

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
        DaggerShadeAnimationInteractorSceneContainerImplTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule { set(Flags.FULL_SCREEN_USER_SWITCHER, true) },
                mocks =
                    TestMocksModule(
                        dozeParameters = dozeParameters,
                    ),
            )

    @Test
    fun isAnyCloseAnimationRunning_qsToShade() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isAnyCloseAnimationRunning)

            // WHEN transitioning from QS to Shade
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.QuickSettings,
                        toScene = SceneKey.Shade,
                        progress = MutableStateFlow(.1f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN qs is animating closed
            Truth.assertThat(actual).isFalse()
        }

    @Test
    fun isAnyCloseAnimationRunning_qsToGone_userInputNotOngoing() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isAnyCloseAnimationRunning)

            // WHEN transitioning from QS to Gone with no ongoing user input
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.QuickSettings,
                        toScene = SceneKey.Gone,
                        progress = MutableStateFlow(.1f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN qs is animating closed
            Truth.assertThat(actual).isTrue()
        }

    @Test
    fun isAnyCloseAnimationRunning_qsToGone_userInputOngoing() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isAnyCloseAnimationRunning)

            // WHEN transitioning from QS to Gone with user input ongoing
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.QuickSettings,
                        toScene = SceneKey.Gone,
                        progress = MutableStateFlow(.1f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(true),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN qs is not animating closed
            Truth.assertThat(actual).isFalse()
        }

    @Test
    fun updateIsLaunchingActivity() =
        testComponent.runTest {
            Truth.assertThat(underTest.isLaunchingActivity.value).isEqualTo(false)

            underTest.setIsLaunchingActivity(true)
            Truth.assertThat(underTest.isLaunchingActivity.value).isEqualTo(true)
        }
}
