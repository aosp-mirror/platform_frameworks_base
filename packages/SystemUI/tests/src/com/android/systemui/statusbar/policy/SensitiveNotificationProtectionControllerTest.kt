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

package com.android.systemui.statusbar.policy

import android.app.ActivityOptions
import android.app.Notification
import android.media.projection.MediaProjectionInfo
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.server.notification.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class SensitiveNotificationProtectionControllerTest : SysuiTestCase() {
    @Mock private lateinit var handler: Handler

    @Mock private lateinit var mediaProjectionManager: MediaProjectionManager

    @Mock private lateinit var mediaProjectionInfo: MediaProjectionInfo

    @Mock private lateinit var listener1: Runnable
    @Mock private lateinit var listener2: Runnable
    @Mock private lateinit var listener3: Runnable

    @Captor
    private lateinit var mediaProjectionCallbackCaptor:
        ArgumentCaptor<MediaProjectionManager.Callback>

    private lateinit var controller: SensitiveNotificationProtectionControllerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mSetFlagsRule.enableFlags(Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING)

        setShareFullScreen()

        controller = SensitiveNotificationProtectionControllerImpl(mediaProjectionManager, handler)

        // Obtain useful MediaProjectionCallback
        verify(mediaProjectionManager).addCallback(mediaProjectionCallbackCaptor.capture(), any())
    }

    @Test
    fun init_flagEnabled_registerMediaProjectionManagerCallback() {
        assertNotNull(mediaProjectionCallbackCaptor.value)
    }

    @Test
    fun init_flagDisabled_noRegisterMediaProjectionManagerCallback() {
        mSetFlagsRule.disableFlags(Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING)
        reset(mediaProjectionManager)

        controller = SensitiveNotificationProtectionControllerImpl(mediaProjectionManager, handler)

        verifyZeroInteractions(mediaProjectionManager)
    }

    @Test
    fun registerSensitiveStateListener_singleListener() {
        controller.registerSensitiveStateListener(listener1)

        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)
        mediaProjectionCallbackCaptor.value.onStop(mediaProjectionInfo)

        verify(listener1, times(2)).run()
    }

    @Test
    fun registerSensitiveStateListener_multipleListeners() {
        controller.registerSensitiveStateListener(listener1)
        controller.registerSensitiveStateListener(listener2)

        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)
        mediaProjectionCallbackCaptor.value.onStop(mediaProjectionInfo)

        verify(listener1, times(2)).run()
        verify(listener2, times(2)).run()
    }

    @Test
    fun registerSensitiveStateListener_afterProjectionActive() {
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)

        controller.registerSensitiveStateListener(listener1)
        verifyZeroInteractions(listener1)

        mediaProjectionCallbackCaptor.value.onStop(mediaProjectionInfo)

        verify(listener1).run()
    }

    @Test
    fun unregisterSensitiveStateListener_singleListener() {
        controller.registerSensitiveStateListener(listener1)

        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)
        mediaProjectionCallbackCaptor.value.onStop(mediaProjectionInfo)

        verify(listener1, times(2)).run()

        controller.unregisterSensitiveStateListener(listener1)

        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)
        mediaProjectionCallbackCaptor.value.onStop(mediaProjectionInfo)

        verifyNoMoreInteractions(listener1)
    }

    @Test
    fun unregisterSensitiveStateListener_multipleListeners() {
        controller.registerSensitiveStateListener(listener1)
        controller.registerSensitiveStateListener(listener2)
        controller.registerSensitiveStateListener(listener3)

        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)
        mediaProjectionCallbackCaptor.value.onStop(mediaProjectionInfo)

        verify(listener1, times(2)).run()
        verify(listener2, times(2)).run()
        verify(listener3, times(2)).run()

        controller.unregisterSensitiveStateListener(listener1)
        controller.unregisterSensitiveStateListener(listener2)

        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)
        mediaProjectionCallbackCaptor.value.onStop(mediaProjectionInfo)

        verifyNoMoreInteractions(listener1)
        verifyNoMoreInteractions(listener2)
        verify(listener3, times(4)).run()
    }

    @Test
    fun isSensitiveStateActive_projectionInactive_false() {
        assertFalse(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionActive_true() {
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)

        assertTrue(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionInactiveAfterActive_false() {
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)
        mediaProjectionCallbackCaptor.value.onStop(mediaProjectionInfo)

        assertFalse(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionActiveAfterInactive_true() {
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)
        mediaProjectionCallbackCaptor.value.onStop(mediaProjectionInfo)
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)

        assertTrue(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionActive_singleActivity_false() {
        setShareSingleApp()
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)

        assertFalse(controller.isSensitiveStateActive)
    }

    @Test
    fun shouldProtectNotification_projectionInactive_false() {
        val notificationEntry = mock(NotificationEntry::class.java)

        assertFalse(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_singleActivity_false() {
        setShareSingleApp()
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)

        val notificationEntry = setupNotificationEntry(TEST_PACKAGE_NAME)

        assertFalse(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_fgsNotificationFromProjectionApp_false() {
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)

        val notificationEntry = setupFgsNotificationEntry(TEST_PROJECTION_PACKAGE_NAME)

        assertFalse(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_fgsNotificationNotFromProjectionApp_true() {
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)

        val notificationEntry = setupFgsNotificationEntry(TEST_PACKAGE_NAME)

        assertTrue(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_notFgsNotification_true() {
        mediaProjectionCallbackCaptor.value.onStart(mediaProjectionInfo)

        val notificationEntry = setupNotificationEntry(TEST_PROJECTION_PACKAGE_NAME)

        assertTrue(controller.shouldProtectNotification(notificationEntry))
    }

    private fun setShareFullScreen() {
        `when`(mediaProjectionInfo.packageName).thenReturn(TEST_PROJECTION_PACKAGE_NAME)
        `when`(mediaProjectionInfo.launchCookie).thenReturn(null)
    }

    private fun setShareSingleApp() {
        `when`(mediaProjectionInfo.packageName).thenReturn(TEST_PROJECTION_PACKAGE_NAME)
        `when`(mediaProjectionInfo.launchCookie).thenReturn(ActivityOptions.LaunchCookie())
    }

    private fun setupNotificationEntry(
        packageName: String,
        isFgs: Boolean = false
    ): NotificationEntry {
        val notificationEntry = mock(NotificationEntry::class.java)
        val sbn = mock(StatusBarNotification::class.java)
        val notification = mock(Notification::class.java)
        `when`(notificationEntry.sbn).thenReturn(sbn)
        `when`(sbn.packageName).thenReturn(packageName)
        `when`(sbn.notification).thenReturn(notification)
        `when`(notification.isFgsOrUij).thenReturn(isFgs)

        return notificationEntry
    }

    private fun setupFgsNotificationEntry(packageName: String): NotificationEntry {
        return setupNotificationEntry(packageName, /* isFgs= */ true)
    }

    companion object {
        private const val TEST_PROJECTION_PACKAGE_NAME =
            "com.android.systemui.statusbar.policy.projectionpackage"
        private const val TEST_PACKAGE_NAME = "com.android.systemui.statusbar.policy.testpackage"
    }
}
