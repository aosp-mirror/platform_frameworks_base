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

package com.android.systemui.shade.domain.interactor

import android.content.applicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shared.recents.utilities.Utilities
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeBackActionInteractorImplTest : SysuiTestCase() {
    val kosmos = testKosmos().apply { fakeSceneContainerFlags.enabled = true }
    val testScope = kosmos.testScope
    val sceneInteractor = kosmos.sceneInteractor
    val deviceEntryRepository = kosmos.fakeDeviceEntryRepository
    val underTest = kosmos.shadeBackActionInteractor

    @Before
    fun ignoreSplitShade() {
        Assume.assumeFalse(Utilities.isLargeScreen(kosmos.applicationContext))
    }

    @Test
    fun animateCollapseQs_notOnQs() =
        testScope.runTest {
            setScene(Scenes.Shade)
            underTest.animateCollapseQs(true)
            runCurrent()
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Shade)
        }

    @Test
    fun animateCollapseQs_fullyCollapse_entered() =
        testScope.runTest {
            enterDevice()
            setScene(Scenes.QuickSettings)
            underTest.animateCollapseQs(true)
            runCurrent()
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Gone)
        }

    @Test
    fun animateCollapseQs_fullyCollapse_locked() =
        testScope.runTest {
            deviceEntryRepository.setUnlocked(false)
            setScene(Scenes.QuickSettings)
            underTest.animateCollapseQs(true)
            runCurrent()
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun animateCollapseQs_notFullyCollapse() =
        testScope.runTest {
            setScene(Scenes.QuickSettings)
            underTest.animateCollapseQs(false)
            runCurrent()
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Shade)
        }

    private fun enterDevice() {
        deviceEntryRepository.setUnlocked(true)
        testScope.runCurrent()
        setScene(Scenes.Gone)
    }

    private fun setScene(key: SceneKey) {
        sceneInteractor.changeScene(key, "test")
        sceneInteractor.setTransitionState(
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
        )
        testScope.runCurrent()
    }
}
