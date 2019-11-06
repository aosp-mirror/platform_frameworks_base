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

package com.android.systemui.statusbar.phone;

import static android.app.NotificationManager.IMPORTANCE_HIGH;

import static com.android.systemui.statusbar.ForegroundServiceLifetimeExtender.MIN_FGS_TIME_MS;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.Notification;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IPowerManager;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.service.notification.NotificationListenerService.RankingMap;
import android.support.test.filters.SmallTest;
import android.support.test.metricshelper.MetricsAsserts;
import android.support.test.runner.AndroidJUnit4;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.MessageHandler;
import android.testing.TestableLooper.RunWithLooper;
import android.util.DisplayMetrics;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.R;
import com.android.systemui.statusbar.ForegroundServiceLifetimeExtender;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import junit.framework.Assert;

import java.util.ArrayList;
import java.util.Map;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class StatusBarTest extends SysuiTestCase {

    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 123;

    StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    UnlockMethodCache mUnlockMethodCache;
    KeyguardIndicationController mKeyguardIndicationController;
    NotificationStackScrollLayout mStackScroller;
    TestableStatusBar mStatusBar;
    FakeMetricsLogger mMetricsLogger;
    HeadsUpManager mHeadsUpManager;
    NotificationData mNotificationData;
    PowerManager mPowerManager;
    SystemServicesProxy mSystemServicesProxy;
    NotificationPanelView mNotificationPanelView;
    IStatusBarService mBarService;
    RemoteInputController mRemoteInputController;
    ForegroundServiceController mForegroundServiceController;
    ArrayList<Entry> mNotificationList;
    private DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private ForegroundServiceLifetimeExtender mFGSExtender =
        new ForegroundServiceLifetimeExtender();

    @Before
    public void setup() throws Exception {
        mStatusBarKeyguardViewManager = mock(StatusBarKeyguardViewManager.class);
        mUnlockMethodCache = mock(UnlockMethodCache.class);
        mKeyguardIndicationController = mock(KeyguardIndicationController.class);
        mStackScroller = mock(NotificationStackScrollLayout.class);
        mMetricsLogger = new FakeMetricsLogger();
        mHeadsUpManager = mock(HeadsUpManager.class);
        mNotificationData = mock(NotificationData.class);
        mSystemServicesProxy = mock(SystemServicesProxy.class);
        mNotificationPanelView = mock(NotificationPanelView.class);
        mNotificationList = mock(ArrayList.class);
        IPowerManager powerManagerService = mock(IPowerManager.class);
        mRemoteInputController = mock(RemoteInputController.class);
        mForegroundServiceController = mock(ForegroundServiceController.class);
        HandlerThread handlerThread = new HandlerThread("TestThread");
        handlerThread.start();
        mPowerManager = new PowerManager(mContext, powerManagerService,
                new Handler(handlerThread.getLooper()));
        when(powerManagerService.isInteractive()).thenReturn(true);
        mBarService = mock(IStatusBarService.class);

        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mFGSExtender.setCallback(key -> mStatusBar.removeNotification(key, mock(RankingMap.class)));
        mStatusBar = new TestableStatusBar(mStatusBarKeyguardViewManager, mUnlockMethodCache,
                mKeyguardIndicationController, mStackScroller, mHeadsUpManager,
                mNotificationData, mPowerManager, mSystemServicesProxy, mNotificationPanelView,
                mBarService, mFGSExtender, mRemoteInputController, mForegroundServiceController);
        doAnswer(invocation -> {
            OnDismissAction onDismissAction = (OnDismissAction) invocation.getArguments()[0];
            onDismissAction.onDismiss();
            return null;
        }).when(mStatusBarKeyguardViewManager).dismissWithAction(any(), any(), anyBoolean());

        doAnswer(invocation -> {
            Runnable runnable = (Runnable) invocation.getArguments()[0];
            runnable.run();
            return null;
        }).when(mStatusBarKeyguardViewManager).addAfterKeyguardGoneRunnable(any());

        when(mStackScroller.getActivatedChild()).thenReturn(null);
        TestableLooper.get(this).setMessageHandler(new MessageHandler() {
            @Override
            public boolean onMessageHandled(Message m) {
                if (m.getCallback() == mStatusBar.mVisibilityReporter) {
                    return false;
                }
                return true;
            }
        });
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_showingAndOccluded() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(true);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_showing() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_notShowing() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void lockscreenStateMetrics_notShowing() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(false);
        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing hidden insecure lockscreen log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .setSubtype(0));
    }

    @Test
    public void lockscreenStateMetrics_notShowing_secure() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(true);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing hidden secure lockscreen log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .setSubtype(1));
    }

    @Test
    public void lockscreenStateMetrics_isShowing() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(false);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing insecure lockscreen showing",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(0));
    }

    @Test
    public void lockscreenStateMetrics_isShowing_secure() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(true);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing secure lockscreen showing log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(1));
    }

    @Test
    public void lockscreenStateMetrics_isShowingBouncer() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(true);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing bouncer log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.BOUNCER)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(1));
    }

    @Test
    public void onActivatedMetrics() {
        ActivatableNotificationView view =  mock(ActivatableNotificationView.class);
        mStatusBar.onActivated(view);

        MetricsAsserts.assertHasLog("missing lockscreen note tap log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.ACTION_LS_NOTE)
                        .setType(MetricsEvent.TYPE_ACTION));
    }

    @Test
    public void testShouldPeek_nonSuppressedGroupSummary() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldSuppressScreenOn(any())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(getContext(), "a")
                .setGroup("a")
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);

        assertTrue(mStatusBar.shouldPeek(entry, sbn));
    }

    @Test
    public void testShouldPeek_suppressedGroupSummary() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldSuppressScreenOn(any())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(getContext(), "a")
                .setGroup("a")
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);

        assertFalse(mStatusBar.shouldPeek(entry, sbn));
    }

    @Test
    public void testForegroundServiceNotificationKeptForFiveSeconds() throws Exception {
        RankingMap rm = mock(RankingMap.class);

        // sbn posted "just now"
        Notification n = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .build();
        n.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        StatusBarNotification sbn =
                new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID,
                0, n, new UserHandle(ActivityManager.getCurrentUser()), null,
                System.currentTimeMillis());
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        when(mNotificationData.get(any())).thenReturn(entry);
        mStatusBar.removeNotification(sbn.getKey(), rm);
        Map<NotificationData.Entry, NotificationLifetimeExtender> map =
                mStatusBar.getRetainedNotificationMap();
        Assert.assertTrue(map.containsKey(entry));
    }
    @Test
    public void testForegroundServiceNotification_notRetainedIfShownForFiveSeconds() 
        throws Exception {

        RankingMap rm = mock(RankingMap.class);

        // sbn posted "more than 5 seconds ago"
        Notification n = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .build();
        n.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        StatusBarNotification sbn =
            new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID,
                0, n, new UserHandle(ActivityManager.getCurrentUser()), null,
                System.currentTimeMillis() - MIN_FGS_TIME_MS - 1);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        when(mNotificationData.get(any())).thenReturn(entry);
        mStatusBar.removeNotification(sbn.getKey(), rm);
        Map<NotificationData.Entry, NotificationLifetimeExtender> map =
                mStatusBar.getRetainedNotificationMap();
        Assert.assertFalse(map.containsKey(entry));
    }

    @Test
    public void testLogHidden() {
        try {
            mStatusBar.handleVisibleToUserChanged(false);
            verify(mBarService, times(1)).onPanelHidden();
            verify(mBarService, never()).onPanelRevealed(anyBoolean(), anyInt());
        } catch (RemoteException e) {
            fail();
        }
    }

    @Test
    public void testPanelOpenForPeek() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        when(mNotificationData.getActiveNotifications()).thenReturn(mNotificationList);
        when(mNotificationList.size()).thenReturn(5);
        when(mNotificationPanelView.isFullyCollapsed()).thenReturn(true);
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);

        try {
            mStatusBar.handleVisibleToUserChanged(true);

            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(false, 1);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testPanelOpenAndClear() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        when(mNotificationData.getActiveNotifications()).thenReturn(mNotificationList);
        when(mNotificationList.size()).thenReturn(5);
        when(mNotificationPanelView.isFullyCollapsed()).thenReturn(false);
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);

        try {
            mStatusBar.handleVisibleToUserChanged(true);

            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(true, 5);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testPanelOpenAndNoClear() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        when(mNotificationData.getActiveNotifications()).thenReturn(mNotificationList);
        when(mNotificationList.size()).thenReturn(5);
        when(mNotificationPanelView.isFullyCollapsed()).thenReturn(false);
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);

        try {
            mStatusBar.handleVisibleToUserChanged(true);

            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(false, 5);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    static class TestableStatusBar extends StatusBar {
        public TestableStatusBar(StatusBarKeyguardViewManager man,
                UnlockMethodCache unlock, KeyguardIndicationController key,
                NotificationStackScrollLayout stack, HeadsUpManager hum, NotificationData nd,
                PowerManager pm, SystemServicesProxy ssp, NotificationPanelView panelView,
                IStatusBarService barService, ForegroundServiceLifetimeExtender fgsExtender,
                RemoteInputController ric, ForegroundServiceController fsc) {
            mStatusBarKeyguardViewManager = man;
            mUnlockMethodCache = unlock;
            mKeyguardIndicationController = key;
            mStackScroller = stack;
            mHeadsUpManager = hum;
            mNotificationData = nd;
            mUseHeadsUp = true;
            mPowerManager = pm;
            mSystemServicesProxy = ssp;
            mNotificationPanel = panelView;
            mBarService = barService;
            mFGSExtender = fgsExtender;
            mRemoteInputController = ric;
            mForegroundServiceController = fsc;
        }

        public void setBarStateForTest(int state) {
            mState = state;
        }
    }
}
