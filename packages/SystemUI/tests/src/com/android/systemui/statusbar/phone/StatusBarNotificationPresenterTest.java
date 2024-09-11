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

import static com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.BUBBLE;
import static com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PEEK;
import static com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PULSE;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.InitController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.QuickSettingsController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.render.NotifShadeEventSource;
import com.android.systemui.statusbar.notification.domain.interactor.NotificationAlertsInteractor;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionCondition;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionFilter;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionRefactor;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper()
public class StatusBarNotificationPresenterTest extends SysuiTestCase {
    private StatusBarNotificationPresenter mStatusBarNotificationPresenter;
    private final VisualInterruptionDecisionProvider mVisualInterruptionDecisionProvider =
            mock(VisualInterruptionDecisionProvider.class);
    private NotificationInterruptSuppressor mInterruptSuppressor;
    private VisualInterruptionCondition mAlertsDisabledCondition;
    private VisualInterruptionCondition mVrModeCondition;
    private VisualInterruptionFilter mNeedsRedactionFilter;
    private VisualInterruptionCondition mPanelsDisabledCondition;
    private CommandQueue mCommandQueue;
    private final ShadeController mShadeController = mock(ShadeController.class);
    private final NotificationAlertsInteractor mNotificationAlertsInteractor =
            mock(NotificationAlertsInteractor.class);
    private final KeyguardStateController mKeyguardStateController =
            mock(KeyguardStateController.class);

    @Before
    public void setup() {
        mCommandQueue = new CommandQueue(mContext, new FakeDisplayTracker(mContext));
        mDependency.injectTestDependency(StatusBarStateController.class,
                mock(SysuiStatusBarStateController.class));
        mDependency.injectTestDependency(ShadeController.class, mShadeController);
        mDependency.injectMockDependency(NotificationRemoteInputManager.Callback.class);
        mDependency.injectMockDependency(NotificationShadeWindowController.class);

        when(mNotificationAlertsInteractor.areNotificationAlertsEnabled()).thenReturn(true);

        createPresenter();
        if (VisualInterruptionRefactor.isEnabled()) {
            verifyAndCaptureSuppressors();
        } else {
            verifyAndCaptureLegacySuppressor();
        }
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testInit_refactorDisabled() {
        assertFalse(VisualInterruptionRefactor.isEnabled());
        assertNull(mAlertsDisabledCondition);
        assertNull(mVrModeCondition);
        assertNull(mNeedsRedactionFilter);
        assertNull(mPanelsDisabledCondition);
        assertNotNull(mInterruptSuppressor);
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testInit_refactorEnabled() {
        assertTrue(VisualInterruptionRefactor.isEnabled());
        assertNotNull(mAlertsDisabledCondition);
        assertNotNull(mVrModeCondition);
        assertNotNull(mNeedsRedactionFilter);
        assertNotNull(mPanelsDisabledCondition);
        assertNull(mInterruptSuppressor);
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testNoSuppressHeadsUp_default_refactorDisabled() {
        assertFalse(mInterruptSuppressor.suppressAwakeHeadsUp(createNotificationEntry()));
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testNoSuppressHeadsUp_default_refactorEnabled() {
        assertFalse(mAlertsDisabledCondition.shouldSuppress());
        assertFalse(mVrModeCondition.shouldSuppress());
        assertFalse(mNeedsRedactionFilter.shouldSuppress(createNotificationEntry()));
        assertFalse(mAlertsDisabledCondition.shouldSuppress());
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testSuppressHeadsUp_disabledStatusBar_refactorDisabled() {
        mCommandQueue.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_EXPAND, 0,
                false /* animate */);
        TestableLooper.get(this).processAllMessages();

        assertTrue("The panel should suppress heads up while disabled",
                mInterruptSuppressor.suppressAwakeHeadsUp(createNotificationEntry()));
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testSuppressHeadsUp_disabledStatusBar_refactorEnabled() {
        mCommandQueue.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_EXPAND, 0,
                false /* animate */);
        TestableLooper.get(this).processAllMessages();

        assertTrue("The panel should suppress heads up while disabled",
                mPanelsDisabledCondition.shouldSuppress());
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testSuppressHeadsUp_disabledNotificationShade_refactorDisabled() {
        mCommandQueue.disable(DEFAULT_DISPLAY, 0, StatusBarManager.DISABLE2_NOTIFICATION_SHADE,
                false /* animate */);
        TestableLooper.get(this).processAllMessages();

        assertTrue("The panel should suppress interruptions while notification shade disabled",
                mInterruptSuppressor.suppressAwakeHeadsUp(createNotificationEntry()));
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testSuppressHeadsUp_disabledNotificationShade_refactorEnabled() {
        mCommandQueue.disable(DEFAULT_DISPLAY, 0, StatusBarManager.DISABLE2_NOTIFICATION_SHADE,
                false /* animate */);
        TestableLooper.get(this).processAllMessages();

        assertTrue("The panel should suppress interruptions while notification shade disabled",
                mPanelsDisabledCondition.shouldSuppress());
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testPanelsDisabledConditionSuppressesPeek() {
        final Set<VisualInterruptionType> types = mPanelsDisabledCondition.getTypes();
        assertTrue(types.contains(PEEK));
        assertFalse(types.contains(PULSE));
        assertFalse(types.contains(BUBBLE));
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testNoSuppressHeadsUp_FSI_nonOccludedKeyguard_refactorDisabled() {
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);

        assertFalse(mInterruptSuppressor.suppressAwakeHeadsUp(createFsiNotificationEntry()));
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testNoSuppressHeadsUp_FSI_nonOccludedKeyguard_refactorEnabled() {
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);

        assertFalse(mNeedsRedactionFilter.shouldSuppress(createFsiNotificationEntry()));

        final Set<VisualInterruptionType> types = mNeedsRedactionFilter.getTypes();
        assertTrue(types.contains(PEEK));
        assertFalse(types.contains(PULSE));
        assertFalse(types.contains(BUBBLE));
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testSuppressInterruptions_vrMode_refactorDisabled() {
        mStatusBarNotificationPresenter.mVrMode = true;

        assertTrue("Vr mode should suppress interruptions",
                mInterruptSuppressor.suppressAwakeInterruptions(createNotificationEntry()));
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testSuppressInterruptions_vrMode_refactorEnabled() {
        mStatusBarNotificationPresenter.mVrMode = true;

        assertTrue("Vr mode should suppress interruptions", mVrModeCondition.shouldSuppress());

        final Set<VisualInterruptionType> types = mVrModeCondition.getTypes();
        assertTrue(types.contains(PEEK));
        assertFalse(types.contains(PULSE));
        assertTrue(types.contains(BUBBLE));
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testSuppressInterruptions_statusBarAlertsDisabled_refactorDisabled() {
        when(mNotificationAlertsInteractor.areNotificationAlertsEnabled()).thenReturn(false);

        assertTrue("When alerts aren't enabled, interruptions are suppressed",
                mInterruptSuppressor.suppressInterruptions(createNotificationEntry()));
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    public void testSuppressInterruptions_statusBarAlertsDisabled_refactorEnabled() {
        when(mNotificationAlertsInteractor.areNotificationAlertsEnabled()).thenReturn(false);

        assertTrue("When alerts aren't enabled, interruptions are suppressed",
                mAlertsDisabledCondition.shouldSuppress());

        final Set<VisualInterruptionType> types = mAlertsDisabledCondition.getTypes();
        assertTrue(types.contains(PEEK));
        assertTrue(types.contains(PULSE));
        assertTrue(types.contains(BUBBLE));
    }

    private void createPresenter() {
        final ShadeViewController shadeViewController = mock(ShadeViewController.class);

        final NotificationShadeWindowView notificationShadeWindowView =
                mock(NotificationShadeWindowView.class);
        when(notificationShadeWindowView.getResources()).thenReturn(mContext.getResources());

        NotificationStackScrollLayoutController stackScrollLayoutController =
                mock(NotificationStackScrollLayoutController.class);
        when(stackScrollLayoutController.getView()).thenReturn(
                mock(NotificationStackScrollLayout.class));

        final InitController initController = new InitController();

        mStatusBarNotificationPresenter = new StatusBarNotificationPresenter(
                mContext,
                shadeViewController,
                mock(PanelExpansionInteractor.class),
                mock(QuickSettingsController.class),
                mock(HeadsUpManager.class),
                notificationShadeWindowView,
                mock(ActivityStarter.class),
                stackScrollLayoutController,
                mock(DozeScrimController.class),
                mock(NotificationShadeWindowController.class),
                mock(DynamicPrivacyController.class),
                mKeyguardStateController,
                mNotificationAlertsInteractor,
                mock(LockscreenShadeTransitionController.class),
                mock(PowerInteractor.class),
                mCommandQueue,
                mock(NotificationLockscreenUserManager.class),
                mock(SysuiStatusBarStateController.class),
                mock(NotifShadeEventSource.class),
                mock(NotificationMediaManager.class),
                mock(NotificationGutsManager.class),
                initController,
                mVisualInterruptionDecisionProvider,
                mock(NotificationRemoteInputManager.class),
                mock(NotificationRemoteInputManager.Callback.class),
                mock(NotificationListContainer.class));

        initController.executePostInitTasks();
    }

    private void verifyAndCaptureSuppressors() {
        mInterruptSuppressor = null;

        final ArgumentCaptor<VisualInterruptionCondition> conditionCaptor =
                ArgumentCaptor.forClass(VisualInterruptionCondition.class);
        verify(mVisualInterruptionDecisionProvider, times(3)).addCondition(
                conditionCaptor.capture());
        final List<VisualInterruptionCondition> conditions = conditionCaptor.getAllValues();
        mAlertsDisabledCondition = conditions.get(0);
        mVrModeCondition = conditions.get(1);
        mPanelsDisabledCondition = conditions.get(2);

        final ArgumentCaptor<VisualInterruptionFilter> needsRedactionFilterCaptor =
                ArgumentCaptor.forClass(VisualInterruptionFilter.class);
        verify(mVisualInterruptionDecisionProvider).addFilter(needsRedactionFilterCaptor.capture());
        mNeedsRedactionFilter = needsRedactionFilterCaptor.getValue();
    }

    private void verifyAndCaptureLegacySuppressor() {
        mAlertsDisabledCondition = null;
        mVrModeCondition = null;
        mNeedsRedactionFilter = null;
        mPanelsDisabledCondition = null;

        final ArgumentCaptor<NotificationInterruptSuppressor> suppressorCaptor =
                ArgumentCaptor.forClass(NotificationInterruptSuppressor.class);
        verify(mVisualInterruptionDecisionProvider).addLegacySuppressor(suppressorCaptor.capture());
        mInterruptSuppressor = suppressorCaptor.getValue();
    }

    private NotificationEntry createNotificationEntry() {
        return new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(new Notification.Builder(getContext(), "a").build())
                .build();
    }

    private NotificationEntry createFsiNotificationEntry() {
        final Notification notification = new Notification.Builder(getContext(), "a")
                .setFullScreenIntent(mock(PendingIntent.class), true)
                .build();

        return new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(notification)
                .build();
    }
}
