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
import android.content.pm.UserInfo
import android.os.UserHandle
import android.provider.DeviceConfig
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import com.android.systemui.dump.DumpManager
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.argumentCaptor
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
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
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

        private const val LOCATION_INDICATOR =
                SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_ENABLED
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
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var dumpManager: DumpManager
    @Mock
    private lateinit var logger: PrivacyLogger
    @Captor
    private lateinit var argCaptor: ArgumentCaptor<List<PrivacyItem>>
    @Captor
    private lateinit var argCaptorCallback: ArgumentCaptor<AppOpsController.Callback>

    private lateinit var privacyItemController: PrivacyItemController
    private lateinit var executor: FakeExecutor
    private lateinit var fakeClock: FakeSystemClock
    private lateinit var deviceConfigProxy: DeviceConfigProxy

    private val elapsedTime: Long
        get() = fakeClock.elapsedRealtime()

    fun createPrivacyItemController(): PrivacyItemController {
        return PrivacyItemController(
                appOpsController,
                executor,
                executor,
                deviceConfigProxy,
                userTracker,
                logger,
                fakeClock,
                dumpManager)
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        fakeClock = FakeSystemClock()
        executor = FakeExecutor(fakeClock)
        deviceConfigProxy = DeviceConfigProxyFake()

        // Listen to everything by default
        changeMicCamera(true)
        changeLocation(true)

        `when`(userTracker.userProfiles).thenReturn(listOf(UserInfo(CURRENT_USER_ID, "", 0)))

        privacyItemController = createPrivacyItemController()
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
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, "", 0)))
                .`when`(appOpsController).getActiveAppOps(anyBoolean())

        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(capture(argCaptor))
        assertEquals(1, argCaptor.value.size)
    }

    @Test
    fun testSimilarItemsDifferentTimeStamp() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, "", 0),
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, "", 1)))
                .`when`(appOpsController).getActiveAppOps(anyBoolean())

        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(capture(argCaptor))
        assertEquals(2, argCaptor.value.size)
    }

    @Test
    fun testRegisterCallback() {
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(userTracker, atLeastOnce()).addCallback(
                eq(privacyItemController.userTrackerCallback), any())
        verify(userTracker, never()).removeCallback(eq(privacyItemController.userTrackerCallback))
    }

    @Test
    fun testCallback_userChanged() {
        privacyItemController.userTrackerCallback.onUserChanged(0, mContext)
        executor.runAllReady()
        verify(userTracker).userProfiles
    }

    @Test
    fun testReceiver_profilesChanged() {
        privacyItemController.userTrackerCallback.onProfilesChanged(emptyList())
        executor.runAllReady()
        verify(userTracker).userProfiles
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
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())

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
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
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
                .`when`(appOpsController).getActiveAppOps(anyBoolean())
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
                PrivacyApplication("", TEST_UID), 0))
        privacyItemController.privacyList = list
        val privacyList = privacyItemController.privacyList
        assertEquals(list, privacyList)
        assertTrue(list !== privacyList)
    }

    @Test
    fun testNotListeningWhenIndicatorsDisabled() {
        changeLocation(false)
        changeMicCamera(false)
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(appOpsController, never()).addCallback(eq(PrivacyItemController.OPS),
                any())
    }

    @Test
    fun testNotSendingLocationWhenOnlyMicCamera() {
        changeLocation(false)
        changeMicCamera(true)
        executor.runAllReady()

        doReturn(listOf(AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, "", 0),
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, "", 0)))
                .`when`(appOpsController).getActiveAppOps(anyBoolean())

        privacyItemController.addCallback(callback)
        executor.runAllReady()

        verify(callback).onPrivacyItemsChanged(capture(argCaptor))

        assertEquals(1, argCaptor.value.size)
        assertEquals(PrivacyType.TYPE_CAMERA, argCaptor.value[0].privacyType)
    }

    @Test
    fun testNotUpdated_LocationChangeWhenOnlyMicCamera() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, "", 0)))
                .`when`(appOpsController).getActiveAppOps(anyBoolean())

        privacyItemController.addCallback(callback)
        changeLocation(false)
        changeMicCamera(true)
        executor.runAllReady()
        reset(callback) // Clean callback

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true)

        verify(callback, never()).onPrivacyItemsChanged(any())
    }

    @Test
    fun testLogActiveChanged() {
        privacyItemController.addCallback(callback)
        executor.runAllReady()

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true)

        verify(logger).logUpdatedItemFromAppOps(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true)
    }

    @Test
    fun testLogListUpdated() {
        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0))
        ).`when`(appOpsController).getActiveAppOps(anyBoolean())

        privacyItemController.addCallback(callback)
        executor.runAllReady()

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true)
        executor.runAllReady()

        val expected = PrivacyItem(
                PrivacyType.TYPE_LOCATION,
                PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID),
                0
        )

        val captor = argumentCaptor<List<PrivacyItem>>()
        verify(logger, atLeastOnce()).logRetrievedPrivacyItemsList(capture(captor))
        // Let's look at the last log
        val values = captor.allValues
        assertTrue(values[values.size - 1].contains(expected))
    }

    @Test
    fun testListRequestedShowPaused() {
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(appOpsController).getActiveAppOps(true)
    }

    @Test
    fun testListFilterCurrentUser() {
        val otherUser = CURRENT_USER_ID + 1
        val otherUserUid = otherUser * UserHandle.PER_USER_RANGE
        `when`(userTracker.userProfiles).thenReturn(listOf(UserInfo(otherUser, "", 0)))

        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_CAMERA, otherUserUid, TEST_PACKAGE_NAME, 0))
        ).`when`(appOpsController).getActiveAppOps(anyBoolean())

        privacyItemController.userTrackerCallback.onUserChanged(otherUser, mContext)
        executor.runAllReady()

        privacyItemController.addCallback(callback)
        executor.runAllReady()

        verify(callback).onPrivacyItemsChanged(capture(argCaptor))

        assertEquals(1, argCaptor.value.size)
        assertEquals(PrivacyType.TYPE_CAMERA, argCaptor.value[0].privacyType)
        assertEquals(otherUserUid, argCaptor.value[0].application.uid)
    }

    @Test
    fun testAlwaysGetPhoneCameraOps() {
        val otherUser = CURRENT_USER_ID + 1
        `when`(userTracker.userProfiles).thenReturn(listOf(UserInfo(otherUser, "", 0)))

        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_PHONE_CALL_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0))
        ).`when`(appOpsController).getActiveAppOps(anyBoolean())

        privacyItemController.userTrackerCallback.onUserChanged(otherUser, mContext)
        executor.runAllReady()

        privacyItemController.addCallback(callback)
        executor.runAllReady()

        verify(callback).onPrivacyItemsChanged(capture(argCaptor))

        assertEquals(1, argCaptor.value.size)
        assertEquals(PrivacyType.TYPE_CAMERA, argCaptor.value[0].privacyType)
    }

    @Test
    fun testAlwaysGetPhoneMicOps() {
        val otherUser = CURRENT_USER_ID + 1
        `when`(userTracker.userProfiles).thenReturn(listOf(UserInfo(otherUser, "", 0)))

        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_PHONE_CALL_MICROPHONE, TEST_UID, TEST_PACKAGE_NAME, 0))
        ).`when`(appOpsController).getActiveAppOps(anyBoolean())

        privacyItemController.userTrackerCallback.onUserChanged(otherUser, mContext)
        executor.runAllReady()

        privacyItemController.addCallback(callback)
        executor.runAllReady()

        verify(callback).onPrivacyItemsChanged(capture(argCaptor))

        assertEquals(1, argCaptor.value.size)
        assertEquals(PrivacyType.TYPE_MICROPHONE, argCaptor.value[0].privacyType)
    }

    @Test
    fun testPassageOfTimeDoesNotRemoveIndicators() {
        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, elapsedTime)
        )).`when`(appOpsController).getActiveAppOps(anyBoolean())

        privacyItemController.addCallback(callback)

        fakeClock.advanceTime(PrivacyItemController.TIME_TO_HOLD_INDICATORS * 10)
        executor.runAllReady()

        verify(callback, never()).onPrivacyItemsChanged(emptyList())
        assertTrue(privacyItemController.privacyList.isNotEmpty())
    }

    @Test
    fun testNotHeldAfterTimeIsOff() {
        // Start with some element at time 0
        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, elapsedTime)
        )).`when`(appOpsController).getActiveAppOps(anyBoolean())
        privacyItemController.addCallback(callback)
        executor.runAllReady()

        // Then remove it at time HOLD + 1
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        fakeClock.advanceTime(PrivacyItemController.TIME_TO_HOLD_INDICATORS + 1)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, false)
        executor.runAllReady()

        // See it's not there
        verify(callback).onPrivacyItemsChanged(emptyList())
        assertTrue(privacyItemController.privacyList.isEmpty())
    }

    @Test
    fun testElementNotRemovedBeforeHoldTime() {
        // Start with some element at time 0
        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, elapsedTime)
        )).`when`(appOpsController).getActiveAppOps(anyBoolean())
        privacyItemController.addCallback(callback)
        executor.runAllReady()

        // Then remove it at time HOLD - 1
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        fakeClock.advanceTime(PrivacyItemController.TIME_TO_HOLD_INDICATORS - 1)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, false)
        executor.runAllReady()

        // See it's still there
        verify(callback, never()).onPrivacyItemsChanged(emptyList())
        assertTrue(privacyItemController.privacyList.isNotEmpty())
    }

    @Test
    fun testElementAutoRemovedAfterHoldTime() {
        // Start with some element at time 0
        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, elapsedTime)
        )).`when`(appOpsController).getActiveAppOps(anyBoolean())
        privacyItemController.addCallback(callback)
        executor.runAllReady()

        // Then remove it at time HOLD - 1
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        fakeClock.advanceTime(PrivacyItemController.TIME_TO_HOLD_INDICATORS - 1)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, false)
        executor.runAllReady()

        fakeClock.advanceTime(2L)
        executor.runAllReady()

        // See it was auto-removed
        verify(callback).onPrivacyItemsChanged(emptyList())
        assertTrue(privacyItemController.privacyList.isEmpty())
    }

    @Test
    fun testPausedElementsAreRemoved() {
        val item = AppOpItem(
                AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID,
                TEST_PACKAGE_NAME,
                elapsedTime
        )
        `when`(appOpsController.getActiveAppOps(anyBoolean())).thenReturn(listOf(item))
        privacyItemController.addCallback(callback)
        executor.runAllReady()

        item.isDisabled = true
        fakeClock.advanceTime(1)
        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, false)

        executor.runAllReady()

        verify(callback).onPrivacyItemsChanged(emptyList())
        assertTrue(privacyItemController.privacyList.isEmpty())
    }

    private fun changeMicCamera(value: Boolean?) = changeProperty(MIC_CAMERA, value)
    private fun changeLocation(value: Boolean?) = changeProperty(LOCATION_INDICATOR, value)

    private fun changeProperty(name: String, value: Boolean?) {
        deviceConfigProxy.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                name,
                value?.toString(),
                false
        )
    }
}