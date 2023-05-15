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

package com.android.systemui.keyguard.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.SceneTestUtils.Companion.CONTAINER_1
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class LockScreenSceneInteractorTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val utils = SceneTestUtils(this, testScope)
    private val sceneInteractor = utils.sceneInteractor()
    private val authenticationInteractor =
        utils.authenticationInteractor(
            repository = utils.authenticationRepository(),
        )
    private val underTest =
        utils.lockScreenSceneInteractor(
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
            bouncerInteractor =
                utils.bouncerInteractor(
                    authenticationInteractor = authenticationInteractor,
                    sceneInteractor = sceneInteractor,
                ),
        )

    @Test
    fun isDeviceLocked() =
        testScope.runTest {
            val isDeviceLocked by collectLastValue(underTest.isDeviceLocked)

            authenticationInteractor.lockDevice()
            assertThat(isDeviceLocked).isTrue()

            authenticationInteractor.unlockDevice()
            assertThat(isDeviceLocked).isFalse()
        }

    @Test
    fun isSwipeToDismissEnabled_deviceLockedAndAuthMethodSwipe_true() =
        testScope.runTest {
            val isSwipeToDismissEnabled by collectLastValue(underTest.isSwipeToDismissEnabled)

            authenticationInteractor.lockDevice()
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(isSwipeToDismissEnabled).isTrue()
        }

    @Test
    fun isSwipeToDismissEnabled_deviceUnlockedAndAuthMethodSwipe_false() =
        testScope.runTest {
            val isSwipeToDismissEnabled by collectLastValue(underTest.isSwipeToDismissEnabled)

            authenticationInteractor.unlockDevice()
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(isSwipeToDismissEnabled).isFalse()
        }

    @Test
    fun dismissLockScreen_deviceLockedWithSecureAuthMethod_switchesToBouncer() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_1))
            authenticationInteractor.lockDevice()
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            underTest.dismissLockScreen()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun dismissLockScreen_deviceUnlocked_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_1))
            authenticationInteractor.unlockDevice()
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            underTest.dismissLockScreen()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun dismissLockScreen_deviceLockedWithInsecureAuthMethod_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_1))
            authenticationInteractor.lockDevice()
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            underTest.dismissLockScreen()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun deviceLockedInNonLockScreenScene_switchesToLockScreenScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_1))
            runCurrent()
            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.Gone))
            runCurrent()
            authenticationInteractor.unlockDevice()
            runCurrent()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))

            authenticationInteractor.lockDevice()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))
        }

    @Test
    fun deviceBiometricUnlockedInLockScreen_bypassEnabled_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_1))
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.LockScreen))
            if (!authenticationInteractor.isBypassEnabled.value) {
                authenticationInteractor.toggleBypassEnabled()
            }
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            authenticationInteractor.biometricUnlock()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun deviceBiometricUnlockedInLockScreen_bypassNotEnabled_doesNotSwitch() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_1))
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.LockScreen))
            if (authenticationInteractor.isBypassEnabled.value) {
                authenticationInteractor.toggleBypassEnabled()
            }
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            authenticationInteractor.biometricUnlock()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))
        }

    @Test
    fun switchFromLockScreenToGone_authMethodSwipe_unlocksDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.LockScreen))
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            assertThat(isUnlocked).isFalse()

            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.Gone))

            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun switchFromLockScreenToGone_authMethodNotSwipe_doesNotUnlockDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.LockScreen))
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            assertThat(isUnlocked).isFalse()

            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.Gone))

            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun switchFromNonLockScreenToGone_authMethodSwipe_doesNotUnlockDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            runCurrent()
            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.Shade))
            runCurrent()
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            runCurrent()
            assertThat(isUnlocked).isFalse()

            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.Gone))

            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun authMethodChangedToNone_onLockScreenScene_dismissesLockScreen() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_1))
            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.LockScreen))
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun authMethodChangedToNone_notOnLockScreenScene_doesNotDismissLockScreen() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_1))
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            runCurrent()
            sceneInteractor.setCurrentScene(CONTAINER_1, SceneModel(SceneKey.QuickSettings))
            runCurrent()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.QuickSettings))

            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.QuickSettings))
        }
}
