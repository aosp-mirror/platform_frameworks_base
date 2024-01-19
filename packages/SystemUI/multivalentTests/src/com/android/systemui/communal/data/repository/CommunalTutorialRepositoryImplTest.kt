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
import com.android.systemui.communal.data.repository.CommunalTutorialRepositoryImpl.Companion.CURRENT_TUTORIAL_VERSION
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalTutorialRepositoryImplTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var secureSettings: FakeSettings
    private lateinit var userRepository: FakeUserRepository

    private lateinit var underTest: CommunalTutorialRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        secureSettings = FakeSettings()
        userRepository = FakeUserRepository()
        val listOfUserInfo = listOf(MAIN_USER_INFO)
        userRepository.setUserInfos(listOfUserInfo)

        underTest =
            CommunalTutorialRepositoryImpl(
                kosmos.applicationCoroutineScope,
                kosmos.testDispatcher,
                userRepository,
                secureSettings,
                logcatLogBuffer("CommunalTutorialRepositoryImplTest"),
            )
    }

    @Test
    fun tutorialSettingState_defaultToNotStarted() =
        testScope.runTest {
            val tutorialSettingState by collectLastValue(underTest.tutorialSettingState)
            assertThat(tutorialSettingState)
                .isEqualTo(Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED)
        }

    @Test
    fun tutorialSettingState_whenTutorialSettingsUpdatedToStarted() =
        testScope.runTest {
            underTest.setTutorialState(Settings.Secure.HUB_MODE_TUTORIAL_STARTED)
            val tutorialSettingState by collectLastValue(underTest.tutorialSettingState)
            assertThat(tutorialSettingState).isEqualTo(Settings.Secure.HUB_MODE_TUTORIAL_STARTED)
        }

    @Test
    fun tutorialSettingState_whenTutorialSettingsUpdatedToCompleted() =
        testScope.runTest {
            underTest.setTutorialState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
            val tutorialSettingState by collectLastValue(underTest.tutorialSettingState)
            assertThat(tutorialSettingState).isEqualTo(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
        }

    @Test
    fun tutorialVersion_userCompletedCurrentVersion_stateCompleted() =
        testScope.runTest {
            // User completed the current version.
            setTutorialStateSetting(CURRENT_TUTORIAL_VERSION)

            // Verify tutorial state is completed.
            val tutorialSettingState by collectLastValue(underTest.tutorialSettingState)
            assertThat(tutorialSettingState).isEqualTo(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
        }

    @Test
    fun tutorialVersion_userCompletedPreviousVersion_stateNotStarted() =
        testScope.runTest {
            // User completed the previous version.
            setTutorialStateSetting(CURRENT_TUTORIAL_VERSION - 1)

            // Verify tutorial state is not started.
            val tutorialSettingState by collectLastValue(underTest.tutorialSettingState)
            assertThat(tutorialSettingState)
                .isEqualTo(Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED)
        }

    @Test
    fun tutorialVersion_uponTutorialCompletion_writeCurrentVersion() =
        testScope.runTest {
            // Tutorial not started.
            setTutorialStateSetting(Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED)

            // Tutorial completed.
            underTest.setTutorialState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Verify tutorial setting state is updated to current version.
            val settingState = getTutorialStateSetting()
            assertThat(settingState).isEqualTo(CURRENT_TUTORIAL_VERSION)
        }

    private fun setTutorialStateSetting(
        @Settings.Secure.HubModeTutorialState state: Int,
        user: UserInfo = MAIN_USER_INFO
    ) {
        secureSettings.putIntForUser(Settings.Secure.HUB_MODE_TUTORIAL_STATE, state, user.id)
    }

    private fun getTutorialStateSetting(user: UserInfo = MAIN_USER_INFO): Int {
        return secureSettings.getIntForUser(Settings.Secure.HUB_MODE_TUTORIAL_STATE, user.id)
    }

    companion object {
        private val MAIN_USER_INFO =
            UserInfo(/* id= */ 0, /* name= */ "primary", /* flags= */ UserInfo.FLAG_MAIN)
    }
}
