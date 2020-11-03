/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.privacy

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import android.provider.DeviceConfig
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class PrivacyItemControllerTest : SysuiTestCase() {

    companion object {
        val CURRENT_USER_ID = ActivityManager.getCurrentUser()
        val TEST_UID = CURRENT_USER_ID * UserHandle.PER_USER_RANGE
        const val TEST_PACKAGE_NAME = "test"

        private const val ALL_INDICATORS =
                SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED
        private const val MIC_CAMERA = SystemUiDeviceConfigFlags.PROPERTY_MIC_CAMERA_ENABLED
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> eq(value: T): T = Mockito.eq(value) ?: value
        fun <T> any(): T = Mockito.any<T>()
    }

    @Mock
    private lateinit var appOpsController: AppOpsController
    @Mock
    private lateinit var callback: PrivacyItemController.Callback
    @Mock
    private lateinit var userManager: UserManager
    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var dumpManager: DumpManager
    @Captor
    private lateinit var argCaptor: ArgumentCaptor<List<PrivacyItem>>
    @Captor
    private lateinit var argCaptorCallback: ArgumentCaptor<AppOpsController.Callback>

    private lateinit var privacyItemController: PrivacyItemController
    private lateinit var executor: FakeExecutor
    private lateinit var deviceConfigProxy: DeviceConfigProxy

    fun PrivacyItemController(): PrivacyItemController {
        return PrivacyItemController(
                appOpsController,
                executor,
                executor,
                broadcastDispatcher,
                deviceConfigProxy,
                userManager,
                dumpManager
        )
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())
        deviceConfigProxy = DeviceConfigProxyFake()

        changeAll(true)

        doReturn(listOf(object : UserInfo() {
            init {
                id = CURRENT_USER_ID
            }
        })).`when`(userManager).getProfiles(anyInt())

        privacyItemController = PrivacyItemController()
    }

    @Test
    fun testSetListeningTrueByAddingCallback() {
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(appOpsController).addCallback(eq(PrivacyItemController.OPS),
                any())
        verify(callback).onPrivacyItemsChanged(anyList())
    }

    @Test
    fun testSetListeningFalseByRemovingLastCallback() {
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(appOpsController, never()).removeCallback(any(),
                any())
        privacyItemController.removeCallback(callback)
        executor.runAllReady()
        verify(appOpsController).removeCallback(eq(PrivacyItemController.OPS),
                any())
        verify(callback).onPrivacyItemsChanged(emptyList())
    }

    @Test
    fun testDistinctItems() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, "", 0),
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, "", 1)))
                .`when`(appOpsController).getActiveAppOpsForUser(anyInt())

        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(capture(argCaptor))
        assertEquals(1, argCaptor.value.size)
    }

    @Test
    fun testRegisterReceiver_allUsers() {
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(broadcastDispatcher, atLeastOnce()).registerReceiver(
                eq(privacyItemController.userSwitcherReceiver), any(), eq(null), eq(UserHandle.ALL))
        verify(broadcastDispatcher, never())
                .unregisterReceiver(eq(privacyItemController.userSwitcherReceiver))
    }

    @Test
    fun testReceiver_ACTION_USER_FOREGROUND() {
        privacyItemController.userSwitcherReceiver.onReceive(context,
                Intent(Intent.ACTION_USER_SWITCHED))
        executor.runAllReady()
        verify(userManager).getProfiles(anyInt())
    }

    @Test
    fun testReceiver_ACTION_MANAGED_PROFILE_ADDED() {
        privacyItemController.userSwitcherReceiver.onReceive(context,
                Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE))
        executor.runAllReady()
        verify(userManager).getProfiles(anyInt())
    }

    @Test
    fun testReceiver_ACTION_MANAGED_PROFILE_REMOVED() {
        privacyItemController.userSwitcherReceiver.onReceive(context,
                Intent(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE))
        executor.runAllReady()
        verify(userManager).getProfiles(anyInt())
    }

    @Test
    fun testAddMultipleCallbacks() {
        val otherCallback = mock(PrivacyItemController.Callback::class.java)
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(anyList())

        privacyItemController.addCallback(otherCallback)
        executor.runAllReady()
        verify(otherCallback).onPrivacyItemsChanged(anyList())
        // Adding a callback should not unnecessarily call previous ones
        verifyNoMoreInteractions(callback)
    }

    @Test
    fun testMultipleCallbacksAreUpdated() {
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOpsForUser(anyInt())

        val otherCallback = mock(PrivacyItemController.Callback::class.java)
        privacyItemController.addCallback(callback)
        privacyItemController.addCallback(otherCallback)
        executor.runAllReady()
        reset(callback)
        reset(otherCallback)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(0, TEST_UID, "", true)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(anyList())
        verify(otherCallback).onPrivacyItemsChanged(anyList())
    }

    @Test
    fun testRemoveCallback() {
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOpsForUser(anyInt())
        val otherCallback = mock(PrivacyItemController.Callback::class.java)
        privacyItemController.addCallback(callback)
        privacyItemController.addCallback(otherCallback)
        executor.runAllReady()
        executor.runAllReady()
        reset(callback)
        reset(otherCallback)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        privacyItemController.removeCallback(callback)
        argCaptorCallback.value.onActiveStateChanged(0, TEST_UID, "", true)
        executor.runAllReady()
        verify(callback, never()).onPrivacyItemsChanged(anyList())
        verify(otherCallback).onPrivacyItemsChanged(anyList())
    }

    @Test
    fun testListShouldNotHaveNull() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_ACTIVATE_VPN, TEST_UID, "", 0),
                        AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, "", 0)))
                .`when`(appOpsController).getActiveAppOpsForUser(anyInt())
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        executor.runAllReady()

        verify(callback).onPrivacyItemsChanged(capture(argCaptor))
        assertEquals(1, argCaptor.value.size)
        assertThat(argCaptor.value, not(hasItem(nullValue())))
    }

    @Test
    fun testListShouldBeCopy() {
        val list = listOf(PrivacyItem(PrivacyType.TYPE_CAMERA,
                PrivacyApplication("", TEST_UID)))
        privacyItemController.privacyList = list
        val privacyList = privacyItemController.privacyList
        assertEquals(list, privacyList)
        assertTrue(list !== privacyList)
    }

    @Test
    fun testNotListeningWhenIndicatorsDisabled() {
        changeAll(false)
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(appOpsController, never()).addCallback(eq(PrivacyItemController.OPS),
                any())
    }

    @Test
    fun testNotSendingLocationWhenOnlyMicCamera() {
        changeAll(false)
        changeMicCamera(true)
        executor.runAllReady()

        doReturn(listOf(AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, "", 0),
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, "", 0)))
                .`when`(appOpsController).getActiveAppOpsForUser(anyInt())

        privacyItemController.addCallback(callback)
        executor.runAllReady()

        verify(callback).onPrivacyItemsChanged(capture(argCaptor))

        assertEquals(1, argCaptor.value.size)
        assertEquals(PrivacyType.TYPE_CAMERA, argCaptor.value[0].privacyType)
    }

    @Test
    fun testNotUpdated_LocationChangeWhenOnlyMicCamera() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, "", 0)))
                .`when`(appOpsController).getActiveAppOpsForUser(anyInt())

        privacyItemController.addCallback(callback)
        changeAll(false)
        changeMicCamera(true)
        executor.runAllReady()
        reset(callback) // Clean callback

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true)

        verify(callback, never()).onPrivacyItemsChanged(any())
    }

    private fun changeMicCamera(value: Boolean?) = changeProperty(MIC_CAMERA, value)
    private fun changeAll(value: Boolean?) = changeProperty(ALL_INDICATORS, value)

    private fun changeProperty(name: String, value: Boolean?) {
        deviceConfigProxy.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                name,
                value?.toString(),
                false
        )
    }
}