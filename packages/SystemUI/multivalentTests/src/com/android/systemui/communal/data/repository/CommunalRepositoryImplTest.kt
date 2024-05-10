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

package com.android.systemui.communal.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalRepositoryImplTest : SysuiTestCase() {
    private lateinit var underTest: CommunalRepositoryImpl

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var featureFlagsClassic: FakeFeatureFlagsClassic
    private lateinit var sceneContainerRepository: SceneContainerRepository

    @Before
    fun setUp() {
        val sceneTestUtils = SceneTestUtils(this)
        sceneContainerRepository = sceneTestUtils.fakeSceneContainerRepository()
        featureFlagsClassic = FakeFeatureFlagsClassic()

        featureFlagsClassic.set(Flags.COMMUNAL_SERVICE_ENABLED, true)

        underTest = createRepositoryImpl(false)
    }

    private fun createRepositoryImpl(sceneContainerEnabled: Boolean): CommunalRepositoryImpl {
        return CommunalRepositoryImpl(
            testScope.backgroundScope,
            featureFlagsClassic,
            FakeSceneContainerFlags(enabled = sceneContainerEnabled),
            sceneContainerRepository,
        )
    }

    @Test
    fun isCommunalShowing_sceneContainerDisabled_onCommunalScene_true() =
        testScope.runTest {
            underTest.setDesiredScene(CommunalSceneKey.Communal)

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isTrue()
        }

    @Test
    fun isCommunalShowing_sceneContainerDisabled_onBlankScene_false() =
        testScope.runTest {
            underTest.setDesiredScene(CommunalSceneKey.Blank)

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isFalse()
        }

    @Test
    fun isCommunalShowing_sceneContainerEnabled_onCommunalScene_true() =
        testScope.runTest {
            underTest = createRepositoryImpl(true)

            sceneContainerRepository.setDesiredScene(SceneModel(key = SceneKey.Communal))

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isTrue()
        }

    @Test
    fun isCommunalShowing_sceneContainerEnabled_onLockscreenScene_false() =
        testScope.runTest {
            underTest = createRepositoryImpl(true)

            sceneContainerRepository.setDesiredScene(SceneModel(key = SceneKey.Lockscreen))

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isFalse()
        }

    @Test
    fun transitionState_idleByDefault() =
        testScope.runTest {
            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState)
                .isEqualTo(ObservableCommunalTransitionState.Idle(CommunalSceneKey.DEFAULT))
        }

    @Test
    fun transitionState_setTransitionState_returnsNewValue() =
        testScope.runTest {
            val expectedSceneKey = CommunalSceneKey.Communal
            underTest.setTransitionState(
                flowOf(ObservableCommunalTransitionState.Idle(expectedSceneKey))
            )

            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState)
                .isEqualTo(ObservableCommunalTransitionState.Idle(expectedSceneKey))
        }

    @Test
    fun transitionState_setNullTransitionState_returnsDefaultValue() =
        testScope.runTest {
            // Set a value for the transition state flow.
            underTest.setTransitionState(
                flowOf(ObservableCommunalTransitionState.Idle(CommunalSceneKey.Communal))
            )

            // Set the transition state flow back to null.
            underTest.setTransitionState(null)

            // Flow returns default scene key.
            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState)
                .isEqualTo(ObservableCommunalTransitionState.Idle(CommunalSceneKey.DEFAULT))
        }
}
