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

import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_FSI_REQUESTED_BUT_DENIED;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;

import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;
import static com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking;
import static com.android.systemui.util.Assert.runWithCurrentThreadAsMainThread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.Notification.BubbleMetadata;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Icon;
import android.os.Looper;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.TestableLooper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.TestScopeProvider;
import com.android.systemui.TestableDependency;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.controls.util.MediaFeatureFlag;
import com.android.systemui.media.dialog.MediaOutputDialogManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
import com.android.systemui.statusbar.notification.ConversationNotificationProcessor;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.icon.IconBuilder;
import com.android.systemui.statusbar.notification.icon.IconManager;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow.ExpandableNotificationRowLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow.OnExpandClickListener;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainerLogger;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.InflatedSmartReplyState;
import com.android.systemui.statusbar.policy.InflatedSmartReplyViewHolder;
import com.android.systemui.statusbar.policy.SmartReplyConstants;
import com.android.systemui.statusbar.policy.SmartReplyStateInflater;
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewSubcomponent;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.util.time.SystemClockImpl;
import com.android.systemui.wmshell.BubblesManager;
import com.android.systemui.wmshell.BubblesTestActivity;

import kotlin.coroutines.CoroutineContext;

import kotlinx.coroutines.test.TestScope;

import org.mockito.ArgumentCaptor;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * A helper class to create {@link ExpandableNotificationRow} (for both individual and group
 * notifications).
 */
public class NotificationTestHelper {

    /** Package name for testing purposes. */
    public static final String PKG = "com.android.systemui";
    /** System UI id for testing purposes. */
    public static final int UID = 1000;
    /** Current {@link UserHandle} of the system. */
    public static final UserHandle USER_HANDLE = UserHandle.of(ActivityManager.getCurrentUser());

    private static final String GROUP_KEY = "gruKey";
    private static final String APP_NAME = "appName";

    private final Context mContext;
    private final Runnable mBindPipelineAdvancement;
    private int mId;
    private final ExpandableNotificationRowLogger mMockLogger;
    private final GroupMembershipManager mGroupMembershipManager;
    private final GroupExpansionManager mGroupExpansionManager;
    private ExpandableNotificationRow mRow;
    private final HeadsUpManager mHeadsUpManager;
    private final NotifBindPipeline mBindPipeline;
    private final NotifCollectionListener mBindPipelineEntryListener;
    private final RowContentBindStage mBindStage;
    private final IconManager mIconManager;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    private final OnUserInteractionCallback mOnUserInteractionCallback;
    private final NotificationDismissibilityProvider mDismissibilityProvider;
    public final Runnable mFutureDismissalRunnable;
    private @InflationFlag int mDefaultInflationFlags;
    private final FakeFeatureFlags mFeatureFlags;
    private final SystemClock mSystemClock;
    private final RowInflaterTaskLogger mRowInflaterTaskLogger;
    private final TestScope mTestScope = TestScopeProvider.getTestScope();
    private final CoroutineContext mBgCoroutineContext =
            mTestScope.getBackgroundScope().getCoroutineContext();
    private final CoroutineContext mMainCoroutineContext = mTestScope.getCoroutineContext();

    public NotificationTestHelper(
            Context context,
            TestableDependency dependency) {
        this(context, dependency, null);
    }

    public NotificationTestHelper(
            Context context,
            TestableDependency dependency,
            @Nullable TestableLooper testLooper) {
        this(context, dependency, testLooper, new FakeFeatureFlags());
    }

    public NotificationTestHelper(
            Context context,
            TestableDependency dependency,
            @Nullable TestableLooper testLooper,
            @NonNull FakeFeatureFlags featureFlags) {
        mContext = context;
        mFeatureFlags = Objects.requireNonNull(featureFlags);
        dependency.injectTestDependency(FeatureFlags.class, mFeatureFlags);
        dependency.injectMockDependency(NotificationMediaManager.class);
        dependency.injectMockDependency(NotificationShadeWindowController.class);
        dependency.injectMockDependency(MediaOutputDialogManager.class);
        mMockLogger = mock(ExpandableNotificationRowLogger.class);
        mStatusBarStateController = mock(StatusBarStateController.class);
        mKeyguardBypassController = mock(KeyguardBypassController.class);
        mGroupMembershipManager = mock(GroupMembershipManager.class);
        mGroupExpansionManager = mock(GroupExpansionManager.class);
        mHeadsUpManager = mock(HeadsUpManager.class);
        mIconManager = new IconManager(
                mock(CommonNotifCollection.class),
                mock(LauncherApps.class),
                new IconBuilder(mContext),
                mTestScope,
                mBgCoroutineContext,
                mMainCoroutineContext);

        NotificationRowContentBinder contentBinder = new NotificationContentInflater(
                mock(NotifRemoteViewCache.class),
                mock(NotificationRemoteInputManager.class),
                mock(ConversationNotificationProcessor.class),
                mock(MediaFeatureFlag.class),
                mock(Executor.class),
                new MockSmartReplyInflater(),
                mock(NotifLayoutInflaterFactory.Provider.class),
                mock(HeadsUpStyleProvider.class),
                mock(NotificationRowContentBinderLogger.class));
        contentBinder.setInflateSynchronously(true);
        mBindStage = new RowContentBindStage(contentBinder,
                mock(NotifInflationErrorManager.class),
                new RowContentBindStageLogger(logcatLogBuffer()));

        CommonNotifCollection collection = mock(CommonNotifCollection.class);

        // NOTE: This helper supports using either a TestableLooper or its own private FakeExecutor.
        final Runnable processorAdvancement;
        final NotificationEntryProcessorFactory processorFactory;
        if (testLooper == null) {
            FakeExecutor fakeExecutor = new FakeExecutor(new FakeSystemClock());
            processorAdvancement = () -> {
                runWithCurrentThreadAsMainThread(fakeExecutor::runAllReady);
            };
            processorFactory = new NotificationEntryProcessorFactoryExecutorImpl(fakeExecutor);
        } else {
            Looper looper = testLooper.getLooper();
            processorAdvancement = () -> {
                runWithCurrentThreadAsMainThread(testLooper::processAllMessages);
            };
            processorFactory = new NotificationEntryProcessorFactoryLooperImpl(looper);
        }
        mBindPipelineAdvancement = processorAdvancement;
        mBindPipeline = new NotifBindPipeline(
                collection,
                new NotifBindPipelineLogger(logcatLogBuffer()),
                processorFactory);
        mBindPipeline.setStage(mBindStage);

        ArgumentCaptor<NotifCollectionListener> collectionListenerCaptor =
                ArgumentCaptor.forClass(NotifCollectionListener.class);
        verify(collection).addCollectionListener(collectionListenerCaptor.capture());
        mBindPipelineEntryListener = collectionListenerCaptor.getValue();
        mPeopleNotificationIdentifier = mock(PeopleNotificationIdentifier.class);
        mOnUserInteractionCallback = mock(OnUserInteractionCallback.class);
        mDismissibilityProvider = mock(NotificationDismissibilityProvider.class);
        mFutureDismissalRunnable = mock(Runnable.class);
        when(mOnUserInteractionCallback.registerFutureDismissal(any(), anyInt()))
                .thenReturn(mFutureDismissalRunnable);

        mSystemClock = new SystemClockImpl();
        mRowInflaterTaskLogger = mock(RowInflaterTaskLogger.class);
    }

    public void setDefaultInflationFlags(@InflationFlag int defaultInflationFlags) {
        mDefaultInflationFlags = defaultInflationFlags;
    }

    public ExpandableNotificationRowLogger getMockLogger() {
        return mMockLogger;
    }

    public OnUserInteractionCallback getOnUserInteractionCallback() {
        return mOnUserInteractionCallback;
    }

    public NotificationDismissibilityProvider getDismissibilityProvider() {
        return mDismissibilityProvider;
    }

    /**
     * Creates a generic row with rounded border.
     *
     * @return a generic row with the set roundness.
     * @throws Exception
     */
    public ExpandableNotificationRow createRowWithRoundness(
            float topRoundness,
            float bottomRoundness,
            SourceType sourceType
    ) throws Exception {
        ExpandableNotificationRow row = createRow();
        row.requestRoundness(topRoundness, bottomRoundness, sourceType, /*animate = */ false);
        assertEquals(topRoundness, row.getTopRoundness(), /* delta = */ 0f);
        assertEquals(bottomRoundness, row.getBottomRoundness(), /* delta = */ 0f);
        return row;
    }

    /**
     * Creates a generic row.
     *
     * @return a generic row with no special properties.
     * @throws Exception
     */
    public ExpandableNotificationRow createRow() throws Exception {
        return createRow(PKG, UID, USER_HANDLE);
    }

    /**
     * Create a row with the package and user id specified.
     *
     * @param pkg package
     * @param uid user id
     * @return a row with a notification using the package and user id
     * @throws Exception
     */
    public ExpandableNotificationRow createRow(String pkg, int uid, UserHandle userHandle)
            throws Exception {
        return createRow(pkg, uid, userHandle, false /* isGroupSummary */, null /* groupKey */);
    }

    /**
     * Creates a row based off the notification given.
     *
     * @param notification the notification
     * @return a row built off the notification
     * @throws Exception
     */
    public ExpandableNotificationRow createRow(Notification notification) throws Exception {
        return generateRow(notification, PKG, UID, USER_HANDLE, mDefaultInflationFlags);
    }

    public ExpandableNotificationRow createRow(NotificationEntry entry) throws Exception {
        return generateRow(entry, mDefaultInflationFlags);
    }

    /**
     * Create a row with the specified content views inflated in addition to the default.
     *
     * @param extraInflationFlags the flags corresponding to the additional content views that
     *                            should be inflated
     * @return a row with the specified content views inflated in addition to the default
     * @throws Exception
     */
    public ExpandableNotificationRow createRow(@InflationFlag int extraInflationFlags)
            throws Exception {
        return generateRow(createNotification(), PKG, UID, USER_HANDLE, extraInflationFlags);
    }

    /**
     * Returns an {@link ExpandableNotificationRow} group with the given number of child
     * notifications.
     */
    public ExpandableNotificationRow createGroup(int numChildren) throws Exception {
        ExpandableNotificationRow row = createGroupSummary(GROUP_KEY);
        for (int i = 0; i < numChildren; i++) {
            ExpandableNotificationRow childRow = createGroupChild(GROUP_KEY);
            row.addChildNotification(childRow);
        }
        return row;
    }

    /** Returns a group notification with 2 child notifications. */
    public ExpandableNotificationRow createGroup() throws Exception {
        return createGroup(2);
    }

    private ExpandableNotificationRow createGroupSummary(String groupkey) throws Exception {
        return createRow(PKG, UID, USER_HANDLE, true /* isGroupSummary */, groupkey);
    }

    private ExpandableNotificationRow createGroupChild(String groupkey) throws Exception {
        return createRow(PKG, UID, USER_HANDLE, false /* isGroupSummary */, groupkey);
    }

    /**
     * Returns an {@link ExpandableNotificationRow} that should be shown as a bubble.
     */
    public ExpandableNotificationRow createBubble()
            throws Exception {
        Notification n = createNotification(false /* isGroupSummary */,
                null /* groupKey */,
                makeBubbleMetadata(null /* deleteIntent */, false /* autoExpand */));
        n.flags |= FLAG_BUBBLE;
        ExpandableNotificationRow row = generateRow(n, PKG, UID, USER_HANDLE,
                mDefaultInflationFlags, IMPORTANCE_HIGH);
        modifyRanking(row.getEntry())
                .setCanBubble(true)
                .build();
        return row;
    }

    /**
     * Returns an {@link ExpandableNotificationRow} that shows as a sticky FSI HUN.
     */
    public ExpandableNotificationRow createStickyRow()
            throws Exception {
        Notification n = createNotification(false /* isGroupSummary */,
                null /* groupKey */,
                makeBubbleMetadata(null /* deleteIntent */, false /* autoExpand */));
        n.flags |= FLAG_FSI_REQUESTED_BUT_DENIED;
        ExpandableNotificationRow row = generateRow(n, PKG, UID, USER_HANDLE,
                mDefaultInflationFlags, IMPORTANCE_HIGH);
        return row;
    }


    /**
     * Returns an {@link ExpandableNotificationRow} that should be shown as a bubble.
     */
    public ExpandableNotificationRow createShortcutBubble(String shortcutId)
            throws Exception {
        Notification n = createNotification(false /* isGroupSummary */,
                null /* groupKey */, makeShortcutBubbleMetadata(shortcutId));
        n.flags |= FLAG_BUBBLE;
        ExpandableNotificationRow row = generateRow(n, PKG, UID, USER_HANDLE,
                mDefaultInflationFlags, IMPORTANCE_HIGH);
        modifyRanking(row.getEntry())
                .setCanBubble(true)
                .build();
        return row;
    }

    /**
     * Returns an {@link ExpandableNotificationRow} that should be shown as a bubble and is part
     * of a group of notifications.
     */
    public ExpandableNotificationRow createBubbleInGroup()
            throws Exception {
        Notification n = createNotification(false /* isGroupSummary */,
                GROUP_KEY /* groupKey */,
                makeBubbleMetadata(null /* deleteIntent */, false /* autoExpand */));
        n.flags |= FLAG_BUBBLE;
        ExpandableNotificationRow row = generateRow(n, PKG, UID, USER_HANDLE,
                mDefaultInflationFlags, IMPORTANCE_HIGH);
        modifyRanking(row.getEntry())
                .setCanBubble(true)
                .build();
        return row;
    }

    /**
     * Returns an {@link NotificationEntry} that should be shown as a bubble.
     *
     * @param deleteIntent the intent to assign to {@link BubbleMetadata#deleteIntent}
     */
    public NotificationEntry createBubble(@Nullable PendingIntent deleteIntent) {
        return createBubble(makeBubbleMetadata(deleteIntent, false /* autoExpand */), USER_HANDLE);
    }

    /**
     * Returns an {@link NotificationEntry} that should be shown as a bubble.
     *
     * @param handle the user to associate with this bubble.
     */
    public NotificationEntry createBubble(UserHandle handle) {
        return createBubble(makeBubbleMetadata(null /* deleteIntent */, false /* autoExpand */),
                handle);
    }

    /**
     * Returns an {@link NotificationEntry} that should be shown as a auto-expanded bubble.
     */
    public NotificationEntry createAutoExpandedBubble() {
        return createBubble(makeBubbleMetadata(null /* deleteIntent */, true /* autoExpand */),
                USER_HANDLE);
    }

    /**
     * Returns an {@link NotificationEntry} that should be shown as a bubble.
     *
     * @param userHandle the user to associate with this notification.
     */
    private NotificationEntry createBubble(BubbleMetadata metadata, UserHandle userHandle) {
        Notification n = createNotification(false /* isGroupSummary */, null /* groupKey */,
                metadata);
        n.flags |= FLAG_BUBBLE;

        final NotificationChannel channel =
                new NotificationChannel(
                        n.getChannelId(),
                        n.getChannelId(),
                        IMPORTANCE_HIGH);
        channel.setBlockable(true);

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg(PKG)
                .setOpPkg(PKG)
                .setId(mId++)
                .setUid(UID)
                .setInitialPid(2000)
                .setNotification(n)
                .setUser(userHandle)
                .setPostTime(System.currentTimeMillis())
                .setChannel(channel)
                .build();

        modifyRanking(entry)
                .setCanBubble(true)
                .build();
        return entry;
    }

    /**
     * Creates a notification row with the given details.
     *
     * @param pkg package used for creating a {@link StatusBarNotification}
     * @param uid uid used for creating a {@link StatusBarNotification}
     * @param isGroupSummary whether the notification row is a group summary
     * @param groupKey the group key for the notification group used across notifications
     * @return a row with that's either a standalone notification or a group notification if the
     *         groupKey is non-null
     * @throws Exception
     */
    private ExpandableNotificationRow createRow(
            String pkg,
            int uid,
            UserHandle userHandle,
            boolean isGroupSummary,
            @Nullable String groupKey)
            throws Exception {
        Notification notif = createNotification(isGroupSummary, groupKey);
        return generateRow(notif, pkg, uid, userHandle, mDefaultInflationFlags);
    }

    /**
     * Creates a generic notification.
     *
     * @return a notification with no special properties
     */
    public Notification createNotification() {
        return createNotification(false /* isGroupSummary */, null /* groupKey */);
    }

    /**
     * Creates a notification with the given parameters.
     *
     * @param isGroupSummary whether the notification is a group summary
     * @param groupKey the group key for the notification group used across notifications
     * @return a notification that is in the group specified or standalone if unspecified
     */
    private Notification createNotification(boolean isGroupSummary, @Nullable String groupKey) {
        return createNotification(isGroupSummary, groupKey, null /* bubble metadata */);
    }

    /**
     * Creates a notification with the given parameters.
     *
     * @param isGroupSummary whether the notification is a group summary
     * @param groupKey the group key for the notification group used across notifications
     * @param bubbleMetadata the bubble metadata to use for this notification if it exists.
     * @return a notification that is in the group specified or standalone if unspecified
     */
    public Notification createNotification(boolean isGroupSummary,
            @Nullable String groupKey, @Nullable BubbleMetadata bubbleMetadata) {
        Notification publicVersion = new Notification.Builder(mContext).setSmallIcon(
                R.drawable.ic_person)
                .setCustomContentView(new RemoteViews(mContext.getPackageName(),
                        com.android.systemui.tests.R.layout.custom_view_dark))
                .build();
        Notification.Builder notificationBuilder = new Notification.Builder(mContext, "channelId")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .setPublicVersion(publicVersion)
                .setStyle(new Notification.BigTextStyle().bigText("Big Text"));
        if (isGroupSummary) {
            notificationBuilder.setGroupSummary(true);
        }
        if (!TextUtils.isEmpty(groupKey)) {
            notificationBuilder.setGroup(groupKey);
        }
        if (bubbleMetadata != null) {
            notificationBuilder.setBubbleMetadata(bubbleMetadata);
        }
        return notificationBuilder.build();
    }

    public StatusBarStateController getStatusBarStateController() {
        return mStatusBarStateController;
    }

    public KeyguardBypassController getKeyguardBypassController() {
        return mKeyguardBypassController;
    }

    private ExpandableNotificationRow generateRow(
            Notification notification,
            String pkg,
            int uid,
            UserHandle userHandle,
            @InflationFlag int extraInflationFlags)
            throws Exception {
        return generateRow(notification, pkg, uid, userHandle, extraInflationFlags,
                IMPORTANCE_DEFAULT);
    }

    private ExpandableNotificationRow generateRow(
            Notification notification,
            String pkg,
            int uid,
            UserHandle userHandle,
            @InflationFlag int extraInflationFlags,
            int importance)
            throws Exception {
        final NotificationChannel channel =
                new NotificationChannel(
                        notification.getChannelId(),
                        notification.getChannelId(),
                        importance);
        channel.setBlockable(true);

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg(pkg)
                .setOpPkg(pkg)
                .setId(mId++)
                .setUid(uid)
                .setInitialPid(2000)
                .setNotification(notification)
                .setUser(userHandle)
                .setPostTime(System.currentTimeMillis())
                .setChannel(channel)
                .build();

        return generateRow(entry, extraInflationFlags);
    }

    private ExpandableNotificationRow generateRow(
            NotificationEntry entry,
            @InflationFlag int extraInflationFlags)
            throws Exception {

        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.setFactory2(new RowInflaterTask.RowAsyncLayoutInflater(entry, mSystemClock,
                mRowInflaterTaskLogger));
        mRow = (ExpandableNotificationRow) inflater.inflate(
                R.layout.status_bar_notification_row,
                null /* root */,
                false /* attachToRoot */);
        ExpandableNotificationRow row = mRow;

        entry.setRow(row);
        mIconManager.createIcons(entry);

        mBindPipelineEntryListener.onEntryInit(entry);
        mBindPipeline.manageRow(entry, row);

        row.initialize(
                entry,
                mock(RemoteInputViewSubcomponent.Factory.class),
                APP_NAME,
                entry.getKey(),
                mMockLogger,
                mKeyguardBypassController,
                mGroupMembershipManager,
                mGroupExpansionManager,
                mHeadsUpManager,
                mBindStage,
                mock(OnExpandClickListener.class),
                mock(ExpandableNotificationRow.CoordinateOnClickListener.class),
                new FalsingManagerFake(),
                mStatusBarStateController,
                mPeopleNotificationIdentifier,
                mOnUserInteractionCallback,
                Optional.of(mock(BubblesManager.class)),
                mock(NotificationGutsManager.class),
                mDismissibilityProvider,
                mock(MetricsLogger.class),
                new NotificationChildrenContainerLogger(logcatLogBuffer()),
                mock(ColorUpdateLogger.class),
                mock(SmartReplyConstants.class),
                mock(SmartReplyController.class),
                mFeatureFlags,
                mock(IStatusBarService.class));

        row.setAboveShelfChangedListener(aboveShelf -> { });
        mBindStage.getStageParams(entry).requireContentViews(extraInflationFlags);
        inflateAndWait(entry);

        return row;
    }

    private void inflateAndWait(NotificationEntry entry) throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        mBindStage.requestRebind(entry, en -> countDownLatch.countDown());
        mBindPipelineAdvancement.run();
        assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS));
    }

    private BubbleMetadata makeBubbleMetadata(PendingIntent deleteIntent, boolean autoExpand) {
        Intent target = new Intent(mContext, BubblesTestActivity.class);
        PendingIntent bubbleIntent = PendingIntent.getActivity(mContext, 0, target,
                PendingIntent.FLAG_MUTABLE);

        return new BubbleMetadata.Builder(bubbleIntent,
                        Icon.createWithResource(mContext, R.drawable.android))
                .setDeleteIntent(deleteIntent)
                .setDesiredHeight(314)
                .setAutoExpandBubble(autoExpand)
                .build();
    }

    private BubbleMetadata makeShortcutBubbleMetadata(String shortcutId) {
        return new BubbleMetadata.Builder(shortcutId)
                .setDesiredHeight(314)
                .build();
    }

    private static class MockSmartReplyInflater implements SmartReplyStateInflater {
        @Override
        public InflatedSmartReplyState inflateSmartReplyState(NotificationEntry entry) {
            return mock(InflatedSmartReplyState.class);
        }

        @Override
        public InflatedSmartReplyViewHolder inflateSmartReplyViewHolder(Context sysuiContext,
                Context notifPackageContext, NotificationEntry entry,
                InflatedSmartReplyState existingSmartReplyState,
                InflatedSmartReplyState newSmartReplyState) {
            return mock(InflatedSmartReplyViewHolder.class);
        }
    }
}
