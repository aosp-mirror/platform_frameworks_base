/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.systemui.statusbar.phone.StatusBar.getActivityOptions;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.EventLog;
import android.view.View;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.EventLogTags;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationClickNotifier;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowDragController;
import com.android.systemui.statusbar.notification.row.OnUserInteractionCallback;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.wmshell.BubblesManager;

import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Status bar implementation of {@link NotificationActivityStarter}.
 */
public class StatusBarNotificationActivityStarter implements NotificationActivityStarter {

    private final Context mContext;

    private final CommandQueue mCommandQueue;
    private final Handler mMainThreadHandler;
    private final Executor mUiBgExecutor;

    private final NotificationEntryManager mEntryManager;
    private final NotifPipeline mNotifPipeline;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final ActivityStarter mActivityStarter;
    private final NotificationClickNotifier mClickNotifier;
    private final StatusBarStateController mStatusBarStateController;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final KeyguardManager mKeyguardManager;
    private final IDreamManager mDreamManager;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final GroupMembershipManager mGroupMembershipManager;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final ShadeController mShadeController;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final LockPatternUtils mLockPatternUtils;
    private final StatusBarRemoteInputCallback mStatusBarRemoteInputCallback;
    private final ActivityIntentHelper mActivityIntentHelper;

    private final FeatureFlags mFeatureFlags;
    private final MetricsLogger mMetricsLogger;
    private final StatusBarNotificationActivityStarterLogger mLogger;

    private final StatusBar mStatusBar;
    private final NotificationPresenter mPresenter;
    private final NotificationPanelViewController mNotificationPanel;
    private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private final NotificationLaunchAnimatorControllerProvider mNotificationAnimationProvider;
    private final OnUserInteractionCallback mOnUserInteractionCallback;

    private boolean mIsCollapsingToShowActivityOverLockscreen;

    private StatusBarNotificationActivityStarter(
            Context context,
            CommandQueue commandQueue,
            Handler mainThreadHandler,
            Executor uiBgExecutor,
            NotificationEntryManager entryManager,
            NotifPipeline notifPipeline,
            HeadsUpManagerPhone headsUpManager,
            ActivityStarter activityStarter,
            NotificationClickNotifier clickNotifier,
            StatusBarStateController statusBarStateController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            KeyguardManager keyguardManager,
            IDreamManager dreamManager,
            Optional<BubblesManager> bubblesManagerOptional,
            Lazy<AssistManager> assistManagerLazy,
            NotificationRemoteInputManager remoteInputManager,
            GroupMembershipManager groupMembershipManager,
            NotificationLockscreenUserManager lockscreenUserManager,
            ShadeController shadeController,
            KeyguardStateController keyguardStateController,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            LockPatternUtils lockPatternUtils,
            StatusBarRemoteInputCallback remoteInputCallback,
            ActivityIntentHelper activityIntentHelper,

            FeatureFlags featureFlags,
            MetricsLogger metricsLogger,
            StatusBarNotificationActivityStarterLogger logger,
            OnUserInteractionCallback onUserInteractionCallback,

            StatusBar statusBar,
            NotificationPresenter presenter,
            NotificationPanelViewController panel,
            ActivityLaunchAnimator activityLaunchAnimator,
            NotificationLaunchAnimatorControllerProvider notificationAnimationProvider) {
        mContext = context;
        mCommandQueue = commandQueue;
        mMainThreadHandler = mainThreadHandler;
        mUiBgExecutor = uiBgExecutor;
        mEntryManager = entryManager;
        mNotifPipeline = notifPipeline;
        mHeadsUpManager = headsUpManager;
        mActivityStarter = activityStarter;
        mClickNotifier = clickNotifier;
        mStatusBarStateController = statusBarStateController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardManager = keyguardManager;
        mDreamManager = dreamManager;
        mBubblesManagerOptional = bubblesManagerOptional;
        mAssistManagerLazy = assistManagerLazy;
        mRemoteInputManager = remoteInputManager;
        mGroupMembershipManager = groupMembershipManager;
        mLockscreenUserManager = lockscreenUserManager;
        mShadeController = shadeController;
        mKeyguardStateController = keyguardStateController;
        mNotificationInterruptStateProvider = notificationInterruptStateProvider;
        mLockPatternUtils = lockPatternUtils;
        mStatusBarRemoteInputCallback = remoteInputCallback;
        mActivityIntentHelper = activityIntentHelper;

        mFeatureFlags = featureFlags;
        mMetricsLogger = metricsLogger;
        mLogger = logger;
        mOnUserInteractionCallback = onUserInteractionCallback;

        // TODO: use KeyguardStateController#isOccluded to remove this dependency
        mStatusBar = statusBar;
        mPresenter = presenter;
        mNotificationPanel = panel;
        mActivityLaunchAnimator = activityLaunchAnimator;
        mNotificationAnimationProvider = notificationAnimationProvider;

        if (!mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
            mEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
                @Override
                public void onPendingEntryAdded(NotificationEntry entry) {
                    handleFullScreenIntent(entry);
                }
            });
        } else {
            mNotifPipeline.addCollectionListener(new NotifCollectionListener() {
                @Override
                public void onEntryAdded(NotificationEntry entry) {
                    handleFullScreenIntent(entry);
                }
            });
        }
    }

    /**
     * Called when a notification is clicked.
     *
     * @param sbn notification that was clicked
     * @param row row for that notification
     */
    @Override
    public void onNotificationClicked(StatusBarNotification sbn, ExpandableNotificationRow row) {
        mLogger.logStartingActivityFromClick(sbn.getKey());

        final NotificationEntry entry = row.getEntry();
        if (mRemoteInputManager.isRemoteInputActive(entry)
                && !TextUtils.isEmpty(row.getActiveRemoteInputText())) {
            // We have an active remote input typed and the user clicked on the notification.
            // this was probably unintentional, so we're closing the edit text instead.
            mRemoteInputManager.closeRemoteInputs();
            return;
        }
        Notification notification = sbn.getNotification();
        final PendingIntent intent = notification.contentIntent != null
                ? notification.contentIntent
                : notification.fullScreenIntent;
        final boolean isBubble = entry.isBubble();

        // This code path is now executed for notification without a contentIntent.
        // The only valid case is Bubble notifications. Guard against other cases
        // entering here.
        if (intent == null && !isBubble) {
            mLogger.logNonClickableNotification(sbn.getKey());
            return;
        }

        boolean isActivityIntent = intent != null && intent.isActivity() && !isBubble;
        final boolean willLaunchResolverActivity = isActivityIntent
                && mActivityIntentHelper.wouldLaunchResolverActivity(intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());
        final boolean animate = !willLaunchResolverActivity
                && mStatusBar.shouldAnimateLaunch(isActivityIntent);
        boolean showOverLockscreen = mKeyguardStateController.isShowing() && intent != null
                && mActivityIntentHelper.wouldShowOverLockscreen(intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());
        ActivityStarter.OnDismissAction postKeyguardAction = new ActivityStarter.OnDismissAction() {
            @Override
            public boolean onDismiss() {
                return handleNotificationClickAfterKeyguardDismissed(
                        entry, row, intent, isActivityIntent, animate, showOverLockscreen);
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return animate;
            }
        };
        if (showOverLockscreen) {
            mIsCollapsingToShowActivityOverLockscreen = true;
            postKeyguardAction.onDismiss();
        } else {
            mActivityStarter.dismissKeyguardThenExecute(
                    postKeyguardAction, null /* cancel */, willLaunchResolverActivity);
        }
    }

    private boolean handleNotificationClickAfterKeyguardDismissed(
            NotificationEntry entry,
            ExpandableNotificationRow row,
            PendingIntent intent,
            boolean isActivityIntent,
            boolean animate,
            boolean showOverLockscreen) {
        mLogger.logHandleClickAfterKeyguardDismissed(entry.getKey());

        final Runnable runnable = () -> handleNotificationClickAfterPanelCollapsed(
                entry, row, intent, isActivityIntent, animate);

        if (showOverLockscreen) {
            mShadeController.addPostCollapseAction(runnable);
            mShadeController.collapsePanel(true /* animate */);
        } else if (mKeyguardStateController.isShowing()
                && mStatusBar.isOccluded()) {
            mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
            mShadeController.collapsePanel();
        } else {
            runnable.run();
        }

        // Always defer the keyguard dismiss when animating.
        return animate || !mNotificationPanel.isFullyCollapsed();
    }

    private void handleNotificationClickAfterPanelCollapsed(
            NotificationEntry entry,
            ExpandableNotificationRow row,
            PendingIntent intent,
            boolean isActivityIntent,
            boolean animate) {
        String notificationKey = entry.getKey();
        mLogger.logHandleClickAfterPanelCollapsed(notificationKey);

        try {
            // The intent we are sending is for the application, which
            // won't have permission to immediately start an activity after
            // the user switches to home.  We know it is safe to do at this
            // point, so make sure new activity switches are now allowed.
            ActivityManager.getService().resumeAppSwitches();
        } catch (RemoteException e) {
        }
        // If we are launching a work activity and require to launch
        // separate work challenge, we defer the activity action and cancel
        // notification until work challenge is unlocked.
        if (isActivityIntent) {
            final int userId = intent.getCreatorUserHandle().getIdentifier();
            if (mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)
                    && mKeyguardManager.isDeviceLocked(userId)) {
                // TODO(b/28935539): should allow certain activities to
                // bypass work challenge
                if (mStatusBarRemoteInputCallback.startWorkChallengeIfNecessary(userId,
                        intent.getIntentSender(), notificationKey)) {
                    removeHunAfterClick(row);
                    // Show work challenge, do not run PendingIntent and
                    // remove notification
                    collapseOnMainThread();
                    return;
                }
            }
        }
        Intent fillInIntent = null;
        CharSequence remoteInputText = null;
        if (!TextUtils.isEmpty(entry.remoteInputText)) {
            remoteInputText = entry.remoteInputText;
        }
        if (!TextUtils.isEmpty(remoteInputText)
                && !mRemoteInputManager.isSpinning(notificationKey)) {
            fillInIntent = new Intent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT,
                    remoteInputText.toString());
        }
        final boolean canBubble = entry.canBubble();
        if (canBubble) {
            mLogger.logExpandingBubble(notificationKey);
            removeHunAfterClick(row);
            expandBubbleStackOnMainThread(entry);
        } else {
            startNotificationIntent(intent, fillInIntent, entry, row, animate, isActivityIntent);
        }
        if (isActivityIntent || canBubble) {
            mAssistManagerLazy.get().hideAssist();
        }

        NotificationVisibility.NotificationLocation location =
                NotificationLogger.getNotificationLocation(entry);
        final NotificationVisibility nv = NotificationVisibility.obtain(entry.getKey(),
                entry.getRanking().getRank(), getVisibleNotificationsCount(), true, location);

        // retrieve the group summary to remove with this entry before we tell NMS the
        // notification was clicked to avoid a race condition
        final boolean shouldAutoCancel = shouldAutoCancel(entry.getSbn());
        final NotificationEntry summaryToRemove = shouldAutoCancel
                ? mOnUserInteractionCallback.getGroupSummaryToDismiss(entry) : null;

        // inform NMS that the notification was clicked
        mClickNotifier.onNotificationClick(notificationKey, nv);

        if (!canBubble) {
            if (shouldAutoCancel || mRemoteInputManager.isNotificationKeptForRemoteInputHistory(
                    notificationKey)) {
                // Immediately remove notification from visually showing.
                // We have to post the removal to the UI thread for synchronization.
                mMainThreadHandler.post(() -> {
                    final Runnable removeNotification = () ->
                            mOnUserInteractionCallback.onDismiss(
                                    entry, REASON_CLICK, summaryToRemove);
                    if (mPresenter.isCollapsing()) {
                        // To avoid lags we're only performing the remove
                        // after the shade is collapsed
                        mShadeController.addPostCollapseAction(removeNotification);
                    } else {
                        removeNotification.run();
                    }
                });
            }
        }

        mIsCollapsingToShowActivityOverLockscreen = false;
    }

    /**
     * Called when a notification is dropped on proper target window.
     * Intent that is included in this entry notification,
     * will be sent by {@link ExpandableNotificationRowDragController}
     *
     * @param entry notification entry that is dropped.
     */
    @Override
    public void onDragSuccess(NotificationEntry entry) {
        // this method is not responsible for intent sending.
        // will focus follow operation only after drag-and-drop that notification.
        NotificationVisibility.NotificationLocation location =
                NotificationLogger.getNotificationLocation(entry);
        final NotificationVisibility nv = NotificationVisibility.obtain(entry.getKey(),
                entry.getRanking().getRank(), getVisibleNotificationsCount(), true, location);

        // retrieve the group summary to remove with this entry before we tell NMS the
        // notification was clicked to avoid a race condition
        final boolean shouldAutoCancel = shouldAutoCancel(entry.getSbn());
        final NotificationEntry summaryToRemove = shouldAutoCancel
                ? mOnUserInteractionCallback.getGroupSummaryToDismiss(entry) : null;

        String notificationKey = entry.getKey();
        // inform NMS that the notification was clicked
        mClickNotifier.onNotificationClick(notificationKey, nv);

        if (shouldAutoCancel || mRemoteInputManager.isNotificationKeptForRemoteInputHistory(
                notificationKey)) {
            // Immediately remove notification from visually showing.
            // We have to post the removal to the UI thread for synchronization.
            mMainThreadHandler.post(() -> {
                final Runnable removeNotification = () ->
                        mOnUserInteractionCallback.onDismiss(
                                entry, REASON_CLICK, summaryToRemove);
                if (mPresenter.isCollapsing()) {
                    // To avoid lags we're only performing the remove
                    // after the shade is collapsed
                    mShadeController.addPostCollapseAction(removeNotification);
                } else {
                    removeNotification.run();
                }
            });
        }

        mIsCollapsingToShowActivityOverLockscreen = false;
    }

    private void expandBubbleStackOnMainThread(NotificationEntry entry) {
        if (!mBubblesManagerOptional.isPresent()) {
            return;
        }

        if (Looper.getMainLooper().isCurrentThread()) {
            expandBubbleStack(entry);
        } else {
            mMainThreadHandler.post(() -> expandBubbleStack(entry));
        }
    }

    private void expandBubbleStack(NotificationEntry entry) {
        mBubblesManagerOptional.get().expandStackAndSelectBubble(entry);
        mShadeController.collapsePanel();
    }

    private void startNotificationIntent(
            PendingIntent intent,
            Intent fillInIntent,
            NotificationEntry entry,
            ExpandableNotificationRow row,
            boolean animate,
            boolean isActivityIntent) {
        mLogger.logStartNotificationIntent(entry.getKey(), intent);
        try {
            ActivityLaunchAnimator.Controller animationController =
                    new StatusBarLaunchAnimatorController(
                            mNotificationAnimationProvider.getAnimatorController(row), mStatusBar,
                            isActivityIntent);

            mActivityLaunchAnimator.startPendingIntentWithAnimation(animationController,
                    animate, intent.getCreatorPackage(), (adapter) -> {
                        long eventTime = row.getAndResetLastActionUpTime();
                        Bundle options = eventTime > 0
                                ? getActivityOptions(
                                mStatusBar.getDisplayId(),
                                adapter,
                                mKeyguardStateController.isShowing(),
                                eventTime)
                                : getActivityOptions(mStatusBar.getDisplayId(), adapter);
                        return intent.sendAndReturnResult(mContext, 0, fillInIntent, null,
                                null, null, options);
                    });
        } catch (PendingIntent.CanceledException e) {
            // the stack trace isn't very helpful here.
            // Just log the exception message.
            mLogger.logSendingIntentFailed(e);
            // TODO: Dismiss Keyguard.
        }
    }

    @Override
    public void startNotificationGutsIntent(final Intent intent, final int appUid,
            ExpandableNotificationRow row) {
        boolean animate = mStatusBar.shouldAnimateLaunch(true /* isActivityIntent */);
        ActivityStarter.OnDismissAction onDismissAction = new ActivityStarter.OnDismissAction() {
            @Override
            public boolean onDismiss() {
                AsyncTask.execute(() -> {
                    ActivityLaunchAnimator.Controller animationController =
                            new StatusBarLaunchAnimatorController(
                                    mNotificationAnimationProvider.getAnimatorController(row),
                                    mStatusBar, true /* isActivityIntent */);

                    mActivityLaunchAnimator.startIntentWithAnimation(
                            animationController, animate, intent.getPackage(),
                            (adapter) -> TaskStackBuilder.create(mContext)
                                    .addNextIntentWithParentStack(intent)
                                    .startActivities(getActivityOptions(
                                            mStatusBar.getDisplayId(),
                                            adapter),
                                            new UserHandle(UserHandle.getUserId(appUid))));
                });
                return true;
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return animate;
            }
        };
        mActivityStarter.dismissKeyguardThenExecute(onDismissAction, null,
                false /* afterKeyguardGone */);
    }

    @Override
    public void startHistoryIntent(View view, boolean showHistory) {
        boolean animate = mStatusBar.shouldAnimateLaunch(true /* isActivityIntent */);
        ActivityStarter.OnDismissAction onDismissAction = new ActivityStarter.OnDismissAction() {
            @Override
            public boolean onDismiss() {
                AsyncTask.execute(() -> {
                    Intent intent = showHistory ? new Intent(
                            Settings.ACTION_NOTIFICATION_HISTORY) : new Intent(
                            Settings.ACTION_NOTIFICATION_SETTINGS);
                    TaskStackBuilder tsb = TaskStackBuilder.create(mContext)
                            .addNextIntent(new Intent(Settings.ACTION_NOTIFICATION_SETTINGS));
                    if (showHistory) {
                        tsb.addNextIntent(intent);
                    }

                    ActivityLaunchAnimator.Controller viewController =
                            ActivityLaunchAnimator.Controller.fromView(view,
                                    InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON
                            );
                    ActivityLaunchAnimator.Controller animationController =
                            viewController == null ? null
                                : new StatusBarLaunchAnimatorController(viewController, mStatusBar,
                                    true /* isActivityIntent */);

                    mActivityLaunchAnimator.startIntentWithAnimation(animationController, animate,
                            intent.getPackage(),
                            (adapter) -> tsb.startActivities(
                                    getActivityOptions(mStatusBar.getDisplayId(), adapter),
                                    UserHandle.CURRENT));
                });
                return true;
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return animate;
            }
        };
        mActivityStarter.dismissKeyguardThenExecute(onDismissAction, null,
                false /* afterKeyguardGone */);
    }

    private void removeHunAfterClick(ExpandableNotificationRow row) {
        String key = row.getEntry().getSbn().getKey();
        if (mHeadsUpManager != null && mHeadsUpManager.isAlerting(key)) {
            // Release the HUN notification to the shade.
            if (mPresenter.isPresenterFullyCollapsed()) {
                HeadsUpUtil.setNeedsHeadsUpDisappearAnimationAfterClick(row, true);
            }

            // In most cases, when FLAG_AUTO_CANCEL is set, the notification will
            // become canceled shortly by NoMan, but we can't assume that.
            mHeadsUpManager.removeNotification(key, true /* releaseImmediately */);
        }
    }

    private void handleFullScreenIntent(NotificationEntry entry) {
        if (mNotificationInterruptStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry)) {
            if (shouldSuppressFullScreenIntent(entry)) {
                mLogger.logFullScreenIntentSuppressedByDnD(entry.getKey());
            } else if (entry.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                mLogger.logFullScreenIntentNotImportantEnough(entry.getKey());
            } else {
                // Stop screensaver if the notification has a fullscreen intent.
                // (like an incoming phone call)
                mUiBgExecutor.execute(() -> {
                    try {
                        mDreamManager.awaken();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                });

                // not immersive & a fullscreen alert should be shown
                final PendingIntent fullscreenIntent =
                        entry.getSbn().getNotification().fullScreenIntent;
                mLogger.logSendingFullScreenIntent(entry.getKey(), fullscreenIntent);
                try {
                    EventLog.writeEvent(EventLogTags.SYSUI_FULLSCREEN_NOTIFICATION,
                            entry.getKey());
                    fullscreenIntent.send();
                    entry.notifyFullScreenIntentLaunched();
                    mMetricsLogger.count("note_fullscreen", 1);
                } catch (PendingIntent.CanceledException e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public boolean isCollapsingToShowActivityOverLockscreen() {
        return mIsCollapsingToShowActivityOverLockscreen;
    }

    private static boolean shouldAutoCancel(StatusBarNotification sbn) {
        int flags = sbn.getNotification().flags;
        if ((flags & Notification.FLAG_AUTO_CANCEL) != Notification.FLAG_AUTO_CANCEL) {
            return false;
        }
        if ((flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return false;
        }
        return true;
    }

    private void collapseOnMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            mShadeController.collapsePanel();
        } else {
            mMainThreadHandler.post(mShadeController::collapsePanel);
        }
    }

    private boolean shouldSuppressFullScreenIntent(NotificationEntry entry) {
        if (mPresenter.isDeviceInVrMode()) {
            return true;
        }

        return entry.shouldSuppressFullScreenIntent();
    }

    // --------------------- NotificationEntryManager/NotifPipeline methods ------------------------

    private int getVisibleNotificationsCount() {
        if (mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
            return mNotifPipeline.getShadeListCount();
        } else {
            return mEntryManager.getActiveNotificationsCount();
        }
    }

    /**
     * Public builder for {@link StatusBarNotificationActivityStarter}.
     */
    @SysUISingleton
    public static class Builder {
        private final Context mContext;
        private final CommandQueue mCommandQueue;
        private final Handler mMainThreadHandler;

        private final Executor mUiBgExecutor;
        private final NotificationEntryManager mEntryManager;
        private final NotifPipeline mNotifPipeline;
        private final HeadsUpManagerPhone mHeadsUpManager;
        private final ActivityStarter mActivityStarter;
        private final NotificationClickNotifier mClickNotifier;
        private final StatusBarStateController mStatusBarStateController;
        private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
        private final KeyguardManager mKeyguardManager;
        private final IDreamManager mDreamManager;
        private final Optional<BubblesManager> mBubblesManagerOptional;
        private final Lazy<AssistManager> mAssistManagerLazy;
        private final NotificationRemoteInputManager mRemoteInputManager;
        private final GroupMembershipManager mGroupMembershipManager;
        private final NotificationLockscreenUserManager mLockscreenUserManager;
        private final ShadeController mShadeController;
        private final KeyguardStateController mKeyguardStateController;
        private final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
        private final LockPatternUtils mLockPatternUtils;
        private final StatusBarRemoteInputCallback mRemoteInputCallback;
        private final ActivityIntentHelper mActivityIntentHelper;

        private final FeatureFlags mFeatureFlags;
        private final MetricsLogger mMetricsLogger;
        private final StatusBarNotificationActivityStarterLogger mLogger;
        private final OnUserInteractionCallback mOnUserInteractionCallback;

        private StatusBar mStatusBar;
        private NotificationPresenter mNotificationPresenter;
        private NotificationPanelViewController mNotificationPanelViewController;
        private ActivityLaunchAnimator mActivityLaunchAnimator;
        private NotificationLaunchAnimatorControllerProvider mNotificationAnimationProvider;

        @Inject
        public Builder(
                Context context,
                CommandQueue commandQueue,
                @Main Handler mainThreadHandler,
                @UiBackground Executor uiBgExecutor,
                NotificationEntryManager entryManager,
                NotifPipeline notifPipeline,
                HeadsUpManagerPhone headsUpManager,
                ActivityStarter activityStarter,
                NotificationClickNotifier clickNotifier,
                StatusBarStateController statusBarStateController,
                StatusBarKeyguardViewManager statusBarKeyguardViewManager,
                KeyguardManager keyguardManager,
                IDreamManager dreamManager,
                Optional<BubblesManager> bubblesManager,
                Lazy<AssistManager> assistManagerLazy,
                NotificationRemoteInputManager remoteInputManager,
                GroupMembershipManager groupMembershipManager,
                NotificationLockscreenUserManager lockscreenUserManager,
                ShadeController shadeController,
                KeyguardStateController keyguardStateController,
                NotificationInterruptStateProvider notificationInterruptStateProvider,
                LockPatternUtils lockPatternUtils,
                StatusBarRemoteInputCallback remoteInputCallback,
                ActivityIntentHelper activityIntentHelper,

                FeatureFlags featureFlags,
                MetricsLogger metricsLogger,
                StatusBarNotificationActivityStarterLogger logger,
                OnUserInteractionCallback onUserInteractionCallback) {

            mContext = context;
            mCommandQueue = commandQueue;
            mMainThreadHandler = mainThreadHandler;
            mUiBgExecutor = uiBgExecutor;
            mEntryManager = entryManager;
            mNotifPipeline = notifPipeline;
            mHeadsUpManager = headsUpManager;
            mActivityStarter = activityStarter;
            mClickNotifier = clickNotifier;
            mStatusBarStateController = statusBarStateController;
            mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
            mKeyguardManager = keyguardManager;
            mDreamManager = dreamManager;
            mBubblesManagerOptional = bubblesManager;
            mAssistManagerLazy = assistManagerLazy;
            mRemoteInputManager = remoteInputManager;
            mGroupMembershipManager = groupMembershipManager;
            mLockscreenUserManager = lockscreenUserManager;
            mShadeController = shadeController;
            mKeyguardStateController = keyguardStateController;
            mNotificationInterruptStateProvider = notificationInterruptStateProvider;
            mLockPatternUtils = lockPatternUtils;
            mRemoteInputCallback = remoteInputCallback;
            mActivityIntentHelper = activityIntentHelper;

            mFeatureFlags = featureFlags;
            mMetricsLogger = metricsLogger;
            mLogger = logger;
            mOnUserInteractionCallback = onUserInteractionCallback;
        }

        /** Sets the status bar to use as {@link StatusBar}. */
        public Builder setStatusBar(StatusBar statusBar) {
            mStatusBar = statusBar;
            return this;
        }

        public Builder setNotificationPresenter(NotificationPresenter notificationPresenter) {
            mNotificationPresenter = notificationPresenter;
            return this;
        }

        /** Set the ActivityLaunchAnimator. */
        public Builder setActivityLaunchAnimator(ActivityLaunchAnimator activityLaunchAnimator) {
            mActivityLaunchAnimator = activityLaunchAnimator;
            return this;
        }

        /** Set the NotificationLaunchAnimatorControllerProvider. */
        public Builder setNotificationAnimatorControllerProvider(
                NotificationLaunchAnimatorControllerProvider notificationAnimationProvider) {
            mNotificationAnimationProvider = notificationAnimationProvider;
            return this;
        }

        /** Set the NotificationPanelViewController. */
        public Builder setNotificationPanelViewController(
                NotificationPanelViewController notificationPanelViewController) {
            mNotificationPanelViewController = notificationPanelViewController;
            return this;
        }

        public StatusBarNotificationActivityStarter build() {
            return new StatusBarNotificationActivityStarter(
                    mContext,
                    mCommandQueue,
                    mMainThreadHandler,
                    mUiBgExecutor,
                    mEntryManager,
                    mNotifPipeline,
                    mHeadsUpManager,
                    mActivityStarter,
                    mClickNotifier,
                    mStatusBarStateController,
                    mStatusBarKeyguardViewManager,
                    mKeyguardManager,
                    mDreamManager,
                    mBubblesManagerOptional,
                    mAssistManagerLazy,
                    mRemoteInputManager,
                    mGroupMembershipManager,
                    mLockscreenUserManager,
                    mShadeController,
                    mKeyguardStateController,
                    mNotificationInterruptStateProvider,
                    mLockPatternUtils,
                    mRemoteInputCallback,
                    mActivityIntentHelper,
                    mFeatureFlags,
                    mMetricsLogger,
                    mLogger,
                    mOnUserInteractionCallback,
                    mStatusBar,
                    mNotificationPresenter,
                    mNotificationPanelViewController,
                    mActivityLaunchAnimator,
                    mNotificationAnimationProvider);
        }
    }
}
