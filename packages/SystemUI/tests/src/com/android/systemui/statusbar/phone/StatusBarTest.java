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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.support.test.metricshelper.MetricsAsserts;
import android.support.test.runner.AndroidJUnit4;
import android.util.DisplayMetrics;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class StatusBarTest extends SysuiTestCase {

    StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    UnlockMethodCache mUnlockMethodCache;
    KeyguardIndicationController mKeyguardIndicationController;
    NotificationStackScrollLayout mStackScroller;
    StatusBar mStatusBar;
    FakeMetricsLogger mMetricsLogger;
    HeadsUpManager mHeadsUpManager;
    NotificationData mNotificationData;
    PowerManager mPowerManager;
    SystemServicesProxy mSystemServicesProxy;

    private DisplayMetrics mDisplayMetrics = new DisplayMetrics();

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
        IPowerManager powerManagerService = mock(IPowerManager.class);
        HandlerThread handlerThread = new HandlerThread("TestThread");
        handlerThread.start();
        mPowerManager = new PowerManager(mContext, powerManagerService,
                new Handler(handlerThread.getLooper()));
        when(powerManagerService.isInteractive()).thenReturn(true);

        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mStatusBar = new TestableStatusBar(mStatusBarKeyguardViewManager, mUnlockMethodCache,
                mKeyguardIndicationController, mStackScroller, mHeadsUpManager,
                mNotificationData, mPowerManager, mSystemServicesProxy);

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

    static class TestableStatusBar extends StatusBar {
        public TestableStatusBar(StatusBarKeyguardViewManager man,
                UnlockMethodCache unlock, KeyguardIndicationController key,
                NotificationStackScrollLayout stack, HeadsUpManager hum, NotificationData nd,
                PowerManager pm, SystemServicesProxy ssp) {
            mStatusBarKeyguardViewManager = man;
            mUnlockMethodCache = unlock;
            mKeyguardIndicationController = key;
            mStackScroller = stack;
            mHeadsUpManager = hum;
            mNotificationData = nd;
            mUseHeadsUp = true;
            mPowerManager = pm;
            mSystemServicesProxy = ssp;
        }

        @Override
        protected H createHandler() {
            return null;
        }
    }
}