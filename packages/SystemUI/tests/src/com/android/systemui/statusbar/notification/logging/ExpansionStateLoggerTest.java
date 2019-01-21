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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.UiOffloadThread;

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
                eq(NOTIFICATION_KEY), anyBoolean(), anyBoolean());
    }

    @Test
    public void testExpanded() throws RemoteException {
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true);
        waitForUiOffloadThread();

        verify(mBarService, Mockito.never()).onNotificationExpansionChanged(
                eq(NOTIFICATION_KEY), anyBoolean(), anyBoolean());
    }

    @Test
    public void testVisibleAndNotExpanded() throws RemoteException {
        mLogger.onExpansionChanged(NOTIFICATION_KEY, true, false);
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        waitForUiOffloadThread();

        verify(mBarService, Mockito.never()).onNotificationExpansionChanged(
                eq(NOTIFICATION_KEY), anyBoolean(), anyBoolean());
    }

    @Test
    public void testVisibleAndExpanded() throws RemoteException {
        mLogger.onExpansionChanged(NOTIFICATION_KEY, true, true);
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        waitForUiOffloadThread();

        verify(mBarService).onNotificationExpansionChanged(
                NOTIFICATION_KEY, true, true);
    }

    @Test
    public void testExpandedAndVisible_expandedBeforeVisible() throws RemoteException {
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true);
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        waitForUiOffloadThread();

        verify(mBarService).onNotificationExpansionChanged(
                NOTIFICATION_KEY, false, true);
    }

    @Test
    public void testExpandedAndVisible_visibleBeforeExpanded() throws RemoteException {
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true);
        waitForUiOffloadThread();

        verify(mBarService).onNotificationExpansionChanged(
                NOTIFICATION_KEY, false, true);
    }

    @Test
    public void testExpandedAndVisible_logOnceOnly() throws RemoteException {
        mLogger.onVisibilityChanged(
                Collections.singletonList(createNotificationVisibility(NOTIFICATION_KEY, true)),
                Collections.emptyList());
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true);
        mLogger.onExpansionChanged(NOTIFICATION_KEY, false, true);
        waitForUiOffloadThread();

        verify(mBarService).onNotificationExpansionChanged(
                NOTIFICATION_KEY, false, true);
    }

    private NotificationVisibility createNotificationVisibility(String key, boolean visibility) {
        return NotificationVisibility.obtain(key, 0, 0, visibility);
    }
}
