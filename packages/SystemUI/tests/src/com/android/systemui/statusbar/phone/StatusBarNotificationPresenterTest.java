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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.systemui.ForegroundServiceNotificationListener;
import com.android.systemui.InitController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.QuickSettingsController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeNotificationPresenter;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.render.NotifShadeEventSource;
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsInteractor;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper()
public class StatusBarNotificationPresenterTest extends SysuiTestCase {
    private StatusBarNotificationPresenter mStatusBarNotificationPresenter;
    private final NotificationInterruptStateProvider mNotificationInterruptStateProvider =
            mock(NotificationInterruptStateProvider.class);
    private NotificationInterruptSuppressor mInterruptSuppressor;
    private CommandQueue mCommandQueue;
    private FakeMetricsLogger mMetricsLogger;
    private final ShadeController mShadeController = mock(ShadeController.class);
    private final CentralSurfaces mCentralSurfaces = mock(CentralSurfaces.class);
    private final NotificationsInteractor mNotificationsInteractor =
            mock(NotificationsInteractor.class);
    private final KeyguardStateController mKeyguardStateController =
            mock(KeyguardStateController.class);
    private final NotifPipelineFlags mNotifPipelineFlags = mock(NotifPipelineFlags.class);
    private final InitController mInitController = new InitController();

    @Before
    public void setup() {
        mMetricsLogger = new FakeMetricsLogger();
        LockscreenGestureLogger lockscreenGestureLogger = new LockscreenGestureLogger(
                mMetricsLogger);
        mCommandQueue = new CommandQueue(mContext, new FakeDisplayTracker(mContext));
        mDependency.injectTestDependency(StatusBarStateController.class,
                mock(SysuiStatusBarStateController.class));
        mDependency.injectTestDependency(ShadeController.class, mShadeController);
        mDependency.injectMockDependency(NotificationRemoteInputManager.Callback.class);
        mDependency.injectMockDependency(NotificationShadeWindowController.class);
        mDependency.injectMockDependency(ForegroundServiceNotificationListener.class);

        NotificationShadeWindowView notificationShadeWindowView =
                mock(NotificationShadeWindowView.class);
        NotificationStackScrollLayoutController stackScrollLayoutController =
                mock(NotificationStackScrollLayoutController.class);
        when(stackScrollLayoutController.getView()).thenReturn(
                mock(NotificationStackScrollLayout.class));
        when(notificationShadeWindowView.getResources()).thenReturn(mContext.getResources());

        ShadeViewController shadeViewController = mock(ShadeViewController.class);
        when(shadeViewController.getShadeNotificationPresenter())
                .thenReturn(mock(ShadeNotificationPresenter.class));
        mStatusBarNotificationPresenter = new StatusBarNotificationPresenter(
                mContext,
                shadeViewController,
                mock(QuickSettingsController.class),
                mock(HeadsUpManagerPhone.class),
                notificationShadeWindowView,
                mock(ActivityStarter.class),
                stackScrollLayoutController,
                mock(DozeScrimController.class),
                mock(NotificationShadeWindowController.class),
                mock(DynamicPrivacyController.class),
                mKeyguardStateController,
                mCentralSurfaces,
                mNotificationsInteractor,
                mock(LockscreenShadeTransitionController.class),
                mock(PowerInteractor.class),
                mCommandQueue,
                mock(NotificationLockscreenUserManager.class),
                mock(SysuiStatusBarStateController.class),
                mock(NotifShadeEventSource.class),
                mock(NotificationMediaManager.class),
                mock(NotificationGutsManager.class),
                lockscreenGestureLogger,
                mInitController,
                mNotificationInterruptStateProvider,
                mock(NotificationRemoteInputManager.class),
                mNotifPipelineFlags,
                mock(NotificationRemoteInputManager.Callback.class),
                mock(NotificationListContainer.class));
        mInitController.executePostInitTasks();
        ArgumentCaptor<NotificationInterruptSuppressor> suppressorCaptor =
                ArgumentCaptor.forClass(NotificationInterruptSuppressor.class);
        verify(mNotificationInterruptStateProvider).addSuppressor(suppressorCaptor.capture());
        mInterruptSuppressor = suppressorCaptor.getValue();
    }

    @Test
    public void testNoSuppressHeadsUp_default() {
        Notification n = new Notification.Builder(getContext(), "a").build();
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .build();

        assertFalse(mInterruptSuppressor.suppressAwakeHeadsUp(entry));
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
    public void testNoSuppressHeadsUp_FSI_nonOccludedKeyguard() {
        Notification n = new Notification.Builder(getContext(), "a")
                .setFullScreenIntent(mock(PendingIntent.class), true)
                .build();
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .build();

        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mCentralSurfaces.isOccluded()).thenReturn(false);
        assertFalse(mInterruptSuppressor.suppressAwakeHeadsUp(entry));
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
        when(mNotificationsInteractor.areNotificationAlertsEnabled()).thenReturn(false);

        assertTrue("When alerts aren't enabled, interruptions are suppressed",
                mInterruptSuppressor.suppressInterruptions(entry));
    }
}
