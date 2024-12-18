/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.inputdevice.data.repository

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.inputdevice.data.model.UserDeviceConnectionStatus
import com.android.systemui.keyboard.data.repository.keyboardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.touchpad.data.repository.touchpadRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.user.data.repository.userRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UserInputDeviceRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: UserInputDeviceRepository
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val keyboardRepository = kosmos.keyboardRepository
    private val touchpadRepository = kosmos.touchpadRepository
    private val userRepository = kosmos.fakeUserRepository

    @Before
    fun setup() {
        underTest =
            UserInputDeviceRepository(
                kosmos.testDispatcher,
                keyboardRepository,
                touchpadRepository,
                kosmos.userRepository
            )
        userRepository.setUserInfos(USER_INFOS)
    }

    @Test
    fun emitsNewKeyboardConnectedValueOnUserChanged() =
        testScope.runTest {
            val isAnyKeyboardConnected by collectValues(underTest.isAnyKeyboardConnectedForUser)
            userRepository.setSelectedUserInfo(USER_INFOS[0])
            keyboardRepository.setIsAnyKeyboardConnected(true)
            runCurrent()

            userRepository.setSelectedUserInfo(USER_INFOS[1])

            assertThat(isAnyKeyboardConnected)
                .containsExactly(
                    UserDeviceConnectionStatus(isConnected = true, USER_INFOS[0].id),
                    UserDeviceConnectionStatus(isConnected = true, USER_INFOS[1].id)
                )
                .inOrder()
        }

    @Test
    fun emitsNewTouchpadConnectedValueOnUserChanged() =
        testScope.runTest {
            val isAnyTouchpadConnected by collectValues(underTest.isAnyTouchpadConnectedForUser)
            userRepository.setSelectedUserInfo(USER_INFOS[0])
            touchpadRepository.setIsAnyTouchpadConnected(true)
            runCurrent()

            userRepository.setSelectedUserInfo(USER_INFOS[1])

            assertThat(isAnyTouchpadConnected)
                .containsExactly(
                    UserDeviceConnectionStatus(isConnected = true, USER_INFOS[0].id),
                    UserDeviceConnectionStatus(isConnected = true, USER_INFOS[1].id)
                )
                .inOrder()
        }

    companion object {
        private val USER_INFOS =
            listOf(
                UserInfo(100, "First User", 0),
                UserInfo(101, "Second User", 0),
            )
    }
}
