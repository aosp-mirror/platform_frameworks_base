/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP;

import static junit.framework.Assert.assertNotNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.asynclayoutinflater.view.AsyncLayoutInflater;
import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.MediaFeatureFlag;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.ConversationNotificationProcessor;
import com.android.systemui.statusbar.notification.ForegroundServiceDismissalFeatureController;
import com.android.systemui.statusbar.notification.NotificationClicker;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationEntryManagerLogger;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationRankingManager;
import com.android.systemui.statusbar.notification.collection.inflation.LowPriorityInflationHelper;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.icon.IconBuilder;
import com.android.systemui.statusbar.notification.icon.IconManager;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowComponent;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.InflatedSmartReplyState;
import com.android.systemui.statusbar.policy.InflatedSmartReplyViewHolder;
import com.android.systemui.statusbar.policy.SmartReplyStateInflater;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.leak.LeakDetector;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.wmshell.BubblesManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * Functional tests for notification inflation from {@link NotificationEntryManager}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class NotificationEntryManagerInflationTest extends SysuiTestCase {

    private static final String TEST_TITLE = "Title";
    private static final String TEST_TEXT = "Text";
    private static final long TIMEOUT_TIME = 10000;
    private static final Runnable TIMEOUT_RUNNABLE = () -> {
        throw new RuntimeException("Timed out waiting to inflate");
    };

    @Mock private NotificationPresenter mPresenter;
    @Mock private NotificationEntryManager.KeyguardEnvironment mEnvironment;
    @Mock private NotificationListContainer mListContainer;
    @Mock private NotificationEntryListener mEntryListener;
    @Mock private NotificationRowBinderImpl.BindRowCallback mBindCallback;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private NotificationInterruptStateProvider mNotificationInterruptionStateProvider;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationGutsManager mGutsManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private NotificationMediaManager mNotificationMediaManager;
    @Mock(answer = Answers.RETURNS_SELF)
    private ExpandableNotificationRowComponent.Builder mExpandableNotificationRowComponentBuilder;
    @Mock private ExpandableNotificationRowComponent mExpandableNotificationRowComponent;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private StatusBarStateController mStatusBarStateController;

    @Mock private NotificationGroupManagerLegacy mGroupMembershipManager;
    @Mock private NotificationGroupManagerLegacy mGroupExpansionManager;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private LeakDetector mLeakDetector;

    @Mock private ActivatableNotificationViewController mActivatableNotificationViewController;
    @Mock private NotificationRowComponent.Builder mNotificationRowComponentBuilder;
    @Mock private PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    @Mock private InflatedSmartReplyState mInflatedSmartReplyState;
    @Mock private InflatedSmartReplyViewHolder mInflatedSmartReplies;

    private StatusBarNotification mSbn;
    private NotificationListenerService.RankingMap mRankingMap;
    private NotificationEntryManager mEntryManager;
    private NotificationRowBinderImpl mRowBinder;
    private Handler mHandler;
    private FakeExecutor mBgExecutor;
    private RowContentBindStage mRowContentBindStage;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(SmartReplyController.class);
        mDependency.injectMockDependency(MediaOutputDialogFactory.class);

        mHandler = Handler.createAsync(TestableLooper.get(this).getLooper());

        // Add an action so heads up content views are made
        Notification.Action action = new Notification.Action.Builder(null, null, null).build();
        Notification notification = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle(TEST_TITLE)
                .setContentText(TEST_TEXT)
                .setActions(action)
                .build();
        mSbn = new SbnBuilder()
                .setNotification(notification)
                .build();

        when(mFeatureFlags.isNewNotifPipelineEnabled()).thenReturn(false);
        when(mFeatureFlags.isNewNotifPipelineRenderingEnabled()).thenReturn(false);

        mEntryManager = new NotificationEntryManager(
                mock(NotificationEntryManagerLogger.class),
                mGroupMembershipManager,
                mFeatureFlags,
                () -> mRowBinder,
                () -> mRemoteInputManager,
                mLeakDetector,
                mock(ForegroundServiceDismissalFeatureController.class),
                mock(IStatusBarService.class)
        );
        mEntryManager.setRanker(
                new NotificationRankingManager(
                        () -> mock(NotificationMediaManager.class),
                        mGroupMembershipManager,
                        mHeadsUpManager,
                        mock(NotificationFilter.class),
                        mock(NotificationEntryManagerLogger.class),
                        mock(NotificationSectionsFeatureManager.class),
                        mock(PeopleNotificationIdentifier.class),
                        mock(HighPriorityProvider.class),
                        mEnvironment));

        NotifRemoteViewCache cache = new NotifRemoteViewCacheImpl(mEntryManager);
        NotifBindPipeline pipeline = new NotifBindPipeline(
                mEntryManager,
                mock(NotifBindPipelineLogger.class),
                TestableLooper.get(this).getLooper());
        mBgExecutor = new FakeExecutor(new FakeSystemClock());
        NotificationContentInflater binder = new NotificationContentInflater(
                cache,
                mRemoteInputManager,
                mock(ConversationNotificationProcessor.class),
                mock(MediaFeatureFlag.class),
                mBgExecutor,
                new SmartReplyStateInflater() {
                    @Override
                    public InflatedSmartReplyState inflateSmartReplyState(NotificationEntry entry) {
                        return mInflatedSmartReplyState;
                    }

                    @Override
                    public InflatedSmartReplyViewHolder inflateSmartReplyViewHolder(
                            Context sysuiContext, Context notifPackageContext,
                            NotificationEntry entry,
                            InflatedSmartReplyState existingSmartReplyState,
                            InflatedSmartReplyState newSmartReplyState) {
                        return mInflatedSmartReplies;
                    }
                });
        mRowContentBindStage = new RowContentBindStage(
                binder,
                mock(NotifInflationErrorManager.class),
                mock(RowContentBindStageLogger.class));
        pipeline.setStage(mRowContentBindStage);

        ArgumentCaptor<ExpandableNotificationRow> viewCaptor =
                ArgumentCaptor.forClass(ExpandableNotificationRow.class);
        when(mExpandableNotificationRowComponentBuilder
                .expandableNotificationRow(viewCaptor.capture()))
                .thenReturn(mExpandableNotificationRowComponentBuilder);
        when(mExpandableNotificationRowComponentBuilder.build())
                .thenReturn(mExpandableNotificationRowComponent);

        when(mExpandableNotificationRowComponent.getExpandableNotificationRowController())
                .thenAnswer((Answer<ExpandableNotificationRowController>) invocation ->
                        new ExpandableNotificationRowController(
                                viewCaptor.getValue(),
                                mListContainer,
                                mock(ActivatableNotificationViewController.class),
                                mNotificationMediaManager,
                                mock(PluginManager.class),
                                new FakeSystemClock(),
                                "FOOBAR", "FOOBAR",
                                mKeyguardBypassController,
                                mGroupMembershipManager,
                                mGroupExpansionManager,
                                mRowContentBindStage,
                                mock(NotificationLogger.class),
                                mHeadsUpManager,
                                mPresenter,
                                mStatusBarStateController,
                                mGutsManager,
                                true,
                                null,
                                new FalsingManagerFake(),
                                new FalsingCollectorFake(),
                                mPeopleNotificationIdentifier,
                                Optional.of(mock(BubblesManager.class)),
                                mock(ExpandableNotificationRowDragController.class)
                        ));

        when(mNotificationRowComponentBuilder.activatableNotificationView(any()))
                .thenReturn(mNotificationRowComponentBuilder);
        when(mNotificationRowComponentBuilder.build()).thenReturn(
                () -> mActivatableNotificationViewController);

        mRowBinder = new NotificationRowBinderImpl(
                mContext,
                new NotificationMessagingUtil(mContext),
                mRemoteInputManager,
                mLockscreenUserManager,
                pipeline,
                mRowContentBindStage,
                RowInflaterTask::new,
                mExpandableNotificationRowComponentBuilder,
                new IconManager(
                        mEntryManager,
                        mock(LauncherApps.class),
                        new IconBuilder(mContext)),
                mock(LowPriorityInflationHelper.class));

        mEntryManager.setUpWithPresenter(mPresenter);
        mEntryManager.addNotificationEntryListener(mEntryListener);

        mRowBinder.setUpWithPresenter(mPresenter, mListContainer, mBindCallback);
        mRowBinder.setNotificationClicker(mock(NotificationClicker.class));

        Ranking ranking = new Ranking();
        ranking.populate(
                mSbn.getKey(),
                0,
                false,
                0,
                0,
                IMPORTANCE_DEFAULT,
                null,
                null,
                null,
                null,
                null,
                true,
                Ranking.USER_SENTIMENT_NEUTRAL,
                false,
                -1,
                false,
                null,
                null,
                false,
                false,
                false,
                null,
                0,
                false
            );
        mRankingMap = new NotificationListenerService.RankingMap(new Ranking[] {ranking});

        TestableLooper.get(this).processAllMessages();
    }

    @After
    public void cleanUp() {
        // Don't leave anything on main thread
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testAddNotification() {
        // WHEN a notification is added
        mEntryManager.addNotification(mSbn, mRankingMap);
        ArgumentCaptor<NotificationEntry> entryCaptor = ArgumentCaptor.forClass(
                NotificationEntry.class);
        verify(mEntryListener).onPendingEntryAdded(entryCaptor.capture());
        NotificationEntry entry = entryCaptor.getValue();

        waitForInflation();

        // THEN the notification has its row inflated
        assertNotNull(entry.getRow());
        assertNotNull(entry.getRow().getPrivateLayout().getContractedChild());

        // THEN inflation callbacks are called
        verify(mBindCallback).onBindRow(entry.getRow());
        verify(mEntryListener, never()).onInflationError(any(), any());
        verify(mEntryListener).onEntryInflated(entry);
        verify(mEntryListener).onNotificationAdded(entry);

        // THEN the notification is active
        assertNotNull(mEntryManager.getActiveNotificationUnfiltered(mSbn.getKey()));

        // THEN we update the presenter
        verify(mPresenter).updateNotificationViews(any());
    }

    @Test
    public void testUpdateNotification() {
        // GIVEN a notification already added
        mEntryManager.addNotification(mSbn, mRankingMap);
        ArgumentCaptor<NotificationEntry> entryCaptor = ArgumentCaptor.forClass(
                NotificationEntry.class);
        verify(mEntryListener).onPendingEntryAdded(entryCaptor.capture());
        NotificationEntry entry = entryCaptor.getValue();
        waitForInflation();

        Mockito.reset(mEntryListener);
        Mockito.reset(mPresenter);

        // WHEN the notification is updated
        mEntryManager.updateNotification(mSbn, mRankingMap);

        waitForInflation();

        // THEN the notification has its row and inflated
        assertNotNull(entry.getRow());

        // THEN inflation callbacks are called
        verify(mEntryListener, never()).onInflationError(any(), any());
        verify(mEntryListener).onEntryReinflated(entry);

        // THEN we update the presenter
        verify(mPresenter).updateNotificationViews(any());
    }

    @Test
    public void testContentViewInflationDuringRowInflationInflatesCorrectViews() {
        // GIVEN a notification is added and the row is inflating
        mEntryManager.addNotification(mSbn, mRankingMap);
        ArgumentCaptor<NotificationEntry> entryCaptor = ArgumentCaptor.forClass(
                NotificationEntry.class);
        verify(mEntryListener).onPendingEntryAdded(entryCaptor.capture());
        NotificationEntry entry = entryCaptor.getValue();

        // WHEN we try to bind a content view
        mRowContentBindStage.getStageParams(entry).requireContentViews(FLAG_CONTENT_VIEW_HEADS_UP);
        mRowContentBindStage.requestRebind(entry, null);

        waitForInflation();

        // THEN the notification has its row and all relevant content views inflated
        assertNotNull(entry.getRow());
        assertNotNull(entry.getRow().getPrivateLayout().getContractedChild());
        assertNotNull(entry.getRow().getPrivateLayout().getHeadsUpChild());
    }

    /**
     * Wait for inflation to finish.
     *
     * A few things to note
     * 1) Row inflation is done via {@link AsyncLayoutInflater} on its own background thread that
     * calls back to main thread which is why we wait on main thread.
     * 2) Row *content* inflation is done on the {@link FakeExecutor} we pass in in this test class
     * so we control when that work is done. The callback is still always on the main thread.
     */
    private void waitForInflation() {
        mHandler.postDelayed(TIMEOUT_RUNNABLE, TIMEOUT_TIME);
        final CountDownLatch latch = new CountDownLatch(1);
        NotificationEntryListener inflationListener = new NotificationEntryListener() {
            @Override
            public void onEntryInflated(NotificationEntry entry) {
                latch.countDown();
            }

            @Override
            public void onEntryReinflated(NotificationEntry entry) {
                latch.countDown();
            }

            @Override
            public void onInflationError(StatusBarNotification notification, Exception exception) {
                latch.countDown();
            }
        };
        mEntryManager.addNotificationEntryListener(inflationListener);
        while (latch.getCount() != 0) {
            mBgExecutor.runAllReady();
            TestableLooper.get(this).processMessages(1);
        }
        mHandler.removeCallbacks(TIMEOUT_RUNNABLE);
        mEntryManager.removeNotificationEntryListener(inflationListener);
    }

}
