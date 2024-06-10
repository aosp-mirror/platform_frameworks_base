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
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.homeSceneFamilyResolver
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shared.recents.utilities.Utilities
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class ShadeBackActionInteractorImplTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val sceneInteractor by lazy { kosmos.sceneInteractor }
    val shadeInteractor by lazy { kosmos.shadeInteractor }
    val fakeAuthenticationRepository by lazy { kosmos.fakeAuthenticationRepository }
    val deviceEntryFingerprintAuthRepository by lazy { kosmos.deviceEntryFingerprintAuthRepository }

    lateinit var underTest: ShadeBackActionInteractor

    @Before
    fun ignoreSplitShadeAndSetup() {
        Assume.assumeFalse(Utilities.isLargeScreen(kosmos.applicationContext))
        underTest = kosmos.shadeBackActionInteractor
    }

    @Test
    fun animateCollapseQs_notOnQs() =
        testScope.runTest {
            val actual by collectLastValue(sceneInteractor.currentScene)
            setScene(Scenes.Shade)
            underTest.animateCollapseQs(true)
            runCurrent()
            assertThat(actual).isEqualTo(Scenes.Shade)
        }

    @Test
    fun animateCollapseQs_fullyCollapse_entered() =
        testScope.runTest {
            // Ensure that HomeSceneFamilyResolver is running
            kosmos.homeSceneFamilyResolver.resolvedScene.launchIn(backgroundScope)
            val actual by collectLastValue(sceneInteractor.currentScene)
            enterDevice()
            setScene(Scenes.QuickSettings)
            underTest.animateCollapseQs(true)
            runCurrent()
            assertThat(actual).isEqualTo(Scenes.Gone)
        }

    @Test
    fun animateCollapseQs_fullyCollapse_locked() =
        testScope.runTest {
            val actual by collectLastValue(sceneInteractor.currentScene)
            setScene(Scenes.QuickSettings)
            underTest.animateCollapseQs(true)
            runCurrent()
            assertThat(actual).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun animateCollapseQs_notFullyCollapse() =
        testScope.runTest {
            val actual by collectLastValue(sceneInteractor.currentScene)
            setScene(Scenes.QuickSettings)
            underTest.animateCollapseQs(false)
            runCurrent()
            assertThat(actual).isEqualTo(Scenes.Shade)
        }

    private fun TestScope.enterDevice() {
        // configure device unlocked state
        fakeAuthenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
        runCurrent()
        deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            SuccessFingerprintAuthenticationStatus(0, true)
        )
        runCurrent()
        setScene(Scenes.Gone)
    }

    private fun TestScope.setScene(key: SceneKey) {
        val actual by collectLastValue(sceneInteractor.currentScene)
        sceneInteractor.changeScene(key, "test")
        sceneInteractor.setTransitionState(
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
        )
        runCurrent()
        assertThat(actual).isEqualTo(key)
    }
}
