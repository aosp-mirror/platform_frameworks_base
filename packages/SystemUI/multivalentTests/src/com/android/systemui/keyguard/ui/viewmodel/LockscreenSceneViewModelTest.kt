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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import android.content.pm.UserInfo
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LockscreenSceneViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }

    private val underTest by lazy { createLockscreenSceneViewModel() }

    @Test
    fun upTransitionSceneKey_canSwipeToUnlock_gone() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            sceneInteractor.changeScene(SceneModel(SceneKey.Lockscreen), "reason")

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun upTransitionSceneKey_cannotSwipeToUnlock_bouncer() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryRepository.setUnlocked(false)
            sceneInteractor.changeScene(SceneModel(SceneKey.Lockscreen), "reason")

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Bouncer)
        }

    @EnableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun leftTransitionSceneKey_communalIsEnabled_communal() =
        testScope.runTest {
            with(kosmos.fakeUserRepository) {
                setUserInfos(listOf(PRIMARY_USER))
                setSelectedUserInfo(PRIMARY_USER)
            }
            kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)
            val leftDestinationSceneKey by collectLastValue(underTest.leftDestinationSceneKey)
            assertThat(leftDestinationSceneKey).isEqualTo(SceneKey.Communal)
        }

    @DisableFlags(FLAG_COMMUNAL_HUB)
    @Test
    fun leftTransitionSceneKey_communalIsDisabled_null() =
        testScope.runTest {
            with(kosmos.fakeUserRepository) {
                setUserInfos(listOf(PRIMARY_USER))
                setSelectedUserInfo(PRIMARY_USER)
            }
            kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, false)
            val leftDestinationSceneKey by collectLastValue(underTest.leftDestinationSceneKey)
            assertThat(leftDestinationSceneKey).isNull()
        }

    private fun createLockscreenSceneViewModel(): LockscreenSceneViewModel {
        return LockscreenSceneViewModel(
            applicationScope = testScope.backgroundScope,
            deviceEntryInteractor = kosmos.deviceEntryInteractor,
            communalSettingsInteractor = kosmos.communalSettingsInteractor,
            longPress =
                KeyguardLongPressViewModel(
                    interactor = mock(),
                ),
            notifications = kosmos.notificationsPlaceholderViewModel,
        )
    }

    private companion object {
        val PRIMARY_USER =
            UserInfo(/* id= */ 0, /* name= */ "primary user", /* flags= */ UserInfo.FLAG_MAIN)
    }
}
