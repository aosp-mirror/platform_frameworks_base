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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.data.repository.FakePowerRepository;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.power.domain.interactor.PowerInteractorFactory;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeControllerImpl;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shade.data.repository.FakeShadeRepository;
import com.android.systemui.shade.data.repository.ShadeAnimationRepository;
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractorLegacyImpl;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationClickNotifier;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.LaunchFullScreenIntentProvider;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.data.repository.NotificationLaunchAnimationRepository;
import com.android.systemui.statusbar.notification.domain.interactor.NotificationLaunchAnimationInteractor;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.OnUserInteractionCallback;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.HeadsUpManager;
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
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class StatusBarNotificationActivityStarterTest extends SysuiTestCase {

    private static final int DISPLAY_ID = 0;
    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();

    @Mock
    private AssistManager mAssistManager;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private NotificationClickNotifier mClickNotifier;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private NotificationRemoteInputManager mRemoteInputManager;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private Handler mHandler;
    @Mock
    private BubblesManager mBubblesManager;
    @Mock
    private ShadeControllerImpl mShadeController;
    @Mock
    private NotificationVisibilityProvider mVisibilityProvider;
    @Mock
    private ActivityIntentHelper mActivityIntentHelper;
    @Mock
    private PendingIntent mContentIntent;
    @Mock
    private OnUserInteractionCallback mOnUserInteractionCallback;
    @Mock
    private Runnable mFutureDismissalRunnable;
    @Mock
    private StatusBarNotificationActivityStarter mNotificationActivityStarter;
    @Mock
    private ActivityTransitionAnimator mActivityTransitionAnimator;
    @Mock
    private InteractionJankMonitor mJankMonitor;
    private FakePowerRepository mPowerRepository;
    @Mock
    private UserTracker mUserTracker;
    private final FakeExecutor mUiBgExecutor = new FakeExecutor(new FakeSystemClock());
    private ExpandableNotificationRow mNotificationRow;
    private ExpandableNotificationRow mBubbleNotificationRow;

    private final Answer<Void> mCallOnDismiss = answerVoid(
            (OnDismissAction dismissAction, Runnable cancel,
                    Boolean afterKeyguardGone) -> dismissAction.onDismiss());

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContentIntent.isActivity()).thenReturn(true);
        when(mContentIntent.getCreatorUserHandle()).thenReturn(UserHandle.of(1));

        NotificationTestHelper notificationTestHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));

        // Create standard notification with contentIntent
        mNotificationRow = notificationTestHelper.createRow();
        StatusBarNotification sbn = mNotificationRow.getEntry().getSbn();
        sbn.getNotification().contentIntent = mContentIntent;
        sbn.getNotification().flags |= Notification.FLAG_AUTO_CANCEL;

        // Create bubble notification row with contentIntent
        mBubbleNotificationRow = notificationTestHelper.createBubble();
        StatusBarNotification bubbleSbn = mBubbleNotificationRow.getEntry().getSbn();
        bubbleSbn.getNotification().contentIntent = mContentIntent;
        bubbleSbn.getNotification().flags |= Notification.FLAG_AUTO_CANCEL;

//        ArrayList<NotificationEntry> activeNotifications = new ArrayList<>();
//        activeNotifications.add(mNotificationRow.getEntry());
//        activeNotifications.add(mBubbleNotificationRow.getEntry());
//        when(mEntryManager.getVisibleNotifications()).thenReturn(activeNotifications);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        when(mOnUserInteractionCallback.registerFutureDismissal(eq(mNotificationRow.getEntry()),
                anyInt())).thenReturn(mFutureDismissalRunnable);
        when(mVisibilityProvider.obtain(anyString(), anyBoolean()))
                .thenAnswer(invocation -> NotificationVisibility.obtain(
                        invocation.getArgument(0), 0, 1, false));
        when(mVisibilityProvider.obtain(any(NotificationEntry.class), anyBoolean()))
                .thenAnswer(invocation -> NotificationVisibility.obtain(
                        invocation.<NotificationEntry>getArgument(0).getKey(), 0, 1, false));
        when(mUserTracker.getUserHandle()).thenReturn(
                UserHandle.of(ActivityManager.getCurrentUser()));

        mPowerRepository = new FakePowerRepository();
        PowerInteractor mPowerInteractor = PowerInteractorFactory.create(
                mPowerRepository,
                new FalsingCollectorFake(),
                mScreenOffAnimationController,
                mStatusBarStateController).getPowerInteractor();

        HeadsUpManager headsUpManager = mock(HeadsUpManager.class);
        NotificationLaunchAnimatorControllerProvider notificationAnimationProvider =
                new NotificationLaunchAnimatorControllerProvider(
                        new NotificationLaunchAnimationInteractor(
                                new NotificationLaunchAnimationRepository()),
                        mock(NotificationListContainer.class),
                        headsUpManager,
                        mJankMonitor);
        mNotificationActivityStarter =
                new StatusBarNotificationActivityStarter(
                        getContext(),
                        DISPLAY_ID,
                        mHandler,
                        mUiBgExecutor,
                        mVisibilityProvider,
                        headsUpManager,
                        mActivityStarter,
                        mCommandQueue,
                        mClickNotifier,
                        mStatusBarKeyguardViewManager,
                        mock(KeyguardManager.class),
                        mock(IDreamManager.class),
                        Optional.of(mBubblesManager),
                        () -> mAssistManager,
                        mRemoteInputManager,
                        mock(NotificationLockscreenUserManager.class),
                        mShadeController,
                        mKeyguardStateController,
                        mock(LockPatternUtils.class),
                        mock(StatusBarRemoteInputCallback.class),
                        mActivityIntentHelper,
                        mock(MetricsLogger.class),
                        new StatusBarNotificationActivityStarterLogger(logcatLogBuffer()),
                        mOnUserInteractionCallback,
                        mock(NotificationPresenter.class),
                        mock(ShadeViewController.class),
                        mock(NotificationShadeWindowController.class),
                        mActivityTransitionAnimator,
                        new ShadeAnimationInteractorLegacyImpl(
                                new ShadeAnimationRepository(),
                                new FakeShadeRepository()
                        ),
                        notificationAnimationProvider,
                        mock(LaunchFullScreenIntentProvider.class),
                        mPowerInteractor,
                        mUserTracker
                );

        // set up dismissKeyguardThenExecute to synchronously invoke the OnDismissAction arg
        doAnswer(mCallOnDismiss).when(mActivityStarter).dismissKeyguardThenExecute(
                any(OnDismissAction.class), any(), anyBoolean());

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
        // To get the order right, collect posted runnables and run them later
        List<Runnable> runnables = new ArrayList<>();
        doAnswer(answerVoid(r -> runnables.add((Runnable) r)))
                .when(mHandler).post(any(Runnable.class));
        // Given
        NotificationEntry entry = mNotificationRow.getEntry();
        Notification notification = entry.getSbn().getNotification();
        notification.contentIntent = mContentIntent;
        notification.flags |= Notification.FLAG_AUTO_CANCEL;

        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(true);

        // When
        mNotificationActivityStarter.onNotificationClicked(entry, mNotificationRow);
        // Run the collected runnables in fifo order, the way post() really does.
        while (!runnables.isEmpty()) runnables.remove(0).run();

        // Then
        verify(mShadeController, atLeastOnce()).collapseShade();

        verify(mActivityTransitionAnimator).startPendingIntentWithAnimation(any(),
                eq(false) /* animate */, any(), any());

        verify(mAssistManager).hideAssist();

        InOrder orderVerifier = Mockito.inOrder(mClickNotifier, mOnUserInteractionCallback,
                mFutureDismissalRunnable);
        // Notification calls dismiss callback to remove notification due to FLAG_AUTO_CANCEL
        orderVerifier.verify(mOnUserInteractionCallback)
                .registerFutureDismissal(eq(entry), eq(REASON_CLICK));
        orderVerifier.verify(mClickNotifier).onNotificationClick(
                eq(entry.getKey()), any(NotificationVisibility.class));
        orderVerifier.verify(mFutureDismissalRunnable).run();
    }

    @Test
    public void testOnNotificationClicked_bubble_noContentIntent_noKeyGuard()
            throws RemoteException {
        NotificationEntry entry = mBubbleNotificationRow.getEntry();
        StatusBarNotification sbn = entry.getSbn();

        // Given
        sbn.getNotification().contentIntent = null;

        // When
        mNotificationActivityStarter.onNotificationClicked(entry, mBubbleNotificationRow);

        // Then
        verify(mBubblesManager).expandStackAndSelectBubble(eq(mBubbleNotificationRow.getEntry()));

        // This is called regardless, and simply short circuits when there is nothing to do.
        verify(mShadeController, atLeastOnce()).collapseShade();

        verify(mAssistManager).hideAssist();

        verify(mClickNotifier).onNotificationClick(
                eq(entry.getKey()), any(NotificationVisibility.class));

        // The content intent should NOT be sent on click.
        verifyZeroInteractions(mContentIntent);

        // Notification should not be cancelled.
        verify(mOnUserInteractionCallback, never())
                .registerFutureDismissal(eq(mNotificationRow.getEntry()), anyInt());
        verify(mFutureDismissalRunnable, never()).run();
    }

    @Test
    public void testOnNotificationClicked_bubble_noContentIntent_keyGuardShowing()
            throws RemoteException {
        NotificationEntry entry = mBubbleNotificationRow.getEntry();
        StatusBarNotification sbn = entry.getSbn();

        // Given
        sbn.getNotification().contentIntent = null;
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(true);

        // When
        mNotificationActivityStarter.onNotificationClicked(entry, mBubbleNotificationRow);

        // Then
        verify(mBubblesManager).expandStackAndSelectBubble(eq(mBubbleNotificationRow.getEntry()));

        verify(mShadeController, atLeastOnce()).collapseShade();

        verify(mAssistManager).hideAssist();

        verify(mClickNotifier).onNotificationClick(
                eq(entry.getKey()), any(NotificationVisibility.class));

        // The content intent should NOT be sent on click.
        verifyZeroInteractions(mContentIntent);
    }

    @Test
    public void testOnNotificationClicked_bubble_withContentIntent_keyGuardShowing()
            throws RemoteException {
        NotificationEntry entry = mBubbleNotificationRow.getEntry();
        StatusBarNotification sbn = entry.getSbn();

        // Given
        sbn.getNotification().contentIntent = mContentIntent;
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(true);

        // When
        mNotificationActivityStarter.onNotificationClicked(entry, mBubbleNotificationRow);

        // Then
        verify(mBubblesManager).expandStackAndSelectBubble(mBubbleNotificationRow.getEntry());

        verify(mShadeController, atLeastOnce()).collapseShade();

        verify(mAssistManager).hideAssist();

        verify(mClickNotifier).onNotificationClick(
                eq(entry.getKey()), any(NotificationVisibility.class));

        // The content intent should NOT be sent on click.
        verify(mContentIntent).isActivity();
        verifyNoMoreInteractions(mContentIntent);
    }

    @Test
    public void testOnFullScreenIntentWhenDozing_wakeUpDevice() {
        // GIVEN entry that can has a full screen intent that can show
        PendingIntent fullScreenIntent = PendingIntent.getActivity(mContext, 1,
                new Intent("fake_full_screen"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = new Notification.Builder(mContext, "a")
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setFullScreenIntent(fullScreenIntent, true);
        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0,
                "tag" + System.currentTimeMillis(), 0, 0,
                nb.build(), new UserHandle(0), null, 0);
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.getImportance()).thenReturn(NotificationManager.IMPORTANCE_HIGH);
        when(entry.getSbn()).thenReturn(sbn);

        // WHEN the intent is launched while dozing
        when(mStatusBarStateController.isDozing()).thenReturn(true);
        mNotificationActivityStarter.launchFullScreenIntent(entry);

        // THEN display should try wake up for the full screen intent
        assertThat(mPowerRepository.getLastWakeReason()).isNotNull();
        assertThat(mPowerRepository.getLastWakeWhy()).isNotNull();
    }

    @Test
    public void testOnFullScreenIntentWhenDozing_logToStatsd() {
        final int kTestUid = 12345;
        final String kTestActivityName = "TestActivity";
        // GIVEN entry that can has a full screen intent that can show
        PendingIntent mockFullScreenIntent = mock(PendingIntent.class);
        when(mockFullScreenIntent.getCreatorUid()).thenReturn(kTestUid);
        when(mockFullScreenIntent.getIntent()).thenReturn(new Intent("fake_full_screen"));
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.name = kTestActivityName;
        when(mockFullScreenIntent.queryIntentComponents(anyInt()))
                .thenReturn(Arrays.asList(resolveInfo));
        Notification.Builder nb = new Notification.Builder(mContext, "a")
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setFullScreenIntent(mockFullScreenIntent, true);
        StatusBarNotification sbn = new StatusBarNotification("pkg", "pkg", 0,
                "tag" + System.currentTimeMillis(), 0, 0,
                nb.build(), new UserHandle(0), null, 0);
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.getImportance()).thenReturn(NotificationManager.IMPORTANCE_HIGH);
        when(entry.getSbn()).thenReturn(sbn);
        MockitoSession mockingSession = mockitoSession()
                .mockStatic(FrameworkStatsLog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        // WHEN
        mNotificationActivityStarter.launchFullScreenIntent(entry);

        // THEN the full screen intent should be logged to statsd.
        verify(() -> FrameworkStatsLog.write(FrameworkStatsLog.FULL_SCREEN_INTENT_LAUNCHED,
                kTestUid, kTestActivityName));
        mockingSession.finishMocking();
    }
}
