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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalRepositoryImplTest : SysuiTestCase() {
    private lateinit var underTest: CommunalRepositoryImpl

    private lateinit var testScope: TestScope

    private lateinit var featureFlagsClassic: FakeFeatureFlagsClassic
    private lateinit var sceneContainerFlags: FakeSceneContainerFlags
    private lateinit var sceneContainerRepository: SceneContainerRepository

    @Before
    fun setUp() {
        testScope = TestScope()

        val sceneTestUtils = SceneTestUtils(this)
        sceneContainerFlags = FakeSceneContainerFlags(enabled = false)
        sceneContainerRepository = sceneTestUtils.fakeSceneContainerRepository()
        featureFlagsClassic = FakeFeatureFlagsClassic()

        featureFlagsClassic.set(Flags.COMMUNAL_SERVICE_ENABLED, true)

        underTest =
            CommunalRepositoryImpl(
                featureFlagsClassic,
                sceneContainerFlags,
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
            sceneContainerFlags = FakeSceneContainerFlags(enabled = true)
            underTest =
                CommunalRepositoryImpl(
                    featureFlagsClassic,
                    sceneContainerFlags,
                    sceneContainerRepository,
                )

            sceneContainerRepository.setDesiredScene(SceneModel(key = SceneKey.Communal))

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isTrue()
        }

    @Test
    fun isCommunalShowing_sceneContainerEnabled_onLockscreenScene_false() =
        testScope.runTest {
            sceneContainerFlags = FakeSceneContainerFlags(enabled = true)
            underTest =
                CommunalRepositoryImpl(
                    featureFlagsClassic,
                    sceneContainerFlags,
                    sceneContainerRepository,
                )

            sceneContainerRepository.setDesiredScene(SceneModel(key = SceneKey.Lockscreen))

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isFalse()
        }
}
