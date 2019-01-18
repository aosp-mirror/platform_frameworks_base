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

import static com.android.systemui.Dependency.MAIN_HANDLER;
import static com.android.systemui.SysUiServiceProvider.getComponent;
import static com.android.systemui.statusbar.phone.StatusBar.getActivityOptions;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.view.RemoteAnimationAdapter;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Dependency;
import com.android.systemui.EventLogTags;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.PreviewInflater;

/**
 * Status bar implementation of {@link NotificationActivityStarter}.
 */
public class StatusBarNotificationActivityStarter implements NotificationActivityStarter {

    private static final String TAG = "NotificationClickHandler";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final AssistManager mAssistManager = Dependency.get(AssistManager.class);
    private final NotificationGroupManager mGroupManager =
            Dependency.get(NotificationGroupManager.class);
    private final StatusBarRemoteInputCallback mStatusBarRemoteInputCallback =
            (StatusBarRemoteInputCallback) Dependency.get(
                    NotificationRemoteInputManager.Callback.class);
    private final NotificationRemoteInputManager mRemoteInputManager =
            Dependency.get(NotificationRemoteInputManager.class);
    private final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);
    private final ShadeController mShadeController = Dependency.get(ShadeController.class);
    private final KeyguardMonitor mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
    private final ActivityStarter mActivityStarter = Dependency.get(ActivityStarter.class);
    private final NotificationEntryManager mEntryManager =
            Dependency.get(NotificationEntryManager.class);
    private final StatusBarStateController mStatusBarStateController =
            Dependency.get(StatusBarStateController.class);
    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider =
            Dependency.get(NotificationInterruptionStateProvider.class);
    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);

    private final Context mContext;
    private final NotificationPanelView mNotificationPanel;
    private final NotificationPresenter mPresenter;
    private final LockPatternUtils mLockPatternUtils;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final KeyguardManager mKeyguardManager;
    private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private final IStatusBarService mBarService;
    private final CommandQueue mCommandQueue;
    private final IDreamManager mDreamManager;

    private boolean mIsCollapsingToShowActivityOverLockscreen;

    public StatusBarNotificationActivityStarter(Context context,
            NotificationPanelView panel,
            NotificationPresenter presenter,
            HeadsUpManagerPhone headsUpManager,
            ActivityLaunchAnimator activityLaunchAnimator) {
        mContext = context;
        mNotificationPanel = panel;
        mPresenter = presenter;
        mLockPatternUtils = new LockPatternUtils(context);
        mHeadsUpManager = headsUpManager;
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mActivityLaunchAnimator = activityLaunchAnimator;
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mCommandQueue = getComponent(context, CommandQueue.class);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));

        mEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
            @Override
            public void onPendingEntryAdded(NotificationEntry entry) {
                handleFullScreenIntent(entry);
            }
        });
    }

    /**
     * Called when a notification is clicked.
     *
     * @param sbn notification that was clicked
     * @param row row for that notification
     */
    @Override
    public void onNotificationClicked(StatusBarNotification sbn, ExpandableNotificationRow row) {
        RemoteInputController controller = mRemoteInputManager.getController();
        if (controller.isRemoteInputActive(row.getEntry())
                && !TextUtils.isEmpty(row.getActiveRemoteInputText())) {
            // We have an active remote input typed and the user clicked on the notification.
            // this was probably unintentional, so we're closing the edit text instead.
            controller.closeRemoteInputs();
            return;
        }
        Notification notification = sbn.getNotification();
        final PendingIntent intent = notification.contentIntent != null
                ? notification.contentIntent
                : notification.fullScreenIntent;
        final String notificationKey = sbn.getKey();

        boolean isActivityIntent = intent.isActivity();
        final boolean afterKeyguardGone = isActivityIntent
                && PreviewInflater.wouldLaunchResolverActivity(mContext, intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());
        final boolean wasOccluded = mShadeController.isOccluded();
        boolean showOverLockscreen = mKeyguardMonitor.isShowing()
                && PreviewInflater.wouldShowOverLockscreen(mContext,
                intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());
        ActivityStarter.OnDismissAction postKeyguardAction =
                () -> handleNotificationClickAfterKeyguardDismissed(
                        sbn, row, controller, intent, notificationKey,
                        isActivityIntent, wasOccluded, showOverLockscreen);
        if (showOverLockscreen) {
            mIsCollapsingToShowActivityOverLockscreen = true;
            postKeyguardAction.onDismiss();
        } else {
            mActivityStarter.dismissKeyguardThenExecute(
                    postKeyguardAction, null /* cancel */, afterKeyguardGone);
        }
    }

    private boolean handleNotificationClickAfterKeyguardDismissed(
            StatusBarNotification sbn,
            ExpandableNotificationRow row,
            RemoteInputController controller,
            PendingIntent intent,
            String notificationKey,
            boolean isActivityIntent,
            boolean wasOccluded,
            boolean showOverLockscreen) {
        // TODO: Some of this code may be able to move to NotificationEntryManager.
        if (mHeadsUpManager != null && mHeadsUpManager.isAlerting(notificationKey)) {
            // Release the HUN notification to the shade.

            if (mPresenter.isPresenterFullyCollapsed()) {
                HeadsUpUtil.setIsClickedHeadsUpNotification(row, true);
            }
            //
            // In most cases, when FLAG_AUTO_CANCEL is set, the notification will
            // become canceled shortly by NoMan, but we can't assume that.
            mHeadsUpManager.removeNotification(sbn.getKey(),
                    true /* releaseImmediately */);
        }
        StatusBarNotification parentToCancel = null;
        if (shouldAutoCancel(sbn) && mGroupManager.isOnlyChildInGroup(sbn)) {
            StatusBarNotification summarySbn =
                    mGroupManager.getLogicalGroupSummary(sbn).notification;
            if (shouldAutoCancel(summarySbn)) {
                parentToCancel = summarySbn;
            }
        }
        final StatusBarNotification parentToCancelFinal = parentToCancel;
        final Runnable runnable = () -> handleNotificationClickAfterPanelCollapsed(
                sbn, row, controller, intent, notificationKey,
                isActivityIntent, wasOccluded, parentToCancelFinal);

        if (showOverLockscreen) {
            mShadeController.addPostCollapseAction(runnable);
            mShadeController.collapsePanel(true /* animate */);
        } else if (mKeyguardMonitor.isShowing()
                && mShadeController.isOccluded()) {
            mShadeController.addAfterKeyguardGoneRunnable(runnable);
            mShadeController.collapsePanel();
        } else {
            new Thread(runnable).start();
        }

        return !mNotificationPanel.isFullyCollapsed();
    }

    private void handleNotificationClickAfterPanelCollapsed(
            StatusBarNotification sbn,
            ExpandableNotificationRow row,
            RemoteInputController controller,
            PendingIntent intent,
            String notificationKey,
            boolean isActivityIntent,
            boolean wasOccluded,
            StatusBarNotification parentToCancelFinal) {
        try {
            // The intent we are sending is for the application, which
            // won't have permission to immediately start an activity after
            // the user switches to home.  We know it is safe to do at this
            // point, so make sure new activity switches are now allowed.
            ActivityManager.getService().resumeAppSwitches();
        } catch (RemoteException e) {
        }
        int launchResult;
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
                    // Show work challenge, do not run PendingIntent and
                    // remove notification
                    collapseOnMainThread();
                    return;
                }
            }
        }
        Intent fillInIntent = null;
        NotificationEntry entry = row.getEntry();
        CharSequence remoteInputText = null;
        if (!TextUtils.isEmpty(entry.remoteInputText)) {
            remoteInputText = entry.remoteInputText;
        }
        if (!TextUtils.isEmpty(remoteInputText) && !controller.isSpinning(entry.key)) {
            fillInIntent = new Intent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT,
                    remoteInputText.toString());
        }
        RemoteAnimationAdapter adapter = mActivityLaunchAnimator.getLaunchAnimation(
                row, wasOccluded);
        try {
            if (adapter != null) {
                ActivityTaskManager.getService()
                        .registerRemoteAnimationForNextActivityStart(
                                intent.getCreatorPackage(), adapter);
            }
            launchResult = intent.sendAndReturnResult(mContext, 0, fillInIntent, null,
                    null, null, getActivityOptions(adapter));
            mActivityLaunchAnimator.setLaunchResult(launchResult, isActivityIntent);
        } catch (RemoteException | PendingIntent.CanceledException e) {
            // the stack trace isn't very helpful here.
            // Just log the exception message.
            Log.w(TAG, "Sending contentIntent failed: " + e);

            // TODO: Dismiss Keyguard.
        }
        if (isActivityIntent) {
            mAssistManager.hideAssist();
        }
        if (shouldCollapse()) {
            collapseOnMainThread();
        }

        final int count =
                mEntryManager.getNotificationData().getActiveNotifications().size();
        final int rank = mEntryManager.getNotificationData().getRank(notificationKey);
        final NotificationVisibility nv = NotificationVisibility.obtain(notificationKey,
                rank, count, true);
        try {
            mBarService.onNotificationClick(notificationKey, nv);
        } catch (RemoteException ex) {
            // system process is dead if we're here.
        }
        if (parentToCancelFinal != null) {
            removeNotification(parentToCancelFinal);
        }
        if (shouldAutoCancel(sbn)
                || mRemoteInputManager.isNotificationKeptForRemoteInputHistory(
                notificationKey)) {
            // Automatically remove all notifications that we may have kept around longer
            removeNotification(sbn);
        }
        mIsCollapsingToShowActivityOverLockscreen = false;
    }

    @Override
    public void startNotificationGutsIntent(final Intent intent, final int appUid,
            ExpandableNotificationRow row) {
        mActivityStarter.dismissKeyguardThenExecute(() -> {
            AsyncTask.execute(() -> {
                int launchResult = TaskStackBuilder.create(mContext)
                        .addNextIntentWithParentStack(intent)
                        .startActivities(getActivityOptions(
                                mActivityLaunchAnimator.getLaunchAnimation(
                                        row, mShadeController.isOccluded())),
                                new UserHandle(UserHandle.getUserId(appUid)));
                mActivityLaunchAnimator.setLaunchResult(launchResult, true /* isActivityIntent */);
                if (shouldCollapse()) {
                    // Putting it back on the main thread, since we're touching views
                    Dependency.get(MAIN_HANDLER).post(() -> mCommandQueue.animateCollapsePanels(
                            CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */));
                }
            });
            return true;
        }, null, false /* afterKeyguardGone */);
    }

    private void handleFullScreenIntent(NotificationEntry entry) {
        boolean isHeadsUped = mNotificationInterruptionStateProvider.shouldHeadsUp(entry);
        if (!isHeadsUped && entry.notification.getNotification().fullScreenIntent != null) {
            if (shouldSuppressFullScreenIntent(entry)) {
                if (DEBUG) {
                    Log.d(TAG, "No Fullscreen intent: suppressed by DND: " + entry.key);
                }
            } else if (entry.importance < NotificationManager.IMPORTANCE_HIGH) {
                if (DEBUG) {
                    Log.d(TAG, "No Fullscreen intent: not important enough: " + entry.key);
                }
            } else {
                // Stop screensaver if the notification has a fullscreen intent.
                // (like an incoming phone call)
                Dependency.get(UiOffloadThread.class).submit(() -> {
                    try {
                        mDreamManager.awaken();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                });

                // not immersive & a fullscreen alert should be shown
                if (DEBUG) {
                    Log.d(TAG, "Notification has fullScreenIntent; sending fullScreenIntent");
                }
                try {
                    EventLog.writeEvent(EventLogTags.SYSUI_FULLSCREEN_NOTIFICATION,
                            entry.key);
                    entry.notification.getNotification().fullScreenIntent.send();
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
            Dependency.get(MAIN_HANDLER).post(mShadeController::collapsePanel);
        }
    }

    private boolean shouldCollapse() {
        return mStatusBarStateController.getState() != StatusBarState.SHADE
                || !mActivityLaunchAnimator.isAnimationPending();
    }

    private boolean shouldSuppressFullScreenIntent(NotificationEntry entry) {
        if (mPresenter.isDeviceInVrMode()) {
            return true;
        }

        return entry.shouldSuppressFullScreenIntent();
    }

    private void removeNotification(StatusBarNotification notification) {
        // We have to post it to the UI thread for synchronization
        Dependency.get(MAIN_HANDLER).post(() -> {
            Runnable removeRunnable =
                    () -> mEntryManager.performRemoveNotification(notification);
            if (mPresenter.isCollapsing()) {
                // To avoid lags we're only performing the remove
                // after the shade was collapsed
                mShadeController.addPostCollapseAction(removeRunnable);
            } else {
                removeRunnable.run();
            }
        });
    }
}
