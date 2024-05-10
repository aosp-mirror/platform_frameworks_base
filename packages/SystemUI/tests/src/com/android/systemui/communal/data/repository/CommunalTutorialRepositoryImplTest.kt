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
package com.android.systemui.communal.data.repository

import android.content.pm.UserInfo
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalTutorialRepositoryImplTest : SysuiTestCase() {
    private lateinit var secureSettings: FakeSettings
    private lateinit var userRepository: FakeUserRepository
    private lateinit var userTracker: FakeUserTracker
    private lateinit var logBuffer: LogBuffer

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        logBuffer = FakeLogBuffer.Factory.create()
        secureSettings = FakeSettings()
        userRepository = FakeUserRepository()
        val listOfUserInfo = listOf(MAIN_USER_INFO)
        userRepository.setUserInfos(listOfUserInfo)

        userTracker = FakeUserTracker()
        userTracker.set(
            userInfos = listOfUserInfo,
            selectedUserIndex = 0,
        )
    }

    @Test
    fun tutorialSettingState_defaultToNotStarted() =
        testScope.runTest {
            val repository = initCommunalTutorialRepository()
            val tutorialSettingState = collectLastValue(repository.tutorialSettingState)()
            assertThat(tutorialSettingState)
                .isEqualTo(Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED)
        }

    @Test
    fun tutorialSettingState_whenTutorialSettingsUpdatedToStarted() =
        testScope.runTest {
            val repository = initCommunalTutorialRepository()
            setTutorialStateSetting(Settings.Secure.HUB_MODE_TUTORIAL_STARTED)
            val tutorialSettingState = collectLastValue(repository.tutorialSettingState)()
            assertThat(tutorialSettingState).isEqualTo(Settings.Secure.HUB_MODE_TUTORIAL_STARTED)
        }

    @Test
    fun tutorialSettingState_whenTutorialSettingsUpdatedToCompleted() =
        testScope.runTest {
            val repository = initCommunalTutorialRepository()
            setTutorialStateSetting(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
            val tutorialSettingState = collectLastValue(repository.tutorialSettingState)()
            assertThat(tutorialSettingState).isEqualTo(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
        }

    private fun initCommunalTutorialRepository(): CommunalTutorialRepositoryImpl {
        return CommunalTutorialRepositoryImpl(
            testScope.backgroundScope,
            testDispatcher,
            userRepository,
            secureSettings,
            userTracker,
            logBuffer
        )
    }

    private fun setTutorialStateSetting(
        @Settings.Secure.HubModeTutorialState state: Int,
        user: UserInfo = MAIN_USER_INFO
    ) {
        secureSettings.putIntForUser(Settings.Secure.HUB_MODE_TUTORIAL_STATE, state, user.id)
    }

    companion object {
        private val MAIN_USER_INFO =
            UserInfo(/* id= */ 0, /* name= */ "primary", /* flags= */ UserInfo.FLAG_MAIN)
    }
}
