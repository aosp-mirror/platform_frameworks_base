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

package com.android.systemui.notifications.ui.viewmodel

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.domain.interactor.keyguardEnabledInteractor
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.resolver.homeSceneFamilyResolver
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ui.viewmodel.notificationsShadeUserActionsViewModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableSceneContainer
class NotificationsShadeUserActionsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val deviceUnlockedInteractor by lazy { kosmos.deviceUnlockedInteractor }

    private val underTest by lazy { kosmos.notificationsShadeUserActionsViewModel }

    @Test
    fun upTransitionSceneKey_deviceLocked_lockscreen() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            lockDevice()
            underTest.activateIn(this)

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(actions?.get(Swipe.Down)).isNull()
            assertThat(kosmos.homeSceneFamilyResolver.resolvedScene.value)
                .isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_deviceLocked_keyguardDisabled_gone() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            lockDevice()
            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            underTest.activateIn(this)

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(kosmos.homeSceneFamilyResolver.resolvedScene.value).isEqualTo(Scenes.Gone)
        }

    @Test
    fun upTransitionSceneKey_deviceUnlocked_gone() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            lockDevice()
            unlockDevice()
            underTest.activateIn(this)

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(actions?.get(Swipe.Down)).isNull()
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Gone)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            underTest.activateIn(this)

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(kosmos.homeSceneFamilyResolver.resolvedScene.value)
                .isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenDismissed_goesToGone() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)
            val actions by collectLastValue(underTest.actions)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            sceneInteractor // force the lazy; this will kick off StateFlows
            runCurrent()
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            underTest.activateIn(this)

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(kosmos.homeSceneFamilyResolver.resolvedScene.value).isEqualTo(Scenes.Gone)
        }

    private fun TestScope.lockDevice() {
        val deviceUnlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)

        kosmos.fakeAuthenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
        assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
        runCurrent()
    }

    private fun TestScope.unlockDevice() {
        val deviceUnlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)

        kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            SuccessFingerprintAuthenticationStatus(0, true)
        )
        assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
        sceneInteractor.changeScene(Scenes.Gone, "reason")
        runCurrent()
    }
}
