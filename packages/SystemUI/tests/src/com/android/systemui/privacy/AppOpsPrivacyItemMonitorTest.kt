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

package com.android.systemui.privacy

import android.app.AppOpsManager
import android.content.pm.UserInfo
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class AppOpsPrivacyItemMonitorTest : SysuiTestCase() {

    companion object {
        val CURRENT_USER_ID = 1
        val TEST_UID = CURRENT_USER_ID * UserHandle.PER_USER_RANGE
        const val TEST_PACKAGE_NAME = "test"

        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> eq(value: T): T = Mockito.eq(value) ?: value
        fun <T> any(): T = Mockito.any<T>()
    }

    @Mock
    private lateinit var appOpsController: AppOpsController

    @Mock
    private lateinit var callback: PrivacyItemMonitor.Callback

    @Mock
    private lateinit var userTracker: UserTracker

    @Mock
    private lateinit var privacyConfig: PrivacyConfig

    @Mock
    private lateinit var logger: PrivacyLogger

    @Captor
    private lateinit var argCaptorConfigCallback: ArgumentCaptor<PrivacyConfig.Callback>

    @Captor
    private lateinit var argCaptorCallback: ArgumentCaptor<AppOpsController.Callback>

    private lateinit var appOpsPrivacyItemMonitor: AppOpsPrivacyItemMonitor
    private lateinit var executor: FakeExecutor

    fun createAppOpsPrivacyItemMonitor(): AppOpsPrivacyItemMonitor {
        return AppOpsPrivacyItemMonitor(
                appOpsController,
                userTracker,
                privacyConfig,
                executor,
                logger)
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())

        // Listen to everything by default
        `when`(privacyConfig.micCameraAvailable).thenReturn(true)
        `when`(privacyConfig.locationAvailable).thenReturn(true)
        `when`(userTracker.userProfiles).thenReturn(
                listOf(UserInfo(CURRENT_USER_ID, TEST_PACKAGE_NAME, 0)))

        appOpsPrivacyItemMonitor = createAppOpsPrivacyItemMonitor()
        verify(privacyConfig).addCallback(capture(argCaptorConfigCallback))
    }

    @Test
    fun testStartListeningAddsAppOpsCallback() {
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        verify(appOpsController).addCallback(eq(AppOpsPrivacyItemMonitor.OPS), any())
    }

    @Test
    fun testStopListeningRemovesAppOpsCallback() {
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        verify(appOpsController, never()).removeCallback(any(), any())

        appOpsPrivacyItemMonitor.stopListening()
        executor.runAllReady()
        verify(appOpsController).removeCallback(eq(AppOpsPrivacyItemMonitor.OPS), any())
    }

    @Test
    fun testDistinctItems() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0)))
                .`when`(appOpsController).getActiveAppOps(anyBoolean())

        assertEquals(1, appOpsPrivacyItemMonitor.getActivePrivacyItems().size)
    }

    @Test
    fun testSimilarItemsDifferentTimeStamp() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 1)))
                .`when`(appOpsController).getActiveAppOps(anyBoolean())

        assertEquals(2, appOpsPrivacyItemMonitor.getActivePrivacyItems().size)
    }

    @Test
    fun testRegisterUserTrackerCallback() {
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        verify(userTracker, atLeastOnce()).addCallback(
                eq(appOpsPrivacyItemMonitor.userTrackerCallback), any())
        verify(userTracker, never()).removeCallback(
                eq(appOpsPrivacyItemMonitor.userTrackerCallback))
    }

    @Test
    fun testUserTrackerCallback_userChanged() {
        appOpsPrivacyItemMonitor.userTrackerCallback.onUserChanged(0, mContext)
        executor.runAllReady()
        verify(userTracker).userProfiles
    }

    @Test
    fun testUserTrackerCallback_profilesChanged() {
        appOpsPrivacyItemMonitor.userTrackerCallback.onProfilesChanged(emptyList())
        executor.runAllReady()
        verify(userTracker).userProfiles
    }

    @Test
    fun testCallbackIsUpdated() {
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        reset(callback)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(0, TEST_UID, TEST_PACKAGE_NAME, true)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged()
    }

    @Test
    fun testRemoveCallback() {
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        reset(callback)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        appOpsPrivacyItemMonitor.stopListening()
        argCaptorCallback.value.onActiveStateChanged(0, TEST_UID, TEST_PACKAGE_NAME, true)
        executor.runAllReady()
        verify(callback, never()).onPrivacyItemsChanged()
    }

    @Test
    fun testListShouldNotHaveNull() {
        doReturn(listOf(AppOpItem(AppOpsManager.OP_ACTIVATE_VPN, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0)))
                .`when`(appOpsController).getActiveAppOps(anyBoolean())

        assertThat(appOpsPrivacyItemMonitor.getActivePrivacyItems(), not(hasItem(nullValue())))
    }

    @Test
    fun testNotListeningWhenIndicatorsDisabled() {
        changeMicCamera(false)
        changeLocation(false)

        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        verify(appOpsController, never()).addCallback(eq(AppOpsPrivacyItemMonitor.OPS), any())
    }

    @Test
    fun testNotSendingLocationWhenLocationDisabled() {
        changeLocation(false)
        executor.runAllReady()

        doReturn(listOf(AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0)))
                .`when`(appOpsController).getActiveAppOps(anyBoolean())

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(1, privacyItems.size)
        assertEquals(PrivacyType.TYPE_CAMERA, privacyItems[0].privacyType)
    }

    @Test
    fun testNotUpdated_LocationChangeWhenLocationDisabled() {
        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0)))
                .`when`(appOpsController).getActiveAppOps(anyBoolean())

        appOpsPrivacyItemMonitor.startListening(callback)
        changeLocation(false)
        executor.runAllReady()
        reset(callback) // Clean callback

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true)

        verify(callback, never()).onPrivacyItemsChanged()
    }

    @Test
    fun testLogActiveChanged() {
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true)

        verify(logger).logUpdatedItemFromAppOps(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true)
    }

    @Test
    fun testListRequestedShowPaused() {
        appOpsPrivacyItemMonitor.getActivePrivacyItems()
        verify(appOpsController).getActiveAppOps(true)
    }

    @Test
    fun testListFilterCurrentUser() {
        val otherUser = CURRENT_USER_ID + 1
        val otherUserUid = otherUser * UserHandle.PER_USER_RANGE
        `when`(userTracker.userProfiles)
                .thenReturn(listOf(UserInfo(otherUser, TEST_PACKAGE_NAME, 0)))

        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_CAMERA, otherUserUid, TEST_PACKAGE_NAME, 0))
        ).`when`(appOpsController).getActiveAppOps(anyBoolean())

        appOpsPrivacyItemMonitor.userTrackerCallback.onUserChanged(otherUser, mContext)
        executor.runAllReady()

        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()

        assertEquals(1, privacyItems.size)
        assertEquals(PrivacyType.TYPE_CAMERA, privacyItems[0].privacyType)
        assertEquals(otherUserUid, privacyItems[0].application.uid)
    }

    @Test
    fun testAlwaysGetPhoneCameraOps() {
        val otherUser = CURRENT_USER_ID + 1
        `when`(userTracker.userProfiles)
                .thenReturn(listOf(UserInfo(otherUser, TEST_PACKAGE_NAME, 0)))

        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_PHONE_CALL_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0))
        ).`when`(appOpsController).getActiveAppOps(anyBoolean())

        appOpsPrivacyItemMonitor.userTrackerCallback.onUserChanged(otherUser, mContext)
        executor.runAllReady()

        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()

        assertEquals(1, privacyItems.size)
        assertEquals(PrivacyType.TYPE_CAMERA, privacyItems[0].privacyType)
    }

    @Test
    fun testAlwaysGetPhoneMicOps() {
        val otherUser = CURRENT_USER_ID + 1
        `when`(userTracker.userProfiles)
                .thenReturn(listOf(UserInfo(otherUser, TEST_PACKAGE_NAME, 0)))

        doReturn(listOf(
                AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                AppOpItem(AppOpsManager.OP_PHONE_CALL_MICROPHONE, TEST_UID, TEST_PACKAGE_NAME, 0))
        ).`when`(appOpsController).getActiveAppOps(anyBoolean())

        appOpsPrivacyItemMonitor.userTrackerCallback.onUserChanged(otherUser, mContext)
        executor.runAllReady()

        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()

        assertEquals(1, privacyItems.size)
        assertEquals(PrivacyType.TYPE_MICROPHONE, privacyItems[0].privacyType)
    }

    @Test
    fun testDisabledAppOpIsPaused() {
        val item = AppOpItem(AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, 0)
        item.isDisabled = true
        `when`(appOpsController.getActiveAppOps(anyBoolean())).thenReturn(listOf(item))

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(1, privacyItems.size)
        assertTrue(privacyItems[0].paused)
    }

    @Test
    fun testEnabledAppOpIsNotPaused() {
        val item = AppOpItem(AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, 0)
        `when`(appOpsController.getActiveAppOps(anyBoolean())).thenReturn(listOf(item))

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(1, privacyItems.size)
        assertFalse(privacyItems[0].paused)
    }

    private fun changeMicCamera(value: Boolean) {
        `when`(privacyConfig.micCameraAvailable).thenReturn(value)
        argCaptorConfigCallback.value.onFlagMicCameraChanged(value)
    }

    private fun changeLocation(value: Boolean) {
        `when`(privacyConfig.locationAvailable).thenReturn(value)
        argCaptorConfigCallback.value.onFlagLocationChanged(value)
    }
}