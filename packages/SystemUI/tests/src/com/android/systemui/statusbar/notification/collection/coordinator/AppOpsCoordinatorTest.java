/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AppOpsCoordinatorTest extends SysuiTestCase {
    private static final String TEST_PKG = "test_pkg";
    private static final int NOTIF_USER_ID = 0;

    @Mock private ForegroundServiceController mForegroundServiceController;
    @Mock private AppOpsController mAppOpsController;
    @Mock private NotifPipeline mNotifPipeline;

    private NotificationEntryBuilder mEntryBuilder;
    private AppOpsCoordinator mAppOpsCoordinator;
    private NotifFilter mForegroundFilter;
    private NotifCollectionListener mNotifCollectionListener;
    private AppOpsController.Callback mAppOpsCallback;
    private NotifLifetimeExtender mForegroundNotifLifetimeExtender;
    private NotifSection mFgsSection;

    private FakeSystemClock mClock = new FakeSystemClock();
    private FakeExecutor mExecutor = new FakeExecutor(mClock);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();

        mAppOpsCoordinator =
                new AppOpsCoordinator(
                        mForegroundServiceController,
                        mAppOpsController,
                        mExecutor);

        mEntryBuilder = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID));

        mAppOpsCoordinator.attach(mNotifPipeline);

        // capture filter
        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        verify(mNotifPipeline, times(1)).addPreGroupFilter(filterCaptor.capture());
        mForegroundFilter = filterCaptor.getValue();

        // capture lifetime extender
        ArgumentCaptor<NotifLifetimeExtender> lifetimeExtenderCaptor =
                ArgumentCaptor.forClass(NotifLifetimeExtender.class);
        verify(mNotifPipeline, times(1)).addNotificationLifetimeExtender(
                lifetimeExtenderCaptor.capture());
        mForegroundNotifLifetimeExtender = lifetimeExtenderCaptor.getValue();

        // capture notifCollectionListener
        ArgumentCaptor<NotifCollectionListener> notifCollectionCaptor =
                ArgumentCaptor.forClass(NotifCollectionListener.class);
        verify(mNotifPipeline, times(1)).addCollectionListener(
                notifCollectionCaptor.capture());
        mNotifCollectionListener = notifCollectionCaptor.getValue();

        // capture app ops callback
        ArgumentCaptor<AppOpsController.Callback> appOpsCaptor =
                ArgumentCaptor.forClass(AppOpsController.Callback.class);
        verify(mAppOpsController).addCallback(any(int[].class), appOpsCaptor.capture());
        mAppOpsCallback = appOpsCaptor.getValue();

        mFgsSection = mAppOpsCoordinator.getSection();
    }

    @Test
    public void filterTest_disclosureUnnecessary() {
        NotificationEntry entry = mEntryBuilder.build();
        StatusBarNotification sbn = entry.getSbn();

        // GIVEN the notification is a disclosure notification
        when(mForegroundServiceController.isDisclosureNotification(sbn)).thenReturn(true);

        // GIVEN the disclosure isn't needed for this user
        when(mForegroundServiceController.isDisclosureNeededForUser(sbn.getUserId()))
                .thenReturn(false);

        // THEN filter out the notification
        assertTrue(mForegroundFilter.shouldFilterOut(entry, 0));
    }

    @Test
    public void filterTest_systemAlertNotificationUnnecessary() {
        // GIVEN the alert notification isn't needed for this user
        final Bundle extras = new Bundle();
        extras.putStringArray(Notification.EXTRA_FOREGROUND_APPS,
                new String[]{TEST_PKG});
        mEntryBuilder.modifyNotification(mContext)
                .setExtras(extras);
        NotificationEntry entry = mEntryBuilder.build();
        StatusBarNotification sbn = entry.getSbn();
        when(mForegroundServiceController.isSystemAlertWarningNeeded(sbn.getUserId(), TEST_PKG))
                .thenReturn(false);

        // GIVEN the notification is a system alert notification + not a disclosure notification
        when(mForegroundServiceController.isSystemAlertNotification(sbn)).thenReturn(true);
        when(mForegroundServiceController.isDisclosureNotification(sbn)).thenReturn(false);


        // THEN filter out the notification
        assertTrue(mForegroundFilter.shouldFilterOut(entry, 0));
    }

    @Test
    public void filterTest_doNotFilter() {
        NotificationEntry entry = mEntryBuilder.build();
        StatusBarNotification sbn = entry.getSbn();

        // GIVEN the notification isn't a system alert notification nor a disclosure notification
        when(mForegroundServiceController.isSystemAlertNotification(sbn)).thenReturn(false);
        when(mForegroundServiceController.isDisclosureNotification(sbn)).thenReturn(false);

        // THEN don't filter out the notification
        assertFalse(mForegroundFilter.shouldFilterOut(entry, 0));
    }

    @Test
    public void extendLifetimeText_notForeground() {
        // GIVEN the notification doesn't represent a foreground service
        mEntryBuilder.modifyNotification(mContext)
                .setFlag(FLAG_FOREGROUND_SERVICE, false);

        // THEN don't extend the lifetime
        assertFalse(mForegroundNotifLifetimeExtender
                .shouldExtendLifetime(mEntryBuilder.build(),
                        NotificationListenerService.REASON_CLICK));
    }

    @Test
    public void extendLifetimeText_foregroundNotifRecentlyPosted() {
        // GIVEN the notification represents a foreground service that was just posted
        Notification notification = new Notification.Builder(mContext, "test_channel")
                .setFlag(FLAG_FOREGROUND_SERVICE, true)
                .build();
        NotificationEntry entry = mEntryBuilder
                .setSbn(new StatusBarNotification(TEST_PKG, TEST_PKG, NOTIF_USER_ID, "",
                        NOTIF_USER_ID, NOTIF_USER_ID, notification,
                        new UserHandle(NOTIF_USER_ID), "", System.currentTimeMillis()))
                .setNotification(notification)
                .build();

        // THEN extend the lifetime
        assertTrue(mForegroundNotifLifetimeExtender
                .shouldExtendLifetime(entry, NotificationListenerService.REASON_CLICK));
    }

    @Test
    public void extendLifetimeText_foregroundNotifOld() {
        // GIVEN the notification represents a foreground service that was posted 10 seconds ago
        Notification notification = new Notification.Builder(mContext, "test_channel")
                .setFlag(FLAG_FOREGROUND_SERVICE, true)
                .build();
        NotificationEntry entry = mEntryBuilder
                .setSbn(new StatusBarNotification(TEST_PKG, TEST_PKG, NOTIF_USER_ID, "",
                        NOTIF_USER_ID, NOTIF_USER_ID, notification,
                        new UserHandle(NOTIF_USER_ID), "",
                        System.currentTimeMillis() - 10000))
                .setNotification(notification)
                .build();

        // THEN don't extend the lifetime because the extended time exceeds MIN_FGS_TIME_MS
        assertFalse(mForegroundNotifLifetimeExtender
                .shouldExtendLifetime(entry, NotificationListenerService.REASON_CLICK));
    }

    @Test
    public void testAppOpsUpdateOnlyAppliedToRelevantNotificationWithStandardLayout() {
        // GIVEN three current notifications, two with the same key but from different users
        NotificationEntry entry1 = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(1)
                .build();
        NotificationEntry entry2 = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(2)
                .build();
        NotificationEntry entry3_diffUser = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID + 1))
                .setPkg(TEST_PKG)
                .setId(2)
                .build();
        when(mNotifPipeline.getAllNotifs()).thenReturn(List.of(entry1, entry2, entry3_diffUser));

        // GIVEN that only entry2 has a standard layout
        when(mForegroundServiceController.getStandardLayoutKeys(NOTIF_USER_ID, TEST_PKG))
                .thenReturn(new ArraySet<>(List.of(entry2.getKey())));

        // WHEN a new app ops code comes in
        mAppOpsCallback.onActiveStateChanged(47, NOTIF_USER_ID, TEST_PKG, true);
        mExecutor.runAllReady();

        // THEN entry2's app ops are updated, but no one else's are
        assertEquals(
                new ArraySet<>(),
                entry1.mActiveAppOps);
        assertEquals(
                new ArraySet<>(List.of(47)),
                entry2.mActiveAppOps);
        assertEquals(
                new ArraySet<>(),
                entry3_diffUser.mActiveAppOps);
    }

    @Test
    public void testAppOpsUpdateAppliedToAllNotificationsWithStandardLayouts() {
        // GIVEN three notifications with standard layouts
        NotificationEntry entry1 = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(1)
                .build();
        NotificationEntry entry2 = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(2)
                .build();
        NotificationEntry entry3 = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(3)
                .build();
        when(mNotifPipeline.getAllNotifs()).thenReturn(List.of(entry1, entry2, entry3));
        when(mForegroundServiceController.getStandardLayoutKeys(NOTIF_USER_ID, TEST_PKG))
                .thenReturn(new ArraySet<>(List.of(entry1.getKey(), entry2.getKey(),
                        entry3.getKey())));

        // WHEN a new app ops code comes in
        mAppOpsCallback.onActiveStateChanged(47, NOTIF_USER_ID, TEST_PKG, true);
        mExecutor.runAllReady();

        // THEN all entries get updated
        assertEquals(
                new ArraySet<>(List.of(47)),
                entry1.mActiveAppOps);
        assertEquals(
                new ArraySet<>(List.of(47)),
                entry2.mActiveAppOps);
        assertEquals(
                new ArraySet<>(List.of(47)),
                entry3.mActiveAppOps);
    }

    @Test
    public void testAppOpsAreRemoved() {
        // GIVEN One notification which is associated with app ops
        NotificationEntry entry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(2)
                .build();
        when(mNotifPipeline.getAllNotifs()).thenReturn(List.of(entry));
        when(mForegroundServiceController.getStandardLayoutKeys(0, TEST_PKG))
                .thenReturn(new ArraySet<>(List.of(entry.getKey())));

        // GIVEN that the notification's app ops are already [47, 33]
        mAppOpsCallback.onActiveStateChanged(47, NOTIF_USER_ID, TEST_PKG, true);
        mAppOpsCallback.onActiveStateChanged(33, NOTIF_USER_ID, TEST_PKG, true);
        mExecutor.runAllReady();
        assertEquals(
                new ArraySet<>(List.of(47, 33)),
                entry.mActiveAppOps);

        // WHEN one of the app ops is removed
        mAppOpsCallback.onActiveStateChanged(47, NOTIF_USER_ID, TEST_PKG, false);
        mExecutor.runAllReady();

        // THEN the entry's active app ops are updated as well
        assertEquals(
                new ArraySet<>(List.of(33)),
                entry.mActiveAppOps);
    }

    @Test
    public void testNullAppOps() {
        // GIVEN one notification with app ops
        NotificationEntry entry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setPkg(TEST_PKG)
                .setId(2)
                .build();
        entry.mActiveAppOps.clear();
        entry.mActiveAppOps.addAll(List.of(47, 33));

        // WHEN the notification is updated and the foreground service controller returns null for
        // this notification
        when(mForegroundServiceController.getAppOps(entry.getSbn().getUser().getIdentifier(),
                entry.getSbn().getPackageName())).thenReturn(null);
        mNotifCollectionListener.onEntryUpdated(entry);

        // THEN the entry's active app ops is updated to empty
        assertTrue(entry.mActiveAppOps.isEmpty());
    }

    @Test
    public void testIncludeFGSInSection_importanceDefault() {
        // GIVEN the notification represents a colorized foreground service with > min importance
        mEntryBuilder
                .setFlag(mContext, FLAG_FOREGROUND_SERVICE, true)
                .setImportance(IMPORTANCE_DEFAULT)
                .modifyNotification(mContext).setColorized(true);

        // THEN the entry is in the fgs section
        assertTrue(mFgsSection.isInSection(mEntryBuilder.build()));
    }

    @Test
    public void testDiscludeFGSInSection_importanceMin() {
        // GIVEN the notification represents a colorized foreground service with min importance
        mEntryBuilder
                .setFlag(mContext, FLAG_FOREGROUND_SERVICE, true)
                .setImportance(IMPORTANCE_MIN)
                .modifyNotification(mContext).setColorized(true);

        // THEN the entry is NOT in the fgs section
        assertFalse(mFgsSection.isInSection(mEntryBuilder.build()));
    }

    @Test
    public void testDiscludeNonFGSInSection() {
        // GIVEN the notification represents a colorized notification with high importance that
        // is NOT a foreground service
        mEntryBuilder
                .setImportance(IMPORTANCE_HIGH)
                .setFlag(mContext, FLAG_FOREGROUND_SERVICE, false)
                .modifyNotification(mContext).setColorized(false);

        // THEN the entry is NOT in the fgs section
        assertFalse(mFgsSection.isInSection(mEntryBuilder.build()));
    }
}
