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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.StatusBarManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.metricshelper.MetricsAsserts;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.SparseArray;
import android.view.ViewGroup.LayoutParams;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.AppOpsListener;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.FooterView;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.NotificationEntryManager;
import com.android.systemui.statusbar.NotificationGutsManager;
import com.android.systemui.statusbar.NotificationListContainer;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLogger;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitorImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class StatusBarTest extends SysuiTestCase {
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private UnlockMethodCache mUnlockMethodCache;
    @Mock private KeyguardIndicationController mKeyguardIndicationController;
    @Mock private NotificationStackScrollLayout mStackScroller;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private SystemServicesProxy mSystemServicesProxy;
    @Mock private NotificationPanelView mNotificationPanelView;
    @Mock private IStatusBarService mBarService;
    @Mock private ScrimController mScrimController;
    @Mock private ArrayList<Entry> mNotificationList;
    @Mock private FingerprintUnlockController mFingerprintUnlockController;
    @Mock private NotificationData mNotificationData;

    // Mock dependencies:
    @Mock private NotificationViewHierarchyManager mViewHierarchyManager;
    @Mock private VisualStabilityManager mVisualStabilityManager;
    @Mock private NotificationListener mNotificationListener;
    @Mock private KeyguardViewMediator mKeyguardViewMediator;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private RemoteInputController mRemoteInputController;

    private TestableStatusBar mStatusBar;
    private FakeMetricsLogger mMetricsLogger;
    private PowerManager mPowerManager;
    private TestableNotificationEntryManager mEntryManager;
    private NotificationLogger mNotificationLogger;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(AssistManager.class);
        mDependency.injectMockDependency(DeviceProvisionedController.class);
        mDependency.injectMockDependency(NotificationGutsManager.class);
        mDependency.injectMockDependency(NotificationMediaManager.class);
        mDependency.injectMockDependency(ForegroundServiceController.class);
        mDependency.injectTestDependency(NotificationViewHierarchyManager.class,
                mViewHierarchyManager);
        mDependency.injectTestDependency(VisualStabilityManager.class, mVisualStabilityManager);
        mDependency.injectTestDependency(NotificationListener.class, mNotificationListener);
        mDependency.injectTestDependency(KeyguardMonitor.class, mock(KeyguardMonitorImpl.class));
        mDependency.injectTestDependency(AppOpsListener.class, mock(AppOpsListener.class));

        mContext.addMockSystemService(TrustManager.class, mock(TrustManager.class));
        mContext.addMockSystemService(FingerprintManager.class, mock(FingerprintManager.class));

        mMetricsLogger = new FakeMetricsLogger();
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mDependency.injectTestDependency(NotificationLogger.class, mNotificationLogger);
        mNotificationLogger = new NotificationLogger();

        IPowerManager powerManagerService = mock(IPowerManager.class);
        mPowerManager = new PowerManager(mContext, powerManagerService,
                Handler.createAsync(Looper.myLooper()));

        CommandQueue commandQueue = mock(CommandQueue.class);
        when(commandQueue.asBinder()).thenReturn(new Binder());
        mContext.putComponent(CommandQueue.class, commandQueue);

        mContext.setTheme(R.style.Theme_SystemUI_Light);

        when(mStackScroller.generateLayoutParams(any())).thenReturn(new LayoutParams(0, 0));
        when(mNotificationPanelView.getLayoutParams()).thenReturn(new LayoutParams(0, 0));
        when(powerManagerService.isInteractive()).thenReturn(true);
        when(mStackScroller.getActivatedChild()).thenReturn(null);

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

        mEntryManager = new TestableNotificationEntryManager(mSystemServicesProxy, mPowerManager,
                mContext);
        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);
        mStatusBar = new TestableStatusBar(mStatusBarKeyguardViewManager, mUnlockMethodCache,
                mKeyguardIndicationController, mStackScroller, mHeadsUpManager,
                mPowerManager, mNotificationPanelView, mBarService, mNotificationListener,
                mNotificationLogger, mVisualStabilityManager, mViewHierarchyManager,
                mEntryManager, mScrimController, mFingerprintUnlockController,
                mock(ActivityLaunchAnimator.class), mKeyguardViewMediator,
                mRemoteInputManager, mock(NotificationGroupManager.class),
                mock(FalsingManager.class), mock(StatusBarWindowManager.class),
                mock(NotificationIconAreaController.class), mock(DozeScrimController.class),
                mock(NotificationShelf.class), mLockscreenUserManager,
                mock(CommandQueue.class));
        mStatusBar.mContext = mContext;
        mStatusBar.mComponents = mContext.getComponents();
        mEntryManager.setUpForTest(mStatusBar, mStackScroller, mStatusBar, mHeadsUpManager,
                mNotificationData);
        mNotificationLogger.setUpWithEntryManager(mEntryManager, mStackScroller);

        TestableLooper.get(this).setMessageHandler(m -> {
            if (m.getCallback() == mStatusBar.mNotificationLogger.getVisibilityReporter()) {
                return false;
            }
            return true;
        });
    }

    @Test
    public void testSetBouncerShowing_noCrash() {
        mStatusBar.mCommandQueue = mock(CommandQueue.class);
        mStatusBar.setBouncerShowing(true);
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
        when(mNotificationData.shouldSuppressStatusBar(any())).thenReturn(false);
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

        assertTrue(mEntryManager.shouldPeek(entry, sbn));
    }

    @Test
    public void testShouldPeek_suppressedGroupSummary() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldSuppressStatusBar(any())).thenReturn(false);
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

        assertFalse(mEntryManager.shouldPeek(entry, sbn));
    }

    @Test
    public void testShouldPeek_suppressedPeek() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        when(mNotificationData.shouldSuppressPeek(any())).thenReturn(true);

        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);

        assertFalse(mEntryManager.shouldPeek(entry, sbn));
    }

    @Test
    public void testShouldPeek_noSuppressedPeek() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        when(mNotificationData.shouldSuppressPeek(any())).thenReturn(false);

        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);

        assertTrue(mEntryManager.shouldPeek(entry, sbn));
    }

    @Test
    public void testPeek_disabledStatusBar() {
        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        mStatusBar.disable(StatusBarManager.DISABLE_EXPAND, 0, false /* animate */);

        assertFalse("The panel shouldn't allow peek while disabled",
                mStatusBar.shouldPeek(entry, sbn));
    }

    @Test
    public void testPeek_disabledNotificationShade() {
        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        mStatusBar.disable(0, StatusBarManager.DISABLE2_NOTIFICATION_SHADE, false /* animate */);

        assertFalse("The panel shouldn't allow peek while notitifcation shade disabled",
                mStatusBar.shouldPeek(entry, sbn));
    }

    @Test
    public void testLogHidden() {
        try {
            mStatusBar.handleVisibleToUserChanged(false);
            waitForUiOffloadThread();
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
            waitForUiOffloadThread();
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
            waitForUiOffloadThread();
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
            waitForUiOffloadThread();
            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(false, 5);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testDisableExpandStatusBar() {
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);
        mStatusBar.setUserSetupForTest(true);
        when(mStatusBar.isDeviceProvisioned()).thenReturn(true);

        mStatusBar.disable(StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NOTIFICATION_SHADE, false);
        verify(mNotificationPanelView).setQsExpansionEnabled(false);
        mStatusBar.animateExpandNotificationsPanel();
        verify(mNotificationPanelView, never()).expand(anyBoolean());
        mStatusBar.animateExpandSettingsPanel(null);
        verify(mNotificationPanelView, never()).expand(anyBoolean());

        mStatusBar.disable(StatusBarManager.DISABLE_NONE, StatusBarManager.DISABLE2_NONE, false);
        verify(mNotificationPanelView).setQsExpansionEnabled(true);
        mStatusBar.animateExpandNotificationsPanel();
        verify(mNotificationPanelView).expandWithoutQs();
        mStatusBar.animateExpandSettingsPanel(null);
        verify(mNotificationPanelView).expandWithQs();
    }

    @Test
    public void testDump_DoesNotCrash() {
        mStatusBar.dump(null, new PrintWriter(new ByteArrayOutputStream()), null);
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    public void testUpdateKeyguardState_DoesNotCrash() {
        mStatusBar.mState = StatusBarState.KEYGUARD;
        when(mStatusBar.mLockscreenUserManager.getCurrentProfiles()).thenReturn(
                new SparseArray<>());
        mStatusBar.updateKeyguardState(false, false);
    }

    @Test
    public void testFingerprintNotification_UpdatesScrims() {
        mStatusBar.notifyFpAuthModeChanged();
        verify(mScrimController).transitionTo(any(), any());
    }

    @Test
    public void testFingerprintUnlock_UpdatesScrims() {
        // Simulate unlocking from AoD with fingerprint.
        when(mFingerprintUnlockController.getMode())
                .thenReturn(FingerprintUnlockController.MODE_WAKE_AND_UNLOCK);
        mStatusBar.updateScrimController();
        verify(mScrimController).transitionTo(eq(ScrimState.UNLOCKED), any());
    }

    @Test
    public void testSetOccluded_propagatesToScrimController() {
        mStatusBar.setOccluded(true);
        verify(mScrimController).setKeyguardOccluded(eq(true));

        reset(mScrimController);
        mStatusBar.setOccluded(false);
        verify(mScrimController).setKeyguardOccluded(eq(false));
    }

    @Test
    public void testInflateFooterView() {
        mStatusBar.reevaluateStyles();
        ArgumentCaptor<FooterView> captor = ArgumentCaptor.forClass(FooterView.class);
        verify(mStackScroller).setFooterView(captor.capture());

        assertNotNull(captor.getValue().findViewById(R.id.manage_text).hasOnClickListeners());
        assertNotNull(captor.getValue().findViewById(R.id.dismiss_text).hasOnClickListeners());
    }

    @Test
    public void testUpdateFooter_noNotifications() {
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);
        assertEquals(0, mEntryManager.getNotificationData().getActiveNotifications().size());

        mStatusBar.updateFooter();
        verify(mStackScroller).updateFooterView(false, false);
    }

    @Test
    public void testUpdateFooter_remoteInput() {
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(mock(Entry.class));
        when(mNotificationData.getActiveNotifications()).thenReturn(entries);

        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        when(row.canViewBeDismissed()).thenReturn(true);
        when(mStackScroller.getChildCount()).thenReturn(1);
        when(mStackScroller.getChildAt(anyInt())).thenReturn(row);
        when(mRemoteInputController.isRemoteInputActive()).thenReturn(true);

        mStatusBar.updateFooter();
        verify(mStackScroller).updateFooterView(false, true);
    }

    @Test
    public void testUpdateFooter_oneClearableNotification() {
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(mock(Entry.class));
        when(mNotificationData.getActiveNotifications()).thenReturn(entries);

        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        when(row.canViewBeDismissed()).thenReturn(true);
        when(mStackScroller.getChildCount()).thenReturn(1);
        when(mStackScroller.getChildAt(anyInt())).thenReturn(row);

        mStatusBar.updateFooter();
        verify(mStackScroller).updateFooterView(true, true);
    }

    @Test
    public void testUpdateFooter_oneNonClearableNotification() {
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(mock(Entry.class));
        when(mNotificationData.getActiveNotifications()).thenReturn(entries);

        mStatusBar.updateFooter();
        verify(mStackScroller).updateFooterView(true, false);
    }

    @Test
    public void testUpdateFooter_atEnd() {
        // add footer
        mStatusBar.reevaluateStyles();

        // add notification
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        when(row.isClearable()).thenReturn(true);
        mStackScroller.addContainerView(row);

        mStatusBar.onUpdateRowStates();

        // move footer to end
        verify(mStackScroller).changeViewPosition(any(FooterView.class), eq(-1 /* end */));
    }

    @Test
    public void testSetState_changesIsFullScreenUserSwitcherState() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        assertFalse(mStatusBar.isFullScreenUserSwitcherState());

        mStatusBar.setBarStateForTest(StatusBarState.FULLSCREEN_USER_SWITCHER);
        assertTrue(mStatusBar.isFullScreenUserSwitcherState());
    }

    @Test
    public void testShowKeyguardImplementation_setsState() {
        when(mLockscreenUserManager.getCurrentProfiles()).thenReturn(new SparseArray<>());

        mStatusBar.setBarStateForTest(StatusBarState.SHADE);

        // By default, showKeyguardImpl sets state to KEYGUARD.
        mStatusBar.showKeyguardImpl();
        assertTrue(mStatusBar.mState == StatusBarState.KEYGUARD);

        // If useFullscreenUserSwitcher is true, state is set to FULLSCREEN_USER_SWITCHER.
        mStatusBar.mUserSwitcherController = mock(UserSwitcherController.class);
        when(mStatusBar.mUserSwitcherController.useFullscreenUserSwitcher()).thenReturn(true);
        mStatusBar.showKeyguardImpl();
        assertTrue(mStatusBar.mState == StatusBarState.FULLSCREEN_USER_SWITCHER);
    }

    static class TestableStatusBar extends StatusBar {
        public TestableStatusBar(StatusBarKeyguardViewManager man,
                UnlockMethodCache unlock, KeyguardIndicationController key,
                NotificationStackScrollLayout stack, HeadsUpManagerPhone hum,
                PowerManager pm, NotificationPanelView panelView,
                IStatusBarService barService, NotificationListener notificationListener,
                NotificationLogger notificationLogger,
                VisualStabilityManager visualStabilityManager,
                NotificationViewHierarchyManager viewHierarchyManager,
                TestableNotificationEntryManager entryManager, ScrimController scrimController,
                FingerprintUnlockController fingerprintUnlockController,
                ActivityLaunchAnimator launchAnimator, KeyguardViewMediator keyguardViewMediator,
                NotificationRemoteInputManager notificationRemoteInputManager,
                NotificationGroupManager notificationGroupManager,
                FalsingManager falsingManager,
                StatusBarWindowManager statusBarWindowManager,
                NotificationIconAreaController notificationIconAreaController,
                DozeScrimController dozeScrimController,
                NotificationShelf notificationShelf,
                NotificationLockscreenUserManager notificationLockscreenUserManager,
                CommandQueue commandQueue) {
            mStatusBarKeyguardViewManager = man;
            mUnlockMethodCache = unlock;
            mKeyguardIndicationController = key;
            mStackScroller = stack;
            mHeadsUpManager = hum;
            mPowerManager = pm;
            mNotificationPanel = panelView;
            mBarService = barService;
            mNotificationListener = notificationListener;
            mNotificationLogger = notificationLogger;
            mWakefulnessLifecycle = createAwakeWakefulnessLifecycle();
            mVisualStabilityManager = visualStabilityManager;
            mViewHierarchyManager = viewHierarchyManager;
            mEntryManager = entryManager;
            mScrimController = scrimController;
            mFingerprintUnlockController = fingerprintUnlockController;
            mActivityLaunchAnimator = launchAnimator;
            mKeyguardViewMediator = keyguardViewMediator;
            mClearAllEnabled = true;
            mRemoteInputManager = notificationRemoteInputManager;
            mGroupManager = notificationGroupManager;
            mFalsingManager = falsingManager;
            mStatusBarWindowManager = statusBarWindowManager;
            mNotificationIconAreaController = notificationIconAreaController;
            mDozeScrimController = dozeScrimController;
            mNotificationShelf = notificationShelf;
            mLockscreenUserManager = notificationLockscreenUserManager;
            mCommandQueue = commandQueue;
        }

        private WakefulnessLifecycle createAwakeWakefulnessLifecycle() {
            WakefulnessLifecycle wakefulnessLifecycle = new WakefulnessLifecycle();
            wakefulnessLifecycle.dispatchStartedWakingUp();
            wakefulnessLifecycle.dispatchFinishedWakingUp();
            return wakefulnessLifecycle;
        }

        @Override
        protected void updateTheme() {
            // Do nothing for now, until we have more mocking and StatusBar is smaller.
        }

        public void setBarStateForTest(int state) {
            mState = state;
        }

        public void setUserSetupForTest(boolean userSetup) {
            mUserSetup = userSetup;
        }

    }

    private class TestableNotificationEntryManager extends NotificationEntryManager {

        public TestableNotificationEntryManager(SystemServicesProxy systemServicesProxy,
                PowerManager powerManager, Context context) {
            super(context);
            mSystemServicesProxy = systemServicesProxy;
            mPowerManager = powerManager;
        }

        public void setUpForTest(NotificationPresenter presenter,
                NotificationListContainer listContainer,
                Callback callback,
                HeadsUpManagerPhone headsUpManager,
                NotificationData notificationData) {
            super.setUpWithPresenter(presenter, listContainer, callback, headsUpManager);
            mNotificationData = notificationData;
            mUseHeadsUp = true;
        }
    }
}
