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
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.dump.DumpManager
import com.android.systemui.dump.dumpManager
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.FakeStatusBarStateController
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider
import com.android.systemui.statusbar.notification.collection.provider.visualStabilityProvider
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.keyguardBypassController
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.mockExecutorHandler
import com.android.systemui.util.kotlin.JavaAdapter
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
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
    private val mShadeInteractor = mock<ShadeInteractor>()

    val statusBarStateController = kosmos.sysuiStatusBarStateController
    private val mJavaAdapter: JavaAdapter = JavaAdapter(testScope.backgroundScope)

    private lateinit var mAvalancheController: AvalancheController

    private fun createHeadsUpManagerPhone(): HeadsUpManagerImpl {
        return HeadsUpManagerImpl(
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
            mShadeInteractor,
            mAvalancheController,
        )
    }

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

        whenever(mShadeInteractor.isAnyExpanded).thenReturn(MutableStateFlow(false))
        whenever(mShadeInteractor.isQsExpanded).thenReturn(MutableStateFlow(false))
        whenever(kosmos.keyguardBypassController.bypassEnabled).thenReturn(false)
        kosmos.visualStabilityProvider.isReorderingAllowed = true
        mAvalancheController =
            AvalancheController(
                kosmos.dumpManager,
                kosmos.uiEventLoggerFake,
                mHeadsUpManagerLogger,
                mBgHandler,
            )
    }

    @Test
    fun testSnooze() {
        val hmp: HeadsUpManager = createHeadsUpManagerPhone()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(entry)
        hmp.snooze()
        Assert.assertTrue(hmp.isSnoozed(entry.sbn.packageName))
    }

    @Test
    fun testSwipedOutNotification() {
        val hmp: HeadsUpManager = createHeadsUpManagerPhone()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(entry)
        hmp.addSwipedOutNotification(entry.key)

        // Remove should succeed because the notification is swiped out
        val removedImmediately =
            hmp.removeNotification(
                entry.key,
                /* releaseImmediately= */ false,
                /* reason= */ "swipe out",
            )
        Assert.assertTrue(removedImmediately)
        Assert.assertFalse(hmp.isHeadsUpEntry(entry.key))
    }

    @Test
    fun testCanRemoveImmediately_swipedOut() {
        val hmp: HeadsUpManager = createHeadsUpManagerPhone()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(entry)
        hmp.addSwipedOutNotification(entry.key)

        // Notification is swiped so it can be immediately removed.
        Assert.assertTrue(hmp.canRemoveImmediately(entry.key))
    }

    @Ignore("b/141538055")
    @Test
    fun testCanRemoveImmediately_notTopEntry() {
        val hmp: HeadsUpManager = createHeadsUpManagerPhone()
        val earlierEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val laterEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 1, mContext)
        laterEntry.row = mRow
        hmp.showNotification(earlierEntry)
        hmp.showNotification(laterEntry)

        // Notification is "behind" a higher priority notification so we can remove it immediately.
        Assert.assertTrue(hmp.canRemoveImmediately(earlierEntry.key))
    }

    @Test
    fun testExtendHeadsUp() {
        val hmp = createHeadsUpManagerPhone()
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(entry)
        hmp.extendHeadsUp()
        mSystemClock.advanceTime(((TEST_AUTO_DISMISS_TIME + TEST_EXTENSION_TIME) / 2).toLong())
        Assert.assertTrue(hmp.isHeadsUpEntry(entry.key))
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_removeWhenReorderingAllowedTrue() {
        kosmos.visualStabilityProvider.isReorderingAllowed = true
        val hmp = createHeadsUpManagerPhone()

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(notifEntry)
        assertThat(hmp.mEntriesToRemoveWhenReorderingAllowed.contains(notifEntry)).isTrue()
    }

    class TestAnimationStateHandler : AnimationStateHandler {
        override fun setHeadsUpGoingAwayAnimationsAllowed(allowed: Boolean) {}
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testReorderingAllowed_clearsListOfEntriesToRemove() {
        kosmos.visualStabilityProvider.isReorderingAllowed = true
        val hmp = createHeadsUpManagerPhone()

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(notifEntry)
        assertThat(hmp.mEntriesToRemoveWhenReorderingAllowed.contains(notifEntry)).isTrue()

        hmp.setAnimationStateHandler(TestAnimationStateHandler())
        hmp.mOnReorderingAllowedListener.onReorderingAllowed()
        assertThat(hmp.mEntriesToRemoveWhenReorderingAllowed.isEmpty()).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_reorderNotAllowed_seenInShadeTrue() {
        kosmos.visualStabilityProvider.isReorderingAllowed = false
        val hmp = createHeadsUpManagerPhone()

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(notifEntry)
        assertThat(notifEntry.isSeenInShade).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_reorderAllowed_seenInShadeFalse() {
        kosmos.visualStabilityProvider.isReorderingAllowed = true
        val hmp = createHeadsUpManagerPhone()

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(notifEntry)
        assertThat(notifEntry.isSeenInShade).isFalse()
    }

    @Test
    fun testShouldHeadsUpBecomePinned_noFSI_false() =
        kosmos.runTest {
            val hum = createHeadsUpManagerPhone()
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

            Assert.assertFalse(hum.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun testShouldHeadsUpBecomePinned_hasFSI_notUnpinned_true() =
        kosmos.runTest {
            val hum = createHeadsUpManagerPhone()
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            val notifEntry =
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)

            // Add notifEntry to ANM mAlertEntries map and make it NOT unpinned
            hum.showNotification(notifEntry)

            val headsUpEntry = hum.getHeadsUpEntry(notifEntry.key)
            headsUpEntry!!.mWasUnpinned = false

            Assert.assertTrue(hum.shouldHeadsUpBecomePinned(notifEntry))
        }

    @Test
    fun testShouldHeadsUpBecomePinned_wasUnpinned_false() =
        kosmos.runTest {
            val hum = createHeadsUpManagerPhone()
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            val notifEntry =
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)

            // Add notifEntry to ANM mAlertEntries map and make it unpinned
            hum.showNotification(notifEntry)

            val headsUpEntry = hum.getHeadsUpEntry(notifEntry.key)
            headsUpEntry!!.mWasUnpinned = true

            Assert.assertFalse(hum.shouldHeadsUpBecomePinned(notifEntry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeNotExpanded_true() =
        kosmos.runTest {
            // GIVEN
            whenever(mShadeInteractor.isAnyFullyExpanded).thenReturn(MutableStateFlow(false))
            val hmp = createHeadsUpManagerPhone()
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE)

            // THEN
            Assert.assertTrue(hmp.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeLocked_false() =
        kosmos.runTest {
            // GIVEN
            val hmp = createHeadsUpManagerPhone()
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE_LOCKED)

            // THEN
            Assert.assertFalse(hmp.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeUnknown_false() =
        kosmos.runTest {
            // GIVEN
            val hmp = createHeadsUpManagerPhone()
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(1207)

            // THEN
            Assert.assertFalse(hmp.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_keyguardWithBypassOn_true() =
        kosmos.runTest {
            // GIVEN
            whenever(keyguardBypassController.bypassEnabled).thenReturn(true)
            val hmp = createHeadsUpManagerPhone()
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            // THEN
            Assert.assertTrue(hmp.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_keyguardWithBypassOff_false() =
        kosmos.runTest {
            // GIVEN
            whenever(keyguardBypassController.bypassEnabled).thenReturn(false)
            val hmp = createHeadsUpManagerPhone()
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            // THEN
            Assert.assertFalse(hmp.shouldHeadsUpBecomePinned(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeExpanded_false() =
        kosmos.runTest {
            // GIVEN
            whenever(mShadeInteractor.isAnyExpanded).thenReturn(MutableStateFlow(true))
            val hmp = createHeadsUpManagerPhone()
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE)

            // THEN
            Assert.assertFalse(hmp.shouldHeadsUpBecomePinned(entry))
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
