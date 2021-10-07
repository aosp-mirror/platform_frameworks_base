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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Color;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    private NotifSectioner mFgsSection;

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

        mFgsSection = mAppOpsCoordinator.getSectioner();
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
    public void testIncludeFGSInSection_importanceDefault() {
        // GIVEN the notification represents a colorized foreground service with > min importance
        mEntryBuilder
                .setFlag(mContext, FLAG_FOREGROUND_SERVICE, true)
                .setImportance(IMPORTANCE_DEFAULT)
                .modifyNotification(mContext)
                .setColorized(true).setColor(Color.WHITE);

        // THEN the entry is in the fgs section
        assertTrue(mFgsSection.isInSection(mEntryBuilder.build()));
    }

    @Test
    public void testDiscludeFGSInSection_importanceMin() {
        // GIVEN the notification represents a colorized foreground service with min importance
        mEntryBuilder
                .setFlag(mContext, FLAG_FOREGROUND_SERVICE, true)
                .setImportance(IMPORTANCE_MIN)
                .modifyNotification(mContext)
                .setColorized(true).setColor(Color.WHITE);

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
