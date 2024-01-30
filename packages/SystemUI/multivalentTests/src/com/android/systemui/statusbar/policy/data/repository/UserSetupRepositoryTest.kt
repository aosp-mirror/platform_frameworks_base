/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.policy.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener
import com.android.systemui.statusbar.policy.deviceProvisionedController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class UserSetupRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val testScope = kosmos.testScope
    private val deviceProvisionedController : DeviceProvisionedController = mock()

    private val underTest = UserSetupRepositoryImpl(
            deviceProvisionedController,
            kosmos.testDispatcher,
            kosmos.applicationCoroutineScope,
    )

    @Test
    fun userSetup_defaultFalse() =
            testScope.runTest {
                val latest by collectLastValue(underTest.isUserSetUp)

                assertThat(latest).isFalse()
            }

    @Test
    fun userSetup_updatesOnChange() =
            testScope.runTest {
                val latest by collectLastValue(underTest.isUserSetUp)
                runCurrent()

                whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(true)
                val callback = getDeviceProvisionedListener()
                callback.onUserSetupChanged()

                assertThat(latest).isTrue()
            }

    private fun getDeviceProvisionedListener(): DeviceProvisionedListener {
        val captor = argumentCaptor<DeviceProvisionedListener>()
        verify(deviceProvisionedController).addCallback(captor.capture())
        return captor.value!!
    }
}
