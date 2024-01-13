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

import android.annotation.DimenRes
import android.content.res.Resources
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.NotificationHeaderView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.internal.widget.NotificationActionListLayout
import com.android.internal.widget.NotificationExpandButton
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.FeedbackIcon
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class NotificationContentViewTest : SysuiTestCase() {

    private lateinit var row: ExpandableNotificationRow
    private lateinit var fakeParent: ViewGroup
    @Mock private lateinit var mPeopleNotificationIdentifier: PeopleNotificationIdentifier

    private val testableResources = mContext.getOrCreateTestableResources()
    private val contractedHeight =
        px(com.android.systemui.res.R.dimen.min_notification_layout_height)
    private val expandedHeight = px(com.android.systemui.res.R.dimen.notification_max_height)
    private val notificationContentMargin = px(R.dimen.notification_content_margin)

    @Before
    fun setup() {
        initMocks(this)
        fakeParent =
            spy(FrameLayout(mContext, /* attrs= */ null).also { it.visibility = View.GONE })
        row =
            spy(
                ExpandableNotificationRow(mContext, /* attrs= */ null).apply {
                    entry = createMockNotificationEntry()
                }
            )
        ViewUtils.attachView(fakeParent)
    }

    @After
    fun teardown() {
        fakeParent.removeAllViews()
        ViewUtils.detachView(fakeParent)
    }

    @Test
    fun contractedWrapperSelected_whenShadeIsClosed_wrapperNotNotified() {
        // GIVEN the shade is closed
        fakeParent.visibility = View.GONE

        // WHEN a collapsed content is created
        val view = createContentView(isSystemExpanded = false)

        // THEN the contractedWrapper is set
        assertEquals(view.contractedWrapper, view.visibleWrapper)
        // AND the contractedWrapper is visible, but NOT shown
        verify(view.contractedWrapper).setVisible(true)
        verify(view.contractedWrapper, never()).onContentShown(anyBoolean())
    }

    @Test
    fun contractedWrapperSelected_whenShadeIsOpen_wrapperNotified() {
        // GIVEN the shade is open
        fakeParent.visibility = View.VISIBLE

        // WHEN a collapsed content is created
        val view = createContentView(isSystemExpanded = false)

        // THEN the contractedWrapper is set
        assertEquals(view.contractedWrapper, view.visibleWrapper)
        // AND the contractedWrapper is visible and shown
        verify(view.contractedWrapper, Mockito.atLeastOnce()).setVisible(true)
        verify(view.contractedWrapper, times(1)).onContentShown(true)
    }

    @Test
    fun shadeOpens_collapsedWrapperIsSelected_wrapperNotified() {
        // GIVEN the shade is closed
        fakeParent.visibility = View.GONE
        // AND a collapsed content is created
        val view = createContentView(isSystemExpanded = false).apply { clearInvocations() }

        // WHEN the shade opens
        fakeParent.visibility = View.VISIBLE
        view.onVisibilityAggregated(true)

        // THEN the contractedWrapper is set
        assertEquals(view.contractedWrapper, view.visibleWrapper)
        // AND the contractedWrapper is shown
        verify(view.contractedWrapper, times(1)).onContentShown(true)
    }

    @Test
    fun shadeCloses_collapsedWrapperIsShown_wrapperNotified() {
        // GIVEN the shade is closed
        fakeParent.visibility = View.VISIBLE
        // AND a collapsed content is created
        val view = createContentView(isSystemExpanded = false).apply { clearInvocations() }

        // WHEN the shade opens
        fakeParent.visibility = View.GONE
        view.onVisibilityAggregated(false)

        // THEN the contractedWrapper is set
        assertEquals(view.contractedWrapper, view.visibleWrapper)
        // AND the contractedWrapper is NOT shown
        verify(view.contractedWrapper, times(1)).onContentShown(false)
    }

    @Test
    fun expandedWrapperSelected_whenShadeIsClosed_wrapperNotNotified() {
        // GIVEN the shade is closed
        fakeParent.visibility = View.GONE

        // WHEN a system-expanded content is created
        val view = createContentView(isSystemExpanded = true)

        // THEN the contractedWrapper is set
        assertEquals(view.expandedWrapper, view.visibleWrapper)
        // AND the contractedWrapper is visible, but NOT shown
        verify(view.expandedWrapper, Mockito.atLeastOnce()).setVisible(true)
        verify(view.expandedWrapper, never()).onContentShown(anyBoolean())
    }

    @Test
    fun expandedWrapperSelected_whenShadeIsOpen_wrapperNotified() {
        // GIVEN the shade is open
        fakeParent.visibility = View.VISIBLE

        // WHEN an system-expanded content is created
        val view = createContentView(isSystemExpanded = true)

        // THEN the expandedWrapper is set
        assertEquals(view.expandedWrapper, view.visibleWrapper)
        // AND the expandedWrapper is visible and shown
        verify(view.expandedWrapper, Mockito.atLeastOnce()).setVisible(true)
        verify(view.expandedWrapper, times(1)).onContentShown(true)
    }

    @Test
    fun shadeOpens_expandedWrapperIsSelected_wrapperNotified() {
        // GIVEN the shade is closed
        fakeParent.visibility = View.GONE
        // AND a system-expanded content is created
        val view = createContentView(isSystemExpanded = true).apply { clearInvocations() }

        // WHEN the shade opens
        fakeParent.visibility = View.VISIBLE
        view.onVisibilityAggregated(true)

        // THEN the expandedWrapper is set
        assertEquals(view.expandedWrapper, view.visibleWrapper)
        // AND the expandedWrapper is shown
        verify(view.expandedWrapper, times(1)).onContentShown(true)
    }

    @Test
    fun shadeCloses_expandedWrapperIsShown_wrapperNotified() {
        // GIVEN the shade is open
        fakeParent.visibility = View.VISIBLE
        // AND a system-expanded content is created
        val view = createContentView(isSystemExpanded = true).apply { clearInvocations() }

        // WHEN the shade opens
        fakeParent.visibility = View.GONE
        view.onVisibilityAggregated(false)

        // THEN the expandedWrapper is set
        assertEquals(view.expandedWrapper, view.visibleWrapper)
        // AND the expandedWrapper is NOT shown
        verify(view.expandedWrapper, times(1)).onContentShown(false)
    }

    @Test
    fun expandCollapsedNotification_expandedWrapperShown() {
        // GIVEN the shade is open
        fakeParent.visibility = View.VISIBLE
        // AND a collapsed content is created
        val view = createContentView(isSystemExpanded = false).apply { clearInvocations() }

        // WHEN we collapse the notification
        whenever(row.intrinsicHeight).thenReturn(expandedHeight)
        view.contentHeight = expandedHeight

        // THEN the wrappers are updated
        assertEquals(view.expandedWrapper, view.visibleWrapper)
        verify(view.contractedWrapper, times(1)).onContentShown(false)
        verify(view.contractedWrapper).setVisible(false)
        verify(view.expandedWrapper, times(1)).onContentShown(true)
        verify(view.expandedWrapper).setVisible(true)
    }

    @Test
    fun collapseExpandedNotification_expandedWrapperShown() {
        // GIVEN the shade is open
        fakeParent.visibility = View.VISIBLE
        // AND a system-expanded content is created
        val view = createContentView(isSystemExpanded = true).apply { clearInvocations() }

        // WHEN we collapse the notification
        whenever(row.intrinsicHeight).thenReturn(contractedHeight)
        view.contentHeight = contractedHeight

        // THEN the wrappers are updated
        assertEquals(view.contractedWrapper, view.visibleWrapper)
        verify(view.expandedWrapper, times(1)).onContentShown(false)
        verify(view.expandedWrapper).setVisible(false)
        verify(view.contractedWrapper, times(1)).onContentShown(true)
        verify(view.contractedWrapper).setVisible(true)
    }

    @Test
    fun testSetFeedbackIcon() {
        // Given: contractedChild, enpandedChild, and headsUpChild being set
        val view = createContentView(isSystemExpanded = false)

        // When: FeedBackIcon is set
        val icon =
            FeedbackIcon(
                R.drawable.ic_feedback_alerted,
                R.string.notification_feedback_indicator_alerted
            )
        view.setFeedbackIcon(icon)

        // Then: contractedChild, enpandedChild, and headsUpChild is updated with the feedbackIcon
        verify(view.contractedWrapper).setFeedbackIcon(icon)
        verify(view.expandedWrapper).setFeedbackIcon(icon)
        verify(view.headsUpWrapper).setFeedbackIcon(icon)
    }

    @Test
    fun testExpandButtonFocusIsCalled() {
        val mockContractedEB = mock<NotificationExpandButton>()
        val mockContracted = createMockNotificationHeaderView(contractedHeight, mockContractedEB)

        val mockExpandedEB = mock<NotificationExpandButton>()
        val mockExpanded = createMockNotificationHeaderView(expandedHeight, mockExpandedEB)

        val mockHeadsUpEB = mock<NotificationExpandButton>()
        val mockHeadsUp = createMockNotificationHeaderView(contractedHeight, mockHeadsUpEB)

        val view =
            createContentView(
                isSystemExpanded = false,
            )

        // Update all 3 child forms
        view.apply {
            contractedChild = mockContracted
            expandedChild = mockExpanded
            headsUpChild = mockHeadsUp

            expandedWrapper = spy(expandedWrapper)
        }

        // This is required to call requestAccessibilityFocus()
        view.setFocusOnVisibilityChange()

        // The following will initialize the view and switch from not visible to expanded.
        // (heads-up is actually an alternate form of contracted, hence this enters expanded state)
        view.setHeadsUp(true)
        assertEquals(view.expandedWrapper, view.visibleWrapper)
        verify(mockContractedEB, never()).requestAccessibilityFocus()
        verify(mockExpandedEB).requestAccessibilityFocus()
        verify(mockHeadsUpEB, never()).requestAccessibilityFocus()
    }

    private fun createMockNotificationHeaderView(
        height: Int,
        mockExpandedEB: NotificationExpandButton
    ) =
        spy(NotificationHeaderView(mContext, /* attrs= */ null).apply { minimumHeight = height })
            .apply {
                whenever(this.animate()).thenReturn(mock())
                whenever(this.findViewById<View>(R.id.expand_button)).thenReturn(mockExpandedEB)
            }

    @Test
    fun testRemoteInputVisibleSetsActionsUnimportantHideDescendantsForAccessibility() {
        val mockContracted = spy(createViewWithHeight(contractedHeight))

        val mockExpandedActions = mock<NotificationActionListLayout>()
        val mockExpanded = spy(createViewWithHeight(expandedHeight))
        whenever(mockExpanded.findViewById<View>(R.id.actions)).thenReturn(mockExpandedActions)

        val mockHeadsUpActions = mock<NotificationActionListLayout>()
        val mockHeadsUp = spy(createViewWithHeight(contractedHeight))
        whenever(mockHeadsUp.findViewById<View>(R.id.actions)).thenReturn(mockHeadsUpActions)

        val view =
            createContentView(
                isSystemExpanded = false,
                contractedView = mockContracted,
                expandedView = mockExpanded,
                headsUpView = mockHeadsUp
            )

        view.setRemoteInputVisible(true)

        verify(mockContracted, never()).findViewById<View>(0)
        verify(mockExpandedActions).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        verify(mockHeadsUpActions).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    @Test
    fun testRemoteInputInvisibleSetsActionsAutoImportantForAccessibility() {
        val mockContracted = spy(createViewWithHeight(contractedHeight))

        val mockExpandedActions = mock<NotificationActionListLayout>()
        val mockExpanded = spy(createViewWithHeight(expandedHeight))
        whenever(mockExpanded.findViewById<View>(R.id.actions)).thenReturn(mockExpandedActions)

        val mockHeadsUpActions = mock<NotificationActionListLayout>()
        val mockHeadsUp = spy(createViewWithHeight(contractedHeight))
        whenever(mockHeadsUp.findViewById<View>(R.id.actions)).thenReturn(mockHeadsUpActions)

        val view =
            createContentView(
                isSystemExpanded = false,
                contractedView = mockContracted,
                expandedView = mockExpanded,
                headsUpView = mockHeadsUp
            )

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
        val mockNotificationEntry = createMockNotificationEntry()
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
        val view = createContentView(isSystemExpanded = false)

        view.setContainingNotification(mockContainingNotification) // maybe not needed

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
        val mockNotificationEntry = createMockNotificationEntry()
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
        val view = createContentView(isSystemExpanded = false)

        view.setContainingNotification(mockContainingNotification)

        // Given: controller says bubbles are enabled for the user
        view.setBubblesEnabledForUser(true)

        // When: call NotificationContentView.setExpandedChild() to set the expandedChild
        view.expandedChild = mockExpandedChild

        // Then: bottom margin of actionListMarginTarget should be set to 0
        assertEquals(0, getMarginBottom(actionListMarginTarget))
    }

    @Test
    fun onNotificationUpdated_notShowBubbleButton_marginTargetBottomMarginShouldNotChange() {
        // Given: bottom margin of actionListMarginTarget is notificationContentMargin
        val mockNotificationEntry = createMockNotificationEntry()
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
        val view = createContentView(isSystemExpanded = false)

        view.setContainingNotification(mockContainingNotification)
        view.expandedChild = mockExpandedChild
        assertEquals(notificationContentMargin, getMarginBottom(actionListMarginTarget))

        // When: call NotificationContentView.onNotificationUpdated() to update the
        // NotificationEntry, which should not show bubble button
        view.onNotificationUpdated(createMockNotificationEntry())

        // Then: bottom margin of actionListMarginTarget should not change, still be 20
        assertEquals(notificationContentMargin, getMarginBottom(actionListMarginTarget))
    }

    @Test
    fun onNotificationUpdated_showBubbleButton_marginTargetBottomMarginShouldChangeToZero() {
        // Given: bottom margin of actionListMarginTarget is notificationContentMargin
        val mockNotificationEntry = createMockNotificationEntry()
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
        val view = createContentView(isSystemExpanded = false, expandedView = mockExpandedChild)

        view.setContainingNotification(mockContainingNotification)
        assertEquals(notificationContentMargin, getMarginBottom(actionListMarginTarget))

        // When: call NotificationContentView.onNotificationUpdated() to update the
        // NotificationEntry, which should show bubble button
        view.onNotificationUpdated(createMockNotificationEntry(/*true*/ ))

        // Then: no bubble yet
        assertEquals(notificationContentMargin, getMarginBottom(actionListMarginTarget))

        // Given: controller says bubbles are enabled for the user
        view.setBubblesEnabledForUser(true)

        // Then: bottom margin of actionListMarginTarget should not change, still be 20
        assertEquals(0, getMarginBottom(actionListMarginTarget))
    }

    @Test
    fun onSetAnimationRunning() {
        // Given: contractedWrapper, enpandedWrapper, and headsUpWrapper being set
        val view = createContentView(isSystemExpanded = false)

        // When: we set content animation running.
        assertTrue(view.setContentAnimationRunning(true))

        // Then: contractedChild, expandedChild, and headsUpChild should have setAnimationsRunning
        // called on them.
        verify(view.contractedWrapper, times(1)).setAnimationsRunning(true)
        verify(view.expandedWrapper, times(1)).setAnimationsRunning(true)
        verify(view.headsUpWrapper, times(1)).setAnimationsRunning(true)

        // When: we set content animation running true _again_.
        assertFalse(view.setContentAnimationRunning(true))

        // Then: the children should not have setAnimationRunning called on them again.
        // Verify counts number of calls so far on the object, so these still register as 1.
        verify(view.contractedWrapper, times(1)).setAnimationsRunning(true)
        verify(view.expandedWrapper, times(1)).setAnimationsRunning(true)
        verify(view.headsUpWrapper, times(1)).setAnimationsRunning(true)
    }

    @Test
    fun onSetAnimationStopped() {
        // Given: contractedWrapper, expandedWrapper, and headsUpWrapper being set
        val view = createContentView(isSystemExpanded = false)

        // When: we set content animation running.
        assertTrue(view.setContentAnimationRunning(true))

        // Then: contractedChild, expandedChild, and headsUpChild should have setAnimationsRunning
        // called on them.
        verify(view.contractedWrapper).setAnimationsRunning(true)
        verify(view.expandedWrapper).setAnimationsRunning(true)
        verify(view.headsUpWrapper).setAnimationsRunning(true)

        // When: we set content animation running false, the state changes, so the function
        // returns true.
        assertTrue(view.setContentAnimationRunning(false))

        // Then: the children have their animations stopped.
        verify(view.contractedWrapper).setAnimationsRunning(false)
        verify(view.expandedWrapper).setAnimationsRunning(false)
        verify(view.headsUpWrapper).setAnimationsRunning(false)
    }

    @Test
    fun onSetAnimationInitStopped() {
        // Given: contractedWrapper, expandedWrapper, and headsUpWrapper being set
        val view = createContentView(isSystemExpanded = false)

        // When: we try to stop the animations before they've been started.
        assertFalse(view.setContentAnimationRunning(false))

        // Then: the children should not have setAnimationRunning called on them again.
        verify(view.contractedWrapper, never()).setAnimationsRunning(false)
        verify(view.expandedWrapper, never()).setAnimationsRunning(false)
        verify(view.headsUpWrapper, never()).setAnimationsRunning(false)
    }

    @Test
    fun notifySubtreeAccessibilityStateChanged_notifiesParent() {
        // Given: a contentView is created
        val view = createContentView()
        clearInvocations(fakeParent)

        // When: the contentView is notified for an A11y change
        view.notifySubtreeAccessibilityStateChanged(view.contractedChild, view.contractedChild, 0)

        // Then: the contentView propagates the event to its parent
        verify(fakeParent).notifySubtreeAccessibilityStateChanged(any(), any(), anyInt())
    }

    @Test
    fun notifySubtreeAccessibilityStateChanged_animatingContentView_dontNotifyParent() {
        // Given: a collapsed contentView is created
        val view = createContentView()
        clearInvocations(fakeParent)

        // And: it is animating to expanded
        view.setAnimationStartVisibleType(NotificationContentView.VISIBLE_TYPE_EXPANDED)

        // When: the contentView is notified for an A11y change
        view.notifySubtreeAccessibilityStateChanged(view.contractedChild, view.contractedChild, 0)

        // Then: the contentView DOESN'T propagates the event to its parent
        verify(fakeParent, never()).notifySubtreeAccessibilityStateChanged(any(), any(), anyInt())
    }

    private fun createMockContainingNotification(notificationEntry: NotificationEntry) =
        mock<ExpandableNotificationRow>().apply {
            whenever(this.entry).thenReturn(notificationEntry)
            whenever(this.context).thenReturn(mContext)
            whenever(this.bubbleClickListener).thenReturn(View.OnClickListener {})
        }

    private fun createMockNotificationEntry() =
        mock<NotificationEntry>().apply {
            whenever(mPeopleNotificationIdentifier.getPeopleNotificationType(this))
                .thenReturn(PeopleNotificationIdentifier.TYPE_FULL_PERSON)
            whenever(this.bubbleMetadata).thenReturn(mock())
            val sbnMock: StatusBarNotification = mock()
            val userMock: UserHandle = mock()
            whenever(this.sbn).thenReturn(sbnMock)
            whenever(sbnMock.user).thenReturn(userMock)
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
        spy(createViewWithHeight(expandedHeight)).apply {
            whenever(this.findViewById<ImageView>(R.id.bubble_button)).thenReturn(mock())
            whenever(this.findViewById<View>(R.id.actions_container)).thenReturn(mock())
            whenever(this.context).thenReturn(mContext)

            val resourcesMock: Resources = mock()
            whenever(resourcesMock.configuration).thenReturn(mock())
            whenever(this.resources).thenReturn(resourcesMock)
        }

    private fun createContentView(
        isSystemExpanded: Boolean = false,
        contractedView: View = createViewWithHeight(contractedHeight),
        expandedView: View = createViewWithHeight(expandedHeight),
        headsUpView: View = createViewWithHeight(contractedHeight),
        row: ExpandableNotificationRow = this.row
    ): NotificationContentView {
        val height = if (isSystemExpanded) expandedHeight else contractedHeight
        doReturn(height).whenever(row).intrinsicHeight

        return spy(NotificationContentView(mContext, /* attrs= */ null))
            .apply {
                initialize(mPeopleNotificationIdentifier, mock(), mock(), mock(), mock())
                setContainingNotification(row)
                setHeights(
                    /* smallHeight= */ contractedHeight,
                    /* headsUpMaxHeight= */ contractedHeight,
                    /* maxHeight= */ expandedHeight
                )
                contractedChild = contractedView
                expandedChild = expandedView
                headsUpChild = headsUpView
                contractedWrapper = spy(contractedWrapper)
                expandedWrapper = spy(expandedWrapper)
                headsUpWrapper = spy(headsUpWrapper)

                if (isSystemExpanded) {
                    contentHeight = expandedHeight
                }
            }
            .also { contentView ->
                fakeParent.addView(contentView)
                contentView.mockRequestLayout()
            }
    }

    private fun createViewWithHeight(height: Int) =
        View(mContext, /* attrs= */ null).apply { minimumHeight = height }

    private fun getMarginBottom(layout: LinearLayout): Int =
        (layout.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

    private fun px(@DimenRes id: Int): Int = testableResources.resources.getDimensionPixelSize(id)
}

private fun NotificationContentView.mockRequestLayout() {
    measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    layout(0, 0, measuredWidth, measuredHeight)
}

private fun NotificationContentView.clearInvocations() {
    clearInvocations(contractedWrapper, expandedWrapper, headsUpWrapper)
}
