/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.service.notification.NotificationListenerService.REASON_CLICK;

import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationClickNotifier;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.OnUserInteractionCallback;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.wmshell.BubblesManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class StatusBarNotificationActivityStarterTest extends SysuiTestCase {

    @Mock
    private AssistManager mAssistManager;
    @Mock
    private NotificationEntryManager mEntryManager;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private NotificationClickNotifier mClickNotifier;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private NotificationRemoteInputManager mRemoteInputManager;
    @Mock
    private RemoteInputController mRemoteInputController;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private Handler mHandler;
    @Mock
    private BubblesManager mBubblesManager;
    @Mock
    private ShadeControllerImpl mShadeController;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private NotifPipeline mNotifPipeline;

    @Mock
    private ActivityIntentHelper mActivityIntentHelper;
    @Mock
    private PendingIntent mContentIntent;
    @Mock
    private Intent mContentIntentInner;
    @Mock
    private OnUserInteractionCallback mOnUserInteractionCallback;
    @Mock
    private NotificationActivityStarter mNotificationActivityStarter;
    @Mock
    private ActivityLaunchAnimator mActivityLaunchAnimator;
    private FakeExecutor mUiBgExecutor = new FakeExecutor(new FakeSystemClock());

    private NotificationTestHelper mNotificationTestHelper;
    private ExpandableNotificationRow mNotificationRow;
    private ExpandableNotificationRow mBubbleNotificationRow;

    private final Answer<Void> mCallOnDismiss = answerVoid(
            (ActivityStarter.OnDismissAction dismissAction, Runnable cancel,
                    Boolean afterKeyguardGone) -> dismissAction.onDismiss());
    private ArrayList<NotificationEntry> mActiveNotifications;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);

        when(mContentIntent.isActivity()).thenReturn(true);
        when(mContentIntent.getCreatorUserHandle()).thenReturn(UserHandle.of(1));
        when(mContentIntent.getIntent()).thenReturn(mContentIntentInner);

        mNotificationTestHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));

        // Create standard notification with contentIntent
        mNotificationRow = mNotificationTestHelper.createRow();
        StatusBarNotification sbn = mNotificationRow.getEntry().getSbn();
        sbn.getNotification().contentIntent = mContentIntent;
        sbn.getNotification().flags |= Notification.FLAG_AUTO_CANCEL;

        // Create bubble notification row with contentIntent
        mBubbleNotificationRow = mNotificationTestHelper.createBubble();
        StatusBarNotification bubbleSbn = mBubbleNotificationRow.getEntry().getSbn();
        bubbleSbn.getNotification().contentIntent = mContentIntent;
        bubbleSbn.getNotification().flags |= Notification.FLAG_AUTO_CANCEL;

        mActiveNotifications = new ArrayList<>();
        mActiveNotifications.add(mNotificationRow.getEntry());
        mActiveNotifications.add(mBubbleNotificationRow.getEntry());
        when(mEntryManager.getVisibleNotifications()).thenReturn(mActiveNotifications);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        when(mFeatureFlags.isNewNotifPipelineRenderingEnabled()).thenReturn(false);
        when(mOnUserInteractionCallback.getGroupSummaryToDismiss(mNotificationRow.getEntry()))
                .thenReturn(null);

        HeadsUpManagerPhone headsUpManager = mock(HeadsUpManagerPhone.class);
        NotificationLaunchAnimatorControllerProvider notificationAnimationProvider =
                new NotificationLaunchAnimatorControllerProvider(
                        mock(NotificationShadeWindowViewController.class), mock(
                        NotificationListContainer.class),
                        headsUpManager);

        mNotificationActivityStarter =
                new StatusBarNotificationActivityStarter.Builder(
                        getContext(),
                        mock(CommandQueue.class),
                        mHandler,
                        mUiBgExecutor,
                        mEntryManager,
                        mNotifPipeline,
                        headsUpManager,
                        mActivityStarter,
                        mClickNotifier,
                        mock(StatusBarStateController.class),
                        mStatusBarKeyguardViewManager,
                        mock(KeyguardManager.class),
                        mock(IDreamManager.class),
                        Optional.of(mBubblesManager),
                        () -> mAssistManager,
                        mRemoteInputManager,
                        mock(NotificationGroupManagerLegacy.class),
                        mock(NotificationLockscreenUserManager.class),
                        mShadeController,
                        mKeyguardStateController,
                        mock(NotificationInterruptStateProvider.class),
                        mock(LockPatternUtils.class),
                        mock(StatusBarRemoteInputCallback.class),
                        mActivityIntentHelper,

                        mFeatureFlags,
                        mock(MetricsLogger.class),
                        mock(StatusBarNotificationActivityStarterLogger.class),
                        mOnUserInteractionCallback)
                        .setStatusBar(mStatusBar)
                        .setNotificationPresenter(mock(NotificationPresenter.class))
                        .setNotificationPanelViewController(
                                mock(NotificationPanelViewController.class))
                        .setActivityLaunchAnimator(mActivityLaunchAnimator)
                        .setNotificationAnimatorControllerProvider(notificationAnimationProvider)
                        .build();

        // set up dismissKeyguardThenExecute to synchronously invoke the OnDismissAction arg
        doAnswer(mCallOnDismiss).when(mActivityStarter).dismissKeyguardThenExecute(
                any(ActivityStarter.OnDismissAction.class), any(), anyBoolean());

        // set up addAfterKeyguardGoneRunnable to synchronously invoke the Runnable arg
        doAnswer(answerVoid(Runnable::run))
                .when(mStatusBarKeyguardViewManager)
                .addAfterKeyguardGoneRunnable(any(Runnable.class));

        // set up addPostCollapseAction to synchronously invoke the Runnable arg
        doAnswer(answerVoid(Runnable::run))
                .when(mShadeController).addPostCollapseAction(any(Runnable.class));

        // set up Handler to synchronously invoke the Runnable arg
        doAnswer(answerVoid(Runnable::run))
                .when(mHandler).post(any(Runnable.class));
    }

    @Test
    public void testOnNotificationClicked_keyGuardShowing()
            throws PendingIntent.CanceledException, RemoteException {
        // Given
        StatusBarNotification sbn = mNotificationRow.getEntry().getSbn();
        sbn.getNotification().contentIntent = mContentIntent;
        sbn.getNotification().flags |= Notification.FLAG_AUTO_CANCEL;

        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mStatusBar.isOccluded()).thenReturn(true);

        // When
        mNotificationActivityStarter.onNotificationClicked(sbn, mNotificationRow);

        // Then
        verify(mShadeController, atLeastOnce()).collapsePanel();

        verify(mActivityLaunchAnimator).startPendingIntentWithAnimation(any(),
                eq(false) /* animate */, any(), any());

        verify(mAssistManager).hideAssist();

        InOrder orderVerifier = Mockito.inOrder(mClickNotifier, mOnUserInteractionCallback);
        orderVerifier.verify(mClickNotifier).onNotificationClick(
                eq(sbn.getKey()), any(NotificationVisibility.class));
        // Notification calls dismiss callback to remove notification due to FLAG_AUTO_CANCEL
        orderVerifier.verify(mOnUserInteractionCallback).onDismiss(mNotificationRow.getEntry(),
                REASON_CLICK, null);
    }

    @Test
    public void testOnNotificationClicked_bubble_noContentIntent_noKeyGuard()
            throws RemoteException {
        StatusBarNotification sbn = mBubbleNotificationRow.getEntry().getSbn();

        // Given
        sbn.getNotification().contentIntent = null;

        // When
        mNotificationActivityStarter.onNotificationClicked(sbn, mBubbleNotificationRow);

        // Then
        verify(mBubblesManager).expandStackAndSelectBubble(eq(mBubbleNotificationRow.getEntry()));

        // This is called regardless, and simply short circuits when there is nothing to do.
        verify(mShadeController, atLeastOnce()).collapsePanel();

        verify(mAssistManager).hideAssist();

        verify(mClickNotifier).onNotificationClick(
                eq(sbn.getKey()), any(NotificationVisibility.class));

        // The content intent should NOT be sent on click.
        verifyZeroInteractions(mContentIntent);

        // Notification should not be cancelled.
        verify(mOnUserInteractionCallback, never()).onDismiss(eq(mNotificationRow.getEntry()),
                anyInt(), eq(null));
    }

    @Test
    public void testOnNotificationClicked_bubble_noContentIntent_keyGuardShowing()
            throws RemoteException {
        StatusBarNotification sbn = mBubbleNotificationRow.getEntry().getSbn();

        // Given
        sbn.getNotification().contentIntent = null;
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mStatusBar.isOccluded()).thenReturn(true);

        // When
        mNotificationActivityStarter.onNotificationClicked(sbn, mBubbleNotificationRow);

        // Then
        verify(mBubblesManager).expandStackAndSelectBubble(eq(mBubbleNotificationRow.getEntry()));

        verify(mShadeController, atLeastOnce()).collapsePanel();

        verify(mAssistManager).hideAssist();

        verify(mClickNotifier).onNotificationClick(
                eq(sbn.getKey()), any(NotificationVisibility.class));

        // The content intent should NOT be sent on click.
        verifyZeroInteractions(mContentIntent);

        // Notification should not be cancelled.
        verify(mEntryManager, never()).performRemoveNotification(eq(sbn), any(), anyInt());
    }

    @Test
    public void testOnNotificationClicked_bubble_withContentIntent_keyGuardShowing()
            throws RemoteException {
        StatusBarNotification sbn = mBubbleNotificationRow.getEntry().getSbn();

        // Given
        sbn.getNotification().contentIntent = mContentIntent;
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mStatusBar.isOccluded()).thenReturn(true);

        // When
        mNotificationActivityStarter.onNotificationClicked(sbn, mBubbleNotificationRow);

        // Then
        verify(mBubblesManager).expandStackAndSelectBubble(mBubbleNotificationRow.getEntry());

        verify(mShadeController, atLeastOnce()).collapsePanel();

        verify(mAssistManager).hideAssist();

        verify(mClickNotifier).onNotificationClick(
                eq(sbn.getKey()), any(NotificationVisibility.class));

        // The content intent should NOT be sent on click.
        verify(mContentIntent).getIntent();
        verify(mContentIntent).isActivity();
        verifyNoMoreInteractions(mContentIntent);

        // Notification should not be cancelled.
        verify(mEntryManager, never()).performRemoveNotification(eq(sbn), any(), anyInt());
    }
}
