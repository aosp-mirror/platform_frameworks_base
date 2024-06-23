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
import android.app.Notification.EXTRA_COLORIZED
import android.app.Notification.EXTRA_TEMPLATE
import android.app.Notification.FLAG_BUBBLE
import android.app.Notification.FLAG_CAN_COLORIZE
import android.app.Notification.FLAG_FOREGROUND_SERVICE
import android.app.Notification.FLAG_FSI_REQUESTED_BUT_DENIED
import android.app.Notification.FLAG_USER_INITIATED_JOB
import android.app.Notification.GROUP_ALERT_ALL
import android.app.Notification.GROUP_ALERT_CHILDREN
import android.app.Notification.GROUP_ALERT_SUMMARY
import android.app.Notification.VISIBILITY_PRIVATE
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT
import android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
import android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK
import android.app.NotificationManager.VISIBILITY_NO_OVERRIDE
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.Context
import android.content.Intent
import android.content.pm.UserInfo
import android.graphics.drawable.Icon
import android.hardware.display.FakeAmbientDisplayConfiguration
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED
import android.provider.Settings.Global.HEADS_UP_OFF
import android.provider.Settings.Global.HEADS_UP_ON
import com.android.internal.logging.UiEventLogger.UiEventEnum
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.broadcast.FakeBroadcastDispatcher
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogcatEchoTracker
import com.android.systemui.log.core.LogLevel
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.statusbar.FakeStatusBarStateController
import com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.MAX_HUN_WHEN_AGE_MS
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.FSI_SUPPRESSED_NO_HUN_OR_KEYGUARD
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.FSI_SUPPRESSED_SUPPRESSIVE_BUBBLE_METADATA
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.FSI_SUPPRESSED_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.HUN_SUPPRESSED_OLD_WHEN
import com.android.systemui.statusbar.policy.FakeDeviceProvisionedController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.FakeEventLog
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.settings.FakeGlobalSettings
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.utils.leaks.FakeBatteryController
import com.android.systemui.utils.leaks.FakeKeyguardStateController
import com.android.systemui.utils.leaks.LeakCheckedTest
import com.android.systemui.utils.os.FakeHandler
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when` as whenever

abstract class VisualInterruptionDecisionProviderTestBase : SysuiTestCase() {
    private val fakeLogBuffer =
        LogBuffer(
            name = "FakeLog",
            maxSize = 1,
            logcatEchoTracker =
                object : LogcatEchoTracker {
                    override fun isBufferLoggable(bufferName: String, level: LogLevel): Boolean =
                        true

                    override fun isTagLoggable(tagName: String, level: LogLevel): Boolean = true
                },
            systrace = false
        )

    private val leakCheck = LeakCheckedTest.SysuiLeakCheck()

    protected val ambientDisplayConfiguration = FakeAmbientDisplayConfiguration(context)
    protected val batteryController = FakeBatteryController(leakCheck)
    protected val deviceProvisionedController = FakeDeviceProvisionedController()
    protected val eventLog = FakeEventLog()
    protected val flags: NotifPipelineFlags = mock()
    protected val globalSettings =
        FakeGlobalSettings().also { it.putInt(HEADS_UP_NOTIFICATIONS_ENABLED, HEADS_UP_ON) }
    protected val headsUpManager: HeadsUpManager = mock()
    protected val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider =
        mock()
    protected val keyguardStateController = FakeKeyguardStateController(leakCheck)
    protected val mainHandler = FakeHandler(Looper.getMainLooper())
    protected val newLogger = VisualInterruptionDecisionLogger(fakeLogBuffer)
    protected val oldLogger = NotificationInterruptLogger(fakeLogBuffer)
    protected val powerManager: PowerManager = mock()
    protected val statusBarStateController = FakeStatusBarStateController()
    protected val systemClock = FakeSystemClock()
    protected val uiEventLogger = UiEventLoggerFake()
    protected val userTracker = FakeUserTracker()
    protected val avalancheProvider: AvalancheProvider = mock()

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
        val user = UserInfo(ActivityManager.getCurrentUser(), "Current user", /* flags = */ 0)
        userTracker.set(listOf(user), /* currentUserIndex = */ 0)

        provider.start()
    }

    @Test
    fun testShouldPeek() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_settingDisabled() {
        ensurePeekState { hunSettingEnabled = false }
        assertShouldNotHeadsUp(buildPeekEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_packageSnoozed_withoutFsi() {
        ensurePeekState { hunSnoozed = true }
        assertShouldNotHeadsUp(buildPeekEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_packageSnoozed_withFsi() {
        val entry = buildFsiEntry()
        forEachPeekableFsiState {
            ensurePeekState { hunSnoozed = true }
            assertShouldHeadsUp(entry)

            // The old code logs a UiEvent when a HUN snooze is bypassed because the notification
            // has an FSI, but that doesn't fit into the new code's suppressor-based logic, so we're
            // not reimplementing it.
            if (provider !is NotificationInterruptStateProviderWrapper) {
                assertNoEventsLogged()
            }
        }
    }

    @Test
    fun testShouldNotPeek_alreadyBubbled() {
        ensurePeekState { statusBarState = SHADE }
        assertShouldNotHeadsUp(buildPeekEntry { isBubble = true })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_isBubble_shadeLocked() {
        ensurePeekState { statusBarState = SHADE_LOCKED }
        assertShouldHeadsUp(buildPeekEntry { isBubble = true })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_isBubble_keyguard() {
        ensurePeekState { statusBarState = KEYGUARD }
        assertShouldHeadsUp(buildPeekEntry { isBubble = true })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_dnd() {
        ensurePeekState()
        assertShouldNotHeadsUp(buildPeekEntry { suppressedVisualEffects = SUPPRESSED_EFFECT_PEEK })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_notImportant() {
        ensurePeekState()
        assertShouldNotHeadsUp(buildPeekEntry { importance = IMPORTANCE_DEFAULT })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_screenOff() {
        ensurePeekState { isScreenOn = false }
        assertShouldNotHeadsUp(buildPeekEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_dreaming() {
        ensurePeekState { isDreaming = true }
        assertShouldNotHeadsUp(buildPeekEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_oldWhen() {
        ensurePeekState()
        assertShouldNotHeadsUp(buildPeekEntry { whenMs = whenAgo(MAX_HUN_WHEN_AGE_MS) })
    }

    @Test
    fun testLogsHunOldWhen() {
        assertNoEventsLogged()

        ensurePeekState()
        val entry = buildPeekEntry { whenMs = whenAgo(MAX_HUN_WHEN_AGE_MS) }

        // The old code logs the "old when" UiEvent unconditionally, so don't expect that it hasn't.
        if (provider !is NotificationInterruptStateProviderWrapper) {
            provider.makeUnloggedHeadsUpDecision(entry)
            assertNoEventsLogged()
        }

        provider.makeAndLogHeadsUpDecision(entry)
        assertUiEventLogged(HUN_SUPPRESSED_OLD_WHEN, entry.sbn.uid, entry.sbn.packageName)
        assertNoSystemEventLogged()
    }

    @Test
    fun testShouldPeek_oldWhen_now() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry { whenMs = whenAgo(0) })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_oldWhen_notOldEnough() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry { whenMs = whenAgo(MAX_HUN_WHEN_AGE_MS - 1) })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_oldWhen_zeroWhen() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry { whenMs = 0L })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_oldWhen_negativeWhen() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry { whenMs = -1L })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_oldWhen_fullScreenIntent() {
        ensurePeekState()
        assertShouldHeadsUp(buildFsiEntry { whenMs = whenAgo(MAX_HUN_WHEN_AGE_MS) })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_oldWhen_foregroundService() {
        ensurePeekState()
        assertShouldHeadsUp(
            buildPeekEntry {
                whenMs = whenAgo(MAX_HUN_WHEN_AGE_MS)
                isForegroundService = true
            }
        )
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_oldWhen_userInitiatedJob() {
        ensurePeekState()
        assertShouldHeadsUp(
            buildPeekEntry {
                whenMs = whenAgo(MAX_HUN_WHEN_AGE_MS)
                isUserInitiatedJob = true
            }
        )
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_hiddenOnKeyguard() {
        ensurePeekState({ keyguardShouldHideNotification = true })
        assertShouldNotHeadsUp(buildPeekEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPeek_defaultLegacySuppressor() {
        ensurePeekState()
        withLegacySuppressor(neverSuppresses) { assertShouldHeadsUp(buildPeekEntry()) }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_legacySuppressInterruptions() {
        ensurePeekState()
        withLegacySuppressor(alwaysSuppressesInterruptions) {
            assertShouldNotHeadsUp(buildPeekEntry())
        }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_legacySuppressAwakeInterruptions() {
        ensurePeekState()
        withLegacySuppressor(alwaysSuppressesAwakeInterruptions) {
            assertShouldNotHeadsUp(buildPeekEntry())
        }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPeek_legacySuppressAwakeHeadsUp() {
        ensurePeekState()
        withLegacySuppressor(alwaysSuppressesAwakeHeadsUp) {
            assertShouldNotHeadsUp(buildPeekEntry())
        }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPulse() {
        ensurePulseState()
        assertShouldHeadsUp(buildPulseEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPulse_disabled() {
        ensurePulseState { pulseOnNotificationsEnabled = false }
        assertShouldNotHeadsUp(buildPulseEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPulse_batterySaver() {
        ensurePulseState { isAodPowerSave = true }
        assertShouldNotHeadsUp(buildPulseEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPulse_effectSuppressed() {
        ensurePulseState()
        assertShouldNotHeadsUp(
            buildPulseEntry { suppressedVisualEffects = SUPPRESSED_EFFECT_AMBIENT }
        )
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPulse_visibilityOverridePrivate() {
        ensurePulseState()
        assertShouldNotHeadsUp(buildPulseEntry { visibilityOverride = VISIBILITY_PRIVATE })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPulse_importanceLow() {
        ensurePulseState()
        assertShouldNotHeadsUp(buildPulseEntry { importance = IMPORTANCE_LOW })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPulse_hiddenOnKeyguard() {
        ensurePulseState({ keyguardShouldHideNotification = true })
        assertShouldNotHeadsUp(buildPulseEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPulse_defaultLegacySuppressor() {
        ensurePulseState()
        withLegacySuppressor(neverSuppresses) { assertShouldHeadsUp(buildPulseEntry()) }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotPulse_legacySuppressInterruptions() {
        ensurePulseState()
        withLegacySuppressor(alwaysSuppressesInterruptions) {
            assertShouldNotHeadsUp(buildPulseEntry())
        }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPulse_legacySuppressAwakeInterruptions() {
        ensurePulseState()
        withLegacySuppressor(alwaysSuppressesAwakeInterruptions) {
            assertShouldHeadsUp(buildPulseEntry())
        }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldPulse_legacySuppressAwakeHeadsUp() {
        ensurePulseState()
        withLegacySuppressor(alwaysSuppressesAwakeHeadsUp) {
            assertShouldHeadsUp(buildPulseEntry())
        }
        assertNoEventsLogged()
    }

    private fun withPeekAndPulseEntry(
        extendEntry: EntryBuilder.() -> Unit,
        block: (NotificationEntry) -> Unit
    ) {
        ensurePeekState()
        block(buildPeekEntry(extendEntry))

        ensurePulseState()
        block(buildPulseEntry(extendEntry))
    }

    @Test
    fun testShouldNotHeadsUp_suppressiveGroupAlertBehavior() {
        withPeekAndPulseEntry({
            isGrouped = true
            isGroupSummary = false
            groupAlertBehavior = GROUP_ALERT_SUMMARY
        }) {
            assertShouldNotHeadsUp(it)
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldHeadsUp_suppressiveGroupAlertBehavior_notSuppressive() {
        withPeekAndPulseEntry({
            isGrouped = true
            isGroupSummary = false
            groupAlertBehavior = GROUP_ALERT_CHILDREN
        }) {
            assertShouldHeadsUp(it)
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldHeadsUp_suppressiveGroupAlertBehavior_notGrouped() {
        withPeekAndPulseEntry({
            isGrouped = false
            isGroupSummary = false
            groupAlertBehavior = GROUP_ALERT_SUMMARY
        }) {
            assertShouldHeadsUp(it)
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldNotHeadsUp_justLaunchedFsi() {
        withPeekAndPulseEntry({ hasJustLaunchedFsi = true }) {
            assertShouldNotHeadsUp(it)
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldBubble_withIntentAndIcon() {
        ensureBubbleState()
        assertShouldBubble(buildBubbleEntry { bubbleIsShortcut = false })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldBubble_withShortcut() {
        ensureBubbleState()
        assertShouldBubble(buildBubbleEntry { bubbleIsShortcut = true })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldBubble_suppressiveGroupAlertBehavior() {
        ensureBubbleState()
        assertShouldBubble(
            buildBubbleEntry {
                isGrouped = true
                isGroupSummary = false
                groupAlertBehavior = GROUP_ALERT_SUMMARY
            }
        )
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotBubble_notABubble() {
        ensureBubbleState()
        assertShouldNotBubble(
            buildBubbleEntry {
                isBubble = false
                hasBubbleMetadata = false
            }
        )
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotBubble_missingBubbleMetadata() {
        ensureBubbleState()
        assertShouldNotBubble(buildBubbleEntry { hasBubbleMetadata = false })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotBubble_notAllowedToBubble() {
        ensureBubbleState()
        assertShouldNotBubble(buildBubbleEntry { canBubble = false })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldBubble_defaultLegacySuppressor() {
        ensureBubbleState()
        withLegacySuppressor(neverSuppresses) { assertShouldBubble(buildBubbleEntry()) }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotBubble_legacySuppressInterruptions() {
        ensureBubbleState()
        withLegacySuppressor(alwaysSuppressesInterruptions) {
            assertShouldNotBubble(buildBubbleEntry())
        }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotBubble_legacySuppressAwakeInterruptions() {
        ensureBubbleState()
        withLegacySuppressor(alwaysSuppressesAwakeInterruptions) {
            assertShouldNotBubble(buildBubbleEntry())
        }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldBubble_legacySuppressAwakeHeadsUp() {
        ensureBubbleState()
        withLegacySuppressor(alwaysSuppressesAwakeHeadsUp) {
            assertShouldBubble(buildBubbleEntry())
        }
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotBubble_hiddenOnKeyguard() {
        ensureBubbleState({ keyguardShouldHideNotification = true })
        assertShouldNotBubble(buildBubbleEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotBubble_bubbleAppSuspended() {
        ensureBubbleState()
        assertShouldNotBubble(buildBubbleEntry { packageSuspended = true })
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotFsi_noFullScreenIntent() {
        forEachFsiState {
            assertShouldNotFsi(buildFsiEntry { hasFsi = false })
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldNotFsi_showStickyHun() {
        forEachFsiState {
            assertShouldNotFsi(
                buildFsiEntry {
                    hasFsi = false
                    isStickyAndNotDemoted = true
                }
            )
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldNotFsi_onlyDnd() {
        forEachFsiState {
            assertShouldNotFsi(
                buildFsiEntry { suppressedVisualEffects = SUPPRESSED_EFFECT_FULL_SCREEN_INTENT },
                expectWouldInterruptWithoutDnd = true
            )
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldNotFsi_notImportantEnough() {
        forEachFsiState {
            assertShouldNotFsi(buildFsiEntry { importance = IMPORTANCE_DEFAULT })
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldNotFsi_notOnlyDnd() {
        forEachFsiState {
            assertShouldNotFsi(
                buildFsiEntry {
                    suppressedVisualEffects = SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
                    importance = IMPORTANCE_DEFAULT
                },
                expectWouldInterruptWithoutDnd = false
            )
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldNotFsi_suppressiveGroupAlertBehavior() {
        forEachFsiState {
            assertShouldNotFsi(
                buildFsiEntry {
                    isGrouped = true
                    isGroupSummary = true
                    groupAlertBehavior = GROUP_ALERT_CHILDREN
                }
            )
        }
    }

    @Test
    fun testLogsFsiSuppressiveGroupAlertBehavior() {
        ensureNotInteractiveFsiState()
        val entry = buildFsiEntry {
            isGrouped = true
            isGroupSummary = true
            groupAlertBehavior = GROUP_ALERT_CHILDREN
        }

        val decision = provider.makeUnloggedFullScreenIntentDecision(entry)
        assertNoEventsLogged()

        provider.logFullScreenIntentDecision(decision)
        assertUiEventLogged(
            FSI_SUPPRESSED_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR,
            entry.sbn.uid,
            entry.sbn.packageName
        )
        assertSystemEventLogged("231322873", entry.sbn.uid, "groupAlertBehavior")
    }

    @Test
    fun testShouldFsi_suppressiveGroupAlertBehavior_notGrouped() {
        forEachFsiState {
            assertShouldFsi(
                buildFsiEntry {
                    isGrouped = false
                    isGroupSummary = true
                    groupAlertBehavior = GROUP_ALERT_CHILDREN
                }
            )
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldFsi_suppressiveGroupAlertBehavior_notSuppressive() {
        forEachFsiState {
            assertShouldFsi(
                buildFsiEntry {
                    isGrouped = true
                    isGroupSummary = true
                    groupAlertBehavior = GROUP_ALERT_ALL
                }
            )
        }
    }

    @Test
    fun testShouldNotFsi_suppressiveBubbleMetadata() {
        forEachFsiState {
            assertShouldNotFsi(
                buildFsiEntry {
                    hasBubbleMetadata = true
                    bubbleSuppressesNotification = true
                }
            )
        }
    }

    @Test
    fun testLogsFsiSuppressiveBubbleMetadata() {
        ensureNotInteractiveFsiState()
        val entry = buildFsiEntry {
            hasBubbleMetadata = true
            bubbleSuppressesNotification = true
        }

        val decision = provider.makeUnloggedFullScreenIntentDecision(entry)
        assertNoEventsLogged()

        provider.logFullScreenIntentDecision(decision)
        assertUiEventLogged(
            FSI_SUPPRESSED_SUPPRESSIVE_BUBBLE_METADATA,
            entry.sbn.uid,
            entry.sbn.packageName
        )
        assertSystemEventLogged("274759612", entry.sbn.uid, "bubbleMetadata")
    }

    @Test
    fun testShouldNotFsi_packageSuspended() {
        forEachFsiState {
            assertShouldNotFsi(buildFsiEntry { packageSuspended = true })
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldFsi_notInteractive() {
        ensureNotInteractiveFsiState()
        assertShouldFsi(buildFsiEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldFsi_dreaming() {
        ensureDreamingFsiState()
        assertShouldFsi(buildFsiEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldFsi_keyguard() {
        ensureKeyguardFsiState()
        assertShouldFsi(buildFsiEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotFsi_expectedToHun() {
        forEachPeekableFsiState {
            ensurePeekState()
            assertShouldNotFsi(buildFsiEntry())
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldNotFsi_expectedToHun_hunSnoozed() {
        forEachPeekableFsiState {
            ensurePeekState { hunSnoozed = true }
            assertShouldNotFsi(buildFsiEntry())
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldFsi_lockedShade() {
        ensureLockedShadeFsiState()
        assertShouldFsi(buildFsiEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldFsi_keyguardOccluded() {
        ensureKeyguardOccludedFsiState()
        assertShouldFsi(buildFsiEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldFsi_deviceNotProvisioned() {
        ensureDeviceNotProvisionedFsiState()
        assertShouldFsi(buildFsiEntry())
        assertNoEventsLogged()
    }

    @Test
    fun testShouldNotFsi_noHunOrKeyguard() {
        ensureNoHunOrKeyguardFsiState()
        assertShouldNotFsi(buildFsiEntry())
    }

    @Test
    fun testLogsFsiNoHunOrKeyguard() {
        ensureNoHunOrKeyguardFsiState()
        val entry = buildFsiEntry()

        val decision = provider.makeUnloggedFullScreenIntentDecision(entry)
        assertNoEventsLogged()

        provider.logFullScreenIntentDecision(decision)
        assertUiEventLogged(FSI_SUPPRESSED_NO_HUN_OR_KEYGUARD, entry.sbn.uid, entry.sbn.packageName)
        assertSystemEventLogged("231322873", entry.sbn.uid, "no hun or keyguard")
    }

    @Test
    fun testShouldFsi_defaultLegacySuppressor() {
        forEachFsiState {
            withLegacySuppressor(neverSuppresses) { assertShouldFsi(buildFsiEntry()) }
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldFsi_suppressInterruptions() {
        forEachFsiState {
            withLegacySuppressor(alwaysSuppressesInterruptions) { assertShouldFsi(buildFsiEntry()) }
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldFsi_suppressAwakeInterruptions() {
        forEachFsiState {
            withLegacySuppressor(alwaysSuppressesAwakeInterruptions) {
                assertShouldFsi(buildFsiEntry())
            }
            assertNoEventsLogged()
        }
    }

    @Test
    fun testShouldFsi_suppressAwakeHeadsUp() {
        forEachFsiState {
            withLegacySuppressor(alwaysSuppressesAwakeHeadsUp) { assertShouldFsi(buildFsiEntry()) }
            assertNoEventsLogged()
        }
    }

    protected data class State(
        var hunSettingEnabled: Boolean? = null,
        var hunSnoozed: Boolean? = null,
        var isAodPowerSave: Boolean? = null,
        var isDozing: Boolean? = null,
        var isDreaming: Boolean? = null,
        var isInteractive: Boolean? = null,
        var isScreenOn: Boolean? = null,
        var keyguardShouldHideNotification: Boolean? = null,
        var pulseOnNotificationsEnabled: Boolean? = null,
        var statusBarState: Int? = null,
        var keyguardIsShowing: Boolean = false,
        var keyguardIsOccluded: Boolean = false,
        var deviceProvisioned: Boolean = true
    )

    protected fun setState(state: State): Unit =
        state.run {
            hunSettingEnabled?.let {
                val newSetting = if (it) HEADS_UP_ON else HEADS_UP_OFF
                globalSettings.putInt(HEADS_UP_NOTIFICATIONS_ENABLED, newSetting)
            }

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

            keyguardStateController.isOccluded = keyguardIsOccluded
            keyguardStateController.isShowing = keyguardIsShowing

            deviceProvisionedController.deviceProvisioned = deviceProvisioned
        }

    protected fun ensureState(block: State.() -> Unit) =
        State()
            .apply {
                keyguardShouldHideNotification = false
                apply(block)
            }
            .run(this::setState)

    protected fun ensurePeekState(block: State.() -> Unit = {}) = ensureState {
        hunSettingEnabled = true
        hunSnoozed = false
        isDozing = false
        isDreaming = false
        isScreenOn = true
        run(block)
    }

    protected fun ensurePulseState(block: State.() -> Unit = {}) = ensureState {
        isAodPowerSave = false
        isDozing = true
        pulseOnNotificationsEnabled = true
        run(block)
    }

    protected fun ensureBubbleState(block: State.() -> Unit = {}) = ensureState(block)

    protected fun ensureNotInteractiveFsiState(block: State.() -> Unit = {}) = ensureState {
        isInteractive = false
        run(block)
    }

    protected fun ensureDreamingFsiState(block: State.() -> Unit = {}) = ensureState {
        isInteractive = true
        isDreaming = true
        run(block)
    }

    protected fun ensureKeyguardFsiState(block: State.() -> Unit = {}) = ensureState {
        isInteractive = true
        isDreaming = false
        statusBarState = KEYGUARD
        run(block)
    }

    protected fun ensureLockedShadeFsiState(block: State.() -> Unit = {}) = ensureState {
        // It is assumed *but not checked in the code* that statusBarState is SHADE_LOCKED.
        isInteractive = true
        isDreaming = false
        statusBarState = SHADE
        hunSettingEnabled = false
        keyguardIsShowing = true
        keyguardIsOccluded = false
        run(block)
    }

    protected fun ensureKeyguardOccludedFsiState(block: State.() -> Unit = {}) = ensureState {
        isInteractive = true
        isDreaming = false
        statusBarState = SHADE
        hunSettingEnabled = false
        keyguardIsShowing = true
        keyguardIsOccluded = true
        run(block)
    }

    protected fun ensureDeviceNotProvisionedFsiState(block: State.() -> Unit = {}) = ensureState {
        isInteractive = true
        isDreaming = false
        statusBarState = SHADE
        hunSettingEnabled = false
        keyguardIsShowing = false
        deviceProvisioned = false
        run(block)
    }

    protected fun ensureNoHunOrKeyguardFsiState(block: State.() -> Unit = {}) = ensureState {
        isInteractive = true
        isDreaming = false
        statusBarState = SHADE
        hunSettingEnabled = false
        keyguardIsShowing = false
        deviceProvisioned = true
        run(block)
    }

    protected fun forEachFsiState(block: () -> Unit) {
        ensureNotInteractiveFsiState()
        block()

        ensureDreamingFsiState()
        block()

        ensureKeyguardFsiState()
        block()

        ensureLockedShadeFsiState()
        block()

        ensureKeyguardOccludedFsiState()
        block()

        ensureDeviceNotProvisionedFsiState()
        block()
    }

    private fun forEachPeekableFsiState(extendState: State.() -> Unit = {}, block: () -> Unit) {
        ensureLockedShadeFsiState(extendState)
        block()

        ensureKeyguardOccludedFsiState(extendState)
        block()

        ensureDeviceNotProvisionedFsiState(extendState)
        block()
    }

    protected fun withLegacySuppressor(
        suppressor: NotificationInterruptSuppressor,
        block: () -> Unit
    ) {
        provider.addLegacySuppressor(suppressor)
        block()
        provider.removeLegacySuppressor(suppressor)
    }

    protected fun assertShouldHeadsUp(entry: NotificationEntry) =
        provider.makeAndLogHeadsUpDecision(entry).let {
            assertTrue("unexpected suppressed HUN: ${it.logReason}", it.shouldInterrupt)
        }

    protected fun assertShouldNotHeadsUp(entry: NotificationEntry) =
        provider.makeAndLogHeadsUpDecision(entry).let {
            assertFalse("unexpected unsuppressed HUN: ${it.logReason}", it.shouldInterrupt)
        }

    protected fun assertShouldBubble(entry: NotificationEntry) =
        provider.makeAndLogBubbleDecision(entry).let {
            assertTrue("unexpected suppressed bubble: ${it.logReason}", it.shouldInterrupt)
        }

    protected fun assertShouldNotBubble(entry: NotificationEntry) =
        provider.makeAndLogBubbleDecision(entry).let {
            assertFalse("unexpected unsuppressed bubble: ${it.logReason}", it.shouldInterrupt)
        }

    protected fun assertShouldFsi(entry: NotificationEntry) =
        provider.makeUnloggedFullScreenIntentDecision(entry).let {
            provider.logFullScreenIntentDecision(it)
            assertTrue("unexpected suppressed FSI: ${it.logReason}", it.shouldInterrupt)
        }

    protected fun assertShouldNotFsi(
        entry: NotificationEntry,
        expectWouldInterruptWithoutDnd: Boolean? = null
    ) =
        provider.makeUnloggedFullScreenIntentDecision(entry).let {
            provider.logFullScreenIntentDecision(it)
            assertFalse("unexpected unsuppressed FSI: ${it.logReason}", it.shouldInterrupt)
            if (expectWouldInterruptWithoutDnd != null) {
                assertEquals(
                    "unexpected wouldInterruptWithoutDnd for FSI: ${it.logReason}",
                    expectWouldInterruptWithoutDnd,
                    it.wouldInterruptWithoutDnd
                )
            }
        }

    protected class EntryBuilder(val context: Context) {
        // Set on BubbleMetadata:
        var bubbleIsShortcut = false
        var bubbleSuppressesNotification = false

        // Set on Notification.Builder:
        var whenMs: Long? = null
        var isGrouped = false
        var isGroupSummary = false
        var isCall = false
        var category: String? = null
        var groupAlertBehavior: Int? = null
        var hasBubbleMetadata = false
        var hasFsi = false

        // Set on Notification:
        var isForegroundService = false
        var isUserInitiatedJob = false
        var isBubble = false
        var isStickyAndNotDemoted = false
        var isColorized = false

        // Set on NotificationEntryBuilder:
        var importance = IMPORTANCE_DEFAULT
        var canBubble: Boolean? = null
        var isImportantConversation = false

        // Set on NotificationEntry:
        var hasJustLaunchedFsi = false

        // Set on ModifiedRankingBuilder:
        var packageSuspended = false
        var visibilityOverride: Int? = null
        var suppressedVisualEffects: Int? = null
        var isConversation = false

        private fun buildBubbleMetadata(): BubbleMetadata {
            val builder =
                if (bubbleIsShortcut) {
                    BubbleMetadata.Builder(context.packageName + ":test_shortcut_id")
                } else {
                    BubbleMetadata.Builder(
                        PendingIntent.getActivity(
                            context,
                            /* requestCode = */ 0,
                            Intent().setPackage(context.packageName),
                            FLAG_MUTABLE
                        ),
                        Icon.createWithResource(context.resources, R.drawable.android)
                    )
                }

            if (bubbleSuppressesNotification) {
                builder.setSuppressNotification(true)
            }

            return builder.build()
        }

        fun build() =
            Notification.Builder(context, TEST_CHANNEL_ID)
                .also { nb ->
                    nb.setContentTitle(TEST_CONTENT_TITLE)
                    nb.setContentText(TEST_CONTENT_TEXT)

                    whenMs?.let { nb.setWhen(it) }

                    if (isGrouped) {
                        nb.setGroup(TEST_GROUP_KEY)
                    }

                    if (isGroupSummary) {
                        nb.setGroupSummary(true)
                    }

                    if (isCall) {
                        nb.extras.putString(EXTRA_TEMPLATE, Notification.CallStyle::class.java.name)
                    }

                    if (category != null) {
                        nb.setCategory(category)
                    }
                    groupAlertBehavior?.let { nb.setGroupAlertBehavior(it) }

                    if (hasBubbleMetadata) {
                        nb.setBubbleMetadata(buildBubbleMetadata())
                    }

                    if (hasFsi) {
                        nb.setFullScreenIntent(mock(), /* highPriority = */ true)
                    }
                }
                .build()
                .also { n ->
                    if (isForegroundService) {
                        n.flags = n.flags or FLAG_FOREGROUND_SERVICE
                    }

                    if (isUserInitiatedJob) {
                        n.flags = n.flags or FLAG_USER_INITIATED_JOB
                    }

                    if (isBubble) {
                        n.flags = n.flags or FLAG_BUBBLE
                    }

                    if (isStickyAndNotDemoted) {
                        n.flags = n.flags or FLAG_FSI_REQUESTED_BUT_DENIED
                    }
                    if (isColorized) {
                        n.extras.putBoolean(EXTRA_COLORIZED, true)
                        n.flags = n.flags or FLAG_CAN_COLORIZE
                    }
                }
                .let { NotificationEntryBuilder().setNotification(it) }
                .also { neb ->
                    neb.setPkg(TEST_PACKAGE)
                    neb.setOpPkg(TEST_PACKAGE)
                    neb.setTag(TEST_TAG)

                    neb.setImportance(importance)
                    val channel =
                            NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, importance)
                    channel.isImportantConversation = isImportantConversation
                    neb.setChannel(channel)

                    canBubble?.let { neb.setCanBubble(it) }
                }
                .build()!!
                .also { ne ->
                    if (hasJustLaunchedFsi) {
                        ne.notifyFullScreenIntentLaunched()
                    }

                    if (isStickyAndNotDemoted) {
                        assertFalse(ne.isDemoted)
                    }

                    modifyRanking(ne)
                        .also { mrb ->
                            if (packageSuspended) {
                                mrb.setSuspended(true)
                            }
                            visibilityOverride?.let { mrb.setVisibilityOverride(it) }
                            suppressedVisualEffects?.let { mrb.setSuppressedVisualEffects(it) }
                            mrb.setIsConversation(isConversation)
                        }
                        .build()
                }
    }

    protected fun buildEntry(block: EntryBuilder.() -> Unit) =
        EntryBuilder(context).also(block).build()

    protected fun buildPeekEntry(block: EntryBuilder.() -> Unit = {}) = buildEntry {
        importance = IMPORTANCE_HIGH
        run(block)
    }

    protected fun buildPulseEntry(block: EntryBuilder.() -> Unit = {}) = buildEntry {
        importance = IMPORTANCE_DEFAULT
        visibilityOverride = VISIBILITY_NO_OVERRIDE
        run(block)
    }

    protected fun buildBubbleEntry(block: EntryBuilder.() -> Unit = {}) = buildEntry {
        isBubble = true
        canBubble = true
        hasBubbleMetadata = true
        run(block)
    }

    protected fun buildFsiEntry(block: EntryBuilder.() -> Unit = {}) = buildEntry {
        importance = IMPORTANCE_HIGH
        hasFsi = true
        run(block)
    }

    private fun assertNoEventsLogged() {
        assertNoUiEventLogged()
        assertNoSystemEventLogged()
    }

    private fun assertNoUiEventLogged() {
        assertEquals(0, uiEventLogger.numLogs())
    }

    private fun assertUiEventLogged(uiEventId: UiEventEnum, uid: Int, packageName: String) {
        assertEquals(1, uiEventLogger.numLogs())

        val event = uiEventLogger.get(0)
        assertEquals(uiEventId.id, event.eventId)
        assertEquals(uid, event.uid)
        assertEquals(packageName, event.packageName)
    }

    private fun assertNoSystemEventLogged() {
        assertEquals(0, eventLog.events.size)
    }

    private fun assertSystemEventLogged(number: String, uid: Int, description: String) {
        assertEquals(1, eventLog.events.size)

        val event = eventLog.events[0]
        assertEquals(0x534e4554, event.tag)

        val value = event.value
        assertTrue(value is Array<*>)

        if (value is Array<*>) {
            assertEquals(3, value.size)
            assertEquals(number, value[0])
            assertEquals(uid, value[1])
            assertEquals(description, value[2])
        }
    }

    protected fun whenAgo(whenAgeMs: Long) = systemClock.currentTimeMillis() - whenAgeMs
}

private const val TEST_CONTENT_TITLE = "Test Content Title"
private const val TEST_CONTENT_TEXT = "Test content text"
private const val TEST_CHANNEL_ID = "test_channel"
private const val TEST_CHANNEL_NAME = "Test Channel"
private const val TEST_PACKAGE = "test_package"
private const val TEST_TAG = "test_tag"
private const val TEST_GROUP_KEY = "test_group_key"
