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

import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotifInflaterImpl;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager;

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
public class PreparationCoordinatorTest extends SysuiTestCase {
    private static final String TEST_MESSAGE = "TEST_MESSAGE";

    private PreparationCoordinator mCoordinator;
    private NotifFilter mInflationErrorFilter;
    private NotifInflationErrorManager mErrorManager;
    private NotificationEntry mEntry;
    private Exception mInflationError;

    @Mock
    private NotifPipeline mNotifPipeline;
    @Mock private IStatusBarService mService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mEntry = new NotificationEntryBuilder().build();
        mInflationError = new Exception(TEST_MESSAGE);
        mErrorManager = new NotifInflationErrorManager();

        mCoordinator = new PreparationCoordinator(
                mock(PreparationCoordinatorLogger.class),
                mock(NotifInflaterImpl.class),
                mErrorManager,
                mService);

        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        mCoordinator.attach(mNotifPipeline);
        verify(mNotifPipeline, times(2)).addFinalizeFilter(filterCaptor.capture());
        List<NotifFilter> filters = filterCaptor.getAllValues();
        mInflationErrorFilter = filters.get(0);
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
}
