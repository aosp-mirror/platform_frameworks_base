/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.headsup

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Handler
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.kosmos.KosmosJavaAdapter
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManagerImpl
import com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl.HeadsUpEntry
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.concurrency.mockExecutorHandler
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.settings.FakeGlobalSettings
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.eq
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWithLooper
@RunWith(ParameterizedAndroidJunit4::class)
// TODO(b/378142453): Merge this with HeadsUpManagerImplTest.
open class HeadsUpManagerImplOldTest(flags: FlagsParameterization?) : SysuiTestCase() {
    protected var mKosmos: KosmosJavaAdapter = KosmosJavaAdapter(this)

    @JvmField @Rule var rule: MockitoRule = MockitoJUnit.rule()

    private val mUiEventLoggerFake = UiEventLoggerFake()

    private val mLogger: HeadsUpManagerLogger = Mockito.spy(HeadsUpManagerLogger(logcatLogBuffer()))

    @Mock private val mBgHandler: Handler? = null

    @Mock private val dumpManager: DumpManager? = null

    @Mock private val mShadeInteractor: ShadeInteractor? = null
    private var mAvalancheController: AvalancheController? = null

    @Mock private val mAccessibilityMgr: AccessibilityManagerWrapper? = null

    protected val globalSettings: FakeGlobalSettings = FakeGlobalSettings()
    protected val systemClock: FakeSystemClock = FakeSystemClock()
    protected val executor: FakeExecutor = FakeExecutor(systemClock)

    @Mock protected var mRow: ExpandableNotificationRow? = null

    private fun createHeadsUpManager(): HeadsUpManagerImpl {
        return HeadsUpManagerImpl(
            mContext,
            mLogger,
            mKosmos.statusBarStateController,
            mKosmos.keyguardBypassController,
            GroupMembershipManagerImpl(),
            mKosmos.visualStabilityProvider,
            mKosmos.configurationController,
            mockExecutorHandler(executor),
            globalSettings,
            systemClock,
            executor,
            mAccessibilityMgr,
            mUiEventLoggerFake,
            JavaAdapter(mKosmos.testScope),
            mShadeInteractor,
            mAvalancheController,
        )
    }

    private fun createStickyEntry(id: Int): NotificationEntry {
        val notif =
            Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setFullScreenIntent(
                    Mockito.mock(PendingIntent::class.java), /* highPriority */
                    true,
                )
                .build()
        return HeadsUpManagerTestUtil.createEntry(id, notif)
    }

    private fun createStickyForSomeTimeEntry(id: Int): NotificationEntry {
        val notif =
            Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setFlag(Notification.FLAG_FSI_REQUESTED_BUT_DENIED, true)
                .build()
        return HeadsUpManagerTestUtil.createEntry(id, notif)
    }

    private fun useAccessibilityTimeout(use: Boolean) {
        if (use) {
            Mockito.doReturn(TEST_A11Y_AUTO_DISMISS_TIME)
                .`when`(mAccessibilityMgr!!)
                .getRecommendedTimeoutMillis(ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())
        } else {
            Mockito.`when`(
                    mAccessibilityMgr!!.getRecommendedTimeoutMillis(
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.anyInt(),
                    )
                )
                .then { i: InvocationOnMock -> i.getArgument(0) }
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Throws(Exception::class)
    override fun SysuiSetup() {
        super.SysuiSetup()
        mContext.getOrCreateTestableResources().apply {
            this.addOverride(R.integer.ambient_notification_extension_time, TEST_EXTENSION_TIME)
            this.addOverride(R.integer.touch_acceptance_delay, TEST_TOUCH_ACCEPTANCE_TIME)
            this.addOverride(
                R.integer.heads_up_notification_minimum_time,
                TEST_MINIMUM_DISPLAY_TIME,
            )
            this.addOverride(
                R.integer.heads_up_notification_minimum_time_with_throttling,
                TEST_MINIMUM_DISPLAY_TIME,
            )
            this.addOverride(R.integer.heads_up_notification_decay, TEST_AUTO_DISMISS_TIME)
            this.addOverride(
                R.integer.sticky_heads_up_notification_time,
                TEST_STICKY_AUTO_DISMISS_TIME,
            )
        }

        mAvalancheController =
            AvalancheController(dumpManager!!, mUiEventLoggerFake, mLogger, mBgHandler!!)
        Mockito.`when`(mShadeInteractor!!.isAnyExpanded).thenReturn(MutableStateFlow(true))
        Mockito.`when`(mKosmos.keyguardBypassController.bypassEnabled).thenReturn(false)
    }

    @Test
    fun testHasNotifications_headsUpManagerMapNotEmpty_true() {
        val bhum = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        bhum.showNotification(entry)

        Truth.assertThat(bhum.mHeadsUpEntryMap).isNotEmpty()
        Truth.assertThat(bhum.hasNotifications()).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testHasNotifications_avalancheMapNotEmpty_true() {
        val bhum = createHeadsUpManager()
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val headsUpEntry = bhum.createHeadsUpEntry(notifEntry)
        mAvalancheController!!.addToNext(headsUpEntry) {}

        Truth.assertThat(mAvalancheController!!.getWaitingEntryList()).isNotEmpty()
        Truth.assertThat(bhum.hasNotifications()).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testHasNotifications_false() {
        val bhum = createHeadsUpManager()
        Truth.assertThat(bhum.mHeadsUpEntryMap).isEmpty()
        Truth.assertThat(mAvalancheController!!.getWaitingEntryList()).isEmpty()
        Truth.assertThat(bhum.hasNotifications()).isFalse()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testGetHeadsUpEntryList_includesAvalancheEntryList() {
        val bhum = createHeadsUpManager()
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val headsUpEntry = bhum.createHeadsUpEntry(notifEntry)
        mAvalancheController!!.addToNext(headsUpEntry) {}

        Truth.assertThat(bhum.headsUpEntryList).contains(headsUpEntry)
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testGetHeadsUpEntry_returnsAvalancheEntry() {
        val bhum = createHeadsUpManager()
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val headsUpEntry = bhum.createHeadsUpEntry(notifEntry)
        mAvalancheController!!.addToNext(headsUpEntry) {}

        Truth.assertThat(bhum.getHeadsUpEntry(notifEntry.key)).isEqualTo(headsUpEntry)
    }

    @Test
    fun testShowNotification_addsEntry() {
        val alm = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        alm.showNotification(entry)

        assertThat(alm.isHeadsUpEntry(entry.key)).isTrue()
        assertThat(alm.hasNotifications()).isTrue()
        assertThat(alm.getEntry(entry.key)).isEqualTo(entry)
    }

    @Test
    fun testShowNotification_autoDismisses() {
        val alm = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        alm.showNotification(entry)
        systemClock.advanceTime((TEST_AUTO_DISMISS_TIME * 3 / 2).toLong())

        assertThat(alm.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testRemoveNotification_removeDeferred() {
        val alm = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        alm.showNotification(entry)

        val removedImmediately =
            alm.removeNotification(entry.key, /* releaseImmediately= */ false, "removeDeferred")
        assertThat(removedImmediately).isFalse()
        assertThat(alm.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testRemoveNotification_forceRemove() {
        val alm = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        alm.showNotification(entry)

        val removedImmediately =
            alm.removeNotification(entry.key, /* releaseImmediately= */ true, "forceRemove")
        assertThat(removedImmediately).isTrue()
        assertThat(alm.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testReleaseAllImmediately() {
        val alm = createHeadsUpManager()
        for (i in 0 until TEST_NUM_NOTIFICATIONS) {
            val entry = HeadsUpManagerTestUtil.createEntry(i, mContext)
            entry.row = mRow
            alm.showNotification(entry)
        }

        alm.releaseAllImmediately()

        assertThat(alm.allEntries.count()).isEqualTo(0)
    }

    @Test
    fun testCanRemoveImmediately_notShownLongEnough() {
        val alm = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        alm.showNotification(entry)

        // The entry has just been added so we should not remove immediately.
        assertThat(alm.canRemoveImmediately(entry.key)).isFalse()
    }

    @Test
    fun testHunRemovedLogging() {
        val hum = createHeadsUpManager()
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val headsUpEntry = Mockito.mock(HeadsUpEntry::class.java)
        Mockito.`when`(headsUpEntry.pinnedStatus)
            .thenReturn(MutableStateFlow(PinnedStatus.NotPinned))
        headsUpEntry.mEntry = notifEntry

        hum.onEntryRemoved(headsUpEntry, "test")

        Mockito.verify(mLogger, Mockito.times(1)).logNotificationActuallyRemoved(eq(notifEntry))
    }

    @Test
    fun testShowNotification_autoDismissesIncludingTouchAcceptanceDelay() {
        val hum = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(false)

        hum.showNotification(entry)
        systemClock.advanceTime((TEST_TOUCH_ACCEPTANCE_TIME / 2 + TEST_AUTO_DISMISS_TIME).toLong())

        assertThat(hum.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testShowNotification_autoDismissesWithDefaultTimeout() {
        val hum = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(false)

        hum.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME +
                    (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2)
                .toLong()
        )

        assertThat(hum.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testShowNotification_stickyForSomeTime_autoDismissesWithStickyTimeout() {
        val hum = createHeadsUpManager()
        val entry = createStickyForSomeTimeEntry(/* id= */ 0)
        useAccessibilityTimeout(false)

        hum.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME +
                    (TEST_AUTO_DISMISS_TIME + TEST_STICKY_AUTO_DISMISS_TIME) / 2)
                .toLong()
        )

        assertThat(hum.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testShowNotification_sticky_neverAutoDismisses() {
        val hum = createHeadsUpManager()
        val entry = createStickyEntry(/* id= */ 0)
        useAccessibilityTimeout(false)

        hum.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME + 2 * TEST_A11Y_AUTO_DISMISS_TIME).toLong()
        )

        assertThat(hum.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testShowNotification_autoDismissesWithAccessibilityTimeout() {
        val hum = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(true)

        hum.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME +
                    (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2)
                .toLong()
        )

        assertThat(hum.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testShowNotification_stickyForSomeTime_autoDismissesWithAccessibilityTimeout() {
        val hum = createHeadsUpManager()
        val entry = createStickyForSomeTimeEntry(/* id= */ 0)
        useAccessibilityTimeout(true)

        hum.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME +
                    (TEST_STICKY_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2)
                .toLong()
        )

        assertThat(hum.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testRemoveNotification_beforeMinimumDisplayTime() {
        val hum = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(false)

        hum.showNotification(entry)

        val removedImmediately =
            hum.removeNotification(
                entry.key,
                /* releaseImmediately = */ false,
                "beforeMinimumDisplayTime",
            )
        assertThat(removedImmediately).isFalse()
        assertThat(hum.isHeadsUpEntry(entry.key)).isTrue()

        systemClock.advanceTime(((TEST_MINIMUM_DISPLAY_TIME + TEST_AUTO_DISMISS_TIME) / 2).toLong())

        assertThat(hum.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testRemoveNotification_afterMinimumDisplayTime() {
        val hum = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(false)

        hum.showNotification(entry)
        systemClock.advanceTime(((TEST_MINIMUM_DISPLAY_TIME + TEST_AUTO_DISMISS_TIME) / 2).toLong())

        assertThat(hum.isHeadsUpEntry(entry.key)).isTrue()

        val removedImmediately =
            hum.removeNotification(
                entry.key,
                /* releaseImmediately = */ false,
                "afterMinimumDisplayTime",
            )
        assertThat(removedImmediately).isTrue()
        assertThat(hum.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testRemoveNotification_releaseImmediately() {
        val hum = createHeadsUpManager()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        hum.showNotification(entry)

        val removedImmediately =
            hum.removeNotification(
                entry.key,
                /* releaseImmediately = */ true,
                "afterMinimumDisplayTime",
            )
        assertThat(removedImmediately).isTrue()
        assertThat(hum.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testIsSticky_rowPinnedAndExpanded_true() {
        val hum = createHeadsUpManager()
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        Mockito.`when`(mRow!!.isPinned).thenReturn(true)
        notifEntry.row = mRow

        hum.showNotification(notifEntry)

        val headsUpEntry = hum.getHeadsUpEntry(notifEntry.key)
        headsUpEntry!!.setExpanded(true)

        assertThat(hum.isSticky(notifEntry.key)).isTrue()
    }

    @Test
    fun testIsSticky_remoteInputActive_true() {
        val hum = createHeadsUpManager()
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        hum.showNotification(notifEntry)

        val headsUpEntry = hum.getHeadsUpEntry(notifEntry.key)
        headsUpEntry!!.mRemoteInputActive = true

        assertThat(hum.isSticky(notifEntry.key)).isTrue()
    }

    @Test
    fun testIsSticky_hasFullScreenIntent_true() {
        val hum = createHeadsUpManager()
        val notifEntry = HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)

        hum.showNotification(notifEntry)

        assertThat(hum.isSticky(notifEntry.key)).isTrue()
    }

    @Test
    fun testIsSticky_stickyForSomeTime_false() {
        val hum = createHeadsUpManager()
        val entry = createStickyForSomeTimeEntry(/* id= */ 0)

        hum.showNotification(entry)

        assertThat(hum.isSticky(entry.key)).isFalse()
    }

    @Test
    fun testIsSticky_false() {
        val hum = createHeadsUpManager()
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        hum.showNotification(notifEntry)

        val headsUpEntry = hum.getHeadsUpEntry(notifEntry.key)
        headsUpEntry!!.setExpanded(false)
        headsUpEntry.mRemoteInputActive = false

        assertThat(hum.isSticky(notifEntry.key)).isFalse()
    }

    @Test
    fun testCompareTo_withNullEntries() {
        val hum = createHeadsUpManager()
        val alertEntry = NotificationEntryBuilder().setTag("alert").build()

        hum.showNotification(alertEntry)

        assertThat(hum.compare(alertEntry, null)).isLessThan(0)
        assertThat(hum.compare(null, alertEntry)).isGreaterThan(0)
        assertThat(hum.compare(null, null)).isEqualTo(0)
    }

    @Test
    fun testCompareTo_withNonAlertEntries() {
        val hum = createHeadsUpManager()

        val nonAlertEntry1 = NotificationEntryBuilder().setTag("nae1").build()
        val nonAlertEntry2 = NotificationEntryBuilder().setTag("nae2").build()
        val alertEntry = NotificationEntryBuilder().setTag("alert").build()
        hum.showNotification(alertEntry)

        assertThat(hum.compare(alertEntry, nonAlertEntry1)).isLessThan(0)
        assertThat(hum.compare(nonAlertEntry1, alertEntry)).isGreaterThan(0)
        assertThat(hum.compare(nonAlertEntry1, nonAlertEntry2)).isEqualTo(0)
    }

    @Test
    fun testAlertEntryCompareTo_ongoingCallLessThanActiveRemoteInput() {
        val hum = createHeadsUpManager()

        val ongoingCall =
            hum.HeadsUpEntry(
                NotificationEntryBuilder()
                    .setSbn(
                        HeadsUpManagerTestUtil.createSbn(
                            /* id = */ 0,
                            Notification.Builder(mContext, "")
                                .setCategory(Notification.CATEGORY_CALL)
                                .setOngoing(true),
                        )
                    )
                    .build()
            )

        val activeRemoteInput =
            hum.HeadsUpEntry(HeadsUpManagerTestUtil.createEntry(/* id= */ 1, mContext))
        activeRemoteInput.mRemoteInputActive = true

        assertThat(ongoingCall.compareTo(activeRemoteInput)).isLessThan(0)
        assertThat(activeRemoteInput.compareTo(ongoingCall)).isGreaterThan(0)
    }

    @Test
    fun testAlertEntryCompareTo_incomingCallLessThanActiveRemoteInput() {
        val hum = createHeadsUpManager()

        val person = Person.Builder().setName("person").build()
        val intent = Mockito.mock(PendingIntent::class.java)
        val incomingCall =
            hum.HeadsUpEntry(
                NotificationEntryBuilder()
                    .setSbn(
                        HeadsUpManagerTestUtil.createSbn(
                            /* id = */ 0,
                            Notification.Builder(mContext, "")
                                .setStyle(
                                    Notification.CallStyle.forIncomingCall(person, intent, intent)
                                ),
                        )
                    )
                    .build()
            )

        val activeRemoteInput =
            hum.HeadsUpEntry(HeadsUpManagerTestUtil.createEntry(/* id= */ 1, mContext))
        activeRemoteInput.mRemoteInputActive = true

        assertThat(incomingCall.compareTo(activeRemoteInput)).isLessThan(0)
        assertThat(activeRemoteInput.compareTo(incomingCall)).isGreaterThan(0)
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testPinEntry_logsPeek_throttleEnabled() {
        val hum = createHeadsUpManager()

        // Needs full screen intent in order to be pinned
        val entryToPin =
            hum.HeadsUpEntry(
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)
            )

        // Note: the standard way to show a notification would be calling showNotification rather
        // than onAlertEntryAdded. However, in practice showNotification in effect adds
        // the notification and then updates it; in order to not log twice, the entry needs
        // to have a functional ExpandableNotificationRow that can keep track of whether it's
        // pinned or not (via isRowPinned()). That feels like a lot to pull in to test this one bit.
        hum.onEntryAdded(entryToPin)

        assertThat(mUiEventLoggerFake.numLogs()).isEqualTo(2)
        assertThat(AvalancheController.ThrottleEvent.AVALANCHE_THROTTLING_HUN_SHOWN.getId())
            .isEqualTo(mUiEventLoggerFake.eventId(0))
        assertThat(HeadsUpManagerImpl.NotificationPeekEvent.NOTIFICATION_PEEK.id)
            .isEqualTo(mUiEventLoggerFake.eventId(1))
    }

    @Test
    @DisableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testPinEntry_logsPeek_throttleDisabled() {
        val hum = createHeadsUpManager()

        // Needs full screen intent in order to be pinned
        val entryToPin =
            hum.HeadsUpEntry(
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)
            )

        // Note: the standard way to show a notification would be calling showNotification rather
        // than onAlertEntryAdded. However, in practice showNotification in effect adds
        // the notification and then updates it; in order to not log twice, the entry needs
        // to have a functional ExpandableNotificationRow that can keep track of whether it's
        // pinned or not (via isRowPinned()). That feels like a lot to pull in to test this one bit.
        hum.onEntryAdded(entryToPin)

        assertThat(mUiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(HeadsUpManagerImpl.NotificationPeekEvent.NOTIFICATION_PEEK.id)
            .isEqualTo(mUiEventLoggerFake.eventId(0))
    }

    @Test
    fun testSetUserActionMayIndirectlyRemove() {
        val hum = createHeadsUpManager()
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        hum.showNotification(notifEntry)

        assertThat(hum.canRemoveImmediately(notifEntry.key)).isFalse()

        hum.setUserActionMayIndirectlyRemove(notifEntry)

        assertThat(hum.canRemoveImmediately(notifEntry.key)).isTrue()
    }

    companion object {
        const val TEST_TOUCH_ACCEPTANCE_TIME: Int = 200
        const val TEST_A11Y_AUTO_DISMISS_TIME: Int = 1000
        const val TEST_EXTENSION_TIME = 500

        const val TEST_MINIMUM_DISPLAY_TIME: Int = 400
        const val TEST_AUTO_DISMISS_TIME: Int = 600
        const val TEST_STICKY_AUTO_DISMISS_TIME: Int = 800

        // Number of notifications to use in tests requiring multiple notifications
        private const val TEST_NUM_NOTIFICATIONS = 4

        init {
            Truth.assertThat(TEST_MINIMUM_DISPLAY_TIME).isLessThan(TEST_AUTO_DISMISS_TIME)
            Truth.assertThat(TEST_AUTO_DISMISS_TIME).isLessThan(TEST_STICKY_AUTO_DISMISS_TIME)
            Truth.assertThat(TEST_STICKY_AUTO_DISMISS_TIME).isLessThan(TEST_A11Y_AUTO_DISMISS_TIME)
        }

        @get:Parameters(name = "{0}")
        @JvmStatic
        val flags: List<FlagsParameterization>
            get() = FlagsParameterization.allCombinationsOf(NotificationThrottleHun.FLAG_NAME)
    }
}
