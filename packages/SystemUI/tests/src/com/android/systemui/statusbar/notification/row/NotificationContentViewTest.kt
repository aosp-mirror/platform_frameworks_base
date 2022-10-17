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

import android.testing.AndroidTestingRunner
import android.view.NotificationHeaderView
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.internal.widget.NotificationActionListLayout
import com.android.internal.widget.NotificationExpandButton
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.dialog.MediaOutputDialogFactory
import com.android.systemui.statusbar.notification.FeedbackIcon
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationContentViewTest : SysuiTestCase() {
    private lateinit var view: NotificationContentView

    @Before
    fun setup() {
        mDependency.injectMockDependency(MediaOutputDialogFactory::class.java)

        view = NotificationContentView(mContext, /* attrs= */ null)
        val row = ExpandableNotificationRow(mContext, /* attrs= */ null)
        val spyRow = Mockito.spy(row)
        doReturn(10).whenever(spyRow).intrinsicHeight

        with(view) {
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
}
