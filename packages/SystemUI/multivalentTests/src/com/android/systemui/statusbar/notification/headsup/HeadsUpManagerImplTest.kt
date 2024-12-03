/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.os.Handler
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableLooper.RunWithLooper
import android.view.accessibility.accessibilityManagerWrapper
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.dump.dumpManager
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.provider.visualStabilityProvider
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun
import com.android.systemui.statusbar.phone.keyguardBypassController
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.mockExecutorHandler
import com.android.systemui.util.kotlin.JavaAdapter
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper
class HeadsUpManagerImplTest(flags: FlagsParameterization) : HeadsUpManagerImplOldTest(flags) {

    private val mHeadsUpManagerLogger = HeadsUpManagerLogger(logcatLogBuffer())

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope

    private val mGroupManager = mock<GroupMembershipManager>()
    private val mBgHandler = mock<Handler>()

    val statusBarStateController = kosmos.sysuiStatusBarStateController
    private val mJavaAdapter: JavaAdapter = JavaAdapter(testScope.backgroundScope)

    private lateinit var mAvalancheController: AvalancheController
    private lateinit var underTest: HeadsUpManagerImpl

    @Before
    fun setUp() {
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

        whenever(kosmos.keyguardBypassController.bypassEnabled).thenReturn(false)
        kosmos.visualStabilityProvider.isReorderingAllowed = true
        mAvalancheController =
            AvalancheController(
                kosmos.dumpManager,
                kosmos.uiEventLoggerFake,
                mHeadsUpManagerLogger,
                mBgHandler,
            )
        underTest =
            HeadsUpManagerImpl(
                mContext,
                mHeadsUpManagerLogger,
                statusBarStateController,
                kosmos.keyguardBypassController,
                mGroupManager,
                kosmos.visualStabilityProvider,
                kosmos.configurationController,
                mockExecutorHandler(mExecutor),
                mGlobalSettings,
                mSystemClock,
                mExecutor,
                kosmos.accessibilityManagerWrapper,
                kosmos.uiEventLoggerFake,
                mJavaAdapter,
                kosmos.shadeInteractor,
                mAvalancheController,
            )
    }

    @Test
    fun testSnooze() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry)
        underTest.snooze()
        Assert.assertTrue(underTest.isSnoozed(entry.sbn.packageName))
    }

    @Test
    fun testSwipedOutNotification() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry)
        underTest.addSwipedOutNotification(entry.key)

        // Remove should succeed because the notification is swiped out
        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately= */ false,
                /* reason= */ "swipe out",
            )
        Assert.assertTrue(removedImmediately)
        Assert.assertFalse(underTest.isHeadsUpEntry(entry.key))
    }

    @Test
    fun testCanRemoveImmediately_swipedOut() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry)
        underTest.addSwipedOutNotification(entry.key)

        // Notification is swiped so it can be immediately removed.
        Assert.assertTrue(underTest.canRemoveImmediately(entry.key))
    }

    @Ignore("b/141538055")
    @Test
    fun testCanRemoveImmediately_notTopEntry() {
        val earlierEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val laterEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 1, mContext)
        laterEntry.row = mRow
        underTest.showNotification(earlierEntry)
        underTest.showNotification(laterEntry)

        // Notification is "behind" a higher priority notification so we can remove it immediately.
        Assert.assertTrue(underTest.canRemoveImmediately(earlierEntry.key))
    }

    @Test
    fun testExtendHeadsUp() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry)
        underTest.extendHeadsUp()
        mSystemClock.advanceTime(((TEST_AUTO_DISMISS_TIME + TEST_EXTENSION_TIME) / 2).toLong())
        Assert.assertTrue(underTest.isHeadsUpEntry(entry.key))
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_removeWhenReorderingAllowedTrue() {
        kosmos.visualStabilityProvider.isReorderingAllowed = true

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(notifEntry)
        assertThat(underTest.mEntriesToRemoveWhenReorderingAllowed.contains(notifEntry)).isTrue()
    }

    class TestAnimationStateHandler : AnimationStateHandler {
        override fun setHeadsUpGoingAwayAnimationsAllowed(allowed: Boolean) {}
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testReorderingAllowed_clearsListOfEntriesToRemove() {
        kosmos.visualStabilityProvider.isReorderingAllowed = true

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(notifEntry)
        assertThat(underTest.mEntriesToRemoveWhenReorderingAllowed.contains(notifEntry)).isTrue()

        underTest.setAnimationStateHandler(TestAnimationStateHandler())
        underTest.mOnReorderingAllowedListener.onReorderingAllowed()
        assertThat(underTest.mEntriesToRemoveWhenReorderingAllowed.isEmpty()).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_reorderNotAllowed_seenInShadeTrue() {
        kosmos.visualStabilityProvider.isReorderingAllowed = false

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(notifEntry)
        assertThat(notifEntry.isSeenInShade).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_reorderAllowed_seenInShadeFalse() {
        kosmos.visualStabilityProvider.isReorderingAllowed = true

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(notifEntry)
        assertThat(notifEntry.isSeenInShade).isFalse()
    }

    @Test
    fun testShouldHeadsUpBecomePinned_noFSI_false() =
        kosmos.runTest {
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

            Assert.assertFalse(underTest.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun testShouldHeadsUpBecomePinned_hasFSI_notUnpinned_true() =
        kosmos.runTest {
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            val notifEntry =
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)

            // Add notifEntry to ANM mAlertEntries map and make it NOT unpinned
            underTest.showNotification(notifEntry)

            val headsUpEntry = underTest.getHeadsUpEntry(notifEntry.key)
            headsUpEntry!!.mWasUnpinned = false

            Assert.assertTrue(underTest.shouldHeadsUpBecomePinned(notifEntry))
        }

    @Test
    fun testShouldHeadsUpBecomePinned_wasUnpinned_false() =
        kosmos.runTest {
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            val notifEntry =
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)

            // Add notifEntry to ANM mAlertEntries map and make it unpinned
            underTest.showNotification(notifEntry)

            val headsUpEntry = underTest.getHeadsUpEntry(notifEntry.key)
            headsUpEntry!!.mWasUnpinned = true

            Assert.assertFalse(underTest.shouldHeadsUpBecomePinned(notifEntry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeNotExpanded_true() =
        kosmos.runTest {
            // GIVEN
            shadeTestUtil.setShadeExpansion(0f)
            // TODO(b/381869885): Determine why we need both of these ShadeTestUtil calls.
            shadeTestUtil.setLegacyExpandedOrAwaitingInputTransfer(false)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE)

            // THEN
            Assert.assertTrue(underTest.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeLocked_false() =
        kosmos.runTest {
            // GIVEN
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE_LOCKED)

            // THEN
            Assert.assertFalse(underTest.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeUnknown_false() =
        kosmos.runTest {
            // GIVEN
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(1207)

            // THEN
            Assert.assertFalse(underTest.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_keyguardWithBypassOn_true() =
        kosmos.runTest {
            // GIVEN
            whenever(keyguardBypassController.bypassEnabled).thenReturn(true)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            // THEN
            Assert.assertTrue(underTest.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_keyguardWithBypassOff_false() =
        kosmos.runTest {
            // GIVEN
            whenever(keyguardBypassController.bypassEnabled).thenReturn(false)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            // THEN
            Assert.assertFalse(underTest.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeExpanded_false() =
        kosmos.runTest {
            // GIVEN
            shadeTestUtil.setShadeExpansion(1f)
            // TODO(b/381869885): Determine why we need both of these ShadeTestUtil calls.
            shadeTestUtil.setLegacyExpandedOrAwaitingInputTransfer(true)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE)

            // THEN
            Assert.assertFalse(underTest.shouldHeadsUpBecomePinned(entry))
        }

    companion object {
        @get:Parameters(name = "{0}")
        val flags: List<FlagsParameterization>
            get() = buildList {
                addAll(
                    FlagsParameterization.allCombinationsOf(NotificationThrottleHun.FLAG_NAME)
                        .andSceneContainer()
                )
            }
    }
}
