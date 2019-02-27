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
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.view.Display.DEFAULT_DISPLAY;

import static junit.framework.Assert.assertFalse;
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
import android.service.dreams.IDreamManager;
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
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.InitController;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.appops.AppOpsControllerImpl;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.notification.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitorImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class StatusBarTest extends SysuiTestCase {
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private UnlockMethodCache mUnlockMethodCache;
    @Mock private KeyguardIndicationController mKeyguardIndicationController;
    @Mock private NotificationStackScrollLayout mStackScroller;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private NotificationPanelView mNotificationPanelView;
    @Mock private IStatusBarService mBarService;
    @Mock private IDreamManager mDreamManager;
    @Mock private ScrimController mScrimController;
    @Mock private DozeScrimController mDozeScrimController;
    @Mock private ArrayList<NotificationEntry> mNotificationList;
    @Mock private BiometricUnlockController mBiometricUnlockController;
    @Mock private NotificationData mNotificationData;
    @Mock
    private NotificationInterruptionStateProvider.HeadsUpSuppressor mHeadsUpSuppressor;

    // Mock dependencies:
    @Mock private NotificationViewHierarchyManager mViewHierarchyManager;
    @Mock private VisualStabilityManager mVisualStabilityManager;
    @Mock private NotificationListener mNotificationListener;
    @Mock private KeyguardViewMediator mKeyguardViewMediator;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private RemoteInputController mRemoteInputController;
    @Mock private StatusBarStateControllerImpl mStatusBarStateController;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private NotificationPresenter mNotificationPresenter;
    @Mock
    private NotificationEntryListener mEntryListener;
    @Mock private BubbleController mBubbleController;
    @Mock
    private NotificationFilter mNotificationFilter;
    @Mock
    private NotificationAlertingManager mNotificationAlertingManager;
    @Mock
    private NotificationLogger.ExpansionStateLogger mExpansionStateLogger;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private TestableStatusBar mStatusBar;
    private FakeMetricsLogger mMetricsLogger;
    private PowerManager mPowerManager;
    private TestableNotificationEntryManager mEntryManager;
    private TestableNotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
    private NotificationLogger mNotificationLogger;
    private CommandQueue mCommandQueue;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(AssistManager.class);
        mDependency.injectMockDependency(NotificationGutsManager.class);
        mDependency.injectMockDependency(NotificationMediaManager.class);
        mDependency.injectMockDependency(ForegroundServiceController.class);
        mDependency.injectTestDependency(NotificationViewHierarchyManager.class,
                mViewHierarchyManager);
        mDependency.injectTestDependency(VisualStabilityManager.class, mVisualStabilityManager);
        mDependency.injectTestDependency(NotificationListener.class, mNotificationListener);
        mDependency.injectTestDependency(KeyguardMonitor.class, mock(KeyguardMonitorImpl.class));
        mDependency.injectTestDependency(AppOpsController.class, mock(AppOpsControllerImpl.class));
        mDependency.injectTestDependency(StatusBarStateController.class, mStatusBarStateController);
        mDependency.injectTestDependency(DeviceProvisionedController.class,
                mDeviceProvisionedController);
        mDependency.injectMockDependency(BubbleController.class);
        mDependency.injectTestDependency(NotificationFilter.class, mNotificationFilter);
        mDependency.injectTestDependency(NotificationAlertingManager.class,
                mNotificationAlertingManager);

        IPowerManager powerManagerService = mock(IPowerManager.class);
        mPowerManager = new PowerManager(mContext, powerManagerService,
                Handler.createAsync(Looper.myLooper()));

        mNotificationInterruptionStateProvider =
                new TestableNotificationInterruptionStateProvider(mContext, mPowerManager,
                        mDreamManager);
        mDependency.injectTestDependency(NotificationInterruptionStateProvider.class,
                mNotificationInterruptionStateProvider);
        mDependency.injectMockDependency(NavigationBarController.class);

        mContext.addMockSystemService(TrustManager.class, mock(TrustManager.class));
        mContext.addMockSystemService(FingerprintManager.class, mock(FingerprintManager.class));

        mMetricsLogger = new FakeMetricsLogger();
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mEntryManager = new TestableNotificationEntryManager(mContext);
        mNotificationLogger = new NotificationLogger(mNotificationListener,
                Dependency.get(UiOffloadThread.class), mEntryManager, mStatusBarStateController,
                mExpansionStateLogger);
        mDependency.injectTestDependency(NotificationLogger.class, mNotificationLogger);
        DozeLog.traceDozing(mContext, false /* dozing */);

        mCommandQueue = mock(CommandQueue.class);
        when(mCommandQueue.asBinder()).thenReturn(new Binder());
        mContext.putComponent(CommandQueue.class, mCommandQueue);

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

        mNotificationInterruptionStateProvider.setUpWithPresenter(mNotificationPresenter,
                mHeadsUpManager, mHeadsUpSuppressor);

        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);
        mStatusBar = new TestableStatusBar(mStatusBarKeyguardViewManager, mUnlockMethodCache,
                mKeyguardIndicationController, mStackScroller, mHeadsUpManager,
                mPowerManager, mNotificationPanelView, mBarService, mNotificationListener,
                mNotificationLogger, mVisualStabilityManager, mViewHierarchyManager,
                mEntryManager, mScrimController, mBiometricUnlockController,
                mKeyguardViewMediator, mRemoteInputManager, mock(NotificationGroupManager.class),
                mock(NotificationGroupAlertTransferHelper.class), mock(FalsingManager.class),
                mock(StatusBarWindowController.class), mock(NotificationIconAreaController.class),
                mDozeScrimController, mock(NotificationShelf.class),
                mLockscreenUserManager, mCommandQueue, mNotificationPresenter,
                mock(BubbleController.class), mock(NavigationBarController.class),
                mock(AutoHideController.class), mKeyguardUpdateMonitor);
        mStatusBar.mContext = mContext;
        mStatusBar.mComponents = mContext.getComponents();
        SystemUIFactory.getInstance().getRootComponent()
                .getStatusBarInjector()
                .createStatusBar(mStatusBar);
        mStatusBar.putComponent(StatusBar.class, mStatusBar);
        Dependency.get(InitController.class).executePostInitTasks();
        mEntryManager.setUpForTest(mock(NotificationPresenter.class), mStackScroller,
                mHeadsUpManager, mNotificationData);
        mEntryManager.addNotificationEntryListener(mEntryListener);
        mNotificationLogger.setUpWithContainer(mStackScroller);

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
    public void testShouldHeadsUp_nonSuppressedGroupSummary() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(true);

        Notification n = new Notification.Builder(getContext(), "a")
                .setGroup("a")
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationEntry entry = new NotificationEntry(sbn);
        entry.importance = IMPORTANCE_HIGH;

        assertTrue(mNotificationInterruptionStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testShouldHeadsUp_suppressedGroupSummary() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(true);

        Notification n = new Notification.Builder(getContext(), "a")
                .setGroup("a")
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationEntry entry = new NotificationEntry(sbn);
        entry.importance = IMPORTANCE_HIGH;

        assertFalse(mNotificationInterruptionStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testShouldHeadsUp_suppressedHeadsUp() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(true);

        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationEntry entry = new NotificationEntry(sbn);
        entry.suppressedVisualEffects = SUPPRESSED_EFFECT_PEEK;
        entry.importance = IMPORTANCE_HIGH;

        assertFalse(mNotificationInterruptionStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testShouldHeadsUp_noSuppressedHeadsUp() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(true);

        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationEntry entry = new NotificationEntry(sbn);
        entry.importance = IMPORTANCE_HIGH;

        assertTrue(mNotificationInterruptionStateProvider.shouldHeadsUp(entry));
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
    public void testPanelOpenForHeadsUp() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        when(mNotificationData.getActiveNotifications()).thenReturn(mNotificationList);
        when(mNotificationList.size()).thenReturn(5);
        when(mNotificationPresenter.isPresenterFullyCollapsed()).thenReturn(true);
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
        when(mNotificationPresenter.isPresenterFullyCollapsed()).thenReturn(false);
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
        when(mNotificationPresenter.isPresenterFullyCollapsed()).thenReturn(false);
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
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);

        when(mCommandQueue.panelsEnabled()).thenReturn(false);
        mStatusBar.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NOTIFICATION_SHADE, false);
        verify(mNotificationPanelView).setQsExpansionEnabled(false);
        mStatusBar.animateExpandNotificationsPanel();
        verify(mNotificationPanelView, never()).expand(anyBoolean());
        mStatusBar.animateExpandSettingsPanel(null);
        verify(mNotificationPanelView, never()).expand(anyBoolean());

        when(mCommandQueue.panelsEnabled()).thenReturn(true);
        mStatusBar.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NONE, false);
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
        mStatusBar.onStateChanged(StatusBarState.SHADE);
    }

    @Test
    public void testFingerprintNotification_UpdatesScrims() {
        mStatusBar.notifyBiometricAuthModeChanged();
        verify(mScrimController).transitionTo(any(), any());
    }

    @Test
    public void testFingerprintUnlock_UpdatesScrims() {
        // Simulate unlocking from AoD with fingerprint.
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_WAKE_AND_UNLOCK);
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
    public void testPulseWhileDozing_updatesScrimController() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        mStatusBar.showKeyguardImpl();

        // Keep track of callback to be able to stop the pulse
        DozeHost.PulseCallback[] pulseCallback = new DozeHost.PulseCallback[1];
        doAnswer(invocation -> {
            pulseCallback[0] = invocation.getArgument(0);
            return null;
        }).when(mDozeScrimController).pulse(any(), anyInt());

        // Starting a pulse should change the scrim controller to the pulsing state
        mStatusBar.mDozeServiceHost.pulseWhileDozing(mock(DozeHost.PulseCallback.class),
                DozeLog.PULSE_REASON_NOTIFICATION);
        verify(mScrimController).transitionTo(eq(ScrimState.PULSING), any());

        // Ending a pulse should take it back to keyguard state
        pulseCallback[0].onPulseFinished();
        verify(mScrimController).transitionTo(eq(ScrimState.KEYGUARD));
    }

    @Test
    public void testPulseWhileDozing_notifyAuthInterrupt() {
        HashSet<Integer> reasonsWantingAuth = new HashSet<>(
                Collections.singletonList(DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN));
        HashSet<Integer> reasonsSkippingAuth = new HashSet<>(
                Arrays.asList(DozeLog.PULSE_REASON_INTENT,
                        DozeLog.PULSE_REASON_NOTIFICATION,
                        DozeLog.PULSE_REASON_SENSOR_SIGMOTION,
                        DozeLog.REASON_SENSOR_PICKUP,
                        DozeLog.REASON_SENSOR_DOUBLE_TAP,
                        DozeLog.PULSE_REASON_SENSOR_LONG_PRESS,
                        DozeLog.PULSE_REASON_DOCKING,
                        DozeLog.REASON_SENSOR_WAKE_UP,
                        DozeLog.REASON_SENSOR_TAP));
        HashSet<Integer> reasonsThatDontPulse = new HashSet<>(
                Arrays.asList(DozeLog.REASON_SENSOR_PICKUP,
                        DozeLog.REASON_SENSOR_DOUBLE_TAP,
                        DozeLog.REASON_SENSOR_TAP));

        doAnswer(invocation -> {
            DozeHost.PulseCallback callback = invocation.getArgument(0);
            callback.onPulseStarted();
            return null;
        }).when(mDozeScrimController).pulse(any(), anyInt());

        for (int i = 0; i < DozeLog.REASONS; i++) {
            reset(mKeyguardUpdateMonitor);
            mStatusBar.mDozeServiceHost.pulseWhileDozing(mock(DozeHost.PulseCallback.class), i);
            if (reasonsWantingAuth.contains(i)) {
                verify(mKeyguardUpdateMonitor).onAuthInterruptDetected(eq(true));
            } else if (reasonsSkippingAuth.contains(i) || reasonsThatDontPulse.contains(i)) {
                verify(mKeyguardUpdateMonitor, never()).onAuthInterruptDetected(eq(true));
            } else {
                throw new AssertionError("Reason " + i + " isn't specified as wanting or skipping"
                        + " passive auth. Please consider how this pulse reason should behave.");
            }
        }
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
        verify(mStatusBarStateController).setState(eq(StatusBarState.KEYGUARD));

        // If useFullscreenUserSwitcher is true, state is set to FULLSCREEN_USER_SWITCHER.
        mStatusBar.mUserSwitcherController = mock(UserSwitcherController.class);
        when(mStatusBar.mUserSwitcherController.useFullscreenUserSwitcher()).thenReturn(true);
        mStatusBar.showKeyguardImpl();
        verify(mStatusBarStateController).setState(eq(StatusBarState.FULLSCREEN_USER_SWITCHER));
    }

    @Test
    public void testStartStopDozing() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        when(mStatusBarStateController.isKeyguardRequested()).thenReturn(true);

        mStatusBar.mDozeServiceHost.startDozing();
        verify(mStatusBarStateController).setIsDozing(eq(true));

        mStatusBar.mDozeServiceHost.stopDozing();
        verify(mStatusBarStateController).setIsDozing(eq(false));
    }

    @Test
    public void testOnStartedWakingUp_isNotDozing() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        when(mStatusBarStateController.isKeyguardRequested()).thenReturn(true);

        mStatusBar.mDozeServiceHost.startDozing();
        verify(mStatusBarStateController).setIsDozing(eq(true));

        mStatusBar.mWakefulnessObserver.onStartedWakingUp();
        verify(mStatusBarStateController).setIsDozing(eq(false));
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
                BiometricUnlockController biometricUnlockController,
                KeyguardViewMediator keyguardViewMediator,
                NotificationRemoteInputManager notificationRemoteInputManager,
                NotificationGroupManager notificationGroupManager,
                NotificationGroupAlertTransferHelper notificationGroupAlertTransferHelper,
                FalsingManager falsingManager,
                StatusBarWindowController statusBarWindowController,
                NotificationIconAreaController notificationIconAreaController,
                DozeScrimController dozeScrimController,
                NotificationShelf notificationShelf,
                NotificationLockscreenUserManager notificationLockscreenUserManager,
                CommandQueue commandQueue,
                NotificationPresenter notificationPresenter,
                BubbleController bubbleController,
                NavigationBarController navBarController,
                AutoHideController autoHideController,
                KeyguardUpdateMonitor keyguardUpdateMonitor) {
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
            mBiometricUnlockController = biometricUnlockController;
            mKeyguardViewMediator = keyguardViewMediator;
            mRemoteInputManager = notificationRemoteInputManager;
            mGroupManager = notificationGroupManager;
            mGroupAlertTransferHelper = notificationGroupAlertTransferHelper;
            mFalsingManager = falsingManager;
            mStatusBarWindowController = statusBarWindowController;
            mNotificationIconAreaController = notificationIconAreaController;
            mDozeScrimController = dozeScrimController;
            mNotificationShelf = notificationShelf;
            mLockscreenUserManager = notificationLockscreenUserManager;
            mCommandQueue = commandQueue;
            mPresenter = notificationPresenter;
            mGestureWakeLock = mock(PowerManager.WakeLock.class);
            mBubbleController = bubbleController;
            mNavigationBarController = navBarController;
            mAutoHideController = autoHideController;
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
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

    public static class TestableNotificationEntryManager extends NotificationEntryManager {

        public TestableNotificationEntryManager(Context context) {
            super(context);
        }

        public void setUpForTest(NotificationPresenter presenter,
                NotificationListContainer listContainer,
                HeadsUpManagerPhone headsUpManager,
                NotificationData notificationData) {
            super.setUpWithPresenter(presenter, listContainer, headsUpManager);
            mNotificationData = notificationData;
        }
    }

    public static class TestableNotificationInterruptionStateProvider extends
            NotificationInterruptionStateProvider {

        public TestableNotificationInterruptionStateProvider(
                Context context,
                PowerManager powerManager,
                IDreamManager dreamManager) {
            super(context, powerManager, dreamManager);
            mUseHeadsUp = true;
        }
    }
}
