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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.InitController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.util.Assert;

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
    @Mock private NotificationData mNotificationData;
    @Spy private FakeListContainer mListContainer = new FakeListContainer();

    // Dependency mocks:
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationGroupManager mGroupManager;
    @Mock private VisualStabilityManager mVisualStabilityManager;
    @Mock private ShadeController mShadeController;

    private NotificationViewHierarchyManager mViewHierarchyManager;
    private NotificationTestHelper mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Assert.sMainLooper = TestableLooper.get(this).getLooper();
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(NotificationLockscreenUserManager.class,
                mLockscreenUserManager);
        mDependency.injectTestDependency(NotificationGroupManager.class, mGroupManager);
        mDependency.injectTestDependency(VisualStabilityManager.class, mVisualStabilityManager);
        mDependency.injectTestDependency(ShadeController.class, mShadeController);

        mHelper = new NotificationTestHelper(mContext);

        when(mEntryManager.getNotificationData()).thenReturn(mNotificationData);

        mViewHierarchyManager = new NotificationViewHierarchyManager(mContext,
                mLockscreenUserManager, mGroupManager, mVisualStabilityManager,
                mock(StatusBarStateControllerImpl.class), mEntryManager,
                () -> mShadeController);
        Dependency.get(InitController.class).executePostInitTasks();
        mViewHierarchyManager.setUpWithPresenter(mPresenter, mListContainer);
    }

    private NotificationEntry createEntry() throws Exception {
        ExpandableNotificationRow row = mHelper.createRow();
        NotificationEntry entry = new NotificationEntry(row.getStatusBarNotification());
        entry.setRow(row);
        return entry;
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
        when(mNotificationData.getActiveNotifications()).thenReturn(
                Lists.newArrayList(entry0, entry1, entry2));

        // Set up group manager to report that they should be bundled now.
        when(mGroupManager.isChildInGroupWithSummary(entry0.notification)).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry1.notification)).thenReturn(true);
        when(mGroupManager.isChildInGroupWithSummary(entry2.notification)).thenReturn(true);
        when(mGroupManager.getGroupSummary(entry1.notification)).thenReturn(entry0);
        when(mGroupManager.getGroupSummary(entry2.notification)).thenReturn(entry0);

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
        when(mNotificationData.getActiveNotifications()).thenReturn(
                Lists.newArrayList(entry0, entry1, entry2));

        // Set up group manager to report that they should not be bundled now.
        when(mGroupManager.isChildInGroupWithSummary(entry0.notification)).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry1.notification)).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry2.notification)).thenReturn(false);

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
        when(mNotificationData.getActiveNotifications()).thenReturn(
                Lists.newArrayList(entry0, entry1));

        // Set up group manager to report a suppressed summary now.
        when(mGroupManager.isChildInGroupWithSummary(entry0.notification)).thenReturn(false);
        when(mGroupManager.isChildInGroupWithSummary(entry1.notification)).thenReturn(false);
        when(mGroupManager.isSummaryOfSuppressedGroup(entry0.notification)).thenReturn(true);

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
        when(mNotificationData.getActiveNotifications()).thenReturn(
                Lists.newArrayList(entry0));
        mListContainer.addContainerView(entry0.getRow());

        mViewHierarchyManager.updateNotificationViews();

        verify(entry0.getRow(), times(1)).showAppOpsIcons(any());
    }

    private class FakeListContainer implements NotificationListContainer {
        final LinearLayout mLayout = new LinearLayout(mContext);
        final List<View> mRows = Lists.newArrayList();

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
        public void notifyGroupChildRemoved(ExpandableView row, ViewGroup childrenContainer) {}

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
        public void addContainerView(View v) {
            mLayout.addView(v);
            mRows.add(v);
        }

        @Override
        public void setMaxDisplayedNotifications(int maxNotifications) {}

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

    }
}
