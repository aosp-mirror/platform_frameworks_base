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

package com.android.systemui.statusbar.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.android.systemui.util.kotlin.getValue
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBarTouchableRegionManagerTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneRepository = kosmos.sceneContainerRepository

    private val underTest by Lazy { kosmos.statusBarTouchableRegionManager }

    @Test
    @EnableSceneContainer
    fun entireScreenTouchable_sceneContainerEnabled_isRemoteUserInteractionOngoing() =
        testScope.runTest {
            sceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(currentScene = Scenes.Gone))
            )
            runCurrent()
            assertThat(underTest.shouldMakeEntireScreenTouchable()).isFalse()

            sceneRepository.isRemoteUserInteractionOngoing.value = true
            runCurrent()
            assertThat(underTest.shouldMakeEntireScreenTouchable()).isTrue()

            sceneRepository.isRemoteUserInteractionOngoing.value = false
            runCurrent()
            assertThat(underTest.shouldMakeEntireScreenTouchable()).isFalse()
        }

    @Test
    @DisableSceneContainer
    fun entireScreenTouchable_sceneContainerDisabled_isRemoteUserInteractionOngoing() =
        testScope.runTest {
            assertThat(underTest.shouldMakeEntireScreenTouchable()).isFalse()

            sceneRepository.isRemoteUserInteractionOngoing.value = true
            runCurrent()

            assertThat(underTest.shouldMakeEntireScreenTouchable()).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun entireScreenTouchable_sceneContainerEnabled_isIdleOnGone() =
        testScope.runTest {
            sceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(currentScene = Scenes.Gone))
            )
            runCurrent()
            assertThat(underTest.shouldMakeEntireScreenTouchable()).isFalse()

            sceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(currentScene = Scenes.Shade))
            )
            runCurrent()
            assertThat(underTest.shouldMakeEntireScreenTouchable()).isTrue()

            sceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(currentScene = Scenes.Gone))
            )
            runCurrent()
            assertThat(underTest.shouldMakeEntireScreenTouchable()).isFalse()
        }

    @Test
    @DisableSceneContainer
    fun entireScreenTouchable_sceneContainerDisabled_isIdleOnGone() =
        testScope.runTest {
            assertThat(underTest.shouldMakeEntireScreenTouchable()).isFalse()

            sceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(currentScene = Scenes.Shade))
            )
            runCurrent()

            assertThat(underTest.shouldMakeEntireScreenTouchable()).isFalse()
        }
}
