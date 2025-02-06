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
 * limitations under the License
 */
package com.android.systemui.statusbar.phone

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.plugins.fakeDarkIconDispatcher
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.shade.ShadeHeadsUpTracker
import com.android.systemui.shade.shadeViewController
import com.android.systemui.statusbar.HeadsUpStatusBarView
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.commandQueue
import com.android.systemui.statusbar.headsup.shared.StatusBarNoHunBehavior
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationIconInteractor
import com.android.systemui.statusbar.notification.domain.interactor.headsUpNotificationIconInteractor
import com.android.systemui.statusbar.notification.headsup.PinnedStatus
import com.android.systemui.statusbar.notification.headsup.mockHeadsUpManager
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.row.shared.AsyncGroupHeaderViewInflation
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class HeadsUpAppearanceControllerTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val stackScrollerController = mock<NotificationStackScrollLayoutController>()
    private val shadeViewController = kosmos.shadeViewController
    private val shadeHeadsUpTracker = mock<ShadeHeadsUpTracker>()
    private val darkIconDispatcher = kosmos.fakeDarkIconDispatcher
    private val statusBarStateController = kosmos.statusBarStateController
    private val phoneStatusBarTransitions = kosmos.mockPhoneStatusBarTransitions
    private val bypassController = kosmos.keyguardBypassController
    private val wakeUpCoordinator = kosmos.notificationWakeUpCoordinator
    private val keyguardStateController = kosmos.keyguardStateController
    private val commandQueue = kosmos.commandQueue
    private val notificationRoundnessManager = mock<NotificationRoundnessManager>()
    private var headsUpManager = kosmos.mockHeadsUpManager

    private lateinit var testHelper: NotificationTestHelper
    private lateinit var row: ExpandableNotificationRow
    private lateinit var entry: NotificationEntry
    private lateinit var headsUpStatusBarView: HeadsUpStatusBarView
    private lateinit var operatorNameView: View

    private lateinit var underTest: HeadsUpAppearanceController

    @Before
    @Throws(Exception::class)
    fun setUp() {
        allowTestableLooperAsMainThread()
        testHelper = NotificationTestHelper(mContext, mDependency, TestableLooper.get(this))
        row = testHelper.createRow()
        entry = row.entry
        headsUpStatusBarView = HeadsUpStatusBarView(mContext, mock<View>(), mock<TextView>())
        operatorNameView = View(mContext)

        whenever(shadeViewController.shadeHeadsUpTracker).thenReturn(shadeHeadsUpTracker)
        underTest =
            HeadsUpAppearanceController(
                headsUpManager,
                statusBarStateController,
                phoneStatusBarTransitions,
                bypassController,
                wakeUpCoordinator,
                darkIconDispatcher,
                keyguardStateController,
                commandQueue,
                stackScrollerController,
                shadeViewController,
                notificationRoundnessManager,
                headsUpStatusBarView,
                Clock(mContext, null),
                kosmos.headsUpNotificationIconInteractor,
                Optional.of(operatorNameView),
            )
        underTest.setAppearFraction(0.0f, 0.0f)
    }

    @Test
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun showingEntryUpdated_whenPinnedBySystem() {
        row.setPinnedStatus(PinnedStatus.PinnedBySystem)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)

        assertThat(headsUpStatusBarView.showingEntry).isEqualTo(row.entry)

        row.setPinnedStatus(PinnedStatus.NotPinned)
        setHeadsUpNotifOnManager(null)
        underTest.onHeadsUpUnPinned(entry)

        assertThat(headsUpStatusBarView.showingEntry).isNull()
    }

    @Test
    @EnableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun showingEntryNotUpdated_whenPinnedBySystem_whenNoHunBehaviorEnabled() {
        row.setPinnedStatus(PinnedStatus.PinnedBySystem)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)

        assertThat(headsUpStatusBarView.showingEntry).isNull()
    }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME, StatusBarNoHunBehavior.FLAG_NAME)
    fun showingEntryUpdated_whenPinnedByUser_andNotifChipsFlagOff() {
        row.setPinnedStatus(PinnedStatus.PinnedByUser)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)

        assertThat(headsUpStatusBarView.showingEntry).isEqualTo(row.entry)
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun showingEntryNotUpdated_whenPinnedByUser_andNotifChipsFlagOn() {
        // WHEN the HUN was pinned by the user
        row.setPinnedStatus(PinnedStatus.PinnedByUser)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)

        // THEN we don't show the HUN status bar view
        assertThat(headsUpStatusBarView.showingEntry).isNull()
    }

    @Test
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun pinnedStatusUpdatedToSystem_whenPinnedBySystem() {
        row.setPinnedStatus(PinnedStatus.PinnedBySystem)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)
        assertThat(underTest.pinnedStatus).isEqualTo(PinnedStatus.PinnedBySystem)

        row.setPinnedStatus(PinnedStatus.NotPinned)
        setHeadsUpNotifOnManager(null)
        underTest.onHeadsUpUnPinned(entry)
        assertThat(underTest.pinnedStatus).isEqualTo(PinnedStatus.NotPinned)
    }

    @Test
    @EnableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun pinnedStatusNotUpdatedToSystem_whenPinnedBySystem_whenNoHunBehaviorEnabled() {
        row.setPinnedStatus(PinnedStatus.PinnedBySystem)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)

        assertThat(underTest.pinnedStatus).isEqualTo(PinnedStatus.NotPinned)
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun pinnedStatusUpdatedToNotPinned_whenPinnedByUser_andNotifChipsFlagOn() {
        row.setPinnedStatus(PinnedStatus.PinnedByUser)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)

        // It's unintuitive that the pinnedStatus wouldn't match the status on the notification.
        // Explanation: HeadsUpAppearanceController#updateTopEntry doesn't do anything if
        // HeadsUpManager.pinnedHeadsUpStatus != PinnedBySystem. So when we're PinnedByUser,
        // HeadsUpAppearanceController early-returns before even updating the pinned status.
        assertThat(underTest.pinnedStatus).isEqualTo(PinnedStatus.NotPinned)
    }

    @Test
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun isolatedIconSet_whenPinnedBySystem() =
        kosmos.runTest {
            val latestIsolatedIcon by
                collectLastValue(kosmos.headsUpNotificationIconInteractor.isolatedNotification)

            row.setPinnedStatus(PinnedStatus.PinnedBySystem)
            setHeadsUpNotifOnManager(entry)
            underTest.onHeadsUpPinned(entry)

            assertThat(latestIsolatedIcon).isEqualTo(entry.key)

            row.setPinnedStatus(PinnedStatus.NotPinned)
            setHeadsUpNotifOnManager(null)
            underTest.onHeadsUpUnPinned(entry)

            assertThat(latestIsolatedIcon).isNull()
        }

    @Test
    @EnableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun isolatedIconNotSet_whenPinnedBySystem_whenNoHunBehaviorEnabled() =
        kosmos.runTest {
            val latestIsolatedIcon by
                collectLastValue(kosmos.headsUpNotificationIconInteractor.isolatedNotification)

            row.setPinnedStatus(PinnedStatus.PinnedBySystem)
            setHeadsUpNotifOnManager(entry)
            underTest.onHeadsUpPinned(entry)

            assertThat(latestIsolatedIcon).isNull()
        }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME, StatusBarNoHunBehavior.FLAG_NAME)
    fun isolatedIconSet_whenPinnedByUser_andNotifChipsFlagOff() =
        kosmos.runTest {
            val latestIsolatedIcon by
                collectLastValue(kosmos.headsUpNotificationIconInteractor.isolatedNotification)

            row.setPinnedStatus(PinnedStatus.PinnedByUser)
            setHeadsUpNotifOnManager(entry)
            underTest.onHeadsUpPinned(entry)

            assertThat(latestIsolatedIcon).isEqualTo(entry.key)
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun isolatedIconNotSet_whenPinnedByUser_andNotifChipsFlagOn() =
        kosmos.runTest {
            val latestIsolatedIcon by
                collectLastValue(kosmos.headsUpNotificationIconInteractor.isolatedNotification)

            row.setPinnedStatus(PinnedStatus.PinnedByUser)
            setHeadsUpNotifOnManager(entry)
            underTest.onHeadsUpPinned(entry)

            assertThat(latestIsolatedIcon).isNull()
        }

    @Test
    @DisableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    fun testHeaderUpdated() {
        row.setPinnedStatus(PinnedStatus.PinnedBySystem)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)
        assertThat(row.headerVisibleAmount).isEqualTo(0.0f)

        row.setPinnedStatus(PinnedStatus.NotPinned)
        setHeadsUpNotifOnManager(null)
        underTest.onHeadsUpUnPinned(entry)
        assertThat(row.headerVisibleAmount).isEqualTo(1.0f)
    }

    @Test
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun operatorNameViewUpdated_whenPinnedBySystem() {
        underTest.setAnimationsEnabled(false)

        row.setPinnedStatus(PinnedStatus.PinnedBySystem)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)
        assertThat(operatorNameView.visibility).isEqualTo(View.INVISIBLE)

        row.setPinnedStatus(PinnedStatus.NotPinned)
        setHeadsUpNotifOnManager(null)
        underTest.onHeadsUpUnPinned(entry)
        assertThat(operatorNameView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @EnableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun operatorNameViewNotUpdated_whenPinnedBySystem_whenNoHunBehaviorEnabled() {
        underTest.setAnimationsEnabled(false)

        row.setPinnedStatus(PinnedStatus.PinnedBySystem)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)

        assertThat(operatorNameView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME, StatusBarNoHunBehavior.FLAG_NAME)
    fun operatorNameViewUpdated_whenPinnedByUser_andNotifChipsFlagOff() {
        underTest.setAnimationsEnabled(false)

        row.setPinnedStatus(PinnedStatus.PinnedByUser)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)

        assertThat(operatorNameView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun operatorNameViewNotUpdated_whenPinnedByUser_andNotifChipsFlagOn() {
        underTest.setAnimationsEnabled(false)

        // WHEN the row was pinned by the user
        row.setPinnedStatus(PinnedStatus.PinnedByUser)
        setHeadsUpNotifOnManager(entry)
        underTest.onHeadsUpPinned(entry)

        // THEN we don't need to hide the operator name view
        assertThat(operatorNameView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun constructor_animationValuesUpdated() {
        val appearFraction = .75f
        val expandedHeight = 400f
        whenever(stackScrollerController.appearFraction).thenReturn(appearFraction)
        whenever(stackScrollerController.expandedHeight).thenReturn(expandedHeight)

        val newController =
            HeadsUpAppearanceController(
                headsUpManager,
                statusBarStateController,
                phoneStatusBarTransitions,
                bypassController,
                wakeUpCoordinator,
                darkIconDispatcher,
                keyguardStateController,
                commandQueue,
                stackScrollerController,
                shadeViewController,
                notificationRoundnessManager,
                headsUpStatusBarView,
                Clock(mContext, null),
                mock<HeadsUpNotificationIconInteractor>(),
                Optional.empty(),
            )

        assertThat(newController.mExpandedHeight).isEqualTo(expandedHeight)
        assertThat(newController.mAppearFraction).isEqualTo(appearFraction)
    }

    @Test
    fun testDestroy() {
        reset(headsUpManager)
        reset(shadeHeadsUpTracker)
        reset(stackScrollerController)

        underTest.onViewDetached()

        verify(headsUpManager).removeListener(any())
        assertThat(darkIconDispatcher.receivers).isEmpty()
        verify(shadeHeadsUpTracker).removeTrackingHeadsUpListener(any())
        verify(shadeHeadsUpTracker).setHeadsUpAppearanceController(null)
        verify(stackScrollerController).removeOnExpandedHeightChangedListener(any())
    }

    @Test
    fun testPulsingRoundness_onUpdateHeadsUpAndPulsingRoundness() {
        // Pulsing: Enable flag and dozing
        whenever(notificationRoundnessManager.shouldRoundNotificationPulsing()).thenReturn(true)
        whenever(testHelper.statusBarStateController.isDozing).thenReturn(true)

        // Pulsing: Enabled
        row.isHeadsUp = true
        underTest.updateHeadsUpAndPulsingRoundness(entry)

        val debugString: String = row.roundableState.debugString()
        // If Pulsing is enabled, roundness should be set to 1
        assertThat(row.topRoundness.toDouble()).isWithin(0.001).of(1.0)
        assertThat(debugString).contains("Pulsing")

        // Pulsing: Disabled
        row.isHeadsUp = false
        underTest.updateHeadsUpAndPulsingRoundness(entry)

        // If Pulsing is disabled, roundness should be set to 0
        assertThat(row.topRoundness.toDouble()).isWithin(0.001).of(0.0)
    }

    @Test
    fun testPulsingRoundness_onHeadsUpStateChanged() {
        // Pulsing: Enable flag and dozing
        whenever(notificationRoundnessManager.shouldRoundNotificationPulsing()).thenReturn(true)
        whenever(testHelper.statusBarStateController.isDozing).thenReturn(true)

        // Pulsing: Enabled
        entry.setHeadsUp(true)
        underTest.onHeadsUpStateChanged(entry, true)

        val debugString: String = row.roundableState.debugString()
        // If Pulsing is enabled, roundness should be set to 1
        assertThat(row.topRoundness.toDouble()).isWithin(0.001).of(1.0)
        assertThat(debugString).contains("Pulsing")

        // Pulsing: Disabled
        entry.setHeadsUp(false)
        underTest.onHeadsUpStateChanged(entry, false)

        // If Pulsing is disabled, roundness should be set to 0
        assertThat(row.topRoundness.toDouble()).isWithin(0.001).of(0.0)
    }

    @Test
    fun onHeadsUpStateChanged_true_transitionsNotified() {
        underTest.onHeadsUpStateChanged(entry, true)

        verify(phoneStatusBarTransitions).onHeadsUpStateChanged(true)
    }

    @Test
    fun onHeadsUpStateChanged_false_transitionsNotified() {
        underTest.onHeadsUpStateChanged(entry, false)

        verify(phoneStatusBarTransitions).onHeadsUpStateChanged(false)
    }

    private fun setHeadsUpNotifOnManager(entry: NotificationEntry?) {
        if (entry != null) {
            whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(true)
            whenever(headsUpManager.pinnedHeadsUpStatus()).thenReturn(entry.pinnedStatus)
            whenever(headsUpManager.getTopEntry()).thenReturn(entry)
        } else {
            whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(false)
            whenever(headsUpManager.pinnedHeadsUpStatus()).thenReturn(PinnedStatus.NotPinned)
            whenever(headsUpManager.getTopEntry()).thenReturn(null)
        }
    }
}
