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
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
abstract class UserAwareSettingsRepositoryTestBase : SysuiTestCase() {

    protected val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    protected val secureSettings = kosmos.fakeSettings
    protected val userRepository = kosmos.fakeUserRepository
    private lateinit var underTest: UserAwareSettingsRepository

    @Before
    fun setup() {
        underTest = getKosmosUserAwareSettingsRepository()

        userRepository.setUserInfos(USER_INFOS)

        secureSettings.putBoolForUser(BOOL_SETTING_NAME, true, USER_1.id)
        secureSettings.putBoolForUser(BOOL_SETTING_NAME, false, USER_2.id)
        secureSettings.putIntForUser(INT_SETTING_NAME, 1337, USER_1.id)
        secureSettings.putIntForUser(INT_SETTING_NAME, 818, USER_2.id)
    }

    abstract fun getKosmosUserAwareSettingsRepository(): UserAwareSettingsRepository

    @Test
    fun boolSetting_emitsInitialValue() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)

            val enabled by collectLastValue(underTest.boolSetting(BOOL_SETTING_NAME, false))

            assertThat(enabled).isTrue()
        }
    }

    @Test
    fun boolSetting_whenSettingChanges_emitsNewValue() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)
            val enabled by collectValues(underTest.boolSetting(BOOL_SETTING_NAME, false))
            runCurrent()

            secureSettings.putBoolForUser(BOOL_SETTING_NAME, false, USER_1.id)

            assertThat(enabled).containsExactly(true, false).inOrder()
        }
    }

    @Test
    fun boolSetting_whenWhenUserChanges_emitsNewValue() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)
            val enabled by collectLastValue(underTest.boolSetting(BOOL_SETTING_NAME, false))
            runCurrent()

            userRepository.setSelectedUserInfo(USER_2)

            assertThat(enabled).isFalse()
        }
    }

    @Test
    fun intSetting_emitsInitialValue() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)

            val number by collectLastValue(underTest.intSetting(INT_SETTING_NAME, 0))

            assertThat(number).isEqualTo(1337)
        }
    }

    @Test
    fun intSetting_whenSettingChanges_emitsNewValue() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)
            val number by collectValues(underTest.intSetting(INT_SETTING_NAME, 0))
            runCurrent()

            secureSettings.putIntForUser(INT_SETTING_NAME, 1338, USER_1.id)

            assertThat(number).containsExactly(1337, 1338).inOrder()
        }
    }

    @Test
    fun intSetting_whenWhenUserChanges_emitsNewValue() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)
            val number by collectLastValue(underTest.intSetting(INT_SETTING_NAME, 0))
            runCurrent()

            userRepository.setSelectedUserInfo(USER_2)

            assertThat(number).isEqualTo(818)
        }
    }

    @Test
    fun getInt_returnsInitialValue() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)

            assertThat(underTest.getInt(INT_SETTING_NAME, 0)).isEqualTo(1337)
        }

    @Test
    fun getInt_whenSettingChanges_returnsNewValue() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)
            secureSettings.putIntForUser(INT_SETTING_NAME, 999, USER_1.id)

            assertThat(underTest.getInt(INT_SETTING_NAME, 0)).isEqualTo(999)
        }

    @Test
    fun getInt_whenUserChanges_returnsThatUserValue() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_2)

            assertThat(underTest.getInt(INT_SETTING_NAME, 0)).isEqualTo(818)
        }

    private companion object {
        const val BOOL_SETTING_NAME = "BOOL_SETTING_NAME"
        const val INT_SETTING_NAME = "INT_SETTING_NAME"
        val USER_1 = UserInfo(/* id= */ 0, "user1", /* flags= */ 0)
        val USER_2 = UserInfo(/* id= */ 1, "user2", /* flags= */ 0)
        val USER_INFOS = listOf(USER_1, USER_2)
    }
}
