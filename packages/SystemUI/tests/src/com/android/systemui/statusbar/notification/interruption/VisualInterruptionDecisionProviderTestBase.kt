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
import android.app.Notification.FLAG_BUBBLE
import android.app.Notification.GROUP_ALERT_ALL
import android.app.Notification.GROUP_ALERT_CHILDREN
import android.app.Notification.GROUP_ALERT_SUMMARY
import android.app.Notification.VISIBILITY_PRIVATE
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT
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
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
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
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.settings.FakeGlobalSettings
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.utils.leaks.FakeBatteryController
import com.android.systemui.utils.leaks.LeakCheckedTest
import com.android.systemui.utils.os.FakeHandler
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
    protected val mainHandler = FakeHandler(Looper.getMainLooper())
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

        provider.start()
    }

    @Test
    fun testShouldPeek() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldNotPeek_settingDisabled() {
        ensurePeekState { hunSettingEnabled = false }
        assertShouldNotHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldNotPeek_packageSnoozed() {
        ensurePeekState { hunSnoozed = true }
        assertShouldNotHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldPeek_packageSnoozedButFsi() {
        ensurePeekState { hunSnoozed = true }
        assertShouldHeadsUp(buildFsiEntry())
    }

    @Test
    fun testShouldNotPeek_alreadyBubbled() {
        ensurePeekState { statusBarState = SHADE }
        assertShouldNotHeadsUp(buildPeekEntry { isBubble = true })
    }

    @Test
    fun testShouldPeek_isBubble_shadeLocked() {
        ensurePeekState { statusBarState = SHADE_LOCKED }
        assertShouldHeadsUp(buildPeekEntry { isBubble = true })
    }

    @Test
    fun testShouldPeek_isBubble_keyguard() {
        ensurePeekState { statusBarState = KEYGUARD }
        assertShouldHeadsUp(buildPeekEntry { isBubble = true })
    }

    @Test
    fun testShouldNotPeek_dnd() {
        ensurePeekState()
        assertShouldNotHeadsUp(buildPeekEntry { suppressedVisualEffects = SUPPRESSED_EFFECT_PEEK })
    }

    @Test
    fun testShouldNotPeek_notImportant() {
        ensurePeekState()
        assertShouldNotHeadsUp(buildPeekEntry { importance = IMPORTANCE_DEFAULT })
    }

    @Test
    fun testShouldNotPeek_screenOff() {
        ensurePeekState { isScreenOn = false }
        assertShouldNotHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldNotPeek_dreaming() {
        ensurePeekState { isDreaming = true }
        assertShouldNotHeadsUp(buildPeekEntry())
    }

    @Test
    fun testShouldNotPeek_oldWhen() {
        ensurePeekState()
        assertShouldNotHeadsUp(buildPeekEntry { whenMs = whenAgo(MAX_HUN_WHEN_AGE_MS) })
    }

    @Test
    fun testShouldPeek_notQuiteOldEnoughWhen() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry { whenMs = whenAgo(MAX_HUN_WHEN_AGE_MS - 1) })
    }

    @Test
    fun testShouldPeek_zeroWhen() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry { whenMs = 0L })
    }

    @Test
    fun testShouldPeek_oldWhenButFsi() {
        ensurePeekState()
        assertShouldHeadsUp(buildFsiEntry { whenMs = whenAgo(MAX_HUN_WHEN_AGE_MS) })
    }

    @Test
    fun testShouldPeek_defaultLegacySuppressor() {
        ensurePeekState()
        withLegacySuppressor(neverSuppresses) { assertShouldHeadsUp(buildPeekEntry()) }
    }

    @Test
    fun testShouldNotPeek_legacySuppressInterruptions() {
        ensurePeekState()
        withLegacySuppressor(alwaysSuppressesInterruptions) {
            assertShouldNotHeadsUp(buildPeekEntry())
        }
    }

    @Test
    fun testShouldNotPeek_legacySuppressAwakeInterruptions() {
        ensurePeekState()
        withLegacySuppressor(alwaysSuppressesAwakeInterruptions) {
            assertShouldNotHeadsUp(buildPeekEntry())
        }
    }

    @Test
    fun testShouldNotPeek_legacySuppressAwakeHeadsUp() {
        ensurePeekState()
        withLegacySuppressor(alwaysSuppressesAwakeHeadsUp) {
            assertShouldNotHeadsUp(buildPeekEntry())
        }
    }

    @Test
    fun testShouldPulse() {
        ensurePulseState()
        assertShouldHeadsUp(buildPulseEntry())
    }

    @Test
    fun testShouldPulse_defaultLegacySuppressor() {
        ensurePulseState()
        withLegacySuppressor(neverSuppresses) { assertShouldHeadsUp(buildPulseEntry()) }
    }

    @Test
    fun testShouldNotPulse_legacySuppressInterruptions() {
        ensurePulseState()
        withLegacySuppressor(alwaysSuppressesInterruptions) {
            assertShouldNotHeadsUp(buildPulseEntry())
        }
    }

    @Test
    fun testShouldPulse_legacySuppressAwakeInterruptions() {
        ensurePulseState()
        withLegacySuppressor(alwaysSuppressesAwakeInterruptions) {
            assertShouldHeadsUp(buildPulseEntry())
        }
    }

    @Test
    fun testShouldPulse_legacySuppressAwakeHeadsUp() {
        ensurePulseState()
        withLegacySuppressor(alwaysSuppressesAwakeHeadsUp) {
            assertShouldHeadsUp(buildPulseEntry())
        }
    }

    @Test
    fun testShouldNotPulse_disabled() {
        ensurePulseState { pulseOnNotificationsEnabled = false }
        assertShouldNotHeadsUp(buildPulseEntry())
    }

    @Test
    fun testShouldNotPulse_batterySaver() {
        ensurePulseState { isAodPowerSave = true }
        assertShouldNotHeadsUp(buildPulseEntry())
    }

    @Test
    fun testShouldNotPulse_effectSuppressed() {
        ensurePulseState()
        assertShouldNotHeadsUp(
            buildPulseEntry { suppressedVisualEffects = SUPPRESSED_EFFECT_AMBIENT }
        )
    }

    @Test
    fun testShouldNotPulse_visibilityOverridePrivate() {
        ensurePulseState()
        assertShouldNotHeadsUp(buildPulseEntry { visibilityOverride = VISIBILITY_PRIVATE })
    }

    @Test
    fun testShouldNotPulse_importanceLow() {
        ensurePulseState()
        assertShouldNotHeadsUp(buildPulseEntry { importance = IMPORTANCE_LOW })
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
    fun testShouldHeadsUp_groupedSummaryNotif_groupAlertAll() {
        withPeekAndPulseEntry({
            isGrouped = true
            isGroupSummary = true
            groupAlertBehavior = GROUP_ALERT_ALL
        }) {
            assertShouldHeadsUp(it)
        }
    }

    @Test
    fun testShouldHeadsUp_groupedSummaryNotif_groupAlertSummary() {
        withPeekAndPulseEntry({
            isGrouped = true
            isGroupSummary = true
            groupAlertBehavior = GROUP_ALERT_SUMMARY
        }) {
            assertShouldHeadsUp(it)
        }
    }

    @Test
    fun testShouldNotHeadsUp_groupedSummaryNotif_groupAlertChildren() {
        withPeekAndPulseEntry({
            isGrouped = true
            isGroupSummary = true
            groupAlertBehavior = GROUP_ALERT_CHILDREN
        }) {
            assertShouldNotHeadsUp(it)
        }
    }

    @Test
    fun testShouldHeadsUp_ungroupedSummaryNotif_groupAlertChildren() {
        withPeekAndPulseEntry({
            isGrouped = false
            isGroupSummary = true
            groupAlertBehavior = GROUP_ALERT_CHILDREN
        }) {
            assertShouldHeadsUp(it)
        }
    }

    @Test
    fun testShouldHeadsUp_groupedChildNotif_groupAlertAll() {
        withPeekAndPulseEntry({
            isGrouped = true
            isGroupSummary = false
            groupAlertBehavior = GROUP_ALERT_ALL
        }) {
            assertShouldHeadsUp(it)
        }
    }

    @Test
    fun testShouldHeadsUp_groupedChildNotif_groupAlertChildren() {
        withPeekAndPulseEntry({
            isGrouped = true
            isGroupSummary = false
            groupAlertBehavior = GROUP_ALERT_CHILDREN
        }) {
            assertShouldHeadsUp(it)
        }
    }

    @Test
    fun testShouldNotHeadsUp_groupedChildNotif_groupAlertSummary() {
        withPeekAndPulseEntry({
            isGrouped = true
            isGroupSummary = false
            groupAlertBehavior = GROUP_ALERT_SUMMARY
        }) {
            assertShouldNotHeadsUp(it)
        }
    }

    @Test
    fun testShouldHeadsUp_ungroupedChildNotif_groupAlertSummary() {
        withPeekAndPulseEntry({
            isGrouped = false
            isGroupSummary = false
            groupAlertBehavior = GROUP_ALERT_SUMMARY
        }) {
            assertShouldHeadsUp(it)
        }
    }

    @Test
    fun testShouldNotHeadsUp_justLaunchedFsi() {
        withPeekAndPulseEntry({ hasJustLaunchedFsi = true }) { assertShouldNotHeadsUp(it) }
    }

    @Test
    fun testShouldBubble_withIntentAndIcon() {
        ensureBubbleState()
        assertShouldBubble(buildBubbleEntry { bubbleIsShortcut = false })
    }

    @Test
    fun testShouldBubble_withShortcut() {
        ensureBubbleState()
        assertShouldBubble(buildBubbleEntry { bubbleIsShortcut = true })
    }

    @Test
    fun testShouldNotBubble_notAllowed() {
        ensureBubbleState()
        assertShouldNotBubble(buildBubbleEntry { canBubble = false })
    }

    @Test
    fun testShouldNotBubble_noBubbleMetadata() {
        ensureBubbleState()
        assertShouldNotBubble(buildBubbleEntry { hasBubbleMetadata = false })
    }

    @Test
    fun testShouldBubble_defaultLegacySuppressor() {
        ensureBubbleState()
        withLegacySuppressor(neverSuppresses) { assertShouldBubble(buildBubbleEntry()) }
    }

    @Test
    fun testShouldNotBubble_legacySuppressInterruptions() {
        ensureBubbleState()
        withLegacySuppressor(alwaysSuppressesInterruptions) {
            assertShouldNotBubble(buildBubbleEntry())
        }
    }

    @Test
    fun testShouldNotBubble_legacySuppressAwakeInterruptions() {
        ensureBubbleState()
        withLegacySuppressor(alwaysSuppressesAwakeInterruptions) {
            assertShouldNotBubble(buildBubbleEntry())
        }
    }

    @Test
    fun testShouldBubble_legacySuppressAwakeHeadsUp() {
        ensureBubbleState()
        withLegacySuppressor(alwaysSuppressesAwakeHeadsUp) {
            assertShouldBubble(buildBubbleEntry())
        }
    }

    @Test
    fun testShouldNotAlert_hiddenOnKeyguard() {
        ensurePeekState({ keyguardShouldHideNotification = true })
        assertShouldNotHeadsUp(buildPeekEntry())

        ensurePulseState({ keyguardShouldHideNotification = true })
        assertShouldNotHeadsUp(buildPulseEntry())

        ensureBubbleState({ keyguardShouldHideNotification = true })
        assertShouldNotBubble(buildBubbleEntry())
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
        isDreaming = false
        isInteractive = false
        statusBarState = SHADE
        run(block)
    }

    protected fun ensureDreamingFsiState(block: State.() -> Unit = {}) = ensureState {
        isDreaming = true
        isInteractive = true
        statusBarState = SHADE
        run(block)
    }

    protected fun ensureKeyguardFsiState(block: State.() -> Unit = {}) = ensureState {
        isDreaming = false
        isInteractive = true
        statusBarState = KEYGUARD
        run(block)
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
        provider.makeUnloggedHeadsUpDecision(entry).let {
            assertTrue("unexpected suppressed HUN: ${it.logReason}", it.shouldInterrupt)
        }

    protected fun assertShouldNotHeadsUp(entry: NotificationEntry) =
        provider.makeUnloggedHeadsUpDecision(entry).let {
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
            assertTrue("unexpected suppressed FSI: ${it.logReason}", it.shouldInterrupt)
        }

    protected fun assertShouldNotFsi(entry: NotificationEntry) =
        provider.makeUnloggedFullScreenIntentDecision(entry).let {
            assertFalse("unexpected unsuppressed FSI: ${it.logReason}", it.shouldInterrupt)
        }

    protected class EntryBuilder(val context: Context) {
        var importance = IMPORTANCE_DEFAULT
        var suppressedVisualEffects: Int? = null
        var whenMs: Long? = null
        var visibilityOverride: Int? = null
        var hasFsi = false
        var canBubble: Boolean? = null
        var isBubble = false
        var hasBubbleMetadata = false
        var bubbleIsShortcut = false
        var bubbleSuppressesNotification: Boolean? = null
        var isGrouped = false
        var isGroupSummary: Boolean? = null
        var groupAlertBehavior: Int? = null
        var hasJustLaunchedFsi = false

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

            bubbleSuppressesNotification?.let { builder.setSuppressNotification(it) }

            return builder.build()
        }

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

                    if (isGrouped) {
                        setGroup(TEST_GROUP_KEY)
                    }

                    isGroupSummary?.let { setGroupSummary(it) }

                    groupAlertBehavior?.let { setGroupAlertBehavior(it) }
                }
                .build()
                .apply {
                    if (isBubble) {
                        flags = flags or FLAG_BUBBLE
                    }
                }
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
                    if (hasJustLaunchedFsi) {
                        it.notifyFullScreenIntentLaunched()
                    }

                    modifyRanking(it)
                        .apply {
                            suppressedVisualEffects?.let { setSuppressedVisualEffects(it) }
                            visibilityOverride?.let { setVisibilityOverride(it) }
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
        canBubble = true
        hasBubbleMetadata = true
        run(block)
    }

    protected fun buildFsiEntry(block: EntryBuilder.() -> Unit = {}) = buildEntry {
        importance = IMPORTANCE_HIGH
        hasFsi = true
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
private const val TEST_GROUP_KEY = "test_group_key"
