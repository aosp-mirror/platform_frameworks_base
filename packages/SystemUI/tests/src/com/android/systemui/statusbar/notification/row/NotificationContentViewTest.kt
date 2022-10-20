/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row

import android.content.res.Resources
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.view.NotificationHeaderView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.internal.widget.NotificationActionListLayout
import com.android.internal.widget.NotificationExpandButton
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.dialog.MediaOutputDialogFactory
import com.android.systemui.statusbar.notification.FeedbackIcon
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationContentViewTest : SysuiTestCase() {
    private lateinit var view: NotificationContentView

    @Mock private lateinit var mPeopleNotificationIdentifier: PeopleNotificationIdentifier

    private val notificationContentMargin =
        mContext.resources.getDimensionPixelSize(R.dimen.notification_content_margin)

    @Before
    fun setup() {
        initMocks(this)

        mDependency.injectMockDependency(MediaOutputDialogFactory::class.java)

        view = spy(NotificationContentView(mContext, /* attrs= */ null))
        val row = ExpandableNotificationRow(mContext, /* attrs= */ null)
        row.entry = createMockNotificationEntry(false)
        val spyRow = spy(row)
        doReturn(10).whenever(spyRow).intrinsicHeight

        with(view) {
            initialize(mPeopleNotificationIdentifier, mock(), mock(), mock())
            setContainingNotification(spyRow)
            setHeights(/* smallHeight= */ 10, /* headsUpMaxHeight= */ 20, /* maxHeight= */ 30)
            contractedChild = createViewWithHeight(10)
            expandedChild = createViewWithHeight(20)
            headsUpChild = createViewWithHeight(30)
            measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
    }

    private fun createViewWithHeight(height: Int) =
        View(mContext, /* attrs= */ null).apply { minimumHeight = height }

    @Test
    fun testSetFeedbackIcon() {
        // Given: contractedChild, enpandedChild, and headsUpChild being set
        val mockContracted = createMockNotificationHeaderView()
        val mockExpanded = createMockNotificationHeaderView()
        val mockHeadsUp = createMockNotificationHeaderView()

        with(view) {
            contractedChild = mockContracted
            expandedChild = mockExpanded
            headsUpChild = mockHeadsUp
        }

        // When: FeedBackIcon is set
        view.setFeedbackIcon(
            FeedbackIcon(
                R.drawable.ic_feedback_alerted,
                R.string.notification_feedback_indicator_alerted
            )
        )

        // Then: contractedChild, enpandedChild, and headsUpChild should be set to be visible
        verify(mockContracted).visibility = View.VISIBLE
        verify(mockExpanded).visibility = View.VISIBLE
        verify(mockHeadsUp).visibility = View.VISIBLE
    }

    private fun createMockNotificationHeaderView() =
        mock<NotificationHeaderView>().apply {
            whenever(this.findViewById<View>(R.id.feedback)).thenReturn(this)
            whenever(this.context).thenReturn(mContext)
        }

    @Test
    fun testExpandButtonFocusIsCalled() {
        val mockContractedEB = mock<NotificationExpandButton>()
        val mockContracted = createMockNotificationHeaderView(mockContractedEB)

        val mockExpandedEB = mock<NotificationExpandButton>()
        val mockExpanded = createMockNotificationHeaderView(mockExpandedEB)

        val mockHeadsUpEB = mock<NotificationExpandButton>()
        val mockHeadsUp = createMockNotificationHeaderView(mockHeadsUpEB)

        // Set up all 3 child forms
        view.contractedChild = mockContracted
        view.expandedChild = mockExpanded
        view.headsUpChild = mockHeadsUp

        // This is required to call requestAccessibilityFocus()
        view.setFocusOnVisibilityChange()

        // The following will initialize the view and switch from not visible to expanded.
        // (heads-up is actually an alternate form of contracted, hence this enters expanded state)
        view.setHeadsUp(true)
        verify(mockContractedEB, never()).requestAccessibilityFocus()
        verify(mockExpandedEB).requestAccessibilityFocus()
        verify(mockHeadsUpEB, never()).requestAccessibilityFocus()
    }

    private fun createMockNotificationHeaderView(mockExpandedEB: NotificationExpandButton) =
        mock<NotificationHeaderView>().apply {
            whenever(this.animate()).thenReturn(mock())
            whenever(this.findViewById<View>(R.id.expand_button)).thenReturn(mockExpandedEB)
            whenever(this.context).thenReturn(mContext)
        }

    @Test
    fun testRemoteInputVisibleSetsActionsUnimportantHideDescendantsForAccessibility() {
        val mockContracted = mock<NotificationHeaderView>()

        val mockExpandedActions = mock<NotificationActionListLayout>()
        val mockExpanded = mock<NotificationHeaderView>()
        whenever(mockExpanded.findViewById<View>(R.id.actions)).thenReturn(mockExpandedActions)

        val mockHeadsUpActions = mock<NotificationActionListLayout>()
        val mockHeadsUp = mock<NotificationHeaderView>()
        whenever(mockHeadsUp.findViewById<View>(R.id.actions)).thenReturn(mockHeadsUpActions)

        with(view) {
            contractedChild = mockContracted
            expandedChild = mockExpanded
            headsUpChild = mockHeadsUp
        }

        view.setRemoteInputVisible(true)

        verify(mockContracted, never()).findViewById<View>(0)
        verify(mockExpandedActions).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        verify(mockHeadsUpActions).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    @Test
    fun testRemoteInputInvisibleSetsActionsAutoImportantForAccessibility() {
        val mockContracted = mock<NotificationHeaderView>()

        val mockExpandedActions = mock<NotificationActionListLayout>()
        val mockExpanded = mock<NotificationHeaderView>()
        whenever(mockExpanded.findViewById<View>(R.id.actions)).thenReturn(mockExpandedActions)

        val mockHeadsUpActions = mock<NotificationActionListLayout>()
        val mockHeadsUp = mock<NotificationHeaderView>()
        whenever(mockHeadsUp.findViewById<View>(R.id.actions)).thenReturn(mockHeadsUpActions)

        with(view) {
            contractedChild = mockContracted
            expandedChild = mockExpanded
            headsUpChild = mockHeadsUp
        }

        view.setRemoteInputVisible(false)

        verify(mockContracted, never()).findViewById<View>(0)
        verify(mockExpandedActions).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        verify(mockHeadsUpActions).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    @Test
    fun setExpandedChild_notShowBubbleButton_marginTargetBottomMarginShouldNotChange() {
        // Given: bottom margin of actionListMarginTarget is notificationContentMargin
        // Bubble button should not be shown for the given NotificationEntry
        val mockNotificationEntry = createMockNotificationEntry(/* showButton= */ false)
        val mockContainingNotification = createMockContainingNotification(mockNotificationEntry)
        val actionListMarginTarget =
            spy(createLinearLayoutWithBottomMargin(notificationContentMargin))
        val mockExpandedChild = createMockExpandedChild(mockNotificationEntry)
        whenever(
                mockExpandedChild.findViewById<LinearLayout>(
                    R.id.notification_action_list_margin_target
                )
            )
            .thenReturn(actionListMarginTarget)
        view.setContainingNotification(mockContainingNotification)

        // When: call NotificationContentView.setExpandedChild() to set the expandedChild
        view.expandedChild = mockExpandedChild

        // Then: bottom margin of actionListMarginTarget should not change,
        // still be notificationContentMargin
        assertEquals(notificationContentMargin, getMarginBottom(actionListMarginTarget))
    }

    @Test
    fun setExpandedChild_showBubbleButton_marginTargetBottomMarginShouldChangeToZero() {
        // Given: bottom margin of actionListMarginTarget is notificationContentMargin
        // Bubble button should be shown for the given NotificationEntry
        val mockNotificationEntry = createMockNotificationEntry(/* showButton= */ true)
        val mockContainingNotification = createMockContainingNotification(mockNotificationEntry)
        val actionListMarginTarget =
            spy(createLinearLayoutWithBottomMargin(notificationContentMargin))
        val mockExpandedChild = createMockExpandedChild(mockNotificationEntry)
        whenever(
                mockExpandedChild.findViewById<LinearLayout>(
                    R.id.notification_action_list_margin_target
                )
            )
            .thenReturn(actionListMarginTarget)
        view.setContainingNotification(mockContainingNotification)

        // When: call NotificationContentView.setExpandedChild() to set the expandedChild
        view.expandedChild = mockExpandedChild

        // Then: bottom margin of actionListMarginTarget should be set to 0
        assertEquals(0, getMarginBottom(actionListMarginTarget))
    }

    @Test
    fun onNotificationUpdated_notShowBubbleButton_marginTargetBottomMarginShouldNotChange() {
        // Given: bottom margin of actionListMarginTarget is notificationContentMargin
        val mockNotificationEntry = createMockNotificationEntry(/* showButton= */ false)
        val mockContainingNotification = createMockContainingNotification(mockNotificationEntry)
        val actionListMarginTarget =
            spy(createLinearLayoutWithBottomMargin(notificationContentMargin))
        val mockExpandedChild = createMockExpandedChild(mockNotificationEntry)
        whenever(
                mockExpandedChild.findViewById<LinearLayout>(
                    R.id.notification_action_list_margin_target
                )
            )
            .thenReturn(actionListMarginTarget)
        view.setContainingNotification(mockContainingNotification)
        view.expandedChild = mockExpandedChild
        assertEquals(notificationContentMargin, getMarginBottom(actionListMarginTarget))

        // When: call NotificationContentView.onNotificationUpdated() to update the
        // NotificationEntry, which should not show bubble button
        view.onNotificationUpdated(createMockNotificationEntry(/* showButton= */ false))

        // Then: bottom margin of actionListMarginTarget should not change, still be 20
        assertEquals(notificationContentMargin, getMarginBottom(actionListMarginTarget))
    }

    @Test
    fun onNotificationUpdated_showBubbleButton_marginTargetBottomMarginShouldChangeToZero() {
        // Given: bottom margin of actionListMarginTarget is notificationContentMargin
        val mockNotificationEntry = createMockNotificationEntry(/* showButton= */ false)
        val mockContainingNotification = createMockContainingNotification(mockNotificationEntry)
        val actionListMarginTarget =
            spy(createLinearLayoutWithBottomMargin(notificationContentMargin))
        val mockExpandedChild = createMockExpandedChild(mockNotificationEntry)
        whenever(
                mockExpandedChild.findViewById<LinearLayout>(
                    R.id.notification_action_list_margin_target
                )
            )
            .thenReturn(actionListMarginTarget)
        view.setContainingNotification(mockContainingNotification)
        view.expandedChild = mockExpandedChild
        assertEquals(notificationContentMargin, getMarginBottom(actionListMarginTarget))

        // When: call NotificationContentView.onNotificationUpdated() to update the
        // NotificationEntry, which should show bubble button
        view.onNotificationUpdated(createMockNotificationEntry(true))

        // Then: bottom margin of actionListMarginTarget should not change, still be 20
        assertEquals(0, getMarginBottom(actionListMarginTarget))
    }

    private fun createMockContainingNotification(notificationEntry: NotificationEntry) =
        mock<ExpandableNotificationRow>().apply {
            whenever(this.entry).thenReturn(notificationEntry)
            whenever(this.context).thenReturn(mContext)
            whenever(this.bubbleClickListener).thenReturn(View.OnClickListener {})
        }

    private fun createMockNotificationEntry(showButton: Boolean) =
        mock<NotificationEntry>().apply {
            whenever(mPeopleNotificationIdentifier.getPeopleNotificationType(this))
                .thenReturn(PeopleNotificationIdentifier.TYPE_FULL_PERSON)
            whenever(this.bubbleMetadata).thenReturn(mock())
            val sbnMock: StatusBarNotification = mock()
            val userMock: UserHandle = mock()
            whenever(this.sbn).thenReturn(sbnMock)
            whenever(sbnMock.user).thenReturn(userMock)
            doReturn(showButton).whenever(view).shouldShowBubbleButton(this)
        }

    private fun createLinearLayoutWithBottomMargin(bottomMargin: Int): LinearLayout {
        val outerLayout = LinearLayout(mContext)
        val innerLayout = LinearLayout(mContext)
        outerLayout.addView(innerLayout)
        val mlp = innerLayout.layoutParams as ViewGroup.MarginLayoutParams
        mlp.setMargins(0, 0, 0, bottomMargin)
        return innerLayout
    }

    private fun createMockExpandedChild(notificationEntry: NotificationEntry) =
        mock<ExpandableNotificationRow>().apply {
            whenever(this.findViewById<ImageView>(R.id.bubble_button)).thenReturn(mock())
            whenever(this.findViewById<View>(R.id.actions_container)).thenReturn(mock())
            whenever(this.entry).thenReturn(notificationEntry)
            whenever(this.context).thenReturn(mContext)

            val resourcesMock: Resources = mock()
            whenever(resourcesMock.configuration).thenReturn(mock())
            whenever(this.resources).thenReturn(resourcesMock)
        }

    private fun getMarginBottom(layout: LinearLayout): Int =
        (layout.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
}
