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

package com.android.systemui.qs.tiles.dialog.bluetooth

import android.content.pm.UserInfo
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothAutoOnInteractor.Companion.DISABLED
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothAutoOnInteractor.Companion.ENABLED
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothAutoOnRepository.Companion.SETTING_NAME
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothAutoOnRepository.Companion.UNSET
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BluetoothAutoOnRepositoryTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private var secureSettings: FakeSettings = FakeSettings()
    private val userRepository: FakeUserRepository = FakeUserRepository()

    private lateinit var bluetoothAutoOnRepository: BluetoothAutoOnRepository

    @Before
    fun setUp() {
        bluetoothAutoOnRepository =
            BluetoothAutoOnRepository(
                secureSettings,
                userRepository,
                testScope.backgroundScope,
                testDispatcher
            )

        userRepository.setUserInfos(listOf(SECONDARY_USER, SYSTEM_USER))
    }

    @Test
    fun testGetValue_valueUnset() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SYSTEM_USER)
            val actualValue by collectLastValue(bluetoothAutoOnRepository.getValue)

            runCurrent()

            assertThat(actualValue).isEqualTo(UNSET)
            assertThat(bluetoothAutoOnRepository.isValuePresent()).isFalse()
        }
    }

    @Test
    fun testGetValue_valueFalse() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SYSTEM_USER)
            val actualValue by collectLastValue(bluetoothAutoOnRepository.getValue)

            secureSettings.putIntForUser(SETTING_NAME, DISABLED, UserHandle.USER_SYSTEM)
            runCurrent()

            assertThat(actualValue).isEqualTo(DISABLED)
        }
    }

    @Test
    fun testGetValue_valueTrue() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SYSTEM_USER)
            val actualValue by collectLastValue(bluetoothAutoOnRepository.getValue)

            secureSettings.putIntForUser(SETTING_NAME, ENABLED, UserHandle.USER_SYSTEM)
            runCurrent()

            assertThat(actualValue).isEqualTo(ENABLED)
        }
    }

    @Test
    fun testGetValue_valueTrue_secondaryUser_returnUnset() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SECONDARY_USER)
            val actualValue by collectLastValue(bluetoothAutoOnRepository.getValue)

            secureSettings.putIntForUser(SETTING_NAME, ENABLED, SECONDARY_USER_ID)
            runCurrent()

            assertThat(actualValue).isEqualTo(UNSET)
        }
    }

    companion object {
        private const val SYSTEM_USER_ID = 0
        private const val SECONDARY_USER_ID = 1
        private val SYSTEM_USER =
            UserInfo(/* id= */ SYSTEM_USER_ID, /* name= */ "system user", /* flags= */ 0)
        private val SECONDARY_USER =
            UserInfo(/* id= */ SECONDARY_USER_ID, /* name= */ "secondary user", /* flags= */ 0)
    }
}
