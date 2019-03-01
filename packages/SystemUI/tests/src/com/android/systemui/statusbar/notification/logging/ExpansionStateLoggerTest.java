/**
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.logging;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ExpansionStateLoggerTest extends SysuiTestCase {
    private static final String NOTIFICATION_KEY = "notin_key";

    private NotificationLogger.ExpansionStateLogger mLogger;
    @Mock
    private IStatusBarService mBarService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLogger = new NotificationLogger.ExpansionStateLogger(
                Dependency.get(UiOffloadThread.class));
        mLogger.mBarService = mBarService;
    }

    @Test
    public void testVisible() throws RemoteException {
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        waitForUiOffloadThread();

        verify(mBarService, Mockito.never()).onNotificationExpansionChanged(
                eq(NOTIFICATION_KEY), anyBoolean(), anyBoolean(), anyInt());
    }

    @Test
    public void testExpanded() throws RemoteException {
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN);
        waitForUiOffloadThread();

        verify(mBarService, Mockito.never()).onNotificationExpansionChanged(
                eq(NOTIFICATION_KEY), anyBoolean(), anyBoolean(), anyInt());
    }

    @Test
    public void testVisibleAndNotExpanded() throws RemoteException {
        mLogger.onExpansionChanged(NOTIFICATION_KEY, true, false,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN);
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        waitForUiOffloadThread();

        verify(mBarService, Mockito.never()).onNotificationExpansionChanged(
                eq(NOTIFICATION_KEY), anyBoolean(), anyBoolean(), anyInt());
    }

    @Test
    public void testVisibleAndExpanded() throws RemoteException {
        mLogger.onExpansionChanged(NOTIFICATION_KEY, true, true,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN);
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        waitForUiOffloadThread();

        verify(mBarService).onNotificationExpansionChanged(
                NOTIFICATION_KEY, true, true,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN.toMetricsEventEnum());
    }

    @Test
    public void testExpandedAndVisible_expandedBeforeVisible() throws RemoteException {
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN);
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true,
                    NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA)),
                Collections.emptyList());
        waitForUiOffloadThread();

        verify(mBarService).onNotificationExpansionChanged(
                NOTIFICATION_KEY, false, true,
                // The last location seen should be logged (the one passed to onVisibilityChanged).
                NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA.toMetricsEventEnum()
        );
    }

    @Test
    public void testExpandedAndVisible_visibleBeforeExpanded() throws RemoteException {
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true,
                NotificationVisibility.NotificationLocation.LOCATION_FIRST_HEADS_UP);
        waitForUiOffloadThread();

        verify(mBarService).onNotificationExpansionChanged(
                NOTIFICATION_KEY, false, true,
                // The last location seen should be logged (the one passed to onExpansionChanged).
                NotificationVisibility.NotificationLocation.LOCATION_FIRST_HEADS_UP.toMetricsEventEnum());
    }

    @Test
    public void testExpandedAndVisible_logOnceOnly() throws RemoteException {
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN);
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN);
        waitForUiOffloadThread();

        verify(mBarService).onNotificationExpansionChanged(
                NOTIFICATION_KEY, false, true,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN.toMetricsEventEnum());
    }

    @Test
    public void testOnEntryReinflated() throws RemoteException {
        mLogger.onExpansionChanged(NOTIFICATION_KEY, true, true,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN);
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        waitForUiOffloadThread();
        verify(mBarService).onNotificationExpansionChanged(
                NOTIFICATION_KEY, true, true, ExpandableViewState.LOCATION_UNKNOWN);

        mLogger.onEntryReinflated(NOTIFICATION_KEY);
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        waitForUiOffloadThread();
        // onNotificationExpansionChanged is called the second time.
        verify(mBarService, times(2)).onNotificationExpansionChanged(
                NOTIFICATION_KEY, true, true, ExpandableViewState.LOCATION_UNKNOWN);
    }

    private NotificationVisibility createNotificationVisibility(String key, boolean visibility) {
        return createNotificationVisibility(key, visibility,
                NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN);
    }

    private NotificationVisibility createNotificationVisibility(String key, boolean visibility,
            NotificationVisibility.NotificationLocation location) {
        return NotificationVisibility.obtain(key, 0, 0, visibility, location);
    }
}
