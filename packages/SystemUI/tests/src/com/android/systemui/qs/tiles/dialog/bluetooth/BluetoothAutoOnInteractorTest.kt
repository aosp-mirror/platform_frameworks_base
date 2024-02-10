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
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth
import kotlin.test.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BluetoothAutoOnInteractorTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private var secureSettings: FakeSettings = FakeSettings()
    private val userRepository: FakeUserRepository = FakeUserRepository()
    private lateinit var bluetoothAutoOnInteractor: BluetoothAutoOnInteractor

    @Before
    fun setUp() {
        bluetoothAutoOnInteractor =
            BluetoothAutoOnInteractor(
                BluetoothAutoOnRepository(
                    secureSettings,
                    userRepository,
                    testScope.backgroundScope,
                    testDispatcher
                )
            )
    }

    @Test
    fun testSet_bluetoothAutoOnUnset_doNothing() {
        testScope.runTest {
            bluetoothAutoOnInteractor.setEnabled(true)

            val actualValue by collectLastValue(bluetoothAutoOnInteractor.isEnabled)

            runCurrent()

            Truth.assertThat(actualValue).isEqualTo(false)
        }
    }

    @Test
    fun testSet_bluetoothAutoOnSet_setNewValue() {
        testScope.runTest {
            userRepository.setUserInfos(listOf(SYSTEM_USER))
            secureSettings.putIntForUser(
                BluetoothAutoOnRepository.SETTING_NAME,
                BluetoothAutoOnInteractor.DISABLED,
                SYSTEM_USER_ID
            )
            bluetoothAutoOnInteractor.setEnabled(true)

            val actualValue by collectLastValue(bluetoothAutoOnInteractor.isEnabled)

            runCurrent()

            Truth.assertThat(actualValue).isEqualTo(true)
        }
    }

    companion object {
        private const val SYSTEM_USER_ID = 0
        private val SYSTEM_USER =
            UserInfo(/* id= */ SYSTEM_USER_ID, /* name= */ "system user", /* flags= */ 0)
    }
}
