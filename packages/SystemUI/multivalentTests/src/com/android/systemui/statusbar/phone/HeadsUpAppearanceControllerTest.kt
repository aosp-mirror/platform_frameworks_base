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
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeHeadsUpTracker
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.HeadsUpStatusBarView
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationIconInteractor
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.notification.headsup.PinnedStatus
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.row.shared.AsyncGroupHeaderViewInflation
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.KeyguardStateController
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
    private val mStackScrollerController: NotificationStackScrollLayoutController =
        mock<NotificationStackScrollLayoutController>()
    private val mShadeViewController: ShadeViewController = mock<ShadeViewController>()
    private val mShadeHeadsUpTracker: ShadeHeadsUpTracker = mock<ShadeHeadsUpTracker>()
    private val mDarkIconDispatcher: DarkIconDispatcher = mock<DarkIconDispatcher>()
    private var mHeadsUpAppearanceController: HeadsUpAppearanceController? = null
    private var mTestHelper: NotificationTestHelper? = null
    private var mRow: ExpandableNotificationRow? = null
    private var mEntry: NotificationEntry? = null
    private var mHeadsUpStatusBarView: HeadsUpStatusBarView? = null
    private var mHeadsUpManager: HeadsUpManager? = null
    private var mOperatorNameView: View? = null
    private var mStatusbarStateController: StatusBarStateController? = null
    private var mPhoneStatusBarTransitions: PhoneStatusBarTransitions? = null
    private var mBypassController: KeyguardBypassController? = null
    private var mWakeUpCoordinator: NotificationWakeUpCoordinator? = null
    private var mKeyguardStateController: KeyguardStateController? = null
    private var mCommandQueue: CommandQueue? = null
    private var mNotificationRoundnessManager: NotificationRoundnessManager? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        allowTestableLooperAsMainThread()
        mTestHelper = NotificationTestHelper(mContext, mDependency, TestableLooper.get(this))
        mRow = mTestHelper!!.createRow()
        mEntry = mRow!!.entry
        mHeadsUpStatusBarView = HeadsUpStatusBarView(mContext, mock<View>(), mock<TextView>())
        mHeadsUpManager = mock<HeadsUpManager>()
        mOperatorNameView = View(mContext)
        mStatusbarStateController = mock<StatusBarStateController>()
        mPhoneStatusBarTransitions = mock<PhoneStatusBarTransitions>()
        mBypassController = mock<KeyguardBypassController>()
        mWakeUpCoordinator = mock<NotificationWakeUpCoordinator>()
        mKeyguardStateController = mock<KeyguardStateController>()
        mCommandQueue = mock<CommandQueue>()
        mNotificationRoundnessManager = mock<NotificationRoundnessManager>()
        whenever(mShadeViewController.shadeHeadsUpTracker).thenReturn(mShadeHeadsUpTracker)
        mHeadsUpAppearanceController =
            HeadsUpAppearanceController(
                mHeadsUpManager,
                mStatusbarStateController,
                mPhoneStatusBarTransitions,
                mBypassController,
                mWakeUpCoordinator,
                mDarkIconDispatcher,
                mKeyguardStateController,
                mCommandQueue,
                mStackScrollerController,
                mShadeViewController,
                mNotificationRoundnessManager,
                mHeadsUpStatusBarView,
                Clock(mContext, null),
                mock<HeadsUpNotificationIconInteractor>(),
                Optional.of(mOperatorNameView!!),
            )
        mHeadsUpAppearanceController!!.setAppearFraction(0.0f, 0.0f)
    }

    @Test
    fun testShowinEntryUpdated() {
        mRow!!.setPinnedStatus(PinnedStatus.PinnedBySystem)
        whenever(mHeadsUpManager!!.hasPinnedHeadsUp()).thenReturn(true)
        whenever(mHeadsUpManager!!.getTopEntry()).thenReturn(mEntry)
        mHeadsUpAppearanceController!!.onHeadsUpPinned(mEntry)
        assertThat(mHeadsUpStatusBarView!!.showingEntry).isEqualTo(mRow!!.entry)

        mRow!!.setPinnedStatus(PinnedStatus.NotPinned)
        whenever(mHeadsUpManager!!.hasPinnedHeadsUp()).thenReturn(false)
        mHeadsUpAppearanceController!!.onHeadsUpUnPinned(mEntry)
        assertThat(mHeadsUpStatusBarView!!.showingEntry).isNull()
    }

    @Test
    fun testPinnedStatusUpdated() {
        mRow!!.setPinnedStatus(PinnedStatus.PinnedBySystem)
        whenever(mHeadsUpManager!!.hasPinnedHeadsUp()).thenReturn(true)
        whenever(mHeadsUpManager!!.getTopEntry()).thenReturn(mEntry)
        mHeadsUpAppearanceController!!.onHeadsUpPinned(mEntry)
        assertThat(mHeadsUpAppearanceController!!.pinnedStatus)
            .isEqualTo(PinnedStatus.PinnedBySystem)

        mRow!!.setPinnedStatus(PinnedStatus.NotPinned)
        whenever(mHeadsUpManager!!.hasPinnedHeadsUp()).thenReturn(false)
        mHeadsUpAppearanceController!!.onHeadsUpUnPinned(mEntry)
        assertThat(mHeadsUpAppearanceController!!.pinnedStatus).isEqualTo(PinnedStatus.NotPinned)
    }

    @Test
    @DisableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    fun testHeaderUpdated() {
        mRow!!.setPinnedStatus(PinnedStatus.PinnedBySystem)
        whenever(mHeadsUpManager!!.hasPinnedHeadsUp()).thenReturn(true)
        whenever(mHeadsUpManager!!.getTopEntry()).thenReturn(mEntry)
        mHeadsUpAppearanceController!!.onHeadsUpPinned(mEntry)
        assertThat(mRow!!.headerVisibleAmount).isEqualTo(0.0f)

        mRow!!.setPinnedStatus(PinnedStatus.NotPinned)
        whenever(mHeadsUpManager!!.hasPinnedHeadsUp()).thenReturn(false)
        mHeadsUpAppearanceController!!.onHeadsUpUnPinned(mEntry)
        assertThat(mRow!!.headerVisibleAmount).isEqualTo(1.0f)
    }

    @Test
    fun testOperatorNameViewUpdated() {
        mHeadsUpAppearanceController!!.setAnimationsEnabled(false)

        mRow!!.setPinnedStatus(PinnedStatus.PinnedBySystem)
        whenever(mHeadsUpManager!!.hasPinnedHeadsUp()).thenReturn(true)
        whenever(mHeadsUpManager!!.getTopEntry()).thenReturn(mEntry)
        mHeadsUpAppearanceController!!.onHeadsUpPinned(mEntry)
        assertThat(mOperatorNameView!!.visibility).isEqualTo(View.INVISIBLE)

        mRow!!.setPinnedStatus(PinnedStatus.NotPinned)
        whenever(mHeadsUpManager!!.hasPinnedHeadsUp()).thenReturn(false)
        mHeadsUpAppearanceController!!.onHeadsUpUnPinned(mEntry)
        assertThat(mOperatorNameView!!.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun constructor_animationValuesUpdated() {
        val appearFraction = .75f
        val expandedHeight = 400f
        whenever(mStackScrollerController.appearFraction).thenReturn(appearFraction)
        whenever(mStackScrollerController.expandedHeight).thenReturn(expandedHeight)

        val newController =
            HeadsUpAppearanceController(
                mHeadsUpManager,
                mStatusbarStateController,
                mPhoneStatusBarTransitions,
                mBypassController,
                mWakeUpCoordinator,
                mDarkIconDispatcher,
                mKeyguardStateController,
                mCommandQueue,
                mStackScrollerController,
                mShadeViewController,
                mNotificationRoundnessManager,
                mHeadsUpStatusBarView,
                Clock(mContext, null),
                mock<HeadsUpNotificationIconInteractor>(),
                Optional.empty(),
            )

        assertThat(newController.mExpandedHeight).isEqualTo(expandedHeight)
        assertThat(newController.mAppearFraction).isEqualTo(appearFraction)
    }

    @Test
    fun testDestroy() {
        reset(mHeadsUpManager)
        reset(mDarkIconDispatcher)
        reset(mShadeHeadsUpTracker)
        reset(mStackScrollerController)

        mHeadsUpAppearanceController!!.onViewDetached()

        verify(mHeadsUpManager!!).removeListener(any())
        verify(mDarkIconDispatcher).removeDarkReceiver(any())
        verify(mShadeHeadsUpTracker).removeTrackingHeadsUpListener(any())
        verify(mShadeHeadsUpTracker).setHeadsUpAppearanceController(null)
        verify(mStackScrollerController).removeOnExpandedHeightChangedListener(any())
    }

    @Test
    fun testPulsingRoundness_onUpdateHeadsUpAndPulsingRoundness() {
        // Pulsing: Enable flag and dozing
        whenever(mNotificationRoundnessManager!!.shouldRoundNotificationPulsing()).thenReturn(true)
        whenever(mTestHelper!!.statusBarStateController.isDozing).thenReturn(true)

        // Pulsing: Enabled
        mRow!!.isHeadsUp = true
        mHeadsUpAppearanceController!!.updateHeadsUpAndPulsingRoundness(mEntry)

        val debugString: String = mRow!!.roundableState.debugString()
        // If Pulsing is enabled, roundness should be set to 1
        assertThat(mRow!!.topRoundness.toDouble()).isWithin(0.001).of(1.0)
        assertThat(debugString).contains("Pulsing")

        // Pulsing: Disabled
        mRow!!.isHeadsUp = false
        mHeadsUpAppearanceController!!.updateHeadsUpAndPulsingRoundness(mEntry)

        // If Pulsing is disabled, roundness should be set to 0
        assertThat(mRow!!.topRoundness.toDouble()).isWithin(0.001).of(0.0)
    }

    @Test
    fun testPulsingRoundness_onHeadsUpStateChanged() {
        // Pulsing: Enable flag and dozing
        whenever(mNotificationRoundnessManager!!.shouldRoundNotificationPulsing()).thenReturn(true)
        whenever(mTestHelper!!.statusBarStateController.isDozing).thenReturn(true)

        // Pulsing: Enabled
        mEntry!!.setHeadsUp(true)
        mHeadsUpAppearanceController!!.onHeadsUpStateChanged(mEntry!!, true)

        val debugString: String = mRow!!.roundableState.debugString()
        // If Pulsing is enabled, roundness should be set to 1
        assertThat(mRow!!.topRoundness.toDouble()).isWithin(0.001).of(1.0)
        assertThat(debugString).contains("Pulsing")

        // Pulsing: Disabled
        mEntry!!.setHeadsUp(false)
        mHeadsUpAppearanceController!!.onHeadsUpStateChanged(mEntry!!, false)

        // If Pulsing is disabled, roundness should be set to 0
        assertThat(mRow!!.topRoundness.toDouble()).isWithin(0.001).of(0.0)
    }

    @Test
    fun onHeadsUpStateChanged_true_transitionsNotified() {
        mHeadsUpAppearanceController!!.onHeadsUpStateChanged(mEntry!!, true)

        verify(mPhoneStatusBarTransitions!!).onHeadsUpStateChanged(true)
    }

    @Test
    fun onHeadsUpStateChanged_false_transitionsNotified() {
        mHeadsUpAppearanceController!!.onHeadsUpStateChanged(mEntry!!, false)

        verify(mPhoneStatusBarTransitions!!).onHeadsUpStateChanged(false)
    }
}
