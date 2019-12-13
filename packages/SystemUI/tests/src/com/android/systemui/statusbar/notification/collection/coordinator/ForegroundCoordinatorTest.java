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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.statusbar.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.NotifListBuilderImpl;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ForegroundCoordinatorTest extends SysuiTestCase {
    private static final String TEST_PKG = "test_pkg";
    private static final int NOTIF_USER_ID = 0;

    @Mock private Handler mMainHandler;
    @Mock private ForegroundServiceController mForegroundServiceController;
    @Mock private AppOpsController mAppOpsController;
    @Mock private NotifListBuilderImpl mNotifListBuilder;
    @Mock private NotifCollection mNotifCollection;

    private NotificationEntry mEntry;
    private Notification mNotification;
    private ForegroundCoordinator mForegroundCoordinator;
    private NotifFilter mForegroundFilter;
    private NotifLifetimeExtender mForegroundNotifLifetimeExtender;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mForegroundCoordinator = new ForegroundCoordinator(
                mForegroundServiceController, mAppOpsController, mMainHandler);

        mNotification = new Notification();
        mEntry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setNotification(mNotification)
                .build();

        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        ArgumentCaptor<NotifLifetimeExtender> lifetimeExtenderCaptor =
                ArgumentCaptor.forClass(NotifLifetimeExtender.class);

        mForegroundCoordinator.attach(mNotifCollection, mNotifListBuilder);
        verify(mNotifListBuilder, times(1)).addFilter(filterCaptor.capture());
        verify(mNotifCollection, times(1)).addNotificationLifetimeExtender(
                lifetimeExtenderCaptor.capture());

        mForegroundFilter = filterCaptor.getValue();
        mForegroundNotifLifetimeExtender = lifetimeExtenderCaptor.getValue();
    }

    @Test
    public void filterTest_disclosureUnnecessary() {
        StatusBarNotification sbn = mEntry.getSbn();

        // GIVEN the notification is a disclosure notification
        when(mForegroundServiceController.isDisclosureNotification(sbn)).thenReturn(true);

        // GIVEN the disclosure isn't needed for this user
        when(mForegroundServiceController.isDisclosureNeededForUser(sbn.getUserId()))
                .thenReturn(false);

        // THEN filter out the notification
        assertTrue(mForegroundFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void filterTest_systemAlertNotificationUnnecessary() {
        StatusBarNotification sbn = mEntry.getSbn();

        // GIVEN the notification is a system alert notification + not a disclosure notification
        when(mForegroundServiceController.isSystemAlertNotification(sbn)).thenReturn(true);
        when(mForegroundServiceController.isDisclosureNotification(sbn)).thenReturn(false);

        // GIVEN the alert notification isn't needed for this user
        final Bundle extras = new Bundle();
        extras.putStringArray(Notification.EXTRA_FOREGROUND_APPS,
                new String[]{TEST_PKG});
        mNotification.extras = extras;
        when(mForegroundServiceController.isSystemAlertWarningNeeded(sbn.getUserId(), TEST_PKG))
                .thenReturn(false);

        // THEN filter out the notification
        assertTrue(mForegroundFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void filterTest_doNotFilter() {
        StatusBarNotification sbn = mEntry.getSbn();

        // GIVEN the notification isn't a system alert notification nor a disclosure notification
        when(mForegroundServiceController.isSystemAlertNotification(sbn)).thenReturn(false);
        when(mForegroundServiceController.isDisclosureNotification(sbn)).thenReturn(false);

        // THEN don't filter out the notification
        assertFalse(mForegroundFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void extendLifetimeText_notForeground() {
        // GIVEN the notification doesn't represent a foreground service
        mNotification.flags = 0;

        // THEN don't extend the lifetime
        assertFalse(mForegroundNotifLifetimeExtender
                .shouldExtendLifetime(mEntry, NotificationListenerService.REASON_CLICK));
    }

    @Test
    public void extendLifetimeText_foregroundNotifRecentlyPosted() {
        // GIVEN the notification represents a foreground service that was just posted
        mNotification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        mEntry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setSbn(new StatusBarNotification(TEST_PKG, TEST_PKG, NOTIF_USER_ID, "",
                        NOTIF_USER_ID, NOTIF_USER_ID, mNotification,
                        new UserHandle(NOTIF_USER_ID), "", System.currentTimeMillis()))
                .setNotification(mNotification)
                .build();

        // THEN extend the lifetime
        assertTrue(mForegroundNotifLifetimeExtender
                .shouldExtendLifetime(mEntry, NotificationListenerService.REASON_CLICK));
    }

    @Test
    public void extendLifetimeText_foregroundNotifOld() {
        // GIVEN the notification represents a foreground service that was posted 10 seconds ago
        mNotification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        mEntry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .setSbn(new StatusBarNotification(TEST_PKG, TEST_PKG, NOTIF_USER_ID, "",
                        NOTIF_USER_ID, NOTIF_USER_ID, mNotification,
                        new UserHandle(NOTIF_USER_ID), "",
                        System.currentTimeMillis() - 10000))
                .setNotification(mNotification)
                .build();

        // THEN don't extend the lifetime because the extended time exceeds
        // ForegroundCoordinator.MIN_FGS_TIME_MS
        assertFalse(mForegroundNotifLifetimeExtender
                .shouldExtendLifetime(mEntry, NotificationListenerService.REASON_CLICK));
    }
}
