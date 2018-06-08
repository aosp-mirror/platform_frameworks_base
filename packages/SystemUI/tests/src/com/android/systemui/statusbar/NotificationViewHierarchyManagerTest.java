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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
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
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class NotificationViewHierarchyManagerTest extends SysuiTestCase {
    @Mock private NotificationPresenter mPresenter;
    @Mock private NotificationData mNotificationData;
    @Spy private FakeListContainer mListContainer = new FakeListContainer();

    // Dependency mocks:
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationGroupManager mGroupManager;
    @Mock private VisualStabilityManager mVisualStabilityManager;

    private NotificationViewHierarchyManager mViewHierarchyManager;
    private NotificationTestHelper mHelper = new NotificationTestHelper(mContext);;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(NotificationLockscreenUserManager.class,
                mLockscreenUserManager);
        mDependency.injectTestDependency(NotificationGroupManager.class, mGroupManager);
        mDependency.injectTestDependency(VisualStabilityManager.class, mVisualStabilityManager);

        when(mEntryManager.getNotificationData()).thenReturn(mNotificationData);

        mViewHierarchyManager = new NotificationViewHierarchyManager(mContext);
        mViewHierarchyManager.setUpWithPresenter(mPresenter, mEntryManager, mListContainer);
    }

    private NotificationData.Entry createEntry() throws Exception {
        ExpandableNotificationRow row = mHelper.createRow();
        NotificationData.Entry entry = new NotificationData.Entry(row.getStatusBarNotification());
        entry.row = row;
        return entry;
    }

    @Test
    public void testNotificationsBecomingBundled() throws Exception {
        // Tests 3 top level notifications becoming a single bundled notification with |entry0| as
        // the summary.
        NotificationData.Entry entry0 = createEntry();
        NotificationData.Entry entry1 = createEntry();
        NotificationData.Entry entry2 = createEntry();

        // Set up the prior state to look like three top level notifications.
        mListContainer.addContainerView(entry0.row);
        mListContainer.addContainerView(entry1.row);
        mListContainer.addContainerView(entry2.row);
        when(mNotificationData.getActiveNotifications()).thenReturn(
                Lists.newArrayList(entry0, entry1, entry2));

        // Set up group manager to report that they should be bundled now.
        when(mGroupManager.isChildInGroupWithSummary(entry0.notification)).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry1.notification)).thenReturn(true);
        when(mGroupManager.isChildInGroupWithSummary(entry2.notification)).thenReturn(true);
        when(mGroupManager.getGroupSummary(entry1.notification)).thenReturn(entry0.row);
        when(mGroupManager.getGroupSummary(entry2.notification)).thenReturn(entry0.row);

        // Run updateNotifications - the view hierarchy should be reorganized.
        mViewHierarchyManager.updateNotificationViews();

        verify(mListContainer).notifyGroupChildAdded(entry1.row);
        verify(mListContainer).notifyGroupChildAdded(entry2.row);
        assertTrue(Lists.newArrayList(entry0.row).equals(mListContainer.mRows));
    }

    @Test
    public void testNotificationsBecomingUnbundled() throws Exception {
        // Tests a bundled notification becoming three top level notifications.
        NotificationData.Entry entry0 = createEntry();
        NotificationData.Entry entry1 = createEntry();
        NotificationData.Entry entry2 = createEntry();
        entry0.row.addChildNotification(entry1.row);
        entry0.row.addChildNotification(entry2.row);

        // Set up the prior state to look like one top level notification.
        mListContainer.addContainerView(entry0.row);
        when(mNotificationData.getActiveNotifications()).thenReturn(
                Lists.newArrayList(entry0, entry1, entry2));

        // Set up group manager to report that they should not be bundled now.
        when(mGroupManager.isChildInGroupWithSummary(entry0.notification)).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry1.notification)).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry2.notification)).thenReturn(false);

        // Run updateNotifications - the view hierarchy should be reorganized.
        mViewHierarchyManager.updateNotificationViews();

        verify(mListContainer).notifyGroupChildRemoved(
                entry1.row, entry0.row.getChildrenContainer());
        verify(mListContainer).notifyGroupChildRemoved(
                entry2.row, entry0.row.getChildrenContainer());
        assertTrue(Lists.newArrayList(entry0.row, entry1.row, entry2.row).equals(mListContainer.mRows));
    }

    @Test
    public void testNotificationsBecomingSuppressed() throws Exception {
        // Tests two top level notifications becoming a suppressed summary and a child.
        NotificationData.Entry entry0 = createEntry();
        NotificationData.Entry entry1 = createEntry();
        entry0.row.addChildNotification(entry1.row);

        // Set up the prior state to look like a top level notification.
        mListContainer.addContainerView(entry0.row);
        when(mNotificationData.getActiveNotifications()).thenReturn(
                Lists.newArrayList(entry0, entry1));

        // Set up group manager to report a suppressed summary now.
        when(mGroupManager.isChildInGroupWithSummary(entry0.notification)).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry1.notification)).thenReturn(false);
        when(mGroupManager.isSummaryOfSuppressedGroup(entry0.notification)).thenReturn(true);

        // Run updateNotifications - the view hierarchy should be reorganized.
        mViewHierarchyManager.updateNotificationViews();

        verify(mListContainer).notifyGroupChildRemoved(
                entry1.row, entry0.row.getChildrenContainer());
        assertTrue(Lists.newArrayList(entry0.row, entry1.row).equals(mListContainer.mRows));
        assertEquals(View.GONE, entry0.row.getVisibility());
        assertEquals(View.VISIBLE, entry1.row.getVisibility());
    }

    @Test
    public void testUpdateNotificationViews_appOps() throws Exception {
        NotificationData.Entry entry0 = createEntry();
        entry0.row = spy(entry0.row);
        when(mNotificationData.getActiveNotifications()).thenReturn(
                Lists.newArrayList(entry0));
        mListContainer.addContainerView(entry0.row);

        mViewHierarchyManager.updateNotificationViews();

        verify(entry0.row, times(1)).showAppOpsIcons(any());
    }

    private class FakeListContainer implements NotificationListContainer {
        final LinearLayout mLayout = new LinearLayout(mContext);
        final List<View> mRows = Lists.newArrayList();

        @Override
        public void setChildTransferInProgress(boolean childTransferInProgress) {}

        @Override
        public void changeViewPosition(View child, int newIndex) {
            mRows.remove(child);
            mRows.add(newIndex, child);
        }

        @Override
        public void notifyGroupChildAdded(View row) {}

        @Override
        public void notifyGroupChildRemoved(View row, ViewGroup childrenContainer) {}

        @Override
        public void generateAddAnimation(View child, boolean fromMoreCard) {}

        @Override
        public void generateChildOrderChangedEvent() {}

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
        public void addContainerView(View v) {
            mLayout.addView(v);
            mRows.add(v);
        }

        @Override
        public void setMaxDisplayedNotifications(int maxNotifications) {}

        @Override
        public void snapViewIfNeeded(ExpandableNotificationRow row) {}

        @Override
        public ViewGroup getViewParentForNotification(NotificationData.Entry entry) {
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
        public void cleanUpViewState(View view) {}

        @Override
        public boolean isInVisibleLocation(ExpandableNotificationRow row) {
            return true;
        }

        @Override
        public void setChildLocationsChangedListener(
                NotificationLogger.OnChildLocationsChangedListener listener) {}

        @Override
        public boolean hasPulsingNotifications() {
            return false;
        }
    }
}
