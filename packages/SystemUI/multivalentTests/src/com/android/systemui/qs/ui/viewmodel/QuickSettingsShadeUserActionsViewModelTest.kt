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

package com.android.systemui.qs.ui.viewmodel

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Back
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
import com.android.systemui.qs.panels.ui.viewmodel.editModeViewModel
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.resolver.homeSceneFamilyResolver
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
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
class QuickSettingsShadeUserActionsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor = kosmos.sceneInteractor
    private val deviceUnlockedInteractor = kosmos.deviceUnlockedInteractor

    private val underTest by lazy { kosmos.quickSettingsShadeUserActionsViewModel }

    @Test
    fun upTransitionSceneKey_deviceLocked_lockscreen() =
        testScope.runTest {
            underTest.activateIn(this)
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            lockDevice()

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(actions?.get(Swipe.Down)).isNull()
            assertThat(homeScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_deviceLocked_keyguardDisabled_gone() =
        testScope.runTest {
            underTest.activateIn(this)
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            lockDevice()
            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun upTransitionSceneKey_deviceUnlocked_gone() =
        testScope.runTest {
            underTest.activateIn(this)
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            lockDevice()
            unlockDevice()

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(actions?.get(Swipe.Down)).isNull()
            assertThat(homeScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        testScope.runTest {
            underTest.activateIn(this)
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenDismissed_goesToGone() =
        testScope.runTest {
            underTest.activateIn(this)
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            runCurrent()
            sceneInteractor.changeScene(Scenes.Gone, "reason")

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun backTransitionSceneKey_notEditing_Home() =
        testScope.runTest {
            underTest.activateIn(this)
            val actions by collectLastValue(underTest.actions)

            assertThat((actions?.get(Back) as? UserActionResult.ChangeScene)?.toScene)
                .isEqualTo(SceneFamilies.Home)
        }

    @Test
    fun backTransition_editing_noDestination() =
        testScope.runTest {
            underTest.activateIn(this)
            val actions by collectLastValue(underTest.actions)
            kosmos.editModeViewModel.startEditing()

            assertThat(actions!!).isNotEmpty()
            assertThat(actions?.get(Back)).isNull()
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
