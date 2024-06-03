/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static android.provider.Settings.Secure.SHOW_NOTIFICATION_SNOOZE;

import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;
import static com.android.systemui.statusbar.notification.collection.GroupEntry.ROOT_ENTRY;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static java.util.Objects.requireNonNull;

import android.database.ContentObserver;
import android.os.Handler;
import android.os.RemoteException;
import android.testing.TestableLooper;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.inflation.BindEventManagerImpl;
import com.android.systemui.statusbar.notification.collection.inflation.NotifInflater;
import com.android.systemui.statusbar.notification.collection.inflation.NotifUiAdjustmentProvider;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.collection.render.NotifViewBarn;
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager;
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class PreparationCoordinatorTest extends SysuiTestCase {
    private NotifCollectionListener mCollectionListener;
    private OnBeforeFinalizeFilterListener mBeforeFilterListener;
    private NotifFilter mUninflatedFilter;
    private NotifFilter mInflationErrorFilter;
    private NotifInflationErrorManager mErrorManager;
    private NotificationEntry mEntry;
    private Exception mInflationError;

    @Captor private ArgumentCaptor<NotifCollectionListener> mCollectionListenerCaptor;
    @Captor private ArgumentCaptor<OnBeforeFinalizeFilterListener> mBeforeFilterListenerCaptor;
    @Captor private ArgumentCaptor<NotifInflater.Params> mParamsCaptor;

    @Mock private NotifSectioner mNotifSectioner;
    @Mock private NotifSection mNotifSection;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private IStatusBarService mService;
    @Mock private BindEventManagerImpl mBindEventManagerImpl;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private SensitiveNotificationProtectionController mSensitiveNotifProtectionController;
    @Mock private Handler mHandler;
    @Mock private SecureSettings mSecureSettings;
    @Spy private FakeNotifInflater mNotifInflater = new FakeNotifInflater();
    @Mock
    HighPriorityProvider mHighPriorityProvider;
    private SectionStyleProvider mSectionStyleProvider;
    @Mock private UserTracker mUserTracker;
    @Mock private GroupMembershipManager mGroupMembershipManager;

    private NotifUiAdjustmentProvider mAdjustmentProvider;

    @NonNull
    private NotificationEntryBuilder getNotificationEntryBuilder() {
        return new NotificationEntryBuilder().setSection(mNotifSection);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSectionStyleProvider = new SectionStyleProvider(mHighPriorityProvider);
        mAdjustmentProvider = new NotifUiAdjustmentProvider(
                mHandler,
                mSecureSettings,
                mLockscreenUserManager,
                mSensitiveNotifProtectionController,
                mSectionStyleProvider,
                mUserTracker,
                mGroupMembershipManager
                );
        mEntry = getNotificationEntryBuilder().setParent(ROOT_ENTRY).build();
        mInflationError = new Exception(TEST_MESSAGE);
        mErrorManager = new NotifInflationErrorManager();
        when(mNotifSection.getSectioner()).thenReturn(mNotifSectioner);
        setSectionIsLowPriority(false);

        PreparationCoordinator coordinator = new PreparationCoordinator(
                new PreparationCoordinatorLogger(logcatLogBuffer()),
                mNotifInflater,
                mErrorManager,
                mock(NotifViewBarn.class),
                mAdjustmentProvider,
                mService,
                mBindEventManagerImpl,
                TEST_CHILD_BIND_CUTOFF,
                TEST_MAX_GROUP_DELAY);

        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        coordinator.attach(mNotifPipeline);
        verify(mNotifPipeline, times(2)).addFinalizeFilter(filterCaptor.capture());
        List<NotifFilter> filters = filterCaptor.getAllValues();
        mInflationErrorFilter = filters.get(0);
        mUninflatedFilter = filters.get(1);

        verify(mNotifPipeline).addCollectionListener(mCollectionListenerCaptor.capture());
        mCollectionListener = mCollectionListenerCaptor.getValue();

        verify(mNotifPipeline).addOnBeforeFinalizeFilterListener(
                mBeforeFilterListenerCaptor.capture());
        mBeforeFilterListener = mBeforeFilterListenerCaptor.getValue();

        mCollectionListener.onEntryInit(mEntry);
    }

    @Test
    public void testErrorLogsToService() throws RemoteException {
        // WHEN an entry has an inflation error.
        mErrorManager.setInflationError(mEntry, mInflationError);

        // THEN we log to status bar service.
        verify(mService).onNotificationError(
                eq(mEntry.getSbn().getPackageName()),
                eq(mEntry.getSbn().getTag()),
                eq(mEntry.getSbn().getId()),
                eq(mEntry.getSbn().getUid()),
                eq(mEntry.getSbn().getInitialPid()),
                eq(mInflationError.getMessage()),
                eq(mEntry.getSbn().getUser().getIdentifier()));
    }

    @Test
    public void testFiltersOutErroredNotifications() {
        // WHEN an entry has an inflation error.
        mErrorManager.setInflationError(mEntry, mInflationError);

        // THEN we filter it from the notification list.
        assertTrue(mInflationErrorFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testInflatesNewNotification() {
        // WHEN there is a new notification
        mCollectionListener.onEntryInit(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));

        // THEN we inflate it
        verify(mNotifInflater).inflateViews(eq(mEntry), any(), any());

        // THEN we filter it out until it's done inflating.
        assertTrue(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testRebindsInflatedNotificationsOnUpdate() {
        // GIVEN an inflated notification
        mCollectionListener.onEntryInit(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        verify(mNotifInflater).inflateViews(eq(mEntry), any(), any());
        mNotifInflater.invokeInflateCallbackForEntry(mEntry);

        // WHEN notification is updated
        mCollectionListener.onEntryUpdated(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));

        // THEN we rebind it
        verify(mNotifInflater).rebindViews(eq(mEntry), any(), any());

        // THEN we do not filter it because it's not the first inflation.
        assertFalse(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testEntrySmartReplyAdditionWillRebindViews() {
        // GIVEN an inflated notification
        mCollectionListener.onEntryInit(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        verify(mNotifInflater).inflateViews(eq(mEntry), any(), any());
        mNotifInflater.invokeInflateCallbackForEntry(mEntry);

        // WHEN notification ranking now has smart replies
        mEntry.setRanking(new RankingBuilder(mEntry.getRanking()).setSmartReplies("yes").build());
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));

        // THEN we rebind it
        verify(mNotifInflater).rebindViews(eq(mEntry), any(), any());

        // THEN we do not filter it because it's not the first inflation.
        assertFalse(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testEntryChangedToMinimizedSectionWillRebindViews() {
        // GIVEN an inflated notification
        mCollectionListener.onEntryInit(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        verify(mNotifInflater).inflateViews(eq(mEntry), mParamsCaptor.capture(), any());
        assertFalse(mParamsCaptor.getValue().isMinimized());
        mNotifInflater.invokeInflateCallbackForEntry(mEntry);

        // WHEN notification moves to a min priority section
        setSectionIsLowPriority(true);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));

        // THEN we rebind it
        verify(mNotifInflater).rebindViews(eq(mEntry), mParamsCaptor.capture(), any());
        assertTrue(mParamsCaptor.getValue().isMinimized());

        // THEN we do not filter it because it's not the first inflation.
        assertFalse(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testMinimizedEntryMovedIntoGroupWillRebindViews() {
        // GIVEN an inflated, minimized notification
        setSectionIsLowPriority(true);
        mCollectionListener.onEntryInit(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        verify(mNotifInflater).inflateViews(eq(mEntry), mParamsCaptor.capture(), any());
        assertTrue(mParamsCaptor.getValue().isMinimized());
        mNotifInflater.invokeInflateCallbackForEntry(mEntry);

        // WHEN notification is moved under a parent
        NotificationEntryBuilder.setNewParent(mEntry, mock(GroupEntry.class));
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));

        // THEN we rebind it as not-minimized
        verify(mNotifInflater).rebindViews(eq(mEntry), mParamsCaptor.capture(), any());
        assertFalse(mParamsCaptor.getValue().isMinimized());

        // THEN we do not filter it because it's not the first inflation.
        assertFalse(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testEntryRankChangeWillNotRebindViews() {
        // GIVEN an inflated notification
        mCollectionListener.onEntryInit(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        verify(mNotifInflater).inflateViews(eq(mEntry), any(), any());
        mNotifInflater.invokeInflateCallbackForEntry(mEntry);

        // WHEN notification ranking changes rank, which does not affect views
        mEntry.setRanking(new RankingBuilder(mEntry.getRanking()).setRank(100).build());
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));

        // THEN we do not rebind it
        verify(mNotifInflater, never()).rebindViews(eq(mEntry), any(), any());

        // THEN we do not filter it because it's not the first inflation.
        assertFalse(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testEntryCancellationWillRebindViews() {
        // Configure NotifUiAdjustmentProvider to set up SHOW_NOTIFICATION_SNOOZE value
        mEntry = spy(mEntry);
        mAdjustmentProvider.addDirtyListener(mock(Runnable.class));
        when(mSecureSettings.getIntForUser(eq(SHOW_NOTIFICATION_SNOOZE), anyInt(), anyInt()))
                .thenReturn(1);
        ArgumentCaptor<ContentObserver> contentObserverCaptor = ArgumentCaptor.forClass(
                ContentObserver.class);
        verify(mSecureSettings).registerContentObserverForUser(eq(SHOW_NOTIFICATION_SNOOZE),
                contentObserverCaptor.capture(), anyInt());
        ContentObserver contentObserver = contentObserverCaptor.getValue();
        contentObserver.onChange(false);

        // GIVEN an inflated notification
        mCollectionListener.onEntryInit(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        verify(mNotifInflater).inflateViews(eq(mEntry), any(), any());
        mNotifInflater.invokeInflateCallbackForEntry(mEntry);

        // Verify that snooze is initially enabled: from Settings & notification is not cancelled
        assertTrue(mAdjustmentProvider.calculateAdjustment(mEntry).isSnoozeEnabled());

        // WHEN notification is cancelled, rebind views because snooze enabled value changes
        when(mEntry.isCanceled()).thenReturn(true);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));

        assertFalse(mAdjustmentProvider.calculateAdjustment(mEntry).isSnoozeEnabled());

        // THEN we rebind it
        verify(mNotifInflater).rebindViews(eq(mEntry), any(), any());

        // THEN we do not filter it because it's not the first inflation.
        assertFalse(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testDoesntFilterInflatedNotifs() {
        // GIVEN an inflated notification
        mCollectionListener.onEntryInit(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        verify(mNotifInflater).inflateViews(eq(mEntry), any(), any());
        mNotifInflater.invokeInflateCallbackForEntry(mEntry);

        // THEN it isn't filtered from shade list
        assertFalse(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testCutoffGroupChildrenNotInflated() {
        // WHEN there is a new notification group is posted
        int id = 0;
        NotificationEntry summary = getNotificationEntryBuilder()
                .setOverrideGroupKey(TEST_GROUP_KEY)
                .setId(id++)
                .build();
        List<NotificationEntry> children = new ArrayList<>();
        for (int i = 0; i < TEST_CHILD_BIND_CUTOFF + 1; i++) {
            NotificationEntry child = getNotificationEntryBuilder()
                    .setOverrideGroupKey(TEST_GROUP_KEY)
                    .setId(id++)
                    .build();
            children.add(child);
        }
        GroupEntry groupEntry = new GroupEntryBuilder()
                .setKey(TEST_GROUP_KEY)
                .setSummary(summary)
                .setChildren(children)
                .build();

        mCollectionListener.onEntryInit(summary);
        for (NotificationEntry entry : children) {
            mCollectionListener.onEntryInit(entry);
        }

        mCollectionListener.onEntryInit(summary);
        for (NotificationEntry entry : children) {
            mCollectionListener.onEntryInit(entry);
        }

        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(groupEntry));

        // THEN we inflate up to the cut-off only
        for (int i = 0; i < children.size(); i++) {
            if (i < TEST_CHILD_BIND_CUTOFF) {
                verify(mNotifInflater).inflateViews(eq(children.get(i)), any(), any());
            } else {
                verify(mNotifInflater, never()).inflateViews(eq(children.get(i)), any(), any());
            }
        }
    }

    @Test
    public void testPartiallyInflatedGroupsAreFilteredOut() {
        // GIVEN a newly-posted group with a summary and two children
        final GroupEntry group = new GroupEntryBuilder()
                .setCreationTime(400)
                .setSummary(getNotificationEntryBuilder().setId(1).build())
                .addChild(getNotificationEntryBuilder().setId(2).build())
                .addChild(getNotificationEntryBuilder().setId(3).build())
                .build();
        fireAddEvents(List.of(group));
        final NotificationEntry child0 = group.getChildren().get(0);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(group));

        // WHEN one of this children finishes inflating
        mNotifInflater.invokeInflateCallbackForEntry(child0);

        // THEN the inflated child is still filtered out
        assertTrue(mUninflatedFilter.shouldFilterOut(child0, 401));
    }

    @Test
    public void testPartiallyInflatedGroupsAreFilteredOutSummaryVersion() {
        // GIVEN a newly-posted group with a summary and two children
        final GroupEntry group = new GroupEntryBuilder()
                .setCreationTime(400)
                .setSummary(getNotificationEntryBuilder().setId(1).build())
                .addChild(getNotificationEntryBuilder().setId(2).build())
                .addChild(getNotificationEntryBuilder().setId(3).build())
                .build();
        fireAddEvents(List.of(group));
        final NotificationEntry summary = group.getSummary();
        final NotificationEntry child0 = group.getChildren().get(0);
        final NotificationEntry child1 = group.getChildren().get(1);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(group));

        // WHEN all of the children (but not the summary) finish inflating
        mNotifInflater.invokeInflateCallbackForEntry(child0);
        mNotifInflater.invokeInflateCallbackForEntry(child1);

        // THEN the entire group is still filtered out
        assertTrue(mUninflatedFilter.shouldFilterOut(summary, 401));
        assertTrue(mUninflatedFilter.shouldFilterOut(child0, 401));
        assertTrue(mUninflatedFilter.shouldFilterOut(child1, 401));
    }

    @Test
    public void testNullGroupSummary() {
        // GIVEN a newly-posted group with a summary and two children
        final GroupEntry group = new GroupEntryBuilder()
                .setCreationTime(400)
                .setSummary(getNotificationEntryBuilder().setId(1).build())
                .addChild(getNotificationEntryBuilder().setId(2).build())
                .addChild(getNotificationEntryBuilder().setId(3).build())
                .build();
        fireAddEvents(List.of(group));
        final NotificationEntry child0 = group.getChildren().get(0);
        final NotificationEntry child1 = group.getChildren().get(1);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(group));

        // WHEN the summary is pruned
        new GroupEntryBuilder()
                .setCreationTime(400)
                .addChild(child0)
                .addChild(child1)
                .build();

        // WHEN all of the children (but not the summary) finish inflating
        mNotifInflater.invokeInflateCallbackForEntry(child0);
        mNotifInflater.invokeInflateCallbackForEntry(child1);

        // THEN the entire group is not filtered out
        assertFalse(mUninflatedFilter.shouldFilterOut(child0, 401));
        assertFalse(mUninflatedFilter.shouldFilterOut(child1, 401));
    }

    @Test
    public void testPartiallyInflatedGroupsAreNotFilteredOutIfSummaryReinflate() {
        // GIVEN a newly-posted group with a summary and two children
        final String groupKey = "test_reinflate_group";
        final int summaryId = 1;
        final GroupEntry group = new GroupEntryBuilder()
                .setKey(groupKey)
                .setCreationTime(400)
                .setSummary(getNotificationEntryBuilder().setId(summaryId).setImportance(1).build())
                .addChild(getNotificationEntryBuilder().setId(2).build())
                .addChild(getNotificationEntryBuilder().setId(3).build())
                .build();
        fireAddEvents(List.of(group));
        final NotificationEntry summary = group.getSummary();
        final NotificationEntry child0 = group.getChildren().get(0);
        final NotificationEntry child1 = group.getChildren().get(1);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(group));

        // WHEN all of the children (but not the summary) finish inflating
        mNotifInflater.invokeInflateCallbackForEntry(child0);
        mNotifInflater.invokeInflateCallbackForEntry(child1);
        mNotifInflater.invokeInflateCallbackForEntry(summary);

        // WHEN the summary is updated and starts re-inflating
        summary.setRanking(new RankingBuilder(summary.getRanking()).setImportance(4).build());
        fireUpdateEvents(summary);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(group));

        // THEN the entire group is still not filtered out
        assertFalse(mUninflatedFilter.shouldFilterOut(summary, 401));
        assertFalse(mUninflatedFilter.shouldFilterOut(child0, 401));
        assertFalse(mUninflatedFilter.shouldFilterOut(child1, 401));
    }

    @Test
    public void testCompletedInflatedGroupsAreReleased() {
        // GIVEN a newly-posted group with a summary and two children
        final GroupEntry group = new GroupEntryBuilder()
                .setCreationTime(400)
                .setSummary(getNotificationEntryBuilder().setId(1).build())
                .addChild(getNotificationEntryBuilder().setId(2).build())
                .addChild(getNotificationEntryBuilder().setId(3).build())
                .build();
        fireAddEvents(List.of(group));
        final NotificationEntry summary = group.getSummary();
        final NotificationEntry child0 = group.getChildren().get(0);
        final NotificationEntry child1 = group.getChildren().get(1);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(group));

        // WHEN all of the children (and the summary) finish inflating
        mNotifInflater.invokeInflateCallbackForEntry(child0);
        mNotifInflater.invokeInflateCallbackForEntry(child1);
        mNotifInflater.invokeInflateCallbackForEntry(summary);

        // THEN the entire group is no longer filtered out
        assertFalse(mUninflatedFilter.shouldFilterOut(summary, 401));
        assertFalse(mUninflatedFilter.shouldFilterOut(child0, 401));
        assertFalse(mUninflatedFilter.shouldFilterOut(child1, 401));
    }

    @Test
    public void testCallConversationManagerBindWhenInflated() {
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        mNotifInflater.getInflateCallback(mEntry).onInflationFinished(mEntry, null);
        verify(mBindEventManagerImpl, times(1)).notifyViewBound(eq(mEntry));
        verifyNoMoreInteractions(mBindEventManagerImpl);
    }

    @Test
    public void testPartiallyInflatedGroupsAreReleasedAfterTimeout() {
        // GIVEN a newly-posted group with a summary and two children
        final GroupEntry group = new GroupEntryBuilder()
                .setCreationTime(400)
                .setSummary(getNotificationEntryBuilder().setId(1).build())
                .addChild(getNotificationEntryBuilder().setId(2).build())
                .addChild(getNotificationEntryBuilder().setId(3).build())
                .build();
        fireAddEvents(List.of(group));
        final NotificationEntry child0 = group.getChildren().get(0);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(group));

        // WHEN one of this children finishes inflating and enough time passes
        mNotifInflater.invokeInflateCallbackForEntry(child0);

        // THEN the inflated child is not filtered out even though the rest of the group hasn't
        // finished inflating yet
        assertTrue(mUninflatedFilter.shouldFilterOut(child0, TEST_MAX_GROUP_DELAY + 1));
    }

    private static class FakeNotifInflater implements NotifInflater {
        private final Map<NotificationEntry, InflationCallback> mInflateCallbacks = new HashMap<>();

        @Override
        public void inflateViews(@NonNull NotificationEntry entry, @NonNull Params params,
                @NonNull InflationCallback callback) {
            mInflateCallbacks.put(entry, callback);
        }


        @Override
        public void rebindViews(@NonNull NotificationEntry entry, @NonNull Params params,
                @NonNull InflationCallback callback) {
        }

        @Override
        public boolean abortInflation(@NonNull NotificationEntry entry) {
            return false;
        }

        public InflationCallback getInflateCallback(NotificationEntry entry) {
            return requireNonNull(mInflateCallbacks.get(entry));
        }

        public void invokeInflateCallbackForEntry(NotificationEntry entry) {
            getInflateCallback(entry).onInflationFinished(entry, entry.getRowController());
        }

        @Override
        public void releaseViews(@NonNull NotificationEntry entry) {
        }
    }

    private void fireAddEvents(List<? extends ListEntry> entries) {
        for (ListEntry entry : entries) {
            if (entry instanceof GroupEntry) {
                GroupEntry ge = (GroupEntry) entry;
                fireAddEvents(ge.getSummary());
                fireAddEvents(ge.getChildren());
            } else {
                fireAddEvents((NotificationEntry) entry);
            }
        }
    }

    private void fireAddEvents(NotificationEntry entry) {
        mCollectionListener.onEntryInit(entry);
        mCollectionListener.onEntryInit(entry);
    }

    private void fireUpdateEvents(NotificationEntry entry) {
        mCollectionListener.onEntryUpdated(entry);
    }

    private static final String TEST_MESSAGE = "TEST_MESSAGE";
    private static final String TEST_GROUP_KEY = "TEST_GROUP_KEY";
    private static final int TEST_CHILD_BIND_CUTOFF = 9;
    private static final int TEST_MAX_GROUP_DELAY = 100;

    private void setSectionIsLowPriority(boolean minimized) {
        mSectionStyleProvider.setMinimizedSections(minimized
                ? Collections.singleton(mNotifSection.getSectioner())
                : Collections.emptyList());
    }
}
