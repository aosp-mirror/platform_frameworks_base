/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy

import android.os.Handler
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeGlobalSettings
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.wrapper.BuildInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class DeviceProvisionedControllerImplTest : SysuiTestCase() {

    companion object {
        private const val START_USER = 0
    }

    private lateinit var controller: DeviceProvisionedControllerImpl

    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var dumpManager: DumpManager
    @Mock
    private lateinit var listener: DeviceProvisionedController.DeviceProvisionedListener
    @Mock
    private lateinit var buildInfo: BuildInfo
    @Captor
    private lateinit var userTrackerCallbackCaptor: ArgumentCaptor<UserTracker.Callback>

    private lateinit var mainExecutor: FakeExecutor
    private lateinit var testableLooper: TestableLooper
    private lateinit var secureSettings: FakeSettings
    private lateinit var globalSettings: FakeGlobalSettings


    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        mainExecutor = FakeExecutor(FakeSystemClock())
        secureSettings = FakeSettings()
        globalSettings = FakeGlobalSettings()
        `when`(userTracker.userId).thenReturn(START_USER)
        whenever(buildInfo.isDebuggable).thenReturn(false)
        controller = DeviceProvisionedControllerImpl(
                secureSettings,
                globalSettings,
                userTracker,
                dumpManager,
                Handler(testableLooper.looper),
                mainExecutor
        )
    }

    @Test
    fun testNotProvisionedByDefault() {
        init()
        assertThat(controller.isDeviceProvisioned).isFalse()
    }

    @Test
    fun testNotUserSetupByDefault() {
        init()
        assertThat(controller.isUserSetup(START_USER)).isFalse()
    }

    @Test
    fun testProvisionedWhenCreated() {
        globalSettings.putInt(Settings.Global.DEVICE_PROVISIONED, 1)
        init()

        assertThat(controller.isDeviceProvisioned).isTrue()
    }

    @Test
    fun testUserSetupWhenCreated() {
        secureSettings.putIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 1, START_USER)
        init()

        assertThat(controller.isUserSetup(START_USER))
    }

    @Test
    fun testDeviceProvisionedChange() {
        init()

        globalSettings.putInt(Settings.Global.DEVICE_PROVISIONED, 1)
        testableLooper.processAllMessages() // background observer

        assertThat(controller.isDeviceProvisioned).isTrue()
    }

    @Test
    fun testUserSetupChange() {
        init()

        secureSettings.putIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 1, START_USER)
        testableLooper.processAllMessages() // background observer

        assertThat(controller.isUserSetup(START_USER)).isTrue()
    }

    @Test
    fun testUserSetupChange_otherUser() {
        init()
        val otherUser = 10

        secureSettings.putIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 1, otherUser)
        testableLooper.processAllMessages() // background observer

        assertThat(controller.isUserSetup(START_USER)).isFalse()
        assertThat(controller.isUserSetup(otherUser)).isTrue()
    }

    @Test
    fun testCurrentUserSetup() {
        val otherUser = 10
        secureSettings.putIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 1, otherUser)
        init()

        assertThat(controller.isCurrentUserSetup).isFalse()
        switchUser(otherUser)
        testableLooper.processAllMessages()

        assertThat(controller.isCurrentUserSetup).isTrue()
    }

    @Test
    fun testListenerNotCalledOnAdd() {
        init()
        controller.addCallback(listener)

        mainExecutor.runAllReady()

        verify(listener, never()).onDeviceProvisionedChanged()
        verify(listener, never()).onUserSetupChanged()
        verify(listener, never()).onUserSwitched()
    }

    @Test
    fun testListenerCalledOnUserSwitched() {
        init()
        controller.addCallback(listener)

        switchUser(10)

        testableLooper.processAllMessages()
        mainExecutor.runAllReady()

        verify(listener).onUserSwitched()
        verify(listener, never()).onUserSetupChanged()
        verify(listener, never()).onDeviceProvisionedChanged()
    }

    @Test
    fun testListenerCalledOnUserSetupChanged() {
        init()
        controller.addCallback(listener)

        secureSettings.putIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 1, START_USER)
        testableLooper.processAllMessages()
        mainExecutor.runAllReady()

        verify(listener, never()).onUserSwitched()
        verify(listener).onUserSetupChanged()
        verify(listener, never()).onDeviceProvisionedChanged()
    }

    @Test
    fun testListenerCalledOnDeviceProvisionedChanged() {
        init()
        controller.addCallback(listener)

        globalSettings.putInt(Settings.Global.DEVICE_PROVISIONED, 1)
        testableLooper.processAllMessages()
        mainExecutor.runAllReady()

        verify(listener, never()).onUserSwitched()
        verify(listener, never()).onUserSetupChanged()
        verify(listener).onDeviceProvisionedChanged()
    }

    @Test
    fun testRemoveListener() {
        init()
        controller.addCallback(listener)
        controller.removeCallback(listener)

        switchUser(10)
        secureSettings.putIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 1, START_USER)
        globalSettings.putInt(Settings.Global.DEVICE_PROVISIONED, 1)

        testableLooper.processAllMessages()
        mainExecutor.runAllReady()

        verify(listener, never()).onDeviceProvisionedChanged()
        verify(listener, never()).onUserSetupChanged()
        verify(listener, never()).onUserSwitched()
    }

    private fun init() {
        controller.init()
        verify(userTracker).addCallback(capture(userTrackerCallbackCaptor), any())
    }

    private fun switchUser(toUser: Int) {
        `when`(userTracker.userId).thenReturn(toUser)
        userTrackerCallbackCaptor.value.onUserChanged(toUser, mContext)
    }
}