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

package com.android.systemui.util.settings.repository

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class UserAwareSecureSettingsRepositoryTest : SysuiTestCase() {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val secureSettings = FakeSettings()
    private val userRepository = Kosmos().fakeUserRepository
    private lateinit var repository: UserAwareSecureSettingsRepository

    @Before
    fun setup() {
        repository =
            UserAwareSecureSettingsRepositoryImpl(
                secureSettings,
                userRepository,
                dispatcher,
            )
        userRepository.setUserInfos(USER_INFOS)
        setSettingValueForUser(enabled = true, userInfo = SETTING_ENABLED_USER)
        setSettingValueForUser(enabled = false, userInfo = SETTING_DISABLED_USER)
    }

    @Test
    fun settingEnabledEmitsValueForCurrentUser() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SETTING_ENABLED_USER)

            val enabled by collectLastValue(repository.boolSettingForActiveUser(SETTING_NAME))

            assertThat(enabled).isTrue()
        }
    }

    @Test
    fun settingEnabledEmitsNewValueWhenSettingChanges() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SETTING_ENABLED_USER)
            val enabled by collectValues(repository.boolSettingForActiveUser(SETTING_NAME))
            runCurrent()

            setSettingValueForUser(enabled = false, userInfo = SETTING_ENABLED_USER)

            assertThat(enabled).containsExactly(true, false).inOrder()
        }
    }

    @Test
    fun settingEnabledEmitsValueForNewUserWhenUserChanges() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SETTING_ENABLED_USER)
            val enabled by collectLastValue(repository.boolSettingForActiveUser(SETTING_NAME))
            runCurrent()

            userRepository.setSelectedUserInfo(SETTING_DISABLED_USER)

            assertThat(enabled).isFalse()
        }
    }

    private fun setSettingValueForUser(enabled: Boolean, userInfo: UserInfo) {
        secureSettings.putBoolForUser(SETTING_NAME, enabled, userInfo.id)
    }

    private companion object {
        const val SETTING_NAME = "SETTING_NAME"
        val SETTING_ENABLED_USER = UserInfo(/* id= */ 0, "user1", /* flags= */ 0)
        val SETTING_DISABLED_USER = UserInfo(/* id= */ 1, "user2", /* flags= */ 0)
        val USER_INFOS = listOf(SETTING_ENABLED_USER, SETTING_DISABLED_USER)
    }
}
