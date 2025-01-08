/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.domain.interactor

import android.content.pm.UserInfo
import android.content.pm.UserInfo.FLAG_MAIN
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_V2
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalPrefsRepository
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HubOnboardingInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val sceneInteractor = kosmos.sceneInteractor

    private val underTest: HubOnboardingInteractor by lazy { kosmos.hubOnboardingInteractor }

    @Test
    @EnableFlags(FLAG_GLANCEABLE_HUB_V2)
    fun setHubOnboardingDismissed() =
        kosmos.runTest {
            setSelectedUser(MAIN_USER)
            val isHubOnboardingDismissed by
                collectLastValue(fakeCommunalPrefsRepository.isHubOnboardingDismissed(MAIN_USER))

            underTest.setHubOnboardingDismissed()

            assertThat(isHubOnboardingDismissed).isTrue()
        }

    @Test
    @EnableFlags(FLAG_GLANCEABLE_HUB_V2)
    fun shouldShowHubOnboarding_falseWhenDismissed() =
        kosmos.runTest {
            setSelectedUser(MAIN_USER)
            val shouldShowHubOnboarding by collectLastValue(underTest.shouldShowHubOnboarding)

            fakeCommunalPrefsRepository.setHubOnboardingDismissed(MAIN_USER)

            assertThat(shouldShowHubOnboarding).isFalse()
        }

    @Test
    @EnableFlags(FLAG_GLANCEABLE_HUB_V2)
    fun shouldShowHubOnboarding_falseWhenNotIdleOnCommunal() =
        kosmos.runTest {
            setSelectedUser(MAIN_USER)
            val shouldShowHubOnboarding by collectLastValue(underTest.shouldShowHubOnboarding)

            assertThat(shouldShowHubOnboarding).isFalse()
        }

    @Test
    @EnableFlags(FLAG_GLANCEABLE_HUB_V2)
    fun shouldShowHubOnboarding_trueWhenIdleOnCommunal() =
        kosmos.runTest {
            setSelectedUser(MAIN_USER)
            val shouldShowHubOnboarding by collectLastValue(underTest.shouldShowHubOnboarding)

            // Change to Communal scene.
            setIdleScene(Scenes.Communal)

            assertThat(shouldShowHubOnboarding).isFalse()
        }

    @Test
    @DisableFlags(FLAG_GLANCEABLE_HUB_V2)
    fun shouldShowHubOnboarding_falseWhenFlagDisabled() =
        kosmos.runTest {
            setSelectedUser(MAIN_USER)
            val shouldShowHubOnboarding by collectLastValue(underTest.shouldShowHubOnboarding)

            // Change to Communal scene.
            setIdleScene(Scenes.Communal)

            assertThat(shouldShowHubOnboarding).isFalse()
        }

    private fun setIdleScene(scene: SceneKey) {
        sceneInteractor.changeScene(scene, "test")
        val transitionState =
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(scene))
        sceneInteractor.setTransitionState(transitionState)
    }

    private suspend fun setSelectedUser(user: UserInfo) {
        with(kosmos.fakeUserRepository) {
            setUserInfos(listOf(user))
            setSelectedUserInfo(user)
        }
        kosmos.fakeUserTracker.set(userInfos = listOf(user), selectedUserIndex = 0)
    }

    companion object {
        val MAIN_USER = UserInfo(0, "main", FLAG_MAIN)
    }
}
