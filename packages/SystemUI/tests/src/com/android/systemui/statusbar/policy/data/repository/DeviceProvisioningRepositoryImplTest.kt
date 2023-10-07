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
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.policy.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceProvisioningRepositoryImplTest : SysuiTestCase() {

    @Mock lateinit var deviceProvisionedController: DeviceProvisionedController

    lateinit var underTest: DeviceProvisioningRepositoryImpl

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            DeviceProvisioningRepositoryImpl(
                deviceProvisionedController,
            )
    }

    @Test
    fun isDeviceProvisioned_reflectsCurrentControllerState() = runTest {
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
        val deviceProvisioned by collectLastValue(underTest.isDeviceProvisioned)
        assertThat(deviceProvisioned).isTrue()
    }

    @Test
    fun isDeviceProvisioned_updatesWhenControllerStateChanges_toTrue() = runTest {
        val deviceProvisioned by collectLastValue(underTest.isDeviceProvisioned)
        runCurrent()
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
        withArgCaptor { verify(deviceProvisionedController).addCallback(capture()) }
            .onDeviceProvisionedChanged()
        assertThat(deviceProvisioned).isTrue()
    }

    @Test
    fun isDeviceProvisioned_updatesWhenControllerStateChanges_toFalse() = runTest {
        val deviceProvisioned by collectLastValue(underTest.isDeviceProvisioned)
        runCurrent()
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(false)
        withArgCaptor { verify(deviceProvisionedController).addCallback(capture()) }
            .onDeviceProvisionedChanged()
        assertThat(deviceProvisioned).isFalse()
    }

    @Test
    fun isFrpActive_reflectsCurrentControllerState() = runTest {
        whenever(deviceProvisionedController.isFrpActive).thenReturn(true)
        val frpActive by collectLastValue(underTest.isFactoryResetProtectionActive)
        assertThat(frpActive).isTrue()
    }

    @Test
    fun isFrpActive_updatesWhenControllerStateChanges_toTrue() = runTest {
        val frpActive by collectLastValue(underTest.isFactoryResetProtectionActive)
        runCurrent()
        whenever(deviceProvisionedController.isFrpActive).thenReturn(true)
        withArgCaptor { verify(deviceProvisionedController).addCallback(capture()) }
            .onFrpActiveChanged()
        assertThat(frpActive).isTrue()
    }

    @Test
    fun isFrpActive_updatesWhenControllerStateChanges_toFalse() = runTest {
        val frpActive by collectLastValue(underTest.isFactoryResetProtectionActive)
        runCurrent()
        whenever(deviceProvisionedController.isFrpActive).thenReturn(false)
        withArgCaptor { verify(deviceProvisionedController).addCallback(capture()) }
            .onFrpActiveChanged()
        assertThat(frpActive).isFalse()
    }
}
