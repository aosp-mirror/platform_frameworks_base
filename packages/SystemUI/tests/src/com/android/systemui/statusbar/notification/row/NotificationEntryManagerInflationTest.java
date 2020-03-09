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

import static junit.framework.Assert.assertNotNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.Handler;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.ForegroundServiceDismissalFeatureController;
import com.android.systemui.statusbar.notification.NotificationClicker;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationEntryManagerLogger;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationRankingManager;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowComponent;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.SmartReplyConstants;
import com.android.systemui.util.leak.LeakDetector;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

/**
 * Functional tests for notification inflation from {@link NotificationEntryManager}.
 */
@SmallTest
@Ignore("Flaking")
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
    @Mock private NotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationGutsManager mGutsManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private NotificationMediaManager mNotificationMediaManager;
    @Mock private ExpandableNotificationRowComponent.Builder
            mExpandableNotificationRowComponentBuilder;
    @Mock private ExpandableNotificationRowComponent mExpandableNotificationRowComponent;
    @Mock private FalsingManager mFalsingManager;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private StatusBarStateController mStatusBarStateController;

    @Mock private NotificationGroupManager mGroupManager;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private LeakDetector mLeakDetector;

    @Mock private ActivatableNotificationViewController mActivatableNotificationViewController;
    @Mock private NotificationRowComponent.Builder mNotificationRowComponentBuilder;

    private StatusBarNotification mSbn;
    private NotificationListenerService.RankingMap mRankingMap;
    private NotificationEntryManager mEntryManager;
    private NotificationRowBinderImpl mRowBinder;
    private Handler mHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(SmartReplyController.class);

        mHandler = Handler.createAsync(TestableLooper.get(this).getLooper());

        Notification notification = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle(TEST_TITLE)
                .setContentText(TEST_TEXT)
                .build();
        mSbn = new SbnBuilder()
                .setNotification(notification)
                .build();

        when(mFeatureFlags.isNewNotifPipelineEnabled()).thenReturn(false);
        when(mFeatureFlags.isNewNotifPipelineRenderingEnabled()).thenReturn(false);

        mEntryManager = new NotificationEntryManager(
                mock(NotificationEntryManagerLogger.class),
                mGroupManager,
                new NotificationRankingManager(
                        () -> mock(NotificationMediaManager.class),
                        mGroupManager,
                        mHeadsUpManager,
                        mock(NotificationFilter.class),
                        mock(NotificationEntryManagerLogger.class),
                        mock(NotificationSectionsFeatureManager.class),
                        mock(PeopleNotificationIdentifier.class),
                        mock(HighPriorityProvider.class)),
                mEnvironment,
                mFeatureFlags,
                () -> mRowBinder,
                () -> mRemoteInputManager,
                mLeakDetector,
                mock(ForegroundServiceDismissalFeatureController.class)
        );

        NotifRemoteViewCache cache = new NotifRemoteViewCacheImpl(mEntryManager);
        NotifBindPipeline pipeline = new NotifBindPipeline(
                mEntryManager,
                mock(NotifBindPipelineLogger.class));
        NotificationContentInflater binder = new NotificationContentInflater(
                cache,
                mRemoteInputManager,
                () -> mock(SmartReplyConstants.class),
                () -> mock(SmartReplyController.class));
        RowContentBindStage stage = new RowContentBindStage(
                binder,
                mock(NotifInflationErrorManager.class),
                mock(RowContentBindStageLogger.class));
        pipeline.setStage(stage);

        ArgumentCaptor<ExpandableNotificationRow> viewCaptor =
                ArgumentCaptor.forClass(ExpandableNotificationRow.class);
        when(mExpandableNotificationRowComponentBuilder
                .expandableNotificationRow(viewCaptor.capture()))
                .thenReturn(mExpandableNotificationRowComponentBuilder);
        when(mExpandableNotificationRowComponentBuilder
                .notificationEntry(any()))
                .thenReturn(mExpandableNotificationRowComponentBuilder);
        when(mExpandableNotificationRowComponentBuilder
                .onDismissRunnable(any()))
                .thenReturn(mExpandableNotificationRowComponentBuilder);
        when(mExpandableNotificationRowComponentBuilder
                .inflationCallback(any()))
                .thenReturn(mExpandableNotificationRowComponentBuilder);
        when(mExpandableNotificationRowComponentBuilder
                .rowContentBindStage(any()))
                .thenReturn(mExpandableNotificationRowComponentBuilder);
        when(mExpandableNotificationRowComponentBuilder
                .onExpandClickListener(any()))
                .thenReturn(mExpandableNotificationRowComponentBuilder);

        when(mExpandableNotificationRowComponentBuilder.build())
                .thenReturn(mExpandableNotificationRowComponent);
        when(mExpandableNotificationRowComponent.getExpandableNotificationRowController())
                .thenAnswer((Answer<ExpandableNotificationRowController>) invocation ->
                        new ExpandableNotificationRowController(
                                viewCaptor.getValue(),
                                mock(ActivatableNotificationViewController.class),
                                mNotificationMediaManager,
                                mock(PluginManager.class),
                                new FakeSystemClock(),
                                "FOOBAR", "FOOBAR",
                                mKeyguardBypassController,
                                mGroupManager,
                                stage,
                                mock(NotificationLogger.class),
                                mHeadsUpManager,
                                mPresenter,
                                mStatusBarStateController,
                                mEntryManager,
                                mGutsManager,
                                true,
                                null,
                                mFalsingManager
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
                stage,
                true, /* allowLongPress */
                mock(KeyguardBypassController.class),
                mock(StatusBarStateController.class),
                mGroupManager,
                mGutsManager,
                mNotificationInterruptionStateProvider,
                RowInflaterTask::new,
                mExpandableNotificationRowComponentBuilder);

        mEntryManager.setUpWithPresenter(mPresenter);
        mEntryManager.addNotificationEntryListener(mEntryListener);

        mRowBinder.setUpWithPresenter(mPresenter, mListContainer, mBindCallback);
        mRowBinder.setInflationCallback(mEntryManager);
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
                0
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

        // Wait for inflation
        // row inflation, system notification, remote views, contracted view
        waitForMessages(4);

        // THEN the notification has its row inflated
        assertNotNull(entry.getRow());
        assertNotNull(entry.getRow().getPrivateLayout().getContractedChild());

        // THEN inflation callbacks are called
        verify(mBindCallback).onBindRow(eq(entry), any(), eq(mSbn), any());
        verify(mEntryListener, never()).onInflationError(any(), any());
        verify(mEntryListener).onEntryInflated(entry);
        verify(mEntryListener).onNotificationAdded(entry);

        // THEN the notification is active
        assertNotNull(mEntryManager.getActiveNotificationUnfiltered(mSbn.getKey()));

        // THEN we update the presenter
        verify(mPresenter).updateNotificationViews();
    }

    @Test
    public void testUpdateNotification() {
        // GIVEN a notification already added
        mEntryManager.addNotification(mSbn, mRankingMap);
        ArgumentCaptor<NotificationEntry> entryCaptor = ArgumentCaptor.forClass(
                NotificationEntry.class);
        verify(mEntryListener).onPendingEntryAdded(entryCaptor.capture());
        NotificationEntry entry = entryCaptor.getValue();
        waitForMessages(4);

        Mockito.reset(mEntryListener);
        Mockito.reset(mPresenter);

        // WHEN the notification is updated
        mEntryManager.updateNotification(mSbn, mRankingMap);

        // Wait for inflation
        // remote views, contracted view
        waitForMessages(2);

        // THEN the notification has its row and inflated
        assertNotNull(entry.getRow());

        // THEN inflation callbacks are called
        verify(mEntryListener, never()).onInflationError(any(), any());
        verify(mEntryListener).onEntryReinflated(entry);

        // THEN we update the presenter
        verify(mPresenter).updateNotificationViews();
    }

    /**
     * Wait for a certain number of messages to finish before continuing, timing out if they never
     * occur.
     *
     * As part of the inflation pipeline, the main thread is forced to deal with several callbacks
     * due to the nature of the API used (generally because they're {@link android.os.AsyncTask}
     * callbacks). In order, these are
     *
     * 1) Callback after row inflation. See {@link RowInflaterTask}.
     * 2) Callback checking if row is system notification. See
     *    {@link ExpandableNotificationRow#setEntry}
     * 3) Callback after remote views are created. See
     *    {@link NotificationContentInflater.AsyncInflationTask}.
     * 4-6) Callback after each content view is inflated/rebound from remote view. See
     *      {@link NotificationContentInflater#applyRemoteView} and {@link InflationFlag}.
     *
     * Depending on the test, only some of these will be necessary. For example, generally, not
     * every content view is inflated or the row may not be inflated if one already exists.
     *
     * Currently, the burden is on the developer to figure these out until we have a much more
     * test-friendly way of executing inflation logic (i.e. pass in an executor).
     */
    private void waitForMessages(int numMessages) {
        mHandler.postDelayed(TIMEOUT_RUNNABLE, TIMEOUT_TIME);
        TestableLooper.get(this).processMessages(numMessages);
        mHandler.removeCallbacks(TIMEOUT_RUNNABLE);
    }

}
