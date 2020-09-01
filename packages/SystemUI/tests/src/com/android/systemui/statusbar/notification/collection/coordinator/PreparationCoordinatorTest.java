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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.GroupEntryHelper;
import com.android.systemui.statusbar.notification.collection.NotifInflaterImpl;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotifViewBarn;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class PreparationCoordinatorTest extends SysuiTestCase {
    private static final String TEST_MESSAGE = "TEST_MESSAGE";
    private static final String TEST_GROUP_KEY = "TEST_GROUP_KEY";
    private static final int TEST_CHILD_BIND_CUTOFF = 9;

    private PreparationCoordinator mCoordinator;
    private NotifCollectionListener mCollectionListener;
    private OnBeforeFinalizeFilterListener mBeforeFilterListener;
    private NotifFilter mUninflatedFilter;
    private NotifFilter mInflationErrorFilter;
    private NotifInflationErrorManager mErrorManager;
    private NotificationEntry mEntry;
    private Exception mInflationError;

    @Captor private ArgumentCaptor<NotifCollectionListener> mCollectionListenerCaptor;
    @Captor private ArgumentCaptor<OnBeforeFinalizeFilterListener> mBeforeFilterListenerCaptor;
    @Captor private ArgumentCaptor<NotifInflaterImpl.InflationCallback> mCallbackCaptor;

    @Mock private NotifPipeline mNotifPipeline;
    @Mock private IStatusBarService mService;
    @Mock private NotifInflaterImpl mNotifInflater;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mEntry = new NotificationEntryBuilder().build();
        mInflationError = new Exception(TEST_MESSAGE);
        mErrorManager = new NotifInflationErrorManager();

        mCoordinator = new PreparationCoordinator(
                mock(PreparationCoordinatorLogger.class),
                mNotifInflater,
                mErrorManager,
                mock(NotifViewBarn.class),
                mService,
                TEST_CHILD_BIND_CUTOFF);

        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        mCoordinator.attach(mNotifPipeline);
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
                eq(mEntry.getSbn().getUserId()));
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
        mCollectionListener.onEntryAdded(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));

        // THEN we inflate it
        verify(mNotifInflater).inflateViews(eq(mEntry), any());

        // THEN we filter it out until it's done inflating.
        assertTrue(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testRebindsInflatedNotificationsOnUpdate() {
        // GIVEN an inflated notification
        mCollectionListener.onEntryAdded(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        verify(mNotifInflater).inflateViews(eq(mEntry), mCallbackCaptor.capture());
        mCallbackCaptor.getValue().onInflationFinished(mEntry);

        // WHEN notification is updated
        mCollectionListener.onEntryUpdated(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));

        // THEN we rebind it
        verify(mNotifInflater).rebindViews(eq(mEntry), any());

        // THEN we do not filter it because it's not the first inflation.
        assertFalse(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testDoesntFilterInflatedNotifs() {
        // GIVEN an inflated notification
        mCollectionListener.onEntryAdded(mEntry);
        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(mEntry));
        verify(mNotifInflater).inflateViews(eq(mEntry), mCallbackCaptor.capture());
        mCallbackCaptor.getValue().onInflationFinished(mEntry);

        // THEN it isn't filtered from shade list
        assertFalse(mUninflatedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testCutoffGroupChildrenNotInflated() {
        // WHEN there is a new notification group is posted
        int id = 0;
        NotificationEntry summary = new NotificationEntryBuilder()
                .setOverrideGroupKey(TEST_GROUP_KEY)
                .setId(id++)
                .build();
        List<NotificationEntry> children = new ArrayList<>();
        for (int i = 0; i < TEST_CHILD_BIND_CUTOFF + 1; i++) {
            NotificationEntry child = new NotificationEntryBuilder()
                    .setOverrideGroupKey(TEST_GROUP_KEY)
                    .setId(id++)
                    .build();
            children.add(child);
        }
        GroupEntry groupEntry = GroupEntryHelper.createGroup(TEST_GROUP_KEY, summary, children);

        mCollectionListener.onEntryInit(summary);
        for (NotificationEntry entry : children) {
            mCollectionListener.onEntryInit(entry);
        }

        mCollectionListener.onEntryAdded(summary);
        for (NotificationEntry entry : children) {
            mCollectionListener.onEntryAdded(entry);
        }

        mBeforeFilterListener.onBeforeFinalizeFilter(List.of(groupEntry));

        // THEN we inflate up to the cut-off only
        for (int i = 0; i < children.size(); i++) {
            if (i < TEST_CHILD_BIND_CUTOFF) {
                verify(mNotifInflater).inflateViews(eq(children.get(i)), any());
            } else {
                verify(mNotifInflater, never()).inflateViews(eq(children.get(i)), any());
            }
        }
    }
}
