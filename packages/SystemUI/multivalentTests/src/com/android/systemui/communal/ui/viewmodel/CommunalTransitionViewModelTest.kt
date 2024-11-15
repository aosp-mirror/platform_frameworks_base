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

package com.android.systemui.communal.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class CommunalTransitionViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val communalSceneRepository = kosmos.fakeCommunalSceneRepository
    private val sceneInteractor = kosmos.sceneInteractor

    private val underTest: CommunalTransitionViewModel by lazy {
        kosmos.communalTransitionViewModel
    }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun testIsUmoOnCommunalDuringTransitionBetweenLockscreenAndGlanceableHub() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            runCurrent()

            enterCommunal(from = KeyguardState.LOCKSCREEN)
            assertThat(isUmoOnCommunal).isTrue()

            exitCommunal(to = KeyguardState.LOCKSCREEN)
            assertThat(isUmoOnCommunal).isFalse()
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun testIsUmoOnCommunalDuringTransitionBetweenDreamingAndGlanceableHub() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            runCurrent()

            enterCommunal(from = KeyguardState.DREAMING)
            assertThat(isUmoOnCommunal).isTrue()

            exitCommunal(to = KeyguardState.DREAMING)
            assertThat(isUmoOnCommunal).isFalse()
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun testIsUmoOnCommunalDuringTransitionBetweenOccludedAndGlanceableHub() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            runCurrent()

            enterCommunal(from = KeyguardState.OCCLUDED)
            assertThat(isUmoOnCommunal).isTrue()

            exitCommunal(to = KeyguardState.OCCLUDED)
            assertThat(isUmoOnCommunal).isFalse()
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun isUmoOnCommunal_noLongerVisible_returnsFalse() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            runCurrent()

            enterCommunal(from = KeyguardState.LOCKSCREEN)
            assertThat(isUmoOnCommunal).isTrue()

            // Communal is no longer visible.
            communalSceneRepository.changeScene(CommunalScenes.Blank)

            // isUmoOnCommunal returns false, even without any keyguard transition.
            assertThat(isUmoOnCommunal).isFalse()
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun isUmoOnCommunal_idleOnCommunal_returnsTrue() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            assertThat(isUmoOnCommunal).isFalse()

            // Communal is fully visible.
            communalSceneRepository.changeScene(CommunalScenes.Communal)

            // isUmoOnCommunal returns true, even without any keyguard transition.
            assertThat(isUmoOnCommunal).isTrue()
        }

    @EnableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun isUmoOnCommunal_sceneContainerEnabled_idleOnCommunal_returnsTrue() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            assertThat(isUmoOnCommunal).isFalse()

            // Change to communal scene.
            setIdleScene(Scenes.Communal)

            // isUmoOnCommunal returns true, even without any keyguard transition.
            assertThat(isUmoOnCommunal).isTrue()
        }

    private suspend fun TestScope.enterCommunal(from: KeyguardState) {
        keyguardTransitionRepository.sendTransitionSteps(
            from = from,
            to = KeyguardState.GLANCEABLE_HUB,
            testScope,
        )
        communalSceneRepository.changeScene(CommunalScenes.Communal)
        runCurrent()
    }

    private suspend fun TestScope.exitCommunal(to: KeyguardState) {
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.GLANCEABLE_HUB,
            to = to,
            testScope,
        )
        communalSceneRepository.changeScene(CommunalScenes.Blank)
        runCurrent()
    }

    private fun setIdleScene(scene: SceneKey) {
        sceneInteractor.changeScene(scene, "test")
        val transitionState =
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(scene))
        sceneInteractor.setTransitionState(transitionState)
        testScope.runCurrent()
    }
}
