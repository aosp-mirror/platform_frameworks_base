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
import com.android.systemui.authentication.data.repository.AuthenticationRepositoryImpl
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.data.repo.BouncerRepository
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.data.repository.fakeSceneContainerRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
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
    private val sceneInteractor =
        SceneInteractor(
            repository = fakeSceneContainerRepository(),
        )
    private val mAuthenticationInteractor =
        AuthenticationInteractor(
            applicationScope = testScope.backgroundScope,
            repository = AuthenticationRepositoryImpl(),
        )
    private val underTest =
        LockScreenSceneInteractor(
            applicationScope = testScope.backgroundScope,
            authenticationInteractor = mAuthenticationInteractor,
            bouncerInteractorFactory =
                object : BouncerInteractor.Factory {
                    override fun create(containerName: String): BouncerInteractor {
                        return BouncerInteractor(
                            applicationScope = testScope.backgroundScope,
                            applicationContext = context,
                            repository = BouncerRepository(),
                            authenticationInteractor = mAuthenticationInteractor,
                            sceneInteractor = sceneInteractor,
                            containerName = containerName,
                        )
                    }
                },
            sceneInteractor = sceneInteractor,
            containerName = CONTAINER_NAME,
        )

    @Test
    fun isDeviceLocked() =
        testScope.runTest {
            val isDeviceLocked by collectLastValue(underTest.isDeviceLocked)

            mAuthenticationInteractor.lockDevice()
            assertThat(isDeviceLocked).isTrue()

            mAuthenticationInteractor.unlockDevice()
            assertThat(isDeviceLocked).isFalse()
        }

    @Test
    fun isSwipeToDismissEnabled_deviceLockedAndAuthMethodSwipe_true() =
        testScope.runTest {
            val isSwipeToDismissEnabled by collectLastValue(underTest.isSwipeToDismissEnabled)

            mAuthenticationInteractor.lockDevice()
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(isSwipeToDismissEnabled).isTrue()
        }

    @Test
    fun isSwipeToDismissEnabled_deviceUnlockedAndAuthMethodSwipe_false() =
        testScope.runTest {
            val isSwipeToDismissEnabled by collectLastValue(underTest.isSwipeToDismissEnabled)

            mAuthenticationInteractor.unlockDevice()
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(isSwipeToDismissEnabled).isFalse()
        }

    @Test
    fun dismissLockScreen_deviceLockedWithSecureAuthMethod_switchesToBouncer() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.lockDevice()
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            underTest.dismissLockScreen()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun dismissLockScreen_deviceUnlocked_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.unlockDevice()
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            underTest.dismissLockScreen()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun dismissLockScreen_deviceLockedWithInsecureAuthMethod_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.lockDevice()
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            underTest.dismissLockScreen()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun deviceLockedInNonLockScreenScene_switchesToLockScreenScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            runCurrent()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Gone))
            runCurrent()
            mAuthenticationInteractor.unlockDevice()
            runCurrent()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))

            mAuthenticationInteractor.lockDevice()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))
        }

    @Test
    fun deviceBiometricUnlockedInLockScreen_bypassEnabled_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.LockScreen))
            if (!mAuthenticationInteractor.isBypassEnabled.value) {
                mAuthenticationInteractor.toggleBypassEnabled()
            }
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            mAuthenticationInteractor.biometricUnlock()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun deviceBiometricUnlockedInLockScreen_bypassNotEnabled_doesNotSwitch() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.LockScreen))
            if (mAuthenticationInteractor.isBypassEnabled.value) {
                mAuthenticationInteractor.toggleBypassEnabled()
            }
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            mAuthenticationInteractor.biometricUnlock()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))
        }

    @Test
    fun switchFromLockScreenToGone_authMethodSwipe_unlocksDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(mAuthenticationInteractor.isUnlocked)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.LockScreen))
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            assertThat(isUnlocked).isFalse()

            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Gone))

            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun switchFromLockScreenToGone_authMethodNotSwipe_doesNotUnlockDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(mAuthenticationInteractor.isUnlocked)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.LockScreen))
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            assertThat(isUnlocked).isFalse()

            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Gone))

            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun switchFromNonLockScreenToGone_authMethodSwipe_doesNotUnlockDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(mAuthenticationInteractor.isUnlocked)
            runCurrent()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Shade))
            runCurrent()
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            runCurrent()
            assertThat(isUnlocked).isFalse()

            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Gone))

            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun authMethodChangedToNone_onLockScreenScene_dismissesLockScreen() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.LockScreen))
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun authMethodChangedToNone_notOnLockScreenScene_doesNotDismissLockScreen() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            runCurrent()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.QuickSettings))
            runCurrent()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.QuickSettings))

            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.QuickSettings))
        }

    companion object {
        private const val CONTAINER_NAME = "container1"
    }
}
