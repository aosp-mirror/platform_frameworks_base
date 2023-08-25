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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class LockscreenSceneViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val sceneInteractor = utils.sceneInteractor()
    private val authenticationInteractor =
        utils.authenticationInteractor(
            repository = utils.authenticationRepository(),
        )

    private val underTest =
        LockscreenSceneViewModel(
            authenticationInteractor = authenticationInteractor,
            longPress =
                KeyguardLongPressViewModel(
                    interactor = mock(),
                ),
        )

    @Test
    fun upTransitionSceneKey_canSwipeToUnlock_gone() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.authenticationRepository.setLockscreenEnabled(true)
            utils.authenticationRepository.setUnlocked(true)
            sceneInteractor.changeScene(SceneModel(SceneKey.Lockscreen), "reason")

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun upTransitionSceneKey_cannotSwipeToUnlock_bouncer() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.changeScene(SceneModel(SceneKey.Lockscreen), "reason")

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Bouncer)
        }
}
