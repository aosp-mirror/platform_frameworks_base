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

import static android.content.Intent.ACTION_DEVICE_LOCKED_CHANGED;

import static com.android.systemui.statusbar.NotificationLockscreenUserManager.NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.os.RemoteException;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewParent;

import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.ActionClickLogger;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager.Callback;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 */
@SysUISingleton
public class StatusBarRemoteInputCallback implements Callback, Callbacks,
        StatusBarStateController.StateListener {

    private final KeyguardStateController mKeyguardStateController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final ActivityStarter mActivityStarter;
    private final Context mContext;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final com.android.systemui.shade.ShadeController mShadeController;
    private Executor mExecutor;
    private final ActivityIntentHelper mActivityIntentHelper;
    private final GroupExpansionManager mGroupExpansionManager;
    private View mPendingWorkRemoteInputView;
    private View mPendingRemoteInputView;
    private KeyguardManager mKeyguardManager;
    private final CommandQueue mCommandQueue;
    private final ActionClickLogger mActionClickLogger;
    private int mDisabled2;
    protected BroadcastReceiver mChallengeReceiver = new ChallengeReceiver();

    /**
     */
    @Inject
    public StatusBarRemoteInputCallback(
            Context context,
            GroupExpansionManager groupExpansionManager,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            ActivityStarter activityStarter,
            ShadeController shadeController,
            CommandQueue commandQueue,
            ActionClickLogger clickLogger,
            @Main Executor executor) {
        mContext = context;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mShadeController = shadeController;
        mExecutor = executor;
        mContext.registerReceiverAsUser(mChallengeReceiver, UserHandle.ALL,
                new IntentFilter(ACTION_DEVICE_LOCKED_CHANGED), null, null);
        mLockscreenUserManager = notificationLockscreenUserManager;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = (SysuiStatusBarStateController) statusBarStateController;
        mActivityStarter = activityStarter;
        mStatusBarStateController.addCallback(this);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mCommandQueue = commandQueue;
        mCommandQueue.addCallback(this);
        mActionClickLogger = clickLogger;
        mActivityIntentHelper = new ActivityIntentHelper(mContext);
        mGroupExpansionManager = groupExpansionManager;
    }

    @Override
    public void onStateChanged(int state) {
        boolean hasPendingRemoteInput = mPendingRemoteInputView != null;
        if (state == StatusBarState.SHADE
                && (mStatusBarStateController.leaveOpenOnKeyguardHide() || hasPendingRemoteInput)) {
            if (!mStatusBarStateController.isKeyguardRequested()
                    && mKeyguardStateController.isUnlocked()) {
                if (hasPendingRemoteInput) {
                    mExecutor.execute(mPendingRemoteInputView::callOnClick);
                }
                mPendingRemoteInputView = null;
            }
        }
    }

    @Override
    public void onLockedRemoteInput(ExpandableNotificationRow row, View clicked) {
        if (!row.isPinned()) {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
        }
        mStatusBarKeyguardViewManager.showGenericBouncer(true /* scrimmed */);
        mPendingRemoteInputView = clicked;
    }

    protected void onWorkChallengeChanged() {
        mLockscreenUserManager.updatePublicMode();
        if (mPendingWorkRemoteInputView != null
                && !mLockscreenUserManager.isAnyProfilePublicMode()) {
            // Expand notification panel and the notification row, then click on remote input view
            final Runnable clickPendingViewRunnable = () -> {
                final View pendingWorkRemoteInputView = mPendingWorkRemoteInputView;
                if (pendingWorkRemoteInputView == null) {
                    return;
                }

                // Climb up the hierarchy until we get to the container for this row.
                ViewParent p = pendingWorkRemoteInputView.getParent();
                while (!(p instanceof ExpandableNotificationRow)) {
                    if (p == null) {
                        return;
                    }
                    p = p.getParent();
                }

                final ExpandableNotificationRow row = (ExpandableNotificationRow) p;
                ViewParent viewParent = row.getParent();
                if (viewParent instanceof NotificationStackScrollLayout) {
                    final NotificationStackScrollLayout scrollLayout =
                            (NotificationStackScrollLayout) viewParent;
                    row.makeActionsVisibile();
                    row.post(() -> {
                        final Runnable finishScrollingCallback = () -> {
                            mPendingWorkRemoteInputView.callOnClick();
                            mPendingWorkRemoteInputView = null;
                            scrollLayout.setFinishScrollingCallback(null);
                        };
                        if (scrollLayout.scrollTo(row)) {
                            // It scrolls! So call it when it's finished.
                            scrollLayout.setFinishScrollingCallback(finishScrollingCallback);
                        } else {
                            // It does not scroll, so call it now!
                            finishScrollingCallback.run();
                        }
                    });
                }
            };
            mShadeController.postOnShadeExpanded(clickPendingViewRunnable);
            mShadeController.instantExpandNotificationsPanel();
        }
    }

    @Override
    public void onMakeExpandedVisibleForRemoteInput(ExpandableNotificationRow row,
            View clickedView, boolean deferBouncer, Runnable runnable) {
        if (!deferBouncer && mKeyguardStateController.isShowing()) {
            onLockedRemoteInput(row, clickedView);
        } else {
            if (row.isChildInGroup() && !row.areChildrenExpanded()) {
                // The group isn't expanded, let's make sure it's visible!
                mGroupExpansionManager.toggleGroupExpansion(row.getEntry());
            }
            row.setUserExpanded(true);
            row.getPrivateLayout().setOnExpandedVisibleListener(runnable);
        }
    }

    @Override
    public void onLockedWorkRemoteInput(int userId, ExpandableNotificationRow row,
            View clicked) {
        // Collapse notification and show work challenge
        mCommandQueue.animateCollapsePanels();
        startWorkChallengeIfNecessary(userId, null, null);
        // Add pending remote input view after starting work challenge, as starting work challenge
        // will clear all previous pending review view
        mPendingWorkRemoteInputView = clicked;
    }

    boolean startWorkChallengeIfNecessary(int userId, IntentSender intendSender,
            String notificationKey) {
        // Clear pending remote view, as we do not want to trigger pending remote input view when
        // it's called by other code
        mPendingWorkRemoteInputView = null;
        // Begin old BaseStatusBar.startWorkChallengeIfNecessary.
        final Intent newIntent = mKeyguardManager.createConfirmDeviceCredentialIntent(null,
                null, userId);
        if (newIntent == null) {
            return false;
        }
        final Intent callBackIntent = new Intent(NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION);
        callBackIntent.putExtra(Intent.EXTRA_INTENT, intendSender);
        callBackIntent.putExtra(Intent.EXTRA_INDEX, notificationKey);
        callBackIntent.setPackage(mContext.getPackageName());

        PendingIntent callBackPendingIntent = PendingIntent.getBroadcast(
                mContext,
                0,
                callBackIntent,
                PendingIntent.FLAG_CANCEL_CURRENT |
                        PendingIntent.FLAG_ONE_SHOT |
                        PendingIntent.FLAG_IMMUTABLE);
        newIntent.putExtra(
                Intent.EXTRA_INTENT,
                callBackPendingIntent.getIntentSender());
        try {
            ActivityManager.getService().startConfirmDeviceCredentialIntent(newIntent,
                    null /*options*/);
        } catch (RemoteException ex) {
            // ignore
        }
        return true;
        // End old BaseStatusBar.startWorkChallengeIfNecessary.
    }

    @Override
    public boolean shouldHandleRemoteInput(View view, PendingIntent pendingIntent) {
        // Skip remote input as doing so will expand the notification shade.
        return (mDisabled2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0;
    }

    @Override
    public boolean handleRemoteViewClick(View view, PendingIntent pendingIntent,
            boolean appRequestedAuth,
            NotificationRemoteInputManager.ClickHandler defaultHandler) {
        final boolean isActivity = pendingIntent.isActivity();
        if (isActivity || appRequestedAuth) {
            mActionClickLogger.logWaitingToCloseKeyguard(pendingIntent);
            final boolean afterKeyguardGone = mActivityIntentHelper
                    .wouldPendingLaunchResolverActivity(pendingIntent,
                            mLockscreenUserManager.getCurrentUserId());
            mActivityStarter.dismissKeyguardThenExecute(() -> {
                mActionClickLogger.logKeyguardGone(pendingIntent);

                try {
                    ActivityManager.getService().resumeAppSwitches();
                } catch (RemoteException e) {
                }

                boolean handled = defaultHandler.handleClick();

                // close the shade if it was open and maybe wait for activity start.
                return handled && mShadeController.closeShadeIfOpen();
            }, null, afterKeyguardGone);
            return true;
        } else {
            return defaultHandler.handleClick();
        }
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId == mContext.getDisplayId()) {
            mDisabled2 = state2;
        }
    }

    protected class ChallengeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (Intent.ACTION_DEVICE_LOCKED_CHANGED.equals(action)) {
                if (userId != mLockscreenUserManager.getCurrentUserId()
                        && mLockscreenUserManager.isCurrentProfile(userId)) {
                    onWorkChallengeChanged();
                }
            }
        }
    };
}
