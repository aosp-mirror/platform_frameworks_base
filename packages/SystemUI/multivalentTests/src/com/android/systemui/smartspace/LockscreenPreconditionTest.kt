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

package com.android.systemui.smartspace

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.smartspace.preconditions.LockscreenPrecondition
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.util.concurrency.Execution
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@android.platform.test.annotations.EnabledOnRavenwood
class LockscreenPreconditionTest : SysuiTestCase() {
    @Mock
    private lateinit var deviceProvisionedController: DeviceProvisionedController

    @Mock
    private lateinit var execution: Execution

    @Mock
    private lateinit var listener: SmartspacePrecondition.Listener

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    /**
     * Ensures fully enabled state is published.
     */
    @Test
    fun testFullyEnabled() {
        `when`(deviceProvisionedController.isCurrentUserSetup).thenReturn(true)
        `when`(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
        val precondition = LockscreenPrecondition(deviceProvisionedController, execution)
        precondition.addListener(listener)

        `verify`(listener).onCriteriaChanged()
        assertThat(precondition.conditionsMet()).isTrue()
    }

    /**
     * Ensures fully enabled state is published.
     */
    @Test
    fun testProvisioning() {
        `when`(deviceProvisionedController.isCurrentUserSetup).thenReturn(true)
        `when`(deviceProvisionedController.isDeviceProvisioned).thenReturn(false)
        val precondition =
                LockscreenPrecondition(deviceProvisionedController, execution)
        precondition.addListener(listener)

        verify(listener).onCriteriaChanged()
        assertThat(precondition.conditionsMet()).isFalse()

        var argumentCaptor = ArgumentCaptor.forClass(DeviceProvisionedController
                .DeviceProvisionedListener::class.java)
        verify(deviceProvisionedController).addCallback(argumentCaptor.capture())

        Mockito.clearInvocations(listener)

        `when`(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
        argumentCaptor.value.onDeviceProvisionedChanged()
        verify(listener).onCriteriaChanged()
        assertThat(precondition.conditionsMet()).isTrue()
    }

    /**
     * Makes sure user setup changes are propagated.
     */
    @Test
    fun testUserSetup() {
        `when`(deviceProvisionedController.isCurrentUserSetup).thenReturn(false)
        `when`(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
        val precondition =
                LockscreenPrecondition(deviceProvisionedController, execution)
        precondition.addListener(listener)

        verify(listener).onCriteriaChanged()
        assertThat(precondition.conditionsMet()).isFalse()

        var argumentCaptor = ArgumentCaptor.forClass(DeviceProvisionedController
                .DeviceProvisionedListener::class.java)
        verify(deviceProvisionedController).addCallback(argumentCaptor.capture())

        Mockito.clearInvocations(listener)

        `when`(deviceProvisionedController.isCurrentUserSetup).thenReturn(true)
        argumentCaptor.value.onUserSetupChanged()
        verify(listener).onCriteriaChanged()
        assertThat(precondition.conditionsMet()).isTrue()
    }
}
