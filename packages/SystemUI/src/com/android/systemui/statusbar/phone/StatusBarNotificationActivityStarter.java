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

import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
import static android.service.notification.NotificationListenerService.REASON_CLICK;

import static com.android.systemui.statusbar.phone.CentralSurfaces.getActivityOptions;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
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

import androidx.annotation.VisibleForTesting;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.EventLogTags;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationClickNotifier;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.LaunchFullScreenIntentProvider;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowDragController;
import com.android.systemui.statusbar.notification.row.OnUserInteractionCallback;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.wmshell.BubblesManager;

import dagger.Lazy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Status bar implementation of {@link NotificationActivityStarter}.
 */
@SysUISingleton
public class StatusBarNotificationActivityStarter implements NotificationActivityStarter {

    /**
     * Helps to avoid recalculation of values provided to
     * {@link #onDismiss(PendingIntent, boolean, boolean, boolean)}} method
     */
    private interface OnKeyguardDismissedAction {
        /**
         * Invoked when keyguard is dismissed
         *
         * @return is used as return value for {@link ActivityStarter.OnDismissAction#onDismiss()}
         */
        boolean onDismiss(PendingIntent intent, boolean isActivityIntent, boolean animate,
                boolean showOverTheLockScreen);
    }

    private final Context mContext;
    private final int mDisplayId;

    private final Handler mMainThreadHandler;
    private final Executor mUiBgExecutor;

    private final NotificationVisibilityProvider mVisibilityProvider;
    private final HeadsUpManager mHeadsUpManager;
    private final ActivityStarter mActivityStarter;
    private final CommandQueue mCommandQueue;
    private final NotificationClickNotifier mClickNotifier;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final KeyguardManager mKeyguardManager;
    private final IDreamManager mDreamManager;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final ShadeController mShadeController;
    private final KeyguardStateController mKeyguardStateController;
    private final LockPatternUtils mLockPatternUtils;
    private final StatusBarRemoteInputCallback mStatusBarRemoteInputCallback;
    private final ActivityIntentHelper mActivityIntentHelper;
    private final ShadeAnimationInteractor mShadeAnimationInteractor;

    private final MetricsLogger mMetricsLogger;
    private final StatusBarNotificationActivityStarterLogger mLogger;

    private final NotificationPresenter mPresenter;
    private final PanelExpansionInteractor mPanelExpansionInteractor;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final ActivityTransitionAnimator mActivityTransitionAnimator;
    private final NotificationLaunchAnimatorControllerProvider mNotificationAnimationProvider;
    private final PowerInteractor mPowerInteractor;
    private final UserTracker mUserTracker;
    private final OnUserInteractionCallback mOnUserInteractionCallback;

    private boolean mIsCollapsingToShowActivityOverLockscreen;

    @Inject
    StatusBarNotificationActivityStarter(
            Context context,
            @DisplayId int displayId,
            Handler mainThreadHandler,
            @Background Executor uiBgExecutor,
            NotificationVisibilityProvider visibilityProvider,
            HeadsUpManager headsUpManager,
            ActivityStarter activityStarter,
            CommandQueue commandQueue,
            NotificationClickNotifier clickNotifier,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            KeyguardManager keyguardManager,
            IDreamManager dreamManager,
            Optional<BubblesManager> bubblesManagerOptional,
            Lazy<AssistManager> assistManagerLazy,
            NotificationRemoteInputManager remoteInputManager,
            NotificationLockscreenUserManager lockscreenUserManager,
            ShadeController shadeController,
            KeyguardStateController keyguardStateController,
            LockPatternUtils lockPatternUtils,
            StatusBarRemoteInputCallback remoteInputCallback,
            ActivityIntentHelper activityIntentHelper,
            MetricsLogger metricsLogger,
            StatusBarNotificationActivityStarterLogger logger,
            OnUserInteractionCallback onUserInteractionCallback,
            NotificationPresenter presenter,
            PanelExpansionInteractor panelExpansionInteractor,
            NotificationShadeWindowController notificationShadeWindowController,
            ActivityTransitionAnimator activityTransitionAnimator,
            ShadeAnimationInteractor shadeAnimationInteractor,
            NotificationLaunchAnimatorControllerProvider notificationAnimationProvider,
            LaunchFullScreenIntentProvider launchFullScreenIntentProvider,
            PowerInteractor powerInteractor,
            UserTracker userTracker) {
        mContext = context;
        mDisplayId = displayId;
        mMainThreadHandler = mainThreadHandler;
        mUiBgExecutor = uiBgExecutor;
        mVisibilityProvider = visibilityProvider;
        mHeadsUpManager = headsUpManager;
        mActivityStarter = activityStarter;
        mCommandQueue = commandQueue;
        mClickNotifier = clickNotifier;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardManager = keyguardManager;
        mDreamManager = dreamManager;
        mBubblesManagerOptional = bubblesManagerOptional;
        mAssistManagerLazy = assistManagerLazy;
        mRemoteInputManager = remoteInputManager;
        mLockscreenUserManager = lockscreenUserManager;
        mShadeController = shadeController;
        mKeyguardStateController = keyguardStateController;
        mLockPatternUtils = lockPatternUtils;
        mStatusBarRemoteInputCallback = remoteInputCallback;
        mActivityIntentHelper = activityIntentHelper;
        mPanelExpansionInteractor = panelExpansionInteractor;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mShadeAnimationInteractor = shadeAnimationInteractor;
        mMetricsLogger = metricsLogger;
        mLogger = logger;
        mOnUserInteractionCallback = onUserInteractionCallback;
        mPresenter = presenter;
        mActivityTransitionAnimator = activityTransitionAnimator;
        mNotificationAnimationProvider = notificationAnimationProvider;
        mPowerInteractor = powerInteractor;
        mUserTracker = userTracker;

        launchFullScreenIntentProvider.registerListener(entry -> launchFullScreenIntent(entry));
    }

    /**
     * Called when the user clicks on the notification bubble icon.
     *
     * @param entry notification that bubble icon was clicked
     */
    @Override
    public void onNotificationBubbleIconClicked(NotificationEntry entry) {
        Runnable action = () -> {
            mBubblesManagerOptional.ifPresent(bubblesManager ->
                    bubblesManager.onUserChangedBubble(entry, !entry.isBubble()));
            mHeadsUpManager.removeNotification(entry.getKey(), /* releaseImmediately= */ true);
        };
        if (entry.isBubble()) {
            // entry is being un-bubbled, no need to unlock
            action.run();
        } else {
            performActionAfterKeyguardDismissed(entry,
                    (intent, isActivityIntent, animate, showOverTheLockScreen) -> {
                        action.run();
                        return false;
                    });
        }
    }

    /**
     * Called when a notification is clicked.
     *
     * @param entry notification that was clicked
     * @param row row for that notification
     */
    @Override
    public void onNotificationClicked(NotificationEntry entry, ExpandableNotificationRow row) {
        mLogger.logStartingActivityFromClick(entry, row.isHeadsUpState(),
                mKeyguardStateController.isVisible(),
                mNotificationShadeWindowController.getPanelExpanded());
        OnKeyguardDismissedAction action =
                (intent, isActivityIntent, animate, showOverTheLockScreen) ->
                        performActionOnKeyguardDismissed(entry, row, intent, isActivityIntent,
                                animate, showOverTheLockScreen);
        performActionAfterKeyguardDismissed(entry, action);
    }

    private void performActionAfterKeyguardDismissed(NotificationEntry entry,
            OnKeyguardDismissedAction action) {
        if (mRemoteInputManager.isRemoteInputActive(entry)) {
            // We have an active remote input typed and the user clicked on the notification.
            // this was probably unintentional, so we're closing the edit text instead.
            mRemoteInputManager.closeRemoteInputs();
            mLogger.logCloseRemoteInput(entry);
            return;
        }
        Notification notification = entry.getSbn().getNotification();
        final PendingIntent intent = notification.contentIntent != null
                ? notification.contentIntent
                : notification.fullScreenIntent;
        final boolean isBubble = entry.isBubble();

        // This code path is now executed for notification without a contentIntent.
        // The only valid case is Bubble notifications. Guard against other cases
        // entering here.
        if (intent == null && !isBubble) {
            mLogger.logNonClickableNotification(entry);
            return;
        }

        boolean isActivityIntent = intent != null && intent.isActivity() && !isBubble;
        final boolean willLaunchResolverActivity = isActivityIntent
                && mActivityIntentHelper.wouldPendingLaunchResolverActivity(intent,
                mLockscreenUserManager.getCurrentUserId());
        final boolean animate = !willLaunchResolverActivity
                && mActivityStarter.shouldAnimateLaunch(isActivityIntent);
        boolean showOverLockscreen = mKeyguardStateController.isShowing() && intent != null
                && mActivityIntentHelper.wouldPendingShowOverLockscreen(intent,
                mLockscreenUserManager.getCurrentUserId());
        ActivityStarter.OnDismissAction postKeyguardAction = new ActivityStarter.OnDismissAction() {
            @Override
            public boolean onDismiss() {
                return action.onDismiss(intent, isActivityIntent, animate, showOverLockscreen);
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
                    postKeyguardAction,
                    null,
                    willLaunchResolverActivity);
        }
    }

    private boolean performActionOnKeyguardDismissed(
            NotificationEntry entry,
            ExpandableNotificationRow row,
            PendingIntent intent,
            boolean isActivityIntent,
            boolean animate,
            boolean showOverLockscreen) {
        mLogger.logHandleClickAfterKeyguardDismissed(entry);

        final Runnable runnable = () -> handleNotificationClickAfterPanelCollapsed(
                entry, row, intent, isActivityIntent, animate);
        if (showOverLockscreen) {
            mShadeController.addPostCollapseAction(runnable);
            mShadeController.collapseShade(true /* animate */);
        } else if (mKeyguardStateController.isShowing()
                && mKeyguardStateController.isOccluded()) {
            mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
            mShadeController.collapseShade();
        } else {
            runnable.run();
        }

        // Always defer the keyguard dismiss when animating.
        return animate || !mPanelExpansionInteractor.isFullyCollapsed();
    }

    private void handleNotificationClickAfterPanelCollapsed(
            NotificationEntry entry,
            ExpandableNotificationRow row,
            PendingIntent intent,
            boolean isActivityIntent,
            boolean animate) {
        String notificationKey = entry.getKey();
        mLogger.logHandleClickAfterPanelCollapsed(entry);

        try {
            // The intent we are sending is for the application, which
            // won't have permission to immediately start an activity after
            // the user switches to home.  We know it is safe to do at this
            // point, so make sure new activity switches are now allowed.
            ActivityManager.getService().resumeAppSwitches();
        } catch (RemoteException e) {
        }
        // If the notification should be cancelled on click and we are launching a work activity in
        // a locked profile with separate challenge, we defer the activity action and cancelling of
        // the notification until work challenge is unlocked. If the notification shouldn't be
        // cancelled, the work challenge will be shown by ActivityManager if necessary anyway.
        if (isActivityIntent && shouldAutoCancel(entry.getSbn())) {
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
                    mShadeController.collapseOnMainThread();
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
            mLogger.logExpandingBubble(entry);
            removeHunAfterClick(row);
            expandBubbleStackOnMainThread(entry);
        } else {
            startNotificationIntent(intent, fillInIntent, entry, row, animate, isActivityIntent);
        }
        if (isActivityIntent || canBubble) {
            mAssistManagerLazy.get().hideAssist();
        }

        final NotificationVisibility nv = mVisibilityProvider.obtain(entry, true);

        if (!canBubble && (shouldAutoCancel(entry.getSbn())
                || mRemoteInputManager.isNotificationKeptForRemoteInputHistory(notificationKey))) {
            final Runnable removeNotification =
                    mOnUserInteractionCallback.registerFutureDismissal(entry, REASON_CLICK);
            // Immediately remove notification from visually showing.
            // We have to post the removal to the UI thread for synchronization.
            mMainThreadHandler.post(() -> {
                if (mPresenter.isCollapsing()) {
                    // To avoid lags we're only performing the remove after the shade is collapsed
                    mShadeController.addPostCollapseAction(removeNotification);
                } else {
                    removeNotification.run();
                }
            });
        }

        // inform NMS that the notification was clicked
        mClickNotifier.onNotificationClick(notificationKey, nv);

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
        final NotificationVisibility nv = mVisibilityProvider.obtain(entry, true);

        String notificationKey = entry.getKey();
        if (shouldAutoCancel(entry.getSbn())
                || mRemoteInputManager.isNotificationKeptForRemoteInputHistory(notificationKey)) {
            final Runnable removeNotification =
                    mOnUserInteractionCallback.registerFutureDismissal(entry, REASON_CLICK);
            // Immediately remove notification from visually showing.
            // We have to post the removal to the UI thread for synchronization.
            mMainThreadHandler.post(() -> {
                if (mPresenter.isCollapsing()) {
                    // To avoid lags we're only performing the remove
                    // after the shade is collapsed
                    mShadeController.addPostCollapseAction(removeNotification);
                } else {
                    removeNotification.run();
                }
            });
        }

        // inform NMS that the notification was clicked
        mClickNotifier.onNotificationClick(notificationKey, nv);

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
        mShadeController.collapseShade();
    }

    private void startNotificationIntent(
            PendingIntent intent,
            Intent fillInIntent,
            NotificationEntry entry,
            ExpandableNotificationRow row,
            boolean animate,
            boolean isActivityIntent) {
        mLogger.logStartNotificationIntent(entry);
        try {
            ActivityTransitionAnimator.Controller animationController =
                    new StatusBarTransitionAnimatorController(
                            mNotificationAnimationProvider.getAnimatorController(row, null),
                            mShadeAnimationInteractor,
                            mShadeController,
                            mNotificationShadeWindowController,
                            mCommandQueue,
                            mDisplayId,
                            isActivityIntent);
            mActivityTransitionAnimator.startPendingIntentWithAnimation(
                    animationController,
                    animate,
                    intent.getCreatorPackage(),
                    (adapter) -> {
                        long eventTime = row.getAndResetLastActionUpTime();
                        Bundle options = eventTime > 0
                                ? getActivityOptions(
                                mDisplayId,
                                adapter,
                                mKeyguardStateController.isShowing(),
                                eventTime)
                                : getActivityOptions(mDisplayId, adapter);
                        int result = intent.sendAndReturnResult(mContext, 0, fillInIntent, null,
                                null, null, options);
                        mLogger.logSendPendingIntent(entry, intent, result);
                        return result;
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
        boolean animate = mActivityStarter.shouldAnimateLaunch(true /* isActivityIntent */);
        ActivityStarter.OnDismissAction onDismissAction = new ActivityStarter.OnDismissAction() {
            @Override
            public boolean onDismiss() {
                AsyncTask.execute(() -> {
                    ActivityTransitionAnimator.Controller animationController =
                            new StatusBarTransitionAnimatorController(
                                    mNotificationAnimationProvider.getAnimatorController(row),
                                    mShadeAnimationInteractor,
                                    mShadeController,
                                    mNotificationShadeWindowController,
                                    mCommandQueue,
                                    mDisplayId,
                                    true /* isActivityIntent */);

                    mActivityTransitionAnimator.startIntentWithAnimation(
                            animationController, animate, intent.getPackage(),
                            (adapter) -> TaskStackBuilder.create(mContext)
                                    .addNextIntentWithParentStack(intent)
                                    .startActivities(getActivityOptions(
                                            mDisplayId,
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
        boolean animate = mActivityStarter.shouldAnimateLaunch(true /* isActivityIntent */);
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

                    ActivityTransitionAnimator.Controller viewController =
                            ActivityTransitionAnimator.Controller.fromView(view,
                                    InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON
                            );
                    ActivityTransitionAnimator.Controller animationController =
                            viewController == null ? null
                                : new StatusBarTransitionAnimatorController(
                                        viewController,
                                        mShadeAnimationInteractor,
                                        mShadeController,
                                        mNotificationShadeWindowController,
                                        mCommandQueue,
                                        mDisplayId,
                                        true /* isActivityIntent */);

                    mActivityTransitionAnimator.startIntentWithAnimation(
                            animationController, animate, intent.getPackage(),
                            (adapter) -> tsb.startActivities(
                                    getActivityOptions(mDisplayId, adapter),
                                    mUserTracker.getUserHandle()));
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
        if (mHeadsUpManager != null && mHeadsUpManager.isHeadsUpEntry(key)) {
            // Release the HUN notification to the shade.
            if (mPresenter.isPresenterFullyCollapsed()) {
                HeadsUpUtil.setNeedsHeadsUpDisappearAnimationAfterClick(row, true);
            }

            // In most cases, when FLAG_AUTO_CANCEL is set, the notification will
            // become canceled shortly by NoMan, but we can't assume that.
            mHeadsUpManager.removeNotification(key, true /* releaseImmediately */);
        }
    }

    @VisibleForTesting
    void launchFullScreenIntent(NotificationEntry entry) {
        // Skip if device is in VR mode.
        if (mPresenter.isDeviceInVrMode()) {
            mLogger.logFullScreenIntentSuppressedByVR(entry);
            return;
        }
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
        final PendingIntent fullScreenIntent =
                entry.getSbn().getNotification().fullScreenIntent;
        mLogger.logSendingFullScreenIntent(entry, fullScreenIntent);
        try {
            EventLog.writeEvent(EventLogTags.SYSUI_FULLSCREEN_NOTIFICATION,
                    entry.getKey());
            mPowerInteractor.wakeUpForFullScreenIntent();

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(
                    MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            fullScreenIntent.sendAndReturnResult(null, 0, null, null, null, null,
                    options.toBundle());
            entry.notifyFullScreenIntentLaunched();

            mMetricsLogger.count("note_fullscreen", 1);

            String activityName;
            List<ResolveInfo> resolveInfos = fullScreenIntent.queryIntentComponents(0);
            if (resolveInfos.size() > 0 && resolveInfos.get(0) != null
                    && resolveInfos.get(0).activityInfo != null
                    && resolveInfos.get(0).activityInfo.name != null) {
                activityName = resolveInfos.get(0).activityInfo.name;
            } else {
                activityName = "";
            }
            FrameworkStatsLog.write(FrameworkStatsLog.FULL_SCREEN_INTENT_LAUNCHED,
                    fullScreenIntent.getCreatorUid(),
                    activityName);
        } catch (PendingIntent.CanceledException e) {
            // ignore
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
        return true;
    }

}
