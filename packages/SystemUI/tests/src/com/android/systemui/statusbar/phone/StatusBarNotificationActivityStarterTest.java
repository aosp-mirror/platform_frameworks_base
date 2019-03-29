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

import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
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
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;

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
    private IStatusBarService mStatusBarService;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private NotificationRemoteInputManager mRemoteInputManager;
    @Mock
    private RemoteInputController mRemoteInputController;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private KeyguardMonitor mKeyguardMonitor;
    @Mock
    private Handler mHandler;

    @Mock
    private ActivityIntentHelper mActivityIntentHelper;
    @Mock
    private PendingIntent mContentIntent;
    @Mock
    private NotificationData mNotificationData;
    @Mock
    private NotificationEntry mNotificationEntry;
    @Mock
    private NotificationEntry mBubbleEntry;

    private NotificationActivityStarter mNotificationActivityStarter;

    private NotificationTestHelper mNotificationTestHelper;
    ExpandableNotificationRow mNotificationRow;

    private final Answer<Void> mCallOnDismiss = answerVoid(
            (ActivityStarter.OnDismissAction dismissAction, Runnable cancel,
                    Boolean afterKeyguardGone) -> dismissAction.onDismiss());
    private ArrayList<NotificationEntry> mActiveNotifications;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);
        when(mEntryManager.getNotificationData()).thenReturn(mNotificationData);

        mActiveNotifications = new ArrayList<>();
        mActiveNotifications.add(mNotificationEntry);
        mActiveNotifications.add(mBubbleEntry);
        when(mNotificationData.getActiveNotifications()).thenReturn(mActiveNotifications);
        when(mNotificationEntry.getRow()).thenReturn(mNotificationRow);

        mNotificationTestHelper = new NotificationTestHelper(mContext);
        mNotificationRow = mNotificationTestHelper.createRow();

        mNotificationActivityStarter = new StatusBarNotificationActivityStarter(getContext(),
                mock(CommandQueue.class), mAssistManager, mock(NotificationPanelView.class),
                mock(NotificationPresenter.class), mEntryManager, mock(HeadsUpManagerPhone.class),
                mActivityStarter, mock(ActivityLaunchAnimator.class), mStatusBarService,
                mock(StatusBarStateController.class), mock(KeyguardManager.class),
                mock(IDreamManager.class), mRemoteInputManager,
                mock(StatusBarRemoteInputCallback.class), mock(NotificationGroupManager.class),
                mock(NotificationLockscreenUserManager.class), mShadeController, mKeyguardMonitor,
                mock(NotificationInterruptionStateProvider.class), mock(MetricsLogger.class),
                mock(LockPatternUtils.class), mHandler, mActivityIntentHelper);


        when(mContentIntent.isActivity()).thenReturn(true);
        when(mContentIntent.getCreatorUserHandle()).thenReturn(UserHandle.of(1));

        // SBNActivityStarter expects contentIntent or fullScreenIntent to be set
        mNotificationRow.getEntry().notification.getNotification().contentIntent = mContentIntent;

        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);

        // set up dismissKeyguardThenExecute to synchronously invoke the OnDismissAction arg
        doAnswer(mCallOnDismiss).when(mActivityStarter).dismissKeyguardThenExecute(
                any(ActivityStarter.OnDismissAction.class), any(), anyBoolean());

        // set up addAfterKeyguardGoneRunnable to synchronously invoke the Runnable arg
        doAnswer(answerVoid(Runnable::run))
                .when(mShadeController).addAfterKeyguardGoneRunnable(any(Runnable.class));

        // set up addPostCollapseAction to synchronously invoke the Runnable arg
        doAnswer(answerVoid(Runnable::run))
                .when(mShadeController).addPostCollapseAction(any(Runnable.class));

        // set up Handler to synchronously invoke the Runnable arg
        doAnswer(answerVoid(Runnable::run))
                .when(mHandler).post(any(Runnable.class));
    }

    @Test
    public void testOnNotificationClicked_whileKeyguardVisible()
            throws PendingIntent.CanceledException, RemoteException {
        // Given
        when(mKeyguardMonitor.isShowing()).thenReturn(true);
        when(mShadeController.isOccluded()).thenReturn(true);
        when(mContentIntent.isActivity()).thenReturn(true);
        when(mActivityIntentHelper.wouldShowOverLockscreen(any(Intent.class), anyInt()))
                .thenReturn(false);
        when(mActivityIntentHelper.wouldLaunchResolverActivity(any(Intent.class), anyInt()))
                .thenReturn(false);

        StatusBarNotification statusBarNotification = mNotificationRow.getEntry().notification;
        statusBarNotification.getNotification().flags |= Notification.FLAG_AUTO_CANCEL;

        // When
        mNotificationActivityStarter.onNotificationClicked(statusBarNotification,
                mNotificationRow);

        // Then
        verify(mActivityStarter).dismissKeyguardThenExecute(
                any(ActivityStarter.OnDismissAction.class),
                any() /* cancel */,
                anyBoolean() /* afterKeyguardGone */);

        verify(mShadeController, atLeastOnce()).collapsePanel();

        verify(mContentIntent).sendAndReturnResult(
                any(Context.class),
                anyInt() /* code */,
                any() /* fillInIntent */,
                any() /* PendingIntent.OnFinished */,
                any() /* Handler */,
                any() /* requiredPermission */,
                any() /* Bundle options */);

        verify(mAssistManager).hideAssist();

        verify(mStatusBarService).onNotificationClick(
                eq(mNotificationRow.getEntry().key), any(NotificationVisibility.class));

        // Notification is removed due to FLAG_AUTO_CANCEL
        verify(mEntryManager).performRemoveNotification(eq(statusBarNotification));
    }
}
