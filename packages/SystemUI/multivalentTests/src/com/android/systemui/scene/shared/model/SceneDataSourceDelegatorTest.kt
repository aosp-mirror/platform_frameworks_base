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

package com.android.systemui.scene.shared.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.initialSceneKey
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SceneDataSourceDelegatorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val initialSceneKey = kosmos.initialSceneKey
    private val fakeSceneDataSource = kosmos.fakeSceneDataSource

    // TODO(b/356596436): Add tests for showing, hiding, and replacing overlays after we've defined
    //  them.
    private val underTest = kosmos.sceneDataSourceDelegator

    @Test
    fun currentScene_withoutDelegate_startsWithInitialScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            underTest.setDelegate(null)

            assertThat(currentScene).isEqualTo(initialSceneKey)
        }

    @Test
    fun currentScene_withoutDelegate_doesNothing() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            underTest.setDelegate(null)
            assertThat(currentScene).isNotEqualTo(Scenes.Bouncer)

            underTest.changeScene(toScene = Scenes.Bouncer)

            assertThat(currentScene).isEqualTo(initialSceneKey)
        }

    @Test
    fun currentScene_withDelegate_startsWithInitialScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(initialSceneKey)
        }

    @Test
    fun currentScene_withDelegate_changesScenes() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isNotEqualTo(Scenes.Bouncer)

            underTest.changeScene(toScene = Scenes.Bouncer)

            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
        }

    @Test
    fun currentScene_reflectsDelegate() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)

            fakeSceneDataSource.changeScene(toScene = Scenes.Bouncer)

            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
        }
}
