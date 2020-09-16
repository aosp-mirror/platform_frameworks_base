/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.statusbar.notification.DynamicChildBindController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.inflation.LowPriorityInflationHelper;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.notification.stack.ForegroundServiceSectionController;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationListItem;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationViewHierarchyManagerTest extends SysuiTestCase {
    @Mock private NotificationPresenter mPresenter;
    @Spy private FakeListContainer mListContainer = new FakeListContainer();

    // Dependency mocks:
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationGroupManager mGroupManager;
    @Mock private VisualStabilityManager mVisualStabilityManager;

    private TestableLooper mTestableLooper;
    private Handler mHandler;
    private NotificationViewHierarchyManager mViewHierarchyManager;
    private NotificationTestHelper mHelper;
    private boolean mMadeReentrantCall = false;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();
        mHandler = Handler.createAsync(mTestableLooper.getLooper());

        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(NotificationLockscreenUserManager.class,
                mLockscreenUserManager);
        mDependency.injectTestDependency(NotificationGroupManager.class, mGroupManager);
        mDependency.injectTestDependency(VisualStabilityManager.class, mVisualStabilityManager);
        when(mVisualStabilityManager.areGroupChangesAllowed()).thenReturn(true);
        when(mVisualStabilityManager.isReorderingAllowed()).thenReturn(true);

        mHelper = new NotificationTestHelper(mContext, mDependency, TestableLooper.get(this));

        mViewHierarchyManager = new NotificationViewHierarchyManager(mContext,
                mHandler, mLockscreenUserManager, mGroupManager, mVisualStabilityManager,
                mock(StatusBarStateControllerImpl.class), mEntryManager,
                mock(KeyguardBypassController.class),
                mock(BubbleController.class),
                mock(DynamicPrivacyController.class),
                mock(ForegroundServiceSectionController.class),
                mock(DynamicChildBindController.class),
                mock(LowPriorityInflationHelper.class));
        mViewHierarchyManager.setUpWithPresenter(mPresenter, mListContainer);
    }

    private NotificationEntry createEntry() throws Exception {
        ExpandableNotificationRow row = mHelper.createRow();
        return row.getEntry();
    }

    @Test
    public void testNotificationsBecomingBundled() throws Exception {
        // Tests 3 top level notifications becoming a single bundled notification with |entry0| as
        // the summary.
        NotificationEntry entry0 = createEntry();
        NotificationEntry entry1 = createEntry();
        NotificationEntry entry2 = createEntry();

        // Set up the prior state to look like three top level notifications.
        mListContainer.addContainerView(entry0.getRow());
        mListContainer.addContainerView(entry1.getRow());
        mListContainer.addContainerView(entry2.getRow());
        when(mEntryManager.getVisibleNotifications()).thenReturn(
                Lists.newArrayList(entry0, entry1, entry2));

        // Set up group manager to report that they should be bundled now.
        when(mGroupManager.isChildInGroupWithSummary(entry0.getSbn())).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry1.getSbn())).thenReturn(true);
        when(mGroupManager.isChildInGroupWithSummary(entry2.getSbn())).thenReturn(true);
        when(mGroupManager.getGroupSummary(entry1.getSbn())).thenReturn(entry0);
        when(mGroupManager.getGroupSummary(entry2.getSbn())).thenReturn(entry0);

        // Run updateNotifications - the view hierarchy should be reorganized.
        mViewHierarchyManager.updateNotificationViews();

        verify(mListContainer).notifyGroupChildAdded(entry1.getRow());
        verify(mListContainer).notifyGroupChildAdded(entry2.getRow());
        assertTrue(Lists.newArrayList(entry0.getRow()).equals(mListContainer.mRows));
    }

    @Test
    public void testNotificationsBecomingUnbundled() throws Exception {
        // Tests a bundled notification becoming three top level notifications.
        NotificationEntry entry0 = createEntry();
        NotificationEntry entry1 = createEntry();
        NotificationEntry entry2 = createEntry();
        entry0.getRow().addChildNotification(entry1.getRow());
        entry0.getRow().addChildNotification(entry2.getRow());

        // Set up the prior state to look like one top level notification.
        mListContainer.addContainerView(entry0.getRow());
        when(mEntryManager.getVisibleNotifications()).thenReturn(
                Lists.newArrayList(entry0, entry1, entry2));

        // Set up group manager to report that they should not be bundled now.
        when(mGroupManager.isChildInGroupWithSummary(entry0.getSbn())).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry1.getSbn())).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry2.getSbn())).thenReturn(false);

        // Run updateNotifications - the view hierarchy should be reorganized.
        mViewHierarchyManager.updateNotificationViews();

        verify(mListContainer).notifyGroupChildRemoved(
                entry1.getRow(), entry0.getRow().getChildrenContainer());
        verify(mListContainer).notifyGroupChildRemoved(
                entry2.getRow(), entry0.getRow().getChildrenContainer());
        assertTrue(
                Lists.newArrayList(entry0.getRow(), entry1.getRow(), entry2.getRow())
                        .equals(mListContainer.mRows));
    }

    @Test
    public void testNotificationsBecomingSuppressed() throws Exception {
        // Tests two top level notifications becoming a suppressed summary and a child.
        NotificationEntry entry0 = createEntry();
        NotificationEntry entry1 = createEntry();
        entry0.getRow().addChildNotification(entry1.getRow());

        // Set up the prior state to look like a top level notification.
        mListContainer.addContainerView(entry0.getRow());
        when(mEntryManager.getVisibleNotifications()).thenReturn(
                Lists.newArrayList(entry0, entry1));

        // Set up group manager to report a suppressed summary now.
        when(mGroupManager.isChildInGroupWithSummary(entry0.getSbn())).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry1.getSbn())).thenReturn(false);
        when(mGroupManager.isSummaryOfSuppressedGroup(entry0.getSbn())).thenReturn(true);

        // Run updateNotifications - the view hierarchy should be reorganized.
        mViewHierarchyManager.updateNotificationViews();

        verify(mListContainer).notifyGroupChildRemoved(
                entry1.getRow(), entry0.getRow().getChildrenContainer());
        assertTrue(Lists.newArrayList(entry0.getRow(), entry1.getRow()).equals(mListContainer.mRows));
        assertEquals(View.GONE, entry0.getRow().getVisibility());
        assertEquals(View.VISIBLE, entry1.getRow().getVisibility());
    }

    @Test
    public void testUpdateNotificationViews_appOps() throws Exception {
        NotificationEntry entry0 = createEntry();
        entry0.setRow(spy(entry0.getRow()));
        when(mEntryManager.getVisibleNotifications()).thenReturn(
                Lists.newArrayList(entry0));
        mListContainer.addContainerView(entry0.getRow());

        mViewHierarchyManager.updateNotificationViews();

        verify(entry0.getRow(), times(1)).showAppOpsIcons(any());
    }

    @Test
    public void testReentrantCallsToOnDynamicPrivacyChangedPostForLater() {
        // GIVEN a ListContainer that will make a re-entrant call to updateNotificationViews()
        mMadeReentrantCall = false;
        doAnswer((invocation) -> {
            if (!mMadeReentrantCall) {
                mMadeReentrantCall = true;
                mViewHierarchyManager.onDynamicPrivacyChanged();
            }
            return null;
        }).when(mListContainer).setMaxDisplayedNotifications(anyInt());

        // WHEN we call updateNotificationViews()
        mViewHierarchyManager.updateNotificationViews();

        // THEN onNotificationViewUpdateFinished() is only called once
        verify(mListContainer).onNotificationViewUpdateFinished();

        // WHEN we drain the looper
        mTestableLooper.processAllMessages();

        // THEN updateNotificationViews() is called a second time (for the reentrant call)
        verify(mListContainer, times(2)).onNotificationViewUpdateFinished();
    }

    @Test
    public void testMultipleReentrantCallsToOnDynamicPrivacyChangedOnlyPostOnce() {
        // GIVEN a ListContainer that will make many re-entrant calls to updateNotificationViews()
        mMadeReentrantCall = false;
        doAnswer((invocation) -> {
            if (!mMadeReentrantCall) {
                mMadeReentrantCall = true;
                mViewHierarchyManager.onDynamicPrivacyChanged();
                mViewHierarchyManager.onDynamicPrivacyChanged();
                mViewHierarchyManager.onDynamicPrivacyChanged();
                mViewHierarchyManager.onDynamicPrivacyChanged();
            }
            return null;
        }).when(mListContainer).setMaxDisplayedNotifications(anyInt());

        // WHEN we call updateNotificationViews() and drain the looper
        mViewHierarchyManager.updateNotificationViews();
        verify(mListContainer).onNotificationViewUpdateFinished();
        clearInvocations(mListContainer);
        mTestableLooper.processAllMessages();

        // THEN updateNotificationViews() is called only one more time
        verify(mListContainer).onNotificationViewUpdateFinished();
    }

    private class FakeListContainer implements NotificationListContainer {
        final LinearLayout mLayout = new LinearLayout(mContext);
        final List<View> mRows = Lists.newArrayList();
        private boolean mMakeReentrantCallDuringSetMaxDisplayedNotifications;

        @Override
        public void setChildTransferInProgress(boolean childTransferInProgress) {}

        @Override
        public void changeViewPosition(ExpandableView child, int newIndex) {
            mRows.remove(child);
            mRows.add(newIndex, child);
        }

        @Override
        public void notifyGroupChildAdded(ExpandableView row) {}

        @Override
        public void notifyGroupChildAdded(View v) {}

        @Override
        public void notifyGroupChildRemoved(ExpandableView row, ViewGroup childrenContainer) {}

        @Override
        public void notifyGroupChildRemoved(View v, ViewGroup childrenContainer) {}

        @Override
        public void generateAddAnimation(ExpandableView child, boolean fromMoreCard) {}

        @Override
        public void generateChildOrderChangedEvent() {}

        @Override
        public void onReset(ExpandableView view) {}

        @Override
        public int getContainerChildCount() {
            return mRows.size();
        }

        @Override
        public View getContainerChildAt(int i) {
            return mRows.get(i);
        }

        @Override
        public void removeContainerView(View v) {
            mLayout.removeView(v);
            mRows.remove(v);
        }

        @Override
        public void removeListItem(NotificationListItem li) {
            removeContainerView(li.getView());
        }

        @Override
        public void setNotificationActivityStarter(
                NotificationActivityStarter notificationActivityStarter) {}

        @Override
        public void addContainerView(View v) {
            mLayout.addView(v);
            mRows.add(v);
        }

        @Override
        public void addListItem(NotificationListItem li) {
            addContainerView(li.getView());
        }

        @Override
        public void setMaxDisplayedNotifications(int maxNotifications) {
            if (mMakeReentrantCallDuringSetMaxDisplayedNotifications) {
                mViewHierarchyManager.onDynamicPrivacyChanged();
            }
        }

        @Override
        public ViewGroup getViewParentForNotification(NotificationEntry entry) {
            return null;
        }

        @Override
        public void onHeightChanged(ExpandableView view, boolean animate) {}

        @Override
        public void resetExposedMenuView(boolean animate, boolean force) {}

        @Override
        public NotificationSwipeActionHelper getSwipeActionHelper() {
            return null;
        }

        @Override
        public void cleanUpViewStateForEntry(NotificationEntry entry) { }

        @Override
        public boolean isInVisibleLocation(NotificationEntry entry) {
            return true;
        }

        @Override
        public void setChildLocationsChangedListener(
                NotificationLogger.OnChildLocationsChangedListener listener) {}

        @Override
        public boolean hasPulsingNotifications() {
            return false;
        }

        @Override
        public void onNotificationViewUpdateFinished() { }
    }
}
