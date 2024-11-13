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
package com.android.systemui.statusbar.policy

import android.content.Context
import android.os.Handler
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.FakeStatusBarStateController
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl
import com.android.systemui.statusbar.notification.HeadsUpManagerPhone
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.mockExecutorHandler
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.time.SystemClock
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
import org.mockito.Mock
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper
class HeadsUpManagerPhoneTest(flags: FlagsParameterization) : BaseHeadsUpManagerTest(flags) {

    private val mHeadsUpManagerLogger = HeadsUpManagerLogger(logcatLogBuffer())

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Mock private lateinit var mGroupManager: GroupMembershipManager

    @Mock private lateinit var mVSProvider: VisualStabilityProvider

    @Mock private lateinit var mStatusBarStateController: StatusBarStateController

    @Mock private lateinit var mBypassController: KeyguardBypassController

    @Mock private lateinit var mConfigurationController: ConfigurationControllerImpl

    @Mock private lateinit var mAccessibilityManagerWrapper: AccessibilityManagerWrapper

    @Mock private lateinit var mUiEventLogger: UiEventLogger

    private val mJavaAdapter: JavaAdapter = JavaAdapter(testScope.backgroundScope)

    @Mock private lateinit var mShadeInteractor: ShadeInteractor

    @Mock private lateinit var dumpManager: DumpManager
    private lateinit var mAvalancheController: AvalancheController

    @Mock private lateinit var mBgHandler: Handler

    private class TestableHeadsUpManagerPhone(
        context: Context,
        headsUpManagerLogger: HeadsUpManagerLogger,
        groupManager: GroupMembershipManager,
        visualStabilityProvider: VisualStabilityProvider,
        statusBarStateController: StatusBarStateController,
        keyguardBypassController: KeyguardBypassController,
        configurationController: ConfigurationController,
        globalSettings: GlobalSettings,
        systemClock: SystemClock,
        executor: DelayableExecutor,
        accessibilityManagerWrapper: AccessibilityManagerWrapper,
        uiEventLogger: UiEventLogger,
        javaAdapter: JavaAdapter,
        shadeInteractor: ShadeInteractor,
        avalancheController: AvalancheController
    ) :
        HeadsUpManagerPhone(
            context,
            headsUpManagerLogger,
            statusBarStateController,
            keyguardBypassController,
            groupManager,
            visualStabilityProvider,
            configurationController,
            mockExecutorHandler(executor),
            globalSettings,
            systemClock,
            executor,
            accessibilityManagerWrapper,
            uiEventLogger,
            javaAdapter,
            shadeInteractor,
            avalancheController
        ) {
        init {
            mMinimumDisplayTime = TEST_MINIMUM_DISPLAY_TIME
            mAutoDismissTime = TEST_AUTO_DISMISS_TIME
        }

        /** Wrapper for [BaseHeadsUpManager.shouldHeadsUpBecomePinned] for testing */
        fun shouldHeadsUpBecomePinnedWrapper(entry: NotificationEntry): Boolean {
            return shouldHeadsUpBecomePinned(entry)
        }
    }

    private fun createHeadsUpManagerPhone(): HeadsUpManagerPhone {
        return TestableHeadsUpManagerPhone(
            mContext,
            mHeadsUpManagerLogger,
            mGroupManager,
            mVSProvider,
            mStatusBarStateController,
            mBypassController,
            mConfigurationController,
            mGlobalSettings,
            mSystemClock,
            mExecutor,
            mAccessibilityManagerWrapper,
            mUiEventLogger,
            mJavaAdapter,
            mShadeInteractor,
            mAvalancheController
        )
    }

    @Before
    fun setUp() {
        whenever(mShadeInteractor.isAnyExpanded).thenReturn(MutableStateFlow(false))
        whenever(mVSProvider.isReorderingAllowed).thenReturn(true)
        val accessibilityMgr =
            mDependency.injectMockDependency(AccessibilityManagerWrapper::class.java)
        whenever(
                accessibilityMgr.getRecommendedTimeoutMillis(
                    ArgumentMatchers.anyInt(),
                    ArgumentMatchers.anyInt()
                )
            )
            .thenReturn(TEST_AUTO_DISMISS_TIME)
        mDependency.injectMockDependency(NotificationShadeWindowController::class.java)
        mContext
            .getOrCreateTestableResources()
            .addOverride(R.integer.ambient_notification_extension_time, 500)
        mAvalancheController =
            AvalancheController(dumpManager, mUiEventLogger, mHeadsUpManagerLogger, mBgHandler)
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
                /* reason= */ "swipe out"
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
        mSystemClock.advanceTime((TEST_AUTO_DISMISS_TIME + hmp.mExtensionTime / 2).toLong())
        Assert.assertTrue(hmp.isHeadsUpEntry(entry.key))
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_removeWhenReorderingAllowedTrue() {
        whenever(mVSProvider.isReorderingAllowed).thenReturn(true)
        val hmp = createHeadsUpManagerPhone()

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(notifEntry)
        assertThat(hmp.mEntriesToRemoveWhenReorderingAllowed.contains(notifEntry)).isTrue();
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_reorderNotAllowed_seenInShadeTrue() {
        whenever(mVSProvider.isReorderingAllowed).thenReturn(false)
        val hmp = createHeadsUpManagerPhone()

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(notifEntry)
        assertThat(notifEntry.isSeenInShade).isTrue();
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_reorderAllowed_seenInShadeFalse() {
        whenever(mVSProvider.isReorderingAllowed).thenReturn(true)
        val hmp = createHeadsUpManagerPhone()

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        hmp.showNotification(notifEntry)
        assertThat(notifEntry.isSeenInShade).isFalse();
    }

    @Test
    fun shouldHeadsUpBecomePinned_shadeNotExpanded_true() =
        testScope.runTest {
            // GIVEN
            val statusBarStateController = FakeStatusBarStateController()
            whenever(mShadeInteractor.isAnyFullyExpanded).thenReturn(MutableStateFlow(false))
            val hmp =
                TestableHeadsUpManagerPhone(
                    mContext,
                    mHeadsUpManagerLogger,
                    mGroupManager,
                    mVSProvider,
                    statusBarStateController,
                    mBypassController,
                    mConfigurationController,
                    mGlobalSettings,
                    mSystemClock,
                    mExecutor,
                    mAccessibilityManagerWrapper,
                    mUiEventLogger,
                    mJavaAdapter,
                    mShadeInteractor,
                    mAvalancheController
                )
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE)
            runCurrent()

            // THEN
            Assert.assertTrue(hmp.shouldHeadsUpBecomePinnedWrapper(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeLocked_false() =
        testScope.runTest {
            // GIVEN
            val statusBarStateController = FakeStatusBarStateController()
            val hmp =
                TestableHeadsUpManagerPhone(
                    mContext,
                    mHeadsUpManagerLogger,
                    mGroupManager,
                    mVSProvider,
                    statusBarStateController,
                    mBypassController,
                    mConfigurationController,
                    mGlobalSettings,
                    mSystemClock,
                    mExecutor,
                    mAccessibilityManagerWrapper,
                    mUiEventLogger,
                    mJavaAdapter,
                    mShadeInteractor,
                    mAvalancheController
                )
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE_LOCKED)
            runCurrent()

            // THEN
            Assert.assertFalse(hmp.shouldHeadsUpBecomePinnedWrapper(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeUnknown_false() =
        testScope.runTest {
            // GIVEN
            val statusBarStateController = FakeStatusBarStateController()
            val hmp =
                TestableHeadsUpManagerPhone(
                    mContext,
                    mHeadsUpManagerLogger,
                    mGroupManager,
                    mVSProvider,
                    statusBarStateController,
                    mBypassController,
                    mConfigurationController,
                    mGlobalSettings,
                    mSystemClock,
                    mExecutor,
                    mAccessibilityManagerWrapper,
                    mUiEventLogger,
                    mJavaAdapter,
                    mShadeInteractor,
                    mAvalancheController
                )
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(1207)
            runCurrent()

            // THEN
            Assert.assertFalse(hmp.shouldHeadsUpBecomePinnedWrapper(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_keyguardWithBypassOn_true() =
        testScope.runTest {
            // GIVEN
            val statusBarStateController = FakeStatusBarStateController()
            whenever(mBypassController.bypassEnabled).thenReturn(true)
            val hmp =
                TestableHeadsUpManagerPhone(
                    mContext,
                    mHeadsUpManagerLogger,
                    mGroupManager,
                    mVSProvider,
                    statusBarStateController,
                    mBypassController,
                    mConfigurationController,
                    mGlobalSettings,
                    mSystemClock,
                    mExecutor,
                    mAccessibilityManagerWrapper,
                    mUiEventLogger,
                    mJavaAdapter,
                    mShadeInteractor,
                    mAvalancheController
                )
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.KEYGUARD)
            runCurrent()

            // THEN
            Assert.assertTrue(hmp.shouldHeadsUpBecomePinnedWrapper(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_keyguardWithBypassOff_false() =
        testScope.runTest {
            // GIVEN
            val statusBarStateController = FakeStatusBarStateController()
            whenever(mBypassController.bypassEnabled).thenReturn(false)
            val hmp =
                TestableHeadsUpManagerPhone(
                    mContext,
                    mHeadsUpManagerLogger,
                    mGroupManager,
                    mVSProvider,
                    statusBarStateController,
                    mBypassController,
                    mConfigurationController,
                    mGlobalSettings,
                    mSystemClock,
                    mExecutor,
                    mAccessibilityManagerWrapper,
                    mUiEventLogger,
                    mJavaAdapter,
                    mShadeInteractor,
                    mAvalancheController
                )
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.KEYGUARD)
            runCurrent()

            // THEN
            Assert.assertFalse(hmp.shouldHeadsUpBecomePinnedWrapper(entry))
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeExpanded_false() =
        testScope.runTest {
            // GIVEN
            val statusBarStateController = FakeStatusBarStateController()
            whenever(mShadeInteractor.isAnyExpanded).thenReturn(MutableStateFlow(true))
            val hmp =
                TestableHeadsUpManagerPhone(
                    mContext,
                    mHeadsUpManagerLogger,
                    mGroupManager,
                    mVSProvider,
                    statusBarStateController,
                    mBypassController,
                    mConfigurationController,
                    mGlobalSettings,
                    mSystemClock,
                    mExecutor,
                    mAccessibilityManagerWrapper,
                    mUiEventLogger,
                    mJavaAdapter,
                    mShadeInteractor,
                    mAvalancheController
                )
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE)
            runCurrent()

            // THEN
            Assert.assertFalse(hmp.shouldHeadsUpBecomePinnedWrapper(entry))
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
