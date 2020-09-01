/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.StatusBarManager;
import android.content.Context;
import android.metrics.LogMaker;
import android.support.test.metricshelper.MetricsAsserts;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.systemui.ForegroundServiceNotificationListener;
import com.android.systemui.InitController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper()
public class StatusBarNotificationPresenterTest extends SysuiTestCase {


    private StatusBarNotificationPresenter mStatusBarNotificationPresenter;
    private NotificationInterruptStateProvider mNotificationInterruptStateProvider =
            mock(NotificationInterruptStateProvider.class);
    private NotificationInterruptSuppressor mInterruptSuppressor;
    private CommandQueue mCommandQueue;
    private FakeMetricsLogger mMetricsLogger;
    private ShadeController mShadeController = mock(ShadeController.class);
    private StatusBar mStatusBar = mock(StatusBar.class);
    private InitController mInitController = new InitController();

    @Before
    public void setup() {
        NotificationRemoteInputManager notificationRemoteInputManager =
                mock(NotificationRemoteInputManager.class);
        when(notificationRemoteInputManager.getController())
                .thenReturn(mock(RemoteInputController.class));
        mMetricsLogger = new FakeMetricsLogger();
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mCommandQueue = new CommandQueue(mContext);
        mDependency.injectTestDependency(StatusBarStateController.class,
                mock(SysuiStatusBarStateController.class));
        mDependency.injectTestDependency(ShadeController.class, mShadeController);
        mDependency.injectTestDependency(NotificationRemoteInputManager.class,
                notificationRemoteInputManager);
        mDependency.injectMockDependency(NotificationViewHierarchyManager.class);
        mDependency.injectMockDependency(NotificationRemoteInputManager.Callback.class);
        mDependency.injectMockDependency(NotificationLockscreenUserManager.class);
        mDependency.injectMockDependency(NotificationMediaManager.class);
        mDependency.injectMockDependency(VisualStabilityManager.class);
        mDependency.injectMockDependency(NotificationGutsManager.class);
        mDependency.injectMockDependency(NotificationShadeWindowController.class);
        mDependency.injectMockDependency(ForegroundServiceNotificationListener.class);
        NotificationEntryManager entryManager =
                mDependency.injectMockDependency(NotificationEntryManager.class);
        when(entryManager.getActiveNotificationsForCurrentUser()).thenReturn(new ArrayList<>());

        NotificationShadeWindowView notificationShadeWindowView =
                mock(NotificationShadeWindowView.class);
        when(notificationShadeWindowView.getResources()).thenReturn(mContext.getResources());

        mStatusBarNotificationPresenter = new StatusBarNotificationPresenter(mContext,
                mock(NotificationPanelViewController.class), mock(HeadsUpManagerPhone.class),
                notificationShadeWindowView, mock(NotificationListContainerViewGroup.class),
                mock(DozeScrimController.class), mock(ScrimController.class),
                mock(ActivityLaunchAnimator.class), mock(DynamicPrivacyController.class),
                mock(KeyguardStateController.class),
                mock(KeyguardIndicationController.class), mStatusBar,
                mock(ShadeControllerImpl.class), mCommandQueue, mInitController,
                mNotificationInterruptStateProvider);
        mInitController.executePostInitTasks();
        ArgumentCaptor<NotificationInterruptSuppressor> suppressorCaptor =
                ArgumentCaptor.forClass(NotificationInterruptSuppressor.class);
        verify(mNotificationInterruptStateProvider).addSuppressor(suppressorCaptor.capture());
        mInterruptSuppressor = suppressorCaptor.getValue();
    }

    @Test
    public void testSuppressHeadsUp_disabledStatusBar() {
        Notification n = new Notification.Builder(getContext(), "a").build();
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .build();
        mCommandQueue.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_EXPAND, 0,
                false /* animate */);
        TestableLooper.get(this).processAllMessages();

        assertTrue("The panel should suppress heads up while disabled",
                mInterruptSuppressor.suppressAwakeHeadsUp(entry));
    }

    @Test
    public void testSuppressHeadsUp_disabledNotificationShade() {
        Notification n = new Notification.Builder(getContext(), "a").build();
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .build();
        mCommandQueue.disable(DEFAULT_DISPLAY, 0, StatusBarManager.DISABLE2_NOTIFICATION_SHADE,
                false /* animate */);
        TestableLooper.get(this).processAllMessages();

        assertTrue("The panel should suppress interruptions while notification shade "
                        + "disabled",
                mInterruptSuppressor.suppressAwakeHeadsUp(entry));
    }

    @Test
    public void testSuppressInterruptions_vrMode() {
        Notification n = new Notification.Builder(getContext(), "a").build();
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .build();
        mStatusBarNotificationPresenter.mVrMode = true;

        assertTrue("Vr mode should suppress interruptions",
                mInterruptSuppressor.suppressAwakeInterruptions(entry));
    }

    @Test
    public void testSuppressInterruptions_statusBarAlertsDisabled() {
        Notification n = new Notification.Builder(getContext(), "a").build();
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .build();
        when(mStatusBar.areNotificationAlertsDisabled()).thenReturn(true);

        assertTrue("StatusBar alerts disabled shouldn't allow interruptions",
                mInterruptSuppressor.suppressInterruptions(entry));
    }

    @Test
    public void onActivatedMetrics() {
        ActivatableNotificationView view =  mock(ActivatableNotificationView.class);
        mStatusBarNotificationPresenter.onActivated(view);

        MetricsAsserts.assertHasLog("missing lockscreen note tap log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.ACTION_LS_NOTE)
                        .setType(MetricsEvent.TYPE_ACTION));
    }

    // We need this because mockito doesn't know how to construct a mock that extends ViewGroup
    // and implements NotificationListContainer without it because of classloader issues.
    private abstract static class NotificationListContainerViewGroup extends ViewGroup
            implements NotificationListContainer {

        public NotificationListContainerViewGroup(Context context) {
            super(context);
        }
    }
}
