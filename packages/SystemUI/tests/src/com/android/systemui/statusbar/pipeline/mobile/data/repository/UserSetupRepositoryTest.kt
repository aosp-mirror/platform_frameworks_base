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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class UserSetupRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: UserSetupRepository
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    private val scope = CoroutineScope(IMMEDIATE)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            UserSetupRepositoryImpl(
                deviceProvisionedController,
                IMMEDIATE,
                scope,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun testUserSetup_defaultFalse() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null

            val job = underTest.isUserSetupFlow.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun testUserSetup_updatesOnChange() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null

            val job = underTest.isUserSetupFlow.onEach { latest = it }.launchIn(this)

            whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(true)
            val callback = getDeviceProvisionedListener()
            callback.onUserSetupChanged()

            assertThat(latest).isTrue()

            job.cancel()
        }

    private fun getDeviceProvisionedListener(): DeviceProvisionedListener {
        val captor = argumentCaptor<DeviceProvisionedListener>()
        verify(deviceProvisionedController).addCallback(captor.capture())
        return captor.value!!
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
