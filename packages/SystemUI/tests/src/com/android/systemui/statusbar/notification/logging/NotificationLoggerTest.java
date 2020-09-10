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

package com.android.systemui.statusbar.notification.logging;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.InstanceId;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.logging.nano.Notifications;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationLoggerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    @Mock private NotificationListContainer mListContainer;
    @Mock private IStatusBarService mBarService;
    @Mock private ExpandableNotificationRow mRow;
    @Mock private NotificationLogger.ExpansionStateLogger mExpansionStateLogger;

    // Dependency mocks:
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationListener mListener;
    @Captor private ArgumentCaptor<NotificationEntryListener> mEntryListenerCaptor;

    private NotificationEntry mEntry;
    private TestableNotificationLogger mLogger;
    private ConcurrentLinkedQueue<AssertionError> mErrorQueue = new ConcurrentLinkedQueue<>();
    private FakeExecutor mUiBgExecutor = new FakeExecutor(new FakeSystemClock());
    private NotificationPanelLoggerFake mNotificationPanelLoggerFake =
            new NotificationPanelLoggerFake();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(NotificationListener.class, mListener);

        mEntry = new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setUid(TEST_UID)
                .setNotification(new Notification())
                .setUser(UserHandle.CURRENT)
                .setInstanceId(InstanceId.fakeInstanceId(1))
                .build();
        mEntry.setRow(mRow);

        mLogger = new TestableNotificationLogger(mListener, mUiBgExecutor,
                mEntryManager, mock(StatusBarStateControllerImpl.class), mBarService,
                mExpansionStateLogger);
        mLogger.setUpWithContainer(mListContainer);
        verify(mEntryManager).addNotificationEntryListener(mEntryListenerCaptor.capture());
    }

    @Test
    public void testOnChildLocationsChangedReportsVisibilityChanged() throws Exception {
        NotificationVisibility[] newlyVisibleKeys = {
                NotificationVisibility.obtain(mEntry.getKey(), 0, 1, true)
        };
        NotificationVisibility[] noLongerVisibleKeys = {};
        doAnswer(invocation -> {
                    try {
                        assertArrayEquals(newlyVisibleKeys,
                                (NotificationVisibility[]) invocation.getArguments()[0]);
                        assertArrayEquals(noLongerVisibleKeys,
                                (NotificationVisibility[]) invocation.getArguments()[1]);
                    } catch (AssertionError error) {
                        mErrorQueue.offer(error);
                    }
                    return null;
                }
        ).when(mBarService).onNotificationVisibilityChanged(any(NotificationVisibility[].class),
                any(NotificationVisibility[].class));

        when(mListContainer.isInVisibleLocation(any())).thenReturn(true);
        when(mEntryManager.getVisibleNotifications()).thenReturn(Lists.newArrayList(mEntry));
        mLogger.getChildLocationsChangedListenerForTest().onChildLocationsChanged();
        TestableLooper.get(this).processAllMessages();
        mUiBgExecutor.runAllReady();

        if(!mErrorQueue.isEmpty()) {
            throw mErrorQueue.poll();
        }

        // |mEntry| won't change visibility, so it shouldn't be reported again:
        Mockito.reset(mBarService);
        mLogger.getChildLocationsChangedListenerForTest().onChildLocationsChanged();
        TestableLooper.get(this).processAllMessages();
        mUiBgExecutor.runAllReady();

        verify(mBarService, never()).onNotificationVisibilityChanged(any(), any());
    }

    @Test
    public void testStoppingNotificationLoggingReportsCurrentNotifications()
            throws Exception {
        when(mListContainer.isInVisibleLocation(any())).thenReturn(true);
        when(mEntryManager.getVisibleNotifications()).thenReturn(Lists.newArrayList(mEntry));
        mLogger.getChildLocationsChangedListenerForTest().onChildLocationsChanged();
        TestableLooper.get(this).processAllMessages();
        mUiBgExecutor.runAllReady();
        Mockito.reset(mBarService);

        setStateAsleep();
        mLogger.onDozingChanged(false);  // Wake to lockscreen
        mLogger.onDozingChanged(true);  // And go back to sleep, turning off logging
        mUiBgExecutor.runAllReady();
        // The visibility objects are recycled by NotificationLogger, so we can't use specific
        // matchers here.
        verify(mBarService, times(1)).onNotificationVisibilityChanged(any(), any());
    }

    private void setStateAsleep() {
        mLogger.onPanelExpandedChanged(true);
        mLogger.onDozingChanged(true);
        mLogger.onStateChanged(StatusBarState.KEYGUARD);
    }

    private void setStateAwake() {
        mLogger.onPanelExpandedChanged(false);
        mLogger.onDozingChanged(false);
        mLogger.onStateChanged(StatusBarState.SHADE);
    }

    @Test
    public void testLogPanelShownOnWake() {
        when(mEntryManager.getVisibleNotifications()).thenReturn(Lists.newArrayList(mEntry));
        setStateAsleep();
        mLogger.onDozingChanged(false);  // Wake to lockscreen
        assertEquals(1, mNotificationPanelLoggerFake.getCalls().size());
        assertTrue(mNotificationPanelLoggerFake.get(0).isLockscreen);
        assertEquals(1, mNotificationPanelLoggerFake.get(0).list.notifications.length);
        Notifications.Notification n = mNotificationPanelLoggerFake.get(0).list.notifications[0];
        assertEquals(TEST_PACKAGE_NAME, n.packageName);
        assertEquals(TEST_UID, n.uid);
        assertEquals(1, n.instanceId);
        assertFalse(n.isGroupSummary);
        assertEquals(Notifications.Notification.SECTION_ALERTING, n.section);
    }

    @Test
    public void testLogPanelShownOnShadePull() {
        when(mEntryManager.getVisibleNotifications()).thenReturn(Lists.newArrayList(mEntry));
        setStateAwake();
        // Now expand panel
        mLogger.onPanelExpandedChanged(true);
        assertEquals(1, mNotificationPanelLoggerFake.getCalls().size());
        assertFalse(mNotificationPanelLoggerFake.get(0).isLockscreen);
        assertEquals(1, mNotificationPanelLoggerFake.get(0).list.notifications.length);
        Notifications.Notification n = mNotificationPanelLoggerFake.get(0).list.notifications[0];
        assertEquals(TEST_PACKAGE_NAME, n.packageName);
        assertEquals(TEST_UID, n.uid);
        assertEquals(1, n.instanceId);
        assertFalse(n.isGroupSummary);
        assertEquals(Notifications.Notification.SECTION_ALERTING, n.section);
    }


    @Test
    public void testLogPanelShownHandlesNullInstanceIds() {
        // Construct a NotificationEntry like mEntry, but with a null instance id.
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setUid(TEST_UID)
                .setNotification(new Notification())
                .setUser(UserHandle.CURRENT)
                .build();
        entry.setRow(mRow);

        when(mEntryManager.getVisibleNotifications()).thenReturn(Lists.newArrayList(entry));
        setStateAsleep();
        mLogger.onDozingChanged(false);  // Wake to lockscreen
        assertEquals(1, mNotificationPanelLoggerFake.getCalls().size());
        assertEquals(1, mNotificationPanelLoggerFake.get(0).list.notifications.length);
        Notifications.Notification n = mNotificationPanelLoggerFake.get(0).list.notifications[0];
        assertEquals(0, n.instanceId);
    }

    private class TestableNotificationLogger extends NotificationLogger {

        TestableNotificationLogger(NotificationListener notificationListener,
                Executor uiBgExecutor,
                NotificationEntryManager entryManager,
                StatusBarStateControllerImpl statusBarStateController,
                IStatusBarService barService,
                ExpansionStateLogger expansionStateLogger) {
            super(notificationListener, uiBgExecutor, entryManager, statusBarStateController,
                    expansionStateLogger, mNotificationPanelLoggerFake);
            mBarService = barService;
            // Make this on the current thread so we can wait for it during tests.
            mHandler = Handler.createAsync(Looper.myLooper());
        }

        OnChildLocationsChangedListener
                getChildLocationsChangedListenerForTest() {
            return mNotificationLocationsChangedListener;
        }
    }
}
