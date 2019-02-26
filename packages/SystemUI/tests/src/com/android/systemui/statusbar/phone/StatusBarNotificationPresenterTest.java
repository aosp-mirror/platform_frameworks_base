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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.StatusBarManager;
import android.content.Context;
import android.metrics.LogMaker;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.support.test.metricshelper.MetricsAsserts;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.ViewGroup;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper()
public class StatusBarNotificationPresenterTest extends SysuiTestCase {


    private StatusBarNotificationPresenter mStatusBar;
    private CommandQueue mCommandQueue;
    private FakeMetricsLogger mMetricsLogger;
    private ShadeController mShadeController = mock(ShadeController.class);

    @Before
    public void setup() {
        mMetricsLogger = new FakeMetricsLogger();
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mCommandQueue = new CommandQueue(mContext);
        mContext.putComponent(CommandQueue.class, mCommandQueue);
        mDependency.injectTestDependency(ShadeController.class, mShadeController);

        StatusBarWindowView statusBarWindowView = mock(StatusBarWindowView.class);
        when(statusBarWindowView.getResources()).thenReturn(mContext.getResources());
        mStatusBar = new StatusBarNotificationPresenter(mContext,
                mock(NotificationPanelView.class), mock(HeadsUpManagerPhone.class),
                statusBarWindowView, mock(NotificationListContainerViewGroup.class),
                mock(DozeScrimController.class), mock(ScrimController.class),
                mock(ActivityLaunchAnimator.class), mock(StatusBarKeyguardViewManager.class),
                mock(NotificationAlertingManager.class),
                mock(NotificationRowBinderImpl.class));
    }

    @Test
    public void testHeadsUp_disabledStatusBar() {
        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationEntry entry = new NotificationEntry(sbn);
        mCommandQueue.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_EXPAND, 0,
                false /* animate */);
        TestableLooper.get(this).processAllMessages();

        assertFalse("The panel shouldn't allow heads up while disabled",
                mStatusBar.canHeadsUp(entry, sbn));
    }

    @Test
    public void testHeadsUp_disabledNotificationShade() {
        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationEntry entry = new NotificationEntry(sbn);
        mCommandQueue.disable(DEFAULT_DISPLAY, 0, StatusBarManager.DISABLE2_NOTIFICATION_SHADE,
                false /* animate */);
        TestableLooper.get(this).processAllMessages();

        assertFalse("The panel shouldn't allow heads up while notitifcation shade disabled",
                mStatusBar.canHeadsUp(entry, sbn));
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

    // We need this because mockito doesn't know how to construct a mock that extends ViewGroup
    // and implements NotificationListContainer without it because of classloader issues.
    private abstract static class NotificationListContainerViewGroup extends ViewGroup
            implements NotificationListContainer {

        public NotificationListContainerViewGroup(Context context) {
            super(context);
        }
    }
}

