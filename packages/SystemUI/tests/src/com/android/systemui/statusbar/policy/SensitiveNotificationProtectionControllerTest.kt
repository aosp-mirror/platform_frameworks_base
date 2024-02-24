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
import android.app.IActivityManager
import android.app.Notification
import android.app.Notification.FLAG_FOREGROUND_SERVICE
import android.app.Notification.VISIBILITY_PRIVATE
import android.app.Notification.VISIBILITY_PUBLIC
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.VISIBILITY_NO_OVERRIDE
import android.media.projection.MediaProjectionInfo
import android.media.projection.MediaProjectionManager
import android.platform.test.annotations.EnableFlags
import android.provider.Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.server.notification.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.statusbar.RankingBuilder
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.concurrency.mockExecutorHandler
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.settings.FakeGlobalSettings
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@EnableFlags(Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING)
class SensitiveNotificationProtectionControllerTest : SysuiTestCase() {
    private val logger = SensitiveNotificationProtectionControllerLogger(logcatLogBuffer())

    @Mock private lateinit var activityManager: IActivityManager
    @Mock private lateinit var mediaProjectionManager: MediaProjectionManager
    @Mock private lateinit var mediaProjectionInfo: MediaProjectionInfo
    @Mock private lateinit var listener1: Runnable
    @Mock private lateinit var listener2: Runnable
    @Mock private lateinit var listener3: Runnable

    private lateinit var executor: FakeExecutor
    private lateinit var globalSettings: FakeGlobalSettings
    private lateinit var mediaProjectionCallback: MediaProjectionManager.Callback
    private lateinit var controller: SensitiveNotificationProtectionControllerImpl

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread() // for updating exempt packages and notifying listeners
        MockitoAnnotations.initMocks(this)

        setShareFullScreen()
        whenever(activityManager.bugreportWhitelistedPackages)
            .thenReturn(listOf(BUGREPORT_PACKAGE_NAME))

        executor = FakeExecutor(FakeSystemClock())
        globalSettings = FakeGlobalSettings()
        controller =
            SensitiveNotificationProtectionControllerImpl(
                mContext,
                globalSettings,
                mediaProjectionManager,
                activityManager,
                mockExecutorHandler(executor),
                executor,
                logger
            )

        // Process pending work (getting global setting and list of exemptions)
        executor.runAllReady()

        // Obtain useful MediaProjectionCallback
        mediaProjectionCallback = withArgCaptor {
            verify(mediaProjectionManager).addCallback(capture(), any())
        }
    }

    @Test
    fun init_registerMediaProjectionManagerCallback() {
        assertNotNull(mediaProjectionCallback)
    }

    @Test
    fun registerSensitiveStateListener_singleListener() {
        controller.registerSensitiveStateListener(listener1)

        mediaProjectionCallback.onStart(mediaProjectionInfo)
        mediaProjectionCallback.onStop(mediaProjectionInfo)

        verify(listener1, times(2)).run()
    }

    @Test
    fun registerSensitiveStateListener_multipleListeners() {
        controller.registerSensitiveStateListener(listener1)
        controller.registerSensitiveStateListener(listener2)

        mediaProjectionCallback.onStart(mediaProjectionInfo)
        mediaProjectionCallback.onStop(mediaProjectionInfo)

        verify(listener1, times(2)).run()
        verify(listener2, times(2)).run()
    }

    @Test
    fun registerSensitiveStateListener_afterProjectionActive() {
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        controller.registerSensitiveStateListener(listener1)
        verifyZeroInteractions(listener1)

        mediaProjectionCallback.onStop(mediaProjectionInfo)

        verify(listener1).run()
    }

    @Test
    fun unregisterSensitiveStateListener_singleListener() {
        controller.registerSensitiveStateListener(listener1)

        mediaProjectionCallback.onStart(mediaProjectionInfo)
        mediaProjectionCallback.onStop(mediaProjectionInfo)

        verify(listener1, times(2)).run()

        controller.unregisterSensitiveStateListener(listener1)

        mediaProjectionCallback.onStart(mediaProjectionInfo)
        mediaProjectionCallback.onStop(mediaProjectionInfo)

        verifyNoMoreInteractions(listener1)
    }

    @Test
    fun unregisterSensitiveStateListener_multipleListeners() {
        controller.registerSensitiveStateListener(listener1)
        controller.registerSensitiveStateListener(listener2)
        controller.registerSensitiveStateListener(listener3)

        mediaProjectionCallback.onStart(mediaProjectionInfo)
        mediaProjectionCallback.onStop(mediaProjectionInfo)

        verify(listener1, times(2)).run()
        verify(listener2, times(2)).run()
        verify(listener3, times(2)).run()

        controller.unregisterSensitiveStateListener(listener1)
        controller.unregisterSensitiveStateListener(listener2)

        mediaProjectionCallback.onStart(mediaProjectionInfo)
        mediaProjectionCallback.onStop(mediaProjectionInfo)

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
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        assertTrue(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionInactiveAfterActive_false() {
        mediaProjectionCallback.onStart(mediaProjectionInfo)
        mediaProjectionCallback.onStop(mediaProjectionInfo)

        assertFalse(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionActiveAfterInactive_true() {
        mediaProjectionCallback.onStart(mediaProjectionInfo)
        mediaProjectionCallback.onStop(mediaProjectionInfo)
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        assertTrue(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionActive_singleActivity_false() {
        setShareSingleApp()
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        assertFalse(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionActive_sysuiExempt_false() {
        // SystemUi context packge name is exempt, but in test scenarios its
        // com.android.systemui.tests so use that instead of hardcoding
        whenever(mediaProjectionInfo.packageName).thenReturn(mContext.packageName)
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        assertFalse(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionActive_bugReportHandlerExempt_false() {
        whenever(mediaProjectionInfo.packageName).thenReturn(BUGREPORT_PACKAGE_NAME)
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        assertFalse(controller.isSensitiveStateActive)
    }

    @Test
    fun isSensitiveStateActive_projectionActive_disabledViaDevOption_false() {
        setDisabledViaDeveloperOption()
        mediaProjectionCallback.onStart(mediaProjectionInfo)

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
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        val notificationEntry = setupNotificationEntry(TEST_PACKAGE_NAME)

        assertFalse(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_fgsNotificationFromProjectionApp_false() {
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        val notificationEntry = setupFgsNotificationEntry(TEST_PROJECTION_PACKAGE_NAME)

        assertFalse(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_fgsNotificationNotFromProjectionApp_true() {
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        val notificationEntry = setupFgsNotificationEntry(TEST_PACKAGE_NAME)

        assertTrue(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_notFgsNotification_true() {
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        val notificationEntry = setupNotificationEntry(TEST_PROJECTION_PACKAGE_NAME)

        assertTrue(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_sysuiExempt_false() {
        // SystemUi context packge name is exempt, but in test scenarios its
        // com.android.systemui.tests so use that instead of hardcoding
        whenever(mediaProjectionInfo.packageName).thenReturn(mContext.packageName)
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        val notificationEntry = setupNotificationEntry(TEST_PACKAGE_NAME, false)

        assertFalse(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_bugReportHandlerExempt_false() {
        whenever(mediaProjectionInfo.packageName).thenReturn(BUGREPORT_PACKAGE_NAME)
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        val notificationEntry = setupNotificationEntry(TEST_PACKAGE_NAME, false)

        assertFalse(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_disabledViaDevOption_false() {
        setDisabledViaDeveloperOption()
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        val notificationEntry = setupNotificationEntry(TEST_PROJECTION_PACKAGE_NAME)

        assertFalse(controller.shouldProtectNotification(notificationEntry))
    }
    @Test
    fun shouldProtectNotification_projectionActive_publicNotification_false() {
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        // App marked notification visibility as public
        val notificationEntry = setupPublicNotificationEntry(TEST_PROJECTION_PACKAGE_NAME)

        assertFalse(controller.shouldProtectNotification(notificationEntry))
    }

    @Test
    fun shouldProtectNotification_projectionActive_publicNotificationUserChannelOverride_true() {
        mediaProjectionCallback.onStart(mediaProjectionInfo)

        val notificationEntry =
            setupPublicNotificationEntryWithUserOverriddenChannel(TEST_PROJECTION_PACKAGE_NAME)

        assertTrue(controller.shouldProtectNotification(notificationEntry))
    }

    private fun setDisabledViaDeveloperOption() {
        globalSettings.putInt(DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS, 1)

        // Process pending work that gets current developer option global setting
        executor.runAllReady()
    }

    private fun setShareFullScreen() {
        whenever(mediaProjectionInfo.packageName).thenReturn(TEST_PROJECTION_PACKAGE_NAME)
        whenever(mediaProjectionInfo.launchCookie).thenReturn(null)
    }

    private fun setShareSingleApp() {
        whenever(mediaProjectionInfo.packageName).thenReturn(TEST_PROJECTION_PACKAGE_NAME)
        whenever(mediaProjectionInfo.launchCookie).thenReturn(ActivityOptions.LaunchCookie())
    }

    private fun setupNotificationEntry(
        packageName: String,
        isFgs: Boolean = false,
        overrideVisibility: Boolean = false,
        overrideChannelVisibility: Boolean = false,
    ): NotificationEntry {
        val notification = Notification()
        if (isFgs) {
            notification.flags = notification.flags or FLAG_FOREGROUND_SERVICE
        }
        if (overrideVisibility) {
            // Developer has marked notification as public
            notification.visibility = VISIBILITY_PUBLIC
        }
        val notificationEntry =
            NotificationEntryBuilder().setNotification(notification).setPkg(packageName).build()
        val channel = NotificationChannel("1", "1", IMPORTANCE_HIGH)
        if (overrideChannelVisibility) {
            // User doesn't allow private notifications at the channel level
            channel.lockscreenVisibility = VISIBILITY_PRIVATE
        }
        notificationEntry.setRanking(
            RankingBuilder(notificationEntry.ranking)
                .setChannel(channel)
                .setVisibilityOverride(VISIBILITY_NO_OVERRIDE)
                .build()
        )
        return notificationEntry
    }

    private fun setupFgsNotificationEntry(packageName: String): NotificationEntry {
        return setupNotificationEntry(packageName, isFgs = true)
    }

    private fun setupPublicNotificationEntry(packageName: String): NotificationEntry {
        return setupNotificationEntry(packageName, overrideVisibility = true)
    }

    private fun setupPublicNotificationEntryWithUserOverriddenChannel(
        packageName: String
    ): NotificationEntry {
        return setupNotificationEntry(
            packageName,
            overrideVisibility = true,
            overrideChannelVisibility = true
        )
    }

    companion object {
        private const val TEST_PROJECTION_PACKAGE_NAME =
            "com.android.systemui.statusbar.policy.projectionpackage"
        private const val TEST_PACKAGE_NAME = "com.android.systemui.statusbar.policy.testpackage"
        private const val BUGREPORT_PACKAGE_NAME = "com.android.test.bugreporthandler"
    }
}
