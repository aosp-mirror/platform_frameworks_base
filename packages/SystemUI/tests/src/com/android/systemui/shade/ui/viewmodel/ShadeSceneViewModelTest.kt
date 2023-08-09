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

package com.android.systemui.shade.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class ShadeSceneViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val sceneInteractor = utils.sceneInteractor()
    private val authenticationInteractor =
        utils.authenticationInteractor(
            repository = utils.authenticationRepository(),
            sceneInteractor = sceneInteractor,
        )

    private val underTest =
        ShadeSceneViewModel(
            applicationScope = testScope.backgroundScope,
            authenticationInteractor = authenticationInteractor,
            bouncerInteractor =
                utils.bouncerInteractor(
                    authenticationInteractor = authenticationInteractor,
                    sceneInteractor = sceneInteractor,
                ),
        )

    @Test
    fun upTransitionSceneKey_deviceLocked_lockScreen() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setUnlocked(false)

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_deviceUnlocked_gone() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setUnlocked(true)

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            utils.authenticationRepository.setLockscreenEnabled(true)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            sceneInteractor.changeScene(SceneModel(SceneKey.Lockscreen), "reason")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Lockscreen), "reason")

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenDismissed_goesToGone() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            utils.authenticationRepository.setLockscreenEnabled(true)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            sceneInteractor.changeScene(SceneModel(SceneKey.Gone), "reason")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Gone), "reason")

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun onContentClicked_deviceUnlocked_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setUnlocked(true)
            runCurrent()

            underTest.onContentClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onContentClicked_deviceLockedSecurely_switchesToBouncer() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setUnlocked(false)
            runCurrent()

            underTest.onContentClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }
}
