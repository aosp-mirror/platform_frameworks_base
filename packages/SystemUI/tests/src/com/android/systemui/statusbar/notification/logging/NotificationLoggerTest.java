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

import static kotlinx.coroutines.test.TestCoroutineDispatchersKt.StandardTestDispatcher;

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
import android.platform.test.annotations.DisableFlags;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.InstanceId;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.power.domain.interactor.PowerInteractorFactory;
import com.android.systemui.scene.data.repository.WindowRootViewVisibilityRepository;
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.notification.collection.NotifLiveData;
import com.android.systemui.statusbar.notification.collection.NotifLiveDataStore;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository;
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor;
import com.android.systemui.statusbar.notification.logging.nano.Notifications;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.android.collect.Lists;

import kotlinx.coroutines.test.TestScope;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
@DisableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
public class NotificationLoggerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    @Mock private NotificationListContainer mListContainer;
    @Mock private IStatusBarService mBarService;
    @Mock private ExpandableNotificationRow mRow;
    @Mock private NotificationLogger.ExpansionStateLogger mExpansionStateLogger;

    // Dependency mocks:
    @Mock private NotifLiveDataStore mNotifLiveDataStore;
    @Mock private NotifLiveData<List<NotificationEntry>> mActiveNotifEntries;
    @Mock private NotificationVisibilityProvider mVisibilityProvider;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private NotificationListener mListener;
    @Mock private HeadsUpManager mHeadsUpManager;

    private NotificationEntry mEntry;
    private TestableNotificationLogger mLogger;
    private ConcurrentLinkedQueue<AssertionError> mErrorQueue = new ConcurrentLinkedQueue<>();
    private FakeExecutor mUiBgExecutor = new FakeExecutor(new FakeSystemClock());
    private NotificationPanelLoggerFake mNotificationPanelLoggerFake =
            new NotificationPanelLoggerFake();
    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);
    private final TestScope mTestScope = mKosmos.getTestScope();
    private final FakeKeyguardRepository mKeyguardRepository = new FakeKeyguardRepository();
    private final PowerInteractor mPowerInteractor =
            PowerInteractorFactory.create().getPowerInteractor();
    private final ActiveNotificationListRepository mActiveNotificationListRepository =
            new ActiveNotificationListRepository();
    private final ActiveNotificationsInteractor mActiveNotificationsInteractor =
            new ActiveNotificationsInteractor(mActiveNotificationListRepository,
                    StandardTestDispatcher(null, null));
    private WindowRootViewVisibilityInteractor mWindowRootViewVisibilityInteractor;
    private final JavaAdapter mJavaAdapter = new JavaAdapter(mTestScope.getBackgroundScope());

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mNotifLiveDataStore.getActiveNotifList()).thenReturn(mActiveNotifEntries);

        mWindowRootViewVisibilityInteractor = new WindowRootViewVisibilityInteractor(
                mTestScope.getBackgroundScope(),
                new WindowRootViewVisibilityRepository(mBarService, mUiBgExecutor),
                mKeyguardRepository,
                mHeadsUpManager,
                mPowerInteractor,
                mActiveNotificationsInteractor,
                () -> mKosmos.getSceneInteractor());
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true);

        mEntry = new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setUid(TEST_UID)
                .setNotification(new Notification())
                .setUser(UserHandle.CURRENT)
                .setInstanceId(InstanceId.fakeInstanceId(1))
                .build();
        mEntry.setRow(mRow);

        mLogger = new TestableNotificationLogger(
                mListener,
                mUiBgExecutor,
                mNotifLiveDataStore,
                mVisibilityProvider,
                mNotifPipeline,
                mock(StatusBarStateControllerImpl.class),
                mWindowRootViewVisibilityInteractor,
                mJavaAdapter,
                mBarService,
                mExpansionStateLogger
        );
        mLogger.start();
        mLogger.setUpWithContainer(mListContainer);
        verify(mNotifPipeline).addCollectionListener(any());
    }

    @After
    public void tearDown() {
        mLogger.mHandler.removeCallbacksAndMessages(null);
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
        when(mActiveNotifEntries.getValue()).thenReturn(Lists.newArrayList(mEntry));
        mLogger.onChildLocationsChanged();
        TestableLooper.get(this).processAllMessages();
        mUiBgExecutor.runAllReady();

        if (!mErrorQueue.isEmpty()) {
            throw mErrorQueue.poll();
        }

        // |mEntry| won't change visibility, so it shouldn't be reported again:
        Mockito.reset(mBarService);
        mLogger.onChildLocationsChanged();
        TestableLooper.get(this).processAllMessages();
        mUiBgExecutor.runAllReady();

        verify(mBarService, never()).onNotificationVisibilityChanged(any(), any());
    }

    @Test
    public void testStoppingNotificationLoggingReportsCurrentNotifications()
            throws Exception {
        when(mListContainer.isInVisibleLocation(any())).thenReturn(true);
        when(mActiveNotifEntries.getValue()).thenReturn(Lists.newArrayList(mEntry));
        mLogger.onChildLocationsChanged();
        TestableLooper.get(this).processAllMessages();
        mUiBgExecutor.runAllReady();
        Mockito.reset(mBarService);

        setStateAsleep();

        setStateAwake();  // Wake to lockscreen

        setStateAsleep();  // And go back to sleep, turning off logging
        mUiBgExecutor.runAllReady();

        // The visibility objects are recycled by NotificationLogger, so we can't use specific
        // matchers here.
        verify(mBarService, times(1)).onNotificationVisibilityChanged(any(), any());
    }

    @Test
    public void testLogPanelShownOnWakeToLockscreen() {
        when(mActiveNotifEntries.getValue()).thenReturn(Lists.newArrayList(mEntry));
        setStateAsleep();

        // Wake to lockscreen
        mLogger.onStateChanged(StatusBarState.KEYGUARD);
        setStateAwake();

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
        when(mActiveNotifEntries.getValue()).thenReturn(Lists.newArrayList(mEntry));
        // Start as awake, but with the panel not visible
        setStateAwake();
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(false);

        // Now expand panel
        mLogger.onStateChanged(StatusBarState.SHADE);
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true);

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

        when(mActiveNotifEntries.getValue()).thenReturn(Lists.newArrayList(entry));
        setStateAsleep();

        // Wake to lockscreen
        setStateAwake();

        assertEquals(1, mNotificationPanelLoggerFake.getCalls().size());
        assertEquals(1, mNotificationPanelLoggerFake.get(0).list.notifications.length);
        Notifications.Notification n = mNotificationPanelLoggerFake.get(0).list.notifications[0];
        assertEquals(0, n.instanceId);
    }

    private void setStateAsleep() {
        PowerInteractor.Companion.setAsleepForTest(mPowerInteractor);
        mTestScope.getTestScheduler().runCurrent();
    }

    private void setStateAwake() {
        PowerInteractor.Companion.setAwakeForTest(mPowerInteractor);
        mTestScope.getTestScheduler().runCurrent();
    }

    private class TestableNotificationLogger extends NotificationLogger {

        TestableNotificationLogger(NotificationListener notificationListener,
                Executor uiBgExecutor,
                NotifLiveDataStore notifLiveDataStore,
                NotificationVisibilityProvider visibilityProvider,
                NotifPipeline notifPipeline,
                StatusBarStateControllerImpl statusBarStateController,
                WindowRootViewVisibilityInteractor windowRootViewVisibilityInteractor,
                JavaAdapter javaAdapter,
                IStatusBarService barService,
                ExpansionStateLogger expansionStateLogger) {
            super(
                    notificationListener,
                    uiBgExecutor,
                    notifLiveDataStore,
                    visibilityProvider,
                    notifPipeline,
                    statusBarStateController,
                    windowRootViewVisibilityInteractor,
                    javaAdapter,
                    expansionStateLogger,
                    mNotificationPanelLoggerFake
            );
            mBarService = barService;
            mHandler.removeCallbacksAndMessages(null);
            // Make this on the current thread so we can wait for it during tests.
            mHandler = Handler.createAsync(Looper.myLooper());
        }
    }
}
