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
import android.content.Context
import android.content.Intent
import android.content.pm.UserInfo
import android.graphics.drawable.Icon
import android.hardware.display.FakeAmbientDisplayConfiguration
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED
import android.provider.Settings.Global.HEADS_UP_ON
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
import com.android.systemui.util.settings.FakeGlobalSettings
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.utils.leaks.FakeBatteryController
import com.android.systemui.utils.leaks.LeakCheckedTest
import junit.framework.Assert.assertFalse
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
    protected val globalSettings = FakeGlobalSettings()
    protected val headsUpManager: HeadsUpManager = mock()
    protected val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider =
        mock()
    protected val keyguardStateController: KeyguardStateController = mock()
    protected val logger: NotificationInterruptLogger = mock()
    protected val mainHandler: Handler = mock()
    protected val powerManager: PowerManager = mock()
    protected val statusBarStateController = FakeStatusBarStateController()
    protected val systemClock = FakeSystemClock()
    protected val uiEventLogger = UiEventLoggerFake()
    protected val userTracker = FakeUserTracker()

    protected abstract val provider: VisualInterruptionDecisionProvider

    private val neverSuppresses = object : NotificationInterruptSuppressor {}

    private val alwaysSuppressesInterruptions =
        object : NotificationInterruptSuppressor {
            override fun suppressInterruptions(entry: NotificationEntry?) = true
        }

    private val alwaysSuppressesAwakeInterruptions =
        object : NotificationInterruptSuppressor {
            override fun suppressAwakeInterruptions(entry: NotificationEntry?) = true
        }

    private val alwaysSuppressesAwakeHeadsUp =
        object : NotificationInterruptSuppressor {
            override fun suppressAwakeHeadsUp(entry: NotificationEntry?) = true
        }

    @Before
    fun setUp() {
        globalSettings.putInt(HEADS_UP_NOTIFICATIONS_ENABLED, HEADS_UP_ON)

        val user = UserInfo(ActivityManager.getCurrentUser(), "Current user", /* flags = */ 0)
        userTracker.set(listOf(user), /* currentUserIndex = */ 0)

        whenever(keyguardNotificationVisibilityProvider.shouldHideNotification(any()))
            .thenReturn(false)
    }

    @Test
    fun testShouldPeek() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldPeek_defaultLegacySuppressor() {
        ensurePeekState()
        provider.addLegacySuppressor(neverSuppresses)
        assertShouldHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldNotPeek_legacySuppressInterruptions() {
        ensurePeekState()
        provider.addLegacySuppressor(alwaysSuppressesInterruptions)
        assertShouldNotHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldNotPeek_legacySuppressAwakeInterruptions() {
        ensurePeekState()
        provider.addLegacySuppressor(alwaysSuppressesAwakeInterruptions)
        assertShouldNotHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldNotPeek_legacySuppressAwakeHeadsUp() {
        ensurePeekState()
        provider.addLegacySuppressor(alwaysSuppressesAwakeHeadsUp)
        assertShouldNotHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldPulse() {
        ensurePulseState()
        assertShouldHeadsUp(buildPulseEntry())
    }

    @Test
    fun testShouldPulse_defaultLegacySuppressor() {
        ensurePulseState()
        provider.addLegacySuppressor(neverSuppresses)
        assertShouldHeadsUp(buildPulseEntry())
    }

    @Test
    fun testShouldNotPulse_legacySuppressInterruptions() {
        ensurePulseState()
        provider.addLegacySuppressor(alwaysSuppressesInterruptions)
        assertShouldNotHeadsUp(buildPulseEntry())
    }

    @Test
    fun testShouldPulse_legacySuppressAwakeInterruptions() {
        ensurePulseState()
        provider.addLegacySuppressor(alwaysSuppressesAwakeInterruptions)
        assertShouldHeadsUp(buildPulseEntry())
    }

    @Test
    fun testShouldPulse_legacySuppressAwakeHeadsUp() {
        ensurePulseState()
        provider.addLegacySuppressor(alwaysSuppressesAwakeHeadsUp)
        assertShouldHeadsUp(buildPulseEntry())
    }

    @Test
    fun testShouldBubble() {
        ensureBubbleState()
        assertShouldBubble(buildBubbleEntry())
    }

    @Test
    fun testShouldBubble_defaultLegacySuppressor() {
        ensureBubbleState()
        provider.addLegacySuppressor(neverSuppresses)
        assertShouldBubble(buildBubbleEntry())
    }

    @Test
    fun testShouldNotBubble_legacySuppressInterruptions() {
        ensureBubbleState()
        provider.addLegacySuppressor(alwaysSuppressesInterruptions)
        assertShouldNotBubble(buildBubbleEntry())
    }

    @Test
    fun testShouldNotBubble_legacySuppressAwakeInterruptions() {
        ensureBubbleState()
        provider.addLegacySuppressor(alwaysSuppressesAwakeInterruptions)
        assertShouldNotBubble(buildBubbleEntry())
    }

    @Test
    fun testShouldBubble_legacySuppressAwakeHeadsUp() {
        ensureBubbleState()
        provider.addLegacySuppressor(alwaysSuppressesAwakeHeadsUp)
        assertShouldBubble(buildBubbleEntry())
    }

    @Test
    fun testShouldFsi_notInteractive() {
        ensureNotInteractiveFsiState()
        assertShouldFsi(buildFsiEntry())
    }

    @Test
    fun testShouldFsi_dreaming() {
        ensureDreamingFsiState()
        assertShouldFsi(buildFsiEntry())
    }

    @Test
    fun testShouldFsi_keyguard() {
        ensureKeyguardFsiState()
        assertShouldFsi(buildFsiEntry())
    }

    private data class State(
        var hunSnoozed: Boolean? = null,
        var isAodPowerSave: Boolean? = null,
        var isDozing: Boolean? = null,
        var isDreaming: Boolean? = null,
        var isInteractive: Boolean? = null,
        var isScreenOn: Boolean? = null,
        var keyguardShouldHideNotification: Boolean? = null,
        var pulseOnNotificationsEnabled: Boolean? = null,
        var statusBarState: Int? = null,
    )

    private fun setState(state: State): Unit =
        state.run {
            hunSnoozed?.let { whenever(headsUpManager.isSnoozed(TEST_PACKAGE)).thenReturn(it) }

            isAodPowerSave?.let { batteryController.setIsAodPowerSave(it) }

            isDozing?.let { statusBarStateController.dozing = it }

            isDreaming?.let { statusBarStateController.dreaming = it }

            isInteractive?.let { whenever(powerManager.isInteractive).thenReturn(it) }

            isScreenOn?.let { whenever(powerManager.isScreenOn).thenReturn(it) }

            keyguardShouldHideNotification?.let {
                whenever(keyguardNotificationVisibilityProvider.shouldHideNotification(any()))
                    .thenReturn(it)
            }

            pulseOnNotificationsEnabled?.let {
                ambientDisplayConfiguration.fakePulseOnNotificationEnabled = it
            }

            statusBarState?.let { statusBarStateController.state = it }
        }

    private fun ensureState(block: State.() -> Unit) =
        State()
            .apply {
                keyguardShouldHideNotification = false
                apply(block)
            }
            .run(this::setState)

    private fun ensurePeekState(block: State.() -> Unit = {}) = ensureState {
        hunSnoozed = false
        isDozing = false
        isDreaming = false
        isScreenOn = true
        run(block)
    }

    private fun ensurePulseState(block: State.() -> Unit = {}) = ensureState {
        isAodPowerSave = false
        isDozing = true
        pulseOnNotificationsEnabled = true
        run(block)
    }

    private fun ensureBubbleState(block: State.() -> Unit = {}) = ensureState(block)

    private fun ensureNotInteractiveFsiState(block: State.() -> Unit = {}) = ensureState {
        isDreaming = false
        isInteractive = false
        statusBarState = SHADE
        run(block)
    }

    private fun ensureDreamingFsiState(block: State.() -> Unit = {}) = ensureState {
        isDreaming = true
        isInteractive = true
        statusBarState = SHADE
        run(block)
    }

    private fun ensureKeyguardFsiState(block: State.() -> Unit = {}) = ensureState {
        isDreaming = false
        isInteractive = true
        statusBarState = KEYGUARD
        run(block)
    }

    private fun assertShouldHeadsUp(entry: NotificationEntry) =
        provider.makeUnloggedHeadsUpDecision(entry).let {
            assertTrue("unexpected suppressed HUN: ${it.logReason}", it.shouldInterrupt)
        }

    private fun assertShouldNotHeadsUp(entry: NotificationEntry) =
        provider.makeUnloggedHeadsUpDecision(entry).let {
            assertFalse("unexpected unsuppressed HUN: ${it.logReason}", it.shouldInterrupt)
        }

    private fun assertShouldBubble(entry: NotificationEntry) =
        provider.makeAndLogBubbleDecision(entry).let {
            assertTrue("unexpected suppressed bubble: ${it.logReason}", it.shouldInterrupt)
        }

    private fun assertShouldNotBubble(entry: NotificationEntry) =
        provider.makeAndLogBubbleDecision(entry).let {
            assertFalse("unexpected unsuppressed bubble: ${it.logReason}", it.shouldInterrupt)
        }

    private fun assertShouldFsi(entry: NotificationEntry) =
        provider.makeUnloggedFullScreenIntentDecision(entry).let {
            assertTrue("unexpected suppressed FSI: ${it.logReason}", it.shouldInterrupt)
        }

    private fun assertShouldNotFsi(entry: NotificationEntry) =
        provider.makeUnloggedFullScreenIntentDecision(entry).let {
            assertFalse("unexpected unsuppressed FSI: ${it.logReason}", it.shouldInterrupt)
        }

    private class EntryBuilder(val context: Context) {
        var importance = IMPORTANCE_DEFAULT
        var suppressedVisualEffects: Int? = null
        var whenMs: Long? = null
        var visibilityOverride: Int? = null
        var hasFsi = false
        var canBubble: Boolean? = null
        var hasBubbleMetadata = false
        var bubbleSuppressNotification: Boolean? = null

        private fun buildBubbleMetadata() =
            BubbleMetadata.Builder(
                    PendingIntent.getActivity(
                        context,
                        /* requestCode = */ 0,
                        Intent().setPackage(context.packageName),
                        FLAG_MUTABLE
                    ),
                    Icon.createWithResource(context.resources, R.drawable.android)
                )
                .apply { bubbleSuppressNotification?.let { setSuppressNotification(it) } }
                .build()

        fun build() =
            Notification.Builder(context, TEST_CHANNEL_ID)
                .apply {
                    setContentTitle(TEST_CONTENT_TITLE)
                    setContentText(TEST_CONTENT_TEXT)

                    if (hasFsi) {
                        setFullScreenIntent(mock(), /* highPriority = */ true)
                    }

                    whenMs?.let { setWhen(it) }

                    if (hasBubbleMetadata) {
                        setBubbleMetadata(buildBubbleMetadata())
                    }
                }
                .build()
                .let { NotificationEntryBuilder().setNotification(it) }
                .apply {
                    setPkg(TEST_PACKAGE)
                    setOpPkg(TEST_PACKAGE)
                    setTag(TEST_TAG)

                    setImportance(importance)
                    setChannel(NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, importance))

                    canBubble?.let { setCanBubble(it) }
                }
                .build()!!
                .also {
                    modifyRanking(it)
                        .apply {
                            suppressedVisualEffects?.let { setSuppressedVisualEffects(it) }
                            visibilityOverride?.let { setVisibilityOverride(it) }
                        }
                        .build()
                }
    }

    private fun buildEntry(block: EntryBuilder.() -> Unit) =
        EntryBuilder(context).also(block).build()

    private fun buildPeekEntry(block: EntryBuilder.() -> Unit = {}) = buildEntry {
        importance = IMPORTANCE_HIGH
        run(block)
    }

    private fun buildPulseEntry(block: EntryBuilder.() -> Unit = {}) = buildEntry {
        importance = IMPORTANCE_DEFAULT
        visibilityOverride = VISIBILITY_NO_OVERRIDE
        run(block)
    }

    private fun buildFsiEntry(block: EntryBuilder.() -> Unit = {}) = buildEntry {
        importance = IMPORTANCE_HIGH
        hasFsi = true
        run(block)
    }

    private fun buildBubbleEntry(block: EntryBuilder.() -> Unit = {}) = buildEntry {
        canBubble = true
        hasBubbleMetadata = true
        run(block)
    }

    private fun whenAgo(whenAgeMs: Long) = systemClock.currentTimeMillis() - whenAgeMs
}

private const val TEST_CONTENT_TITLE = "Test Content Title"
private const val TEST_CONTENT_TEXT = "Test content text"
private const val TEST_CHANNEL_ID = "test_channel"
private const val TEST_CHANNEL_NAME = "Test Channel"
private const val TEST_PACKAGE = "test_package"
private const val TEST_TAG = "test_tag"
