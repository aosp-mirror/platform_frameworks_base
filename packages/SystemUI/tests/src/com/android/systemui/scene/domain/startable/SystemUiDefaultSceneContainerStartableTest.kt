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

@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneContainerNames
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class SystemUiDefaultSceneContainerStartableTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val sceneInteractor = utils.sceneInteractor()
    private val featureFlags = FakeFeatureFlags()

    private val underTest =
        SystemUiDefaultSceneContainerStartable(
            applicationScope = testScope.backgroundScope,
            sceneInteractor = sceneInteractor,
            featureFlags = featureFlags,
        )

    @Test
    fun start_featureEnabled_keepsVisibilityUpdated() =
        testScope.runTest {
            featureFlags.set(Flags.SCENE_CONTAINER, true)
            val isVisible by
                collectLastValue(sceneInteractor.isVisible(SceneContainerNames.SYSTEM_UI_DEFAULT))
            assertThat(isVisible).isTrue()

            underTest.start()

            sceneInteractor.setCurrentScene(
                SceneContainerNames.SYSTEM_UI_DEFAULT,
                SceneModel(SceneKey.Gone)
            )
            assertThat(isVisible).isFalse()

            sceneInteractor.setCurrentScene(
                SceneContainerNames.SYSTEM_UI_DEFAULT,
                SceneModel(SceneKey.Shade)
            )
            assertThat(isVisible).isTrue()
        }

    @Test
    fun start_featureDisabled_doesNotUpdateVisibility() =
        testScope.runTest {
            featureFlags.set(Flags.SCENE_CONTAINER, false)
            val isVisible by
                collectLastValue(sceneInteractor.isVisible(SceneContainerNames.SYSTEM_UI_DEFAULT))
            assertThat(isVisible).isTrue()

            underTest.start()

            sceneInteractor.setCurrentScene(
                SceneContainerNames.SYSTEM_UI_DEFAULT,
                SceneModel(SceneKey.Gone)
            )
            assertThat(isVisible).isTrue()

            sceneInteractor.setCurrentScene(
                SceneContainerNames.SYSTEM_UI_DEFAULT,
                SceneModel(SceneKey.Shade)
            )
            assertThat(isVisible).isTrue()
        }
}
