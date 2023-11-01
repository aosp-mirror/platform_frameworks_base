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

package com.android.systemui.statusbar.notification.interruption

import android.app.ActivityManager
import android.app.Notification
import android.app.Notification.BubbleMetadata
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.VISIBILITY_NO_OVERRIDE
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.Intent
import android.content.pm.UserInfo
import android.graphics.drawable.Icon
import android.hardware.display.FakeAmbientDisplayConfiguration
import android.os.Handler
import android.os.PowerManager
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.statusbar.FakeStatusBarStateController
import com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.utils.leaks.FakeBatteryController
import com.android.systemui.utils.leaks.LeakCheckedTest
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when` as whenever

abstract class VisualInterruptionDecisionProviderTestBase : SysuiTestCase() {
    private val leakCheck = LeakCheckedTest.SysuiLeakCheck()

    protected val ambientDisplayConfiguration = FakeAmbientDisplayConfiguration(context)
    protected val batteryController = FakeBatteryController(leakCheck)
    protected val deviceProvisionedController: DeviceProvisionedController = mock()
    protected val flags: NotifPipelineFlags = mock()
    protected val headsUpManager: HeadsUpManager = mock()
    protected val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider =
        mock()
    protected val keyguardStateController: KeyguardStateController = mock()
    protected val logger: NotificationInterruptLogger = mock()
    protected val mainHandler: Handler = mock()
    protected val powerManager: PowerManager = mock()
    protected val statusBarStateController = FakeStatusBarStateController()
    protected val uiEventLogger = UiEventLoggerFake()
    protected val userTracker = FakeUserTracker()

    protected abstract val provider: VisualInterruptionDecisionProvider

    @Before
    fun setUp() {
        val user = UserInfo(ActivityManager.getCurrentUser(), "Current user", /* flags = */ 0)
        userTracker.set(listOf(user), /* currentUserIndex = */ 0)

        whenever(headsUpManager.isSnoozed(any())).thenReturn(false)
        whenever(keyguardNotificationVisibilityProvider.shouldHideNotification(any()))
            .thenReturn(false)
    }

    @Test
    fun testShouldPeek() {
        ensureStateForPeek()

        assertTrue(provider.makeUnloggedHeadsUpDecision(createPeekEntry()).shouldInterrupt)
    }

    @Test
    fun testShouldPulse() {
        ensureStateForPulse()

        assertTrue(provider.makeUnloggedHeadsUpDecision(createPulseEntry()).shouldInterrupt)
    }

    @Test
    fun testShouldFsi_awake() {
        ensureStateForAwakeFsi()

        assertTrue(provider.makeUnloggedFullScreenIntentDecision(createFsiEntry()).shouldInterrupt)
    }

    @Test
    fun testShouldFsi_dreaming() {
        ensureStateForDreamingFsi()

        assertTrue(provider.makeUnloggedFullScreenIntentDecision(createFsiEntry()).shouldInterrupt)
    }

    @Test
    fun testShouldFsi_keyguard() {
        ensureStateForKeyguardFsi()

        assertTrue(provider.makeUnloggedFullScreenIntentDecision(createFsiEntry()).shouldInterrupt)
    }

    @Test
    fun testShouldBubble() {
        assertTrue(provider.makeAndLogBubbleDecision(createBubbleEntry()).shouldInterrupt)
    }

    private fun ensureStateForPeek() {
        whenever(powerManager.isScreenOn).thenReturn(true)
        statusBarStateController.dozing = false
        statusBarStateController.dreaming = false
    }

    private fun ensureStateForPulse() {
        ambientDisplayConfiguration.fakePulseOnNotificationEnabled = true
        batteryController.setIsAodPowerSave(false)
        statusBarStateController.dozing = true
    }

    private fun ensureStateForAwakeFsi() {
        whenever(powerManager.isInteractive).thenReturn(false)
        statusBarStateController.dreaming = false
        statusBarStateController.state = SHADE
    }

    private fun ensureStateForDreamingFsi() {
        whenever(powerManager.isInteractive).thenReturn(true)
        statusBarStateController.dreaming = true
        statusBarStateController.state = SHADE
    }

    private fun ensureStateForKeyguardFsi() {
        whenever(powerManager.isInteractive).thenReturn(true)
        statusBarStateController.dreaming = false
        statusBarStateController.state = KEYGUARD
    }

    private fun createNotif(
        hasFsi: Boolean = false,
        bubbleMetadata: BubbleMetadata? = null
    ): Notification {
        return Notification.Builder(context, TEST_CHANNEL_ID)
            .apply {
                setContentTitle(TEST_CONTENT_TITLE)
                setContentText(TEST_CONTENT_TEXT)

                if (hasFsi) {
                    setFullScreenIntent(mock(), /* highPriority = */ true)
                }

                if (bubbleMetadata != null) {
                    setBubbleMetadata(bubbleMetadata)
                }
            }
            .setContentTitle(TEST_CONTENT_TITLE)
            .setContentText(TEST_CONTENT_TEXT)
            .build()
    }

    private fun createBubbleMetadata(): BubbleMetadata {
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                /* requestCode = */ 0,
                Intent().setPackage(context.packageName),
                FLAG_MUTABLE
            )

        val icon = Icon.createWithResource(context.resources, R.drawable.android)

        return BubbleMetadata.Builder(pendingIntent, icon).build()
    }

    private fun createEntry(
        notif: Notification,
        importance: Int = IMPORTANCE_DEFAULT,
        canBubble: Boolean? = null
    ): NotificationEntry {
        return NotificationEntryBuilder()
            .apply {
                setPkg(TEST_PACKAGE)
                setOpPkg(TEST_PACKAGE)
                setTag(TEST_TAG)
                setChannel(NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, importance))
                setNotification(notif)
                setImportance(importance)

                if (canBubble != null) {
                    setCanBubble(canBubble)
                }
            }
            .build()
    }

    private fun createPeekEntry() = createEntry(notif = createNotif(), importance = IMPORTANCE_HIGH)

    private fun createPulseEntry() =
        createEntry(notif = createNotif(), importance = IMPORTANCE_HIGH).also {
            modifyRanking(it).setVisibilityOverride(VISIBILITY_NO_OVERRIDE).build()
        }

    private fun createFsiEntry() =
        createEntry(notif = createNotif(hasFsi = true), importance = IMPORTANCE_HIGH)

    private fun createBubbleEntry() =
        createEntry(
            notif = createNotif(bubbleMetadata = createBubbleMetadata()),
            importance = IMPORTANCE_HIGH,
            canBubble = true
        )
}

private const val TEST_CONTENT_TITLE = "Test Content Title"
private const val TEST_CONTENT_TEXT = "Test content text"
private const val TEST_CHANNEL_ID = "test_channel"
private const val TEST_CHANNEL_NAME = "Test Channel"
private const val TEST_PACKAGE = "test_package"
private const val TEST_TAG = "test_tag"
