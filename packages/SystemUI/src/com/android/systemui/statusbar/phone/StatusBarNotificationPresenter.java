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

import static com.android.systemui.Dependency.MAIN_HANDLER;
import static com.android.systemui.SysUiServiceProvider.getComponent;
import static com.android.systemui.statusbar.phone.StatusBar.CLOSE_PANEL_WHEN_EMPTIED;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG;
import static com.android.systemui.statusbar.phone.StatusBar.MULTIUSER_DEBUG;
import static com.android.systemui.statusbar.phone.StatusBar.SPEW;
import static com.android.systemui.statusbar.phone.StatusBar.getActivityOptions;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.RemoteAnimationAdapter;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.InitController;
import com.android.systemui.R;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.statusbar.AmbientPulseManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager.Callback;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.AboveShelfObserver;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.NotificationData.Entry;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager.OnSettingsClickListener;
import com.android.systemui.statusbar.notification.row.NotificationInfo.CheckSaveListener;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.PreviewInflater;

public class StatusBarNotificationPresenter implements NotificationPresenter {

    private final LockscreenGestureLogger mLockscreenGestureLogger =
            Dependency.get(LockscreenGestureLogger.class);

    private static final String TAG = "StatusBarNotificationPresenter";

    private final ShadeController mShadeController = Dependency.get(ShadeController.class);
    private final ActivityStarter mActivityStarter = Dependency.get(ActivityStarter.class);
    private final AssistManager mAssistManager = Dependency.get(AssistManager.class);
    private final KeyguardMonitor mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
    private final NotificationViewHierarchyManager mViewHierarchyManager =
            Dependency.get(NotificationViewHierarchyManager.class);
    private final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);
    private final StatusBarStateController mStatusBarStateController =
            Dependency.get(StatusBarStateController.class);
    private final NotificationEntryManager mEntryManager =
            Dependency.get(NotificationEntryManager.class);
    private final NotificationMediaManager mMediaManager =
            Dependency.get(NotificationMediaManager.class);
    private final NotificationRemoteInputManager mRemoteInputManager =
            Dependency.get(NotificationRemoteInputManager.class);
    private final NotificationGroupManager mGroupManager =
            Dependency.get(NotificationGroupManager.class);
    private final StatusBarRemoteInputCallback mStatusBarRemoteInputCallback =
            (StatusBarRemoteInputCallback) Dependency.get(Callback.class);
    protected AmbientPulseManager mAmbientPulseManager = Dependency.get(AmbientPulseManager.class);

    private final NotificationPanelView mNotificationPanel;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final AboveShelfObserver mAboveShelfObserver;
    private final DozeScrimController mDozeScrimController;
    private final ScrimController mScrimController;
    private final Context mContext;
    private final CommandQueue mCommandQueue;

    private final AccessibilityManager mAccessibilityManager;
    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardManager mKeyguardManager;
    private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private final int mMaxAllowedKeyguardNotifications;
    private final IStatusBarService mBarService;
    private boolean mReinflateNotificationsOnUserSwitched;
    private final UnlockMethodCache mUnlockMethodCache;
    private TextView mNotificationPanelDebugText;

    protected boolean mVrMode;
    private int mMaxKeyguardNotifications;
    private boolean mIsCollapsingToShowActivityOverLockscreen;

    public StatusBarNotificationPresenter(Context context, NotificationPanelView panel,
            HeadsUpManagerPhone headsUp, StatusBarWindowView statusBarWindow,
            ViewGroup stackScroller, DozeScrimController dozeScrimController,
            ScrimController scrimController,
            ActivityLaunchAnimator.Callback launchAnimatorCallback) {
        mContext = context;
        mNotificationPanel = panel;
        mHeadsUpManager = headsUp;
        mCommandQueue = getComponent(context, CommandQueue.class);
        mAboveShelfObserver = new AboveShelfObserver(stackScroller);
        mAboveShelfObserver.setListener(statusBarWindow.findViewById(
                R.id.notification_container_parent));
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mDozeScrimController = dozeScrimController;
        mScrimController = scrimController;
        mUnlockMethodCache = UnlockMethodCache.getInstance(mContext);
        mLockPatternUtils = new LockPatternUtils(context);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mMaxAllowedKeyguardNotifications = context.getResources().getInteger(
                R.integer.keyguard_max_notification_count);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mActivityLaunchAnimator = new ActivityLaunchAnimator(statusBarWindow,
                launchAnimatorCallback,
                mNotificationPanel,
                (NotificationListContainer) stackScroller);

        if (MULTIUSER_DEBUG) {
            mNotificationPanelDebugText = mNotificationPanel.findViewById(R.id.header_debug_info);
            mNotificationPanelDebugText.setVisibility(View.VISIBLE);
        }

        IVrManager vrManager = IVrManager.Stub.asInterface(ServiceManager.getService(
                Context.VR_SERVICE));
        if (vrManager != null) {
            try {
                vrManager.registerListener(mVrStateCallbacks);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register VR mode state listener: " + e);
            }
        }
        mRemoteInputManager.setUpWithPresenter(this,
                Dependency.get(NotificationRemoteInputManager.Callback.class),
                mNotificationPanel.createRemoteInputDelegate());
        mRemoteInputManager.getController().addCallback(
                Dependency.get(StatusBarWindowController.class));

        NotificationListContainer notifListContainer = (NotificationListContainer) stackScroller;
        Dependency.get(InitController.class).addPostInitTask(() -> {
            mViewHierarchyManager.setUpWithPresenter(this, notifListContainer);
            mEntryManager.setUpWithPresenter(this, notifListContainer, this, mHeadsUpManager);
            mLockscreenUserManager.setUpWithPresenter(this);
            mMediaManager.setUpWithPresenter(this);
            Dependency.get(NotificationGutsManager.class).setUpWithPresenter(this,
                    notifListContainer, mCheckSaveListener, mOnSettingsClickListener);

            onUserSwitched(mLockscreenUserManager.getCurrentUserId());
        });
    }

    public void onDensityOrFontScaleChanged() {
        if (!KeyguardUpdateMonitor.getInstance(mContext).isSwitchingUser()) {
            mEntryManager.updateNotificationsOnDensityOrFontScaleChanged();
        } else {
            mReinflateNotificationsOnUserSwitched = true;
        }
    }

    @Override
    public ActivityLaunchAnimator getActivityLaunchAnimator() {
        return mActivityLaunchAnimator;
    }

    @Override
    public boolean isCollapsing() {
        return mNotificationPanel.isCollapsing()
                || mActivityLaunchAnimator.isAnimationPending()
                || mActivityLaunchAnimator.isAnimationRunning();
    }

    @Override
    public boolean isCollapsingToShowActivityOverLockscreen() {
        return mIsCollapsingToShowActivityOverLockscreen;
    }

    @Override
    public void onPerformRemoveNotification(StatusBarNotification n) {
        if (mNotificationPanel.hasPulsingNotifications() &&
                    !mAmbientPulseManager.hasNotifications()) {
            // We were showing a pulse for a notification, but no notifications are pulsing anymore.
            // Finish the pulse.
            mDozeScrimController.pulseOutNow();
        }
    }

    @Override
    public void updateNotificationViews() {
        // The function updateRowStates depends on both of these being non-null, so check them here.
        // We may be called before they are set from DeviceProvisionedController's callback.
        if (mScrimController == null) return;

        // Do not modify the notifications during collapse.
        if (isCollapsing()) {
            mShadeController.addPostCollapseAction(this::updateNotificationViews);
            return;
        }

        mViewHierarchyManager.updateNotificationViews();

        mNotificationPanel.updateNotificationViews();
    }

    @Override
    public void onNotificationAdded(Entry shadeEntry) {
        // Recalculate the position of the sliding windows and the titles.
        mShadeController.updateAreThereNotifications();
    }

    @Override
    public void onNotificationUpdated(StatusBarNotification notification) {
        mShadeController.updateAreThereNotifications();
    }

    @Override
    public void onNotificationRemoved(String key, StatusBarNotification old) {
        if (SPEW) Log.d(TAG, "removeNotification key=" + key + " old=" + old);

        if (old != null) {
            if (CLOSE_PANEL_WHEN_EMPTIED && !hasActiveNotifications()
                    && !mNotificationPanel.isTracking() && !mNotificationPanel.isQsExpanded()) {
                if (mStatusBarStateController.getState() == StatusBarState.SHADE) {
                    mCommandQueue.animateCollapsePanels();
                } else if (mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED
                        && !isCollapsing()) {
                    mShadeController.goToKeyguard();
                }
            }
        }
        mShadeController.updateAreThereNotifications();
    }

    public boolean hasActiveNotifications() {
        return !mEntryManager.getNotificationData().getActiveNotifications().isEmpty();
    }

    @Override
    public boolean canHeadsUp(Entry entry, StatusBarNotification sbn) {
        if (mShadeController.isDozing()) {
            return false;
        }

        if (mShadeController.isOccluded()) {
            boolean devicePublic = mLockscreenUserManager.
                    isLockscreenPublicMode(mLockscreenUserManager.getCurrentUserId());
            boolean userPublic = devicePublic
                    || mLockscreenUserManager.isLockscreenPublicMode(sbn.getUserId());
            boolean needsRedaction = mLockscreenUserManager.needsRedaction(entry);
            if (userPublic && needsRedaction) {
                return false;
            }
        }

        if (!mCommandQueue.panelsEnabled()) {
            if (DEBUG) {
                Log.d(TAG, "No heads up: disabled panel : " + sbn.getKey());
            }
            return false;
        }

        if (sbn.getNotification().fullScreenIntent != null) {
            if (mAccessibilityManager.isTouchExplorationEnabled()) {
                if (DEBUG) Log.d(TAG, "No heads up: accessible fullscreen: " + sbn.getKey());
                return false;
            } else {
                // we only allow head-up on the lockscreen if it doesn't have a fullscreen intent
                return !mKeyguardMonitor.isShowing()
                        || mShadeController.isOccluded();
            }
        }
        return true;
    }

    @Override
    public void onUserSwitched(int newUserId) {
        // Begin old BaseStatusBar.userSwitched
        mHeadsUpManager.setUser(newUserId);
        // End old BaseStatusBar.userSwitched
        if (MULTIUSER_DEBUG) mNotificationPanelDebugText.setText("USER " + newUserId);
        mCommandQueue.animateCollapsePanels();
        if (mReinflateNotificationsOnUserSwitched) {
            mEntryManager.updateNotificationsOnDensityOrFontScaleChanged();
            mReinflateNotificationsOnUserSwitched = false;
        }
        updateNotificationViews();
        mMediaManager.clearCurrentMediaNotification();
        mShadeController.setLockscreenUser(newUserId);
        updateMediaMetaData(true, false);
    }

    @Override
    public void onBindRow(Entry entry, PackageManager pmUser,
            StatusBarNotification sbn, ExpandableNotificationRow row) {
        row.setAboveShelfChangedListener(mAboveShelfObserver);
        row.setSecureStateProvider(mUnlockMethodCache::canSkipBouncer);
    }

    @Override
    public boolean isPresenterFullyCollapsed() {
        return mNotificationPanel.isFullyCollapsed();
    }

    @Override
    public void onActivated(ActivatableNotificationView view) {
        onActivated();
        if (view != null) mNotificationPanel.setActivatedChild(view);
    }

    public void onActivated() {
        mLockscreenGestureLogger.write(
                MetricsEvent.ACTION_LS_NOTE,
                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
        mNotificationPanel.showTransientIndication(R.string.notification_tap_again);
        ActivatableNotificationView previousView = mNotificationPanel.getActivatedChild();
        if (previousView != null) {
            previousView.makeInactive(true /* animate */);
        }
    }

    @Override
    public void onActivationReset(ActivatableNotificationView view) {
        if (view == mNotificationPanel.getActivatedChild()) {
            mNotificationPanel.setActivatedChild(null);
            mShadeController.onActivationReset();
        }
    }

    @Override
    public void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation) {
        mMediaManager.updateMediaMetaData(metaDataChanged, allowEnterAnimation);
    }

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
        OnDismissAction postKeyguardAction = () -> {
            // TODO: Some of this code may be able to move to NotificationEntryManager.
            if (mHeadsUpManager != null && mHeadsUpManager.isAlerting(notificationKey)) {
                // Release the HUN notification to the shade.

                if (isPresenterFullyCollapsed()) {
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
                        mGroupManager.getLogicalGroupSummary(sbn).getStatusBarNotification();
                if (shouldAutoCancel(summarySbn)) {
                    parentToCancel = summarySbn;
                }
            }
            final StatusBarNotification parentToCancelFinal = parentToCancel;
            final Runnable runnable = () -> {
                try {
                    // The intent we are sending is for the application, which
                    // won't have permission to immediately start an activity after
                    // the user switches to home.  We know it is safe to do at this
                    // point, so make sure new activity switches are now allowed.
                    ActivityManager.getService().resumeAppSwitches();
                } catch (RemoteException e) {
                }
                int launchResult = ActivityManager.START_CANCELED;
                if (intent != null) {
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
                    Entry entry = row.getEntry();
                    CharSequence remoteInputText = null;
                    if (!TextUtils.isEmpty(entry.remoteInputText)) {
                        remoteInputText = entry.remoteInputText;
                    }
                    if (!TextUtils.isEmpty(remoteInputText)
                            && !controller.isSpinning(entry.key)) {
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
            };

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
        };
        if (showOverLockscreen) {
            mIsCollapsingToShowActivityOverLockscreen = true;
            postKeyguardAction.onDismiss();
        } else {
            mActivityStarter.dismissKeyguardThenExecute(
                    postKeyguardAction, null /* cancel */, afterKeyguardGone);
        }
    }

    private void removeNotification(StatusBarNotification notification) {
        // We have to post it to the UI thread for synchronization
        Dependency.get(MAIN_HANDLER).post(() -> {
            Runnable removeRunnable =
                    () -> mEntryManager.performRemoveNotification(notification);
            if (isCollapsing()) {
                // To avoid lags we're only performing the remove
                // after the shade was collapsed
                mShadeController.addPostCollapseAction(removeRunnable);
            } else {
                removeRunnable.run();
            }
        });
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

    @Override
    public int getMaxNotificationsWhileLocked(boolean recompute) {
        if (recompute) {
            mMaxKeyguardNotifications = Math.max(1,
                    mNotificationPanel.computeMaxKeyguardNotifications(
                            mMaxAllowedKeyguardNotifications));
            return mMaxKeyguardNotifications;
        }
        return mMaxKeyguardNotifications;
    }

    @Override
    public void onUpdateRowStates() {
        mNotificationPanel.onUpdateRowStates();
    }

    @Override
    public void onExpandClicked(Entry clickedEntry, boolean nowExpanded) {
        mHeadsUpManager.setExpanded(clickedEntry, nowExpanded);
        if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD && nowExpanded) {
            mShadeController.goToLockedShade(clickedEntry.row);
        }
    }

    @Override
    public boolean isDeviceInVrMode() {
        return mVrMode;
    }

    @Override
    public boolean isPresenterLocked() {
        return mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
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

    private void onLockedNotificationImportanceChange(OnDismissAction dismissAction) {
        mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
        mActivityStarter.dismissKeyguardThenExecute(dismissAction, null,
                true /* afterKeyguardGone */);
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

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            mVrMode = enabled;
        }
    };

    private final CheckSaveListener mCheckSaveListener = new CheckSaveListener() {
        @Override
        public void checkSave(Runnable saveImportance, StatusBarNotification sbn) {
            int state = mStatusBarStateController.getState();
            // If the user has security enabled, show challenge if the setting is changed.
            if (mLockscreenUserManager.isLockscreenPublicMode(sbn.getUser().getIdentifier())
                    && mKeyguardManager.isKeyguardLocked()) {
                onLockedNotificationImportanceChange(() -> {
                    saveImportance.run();
                    return true;
                });
            } else {
                saveImportance.run();
            }
        }
    };

    private final OnSettingsClickListener mOnSettingsClickListener = new OnSettingsClickListener() {
        @Override
        public void onSettingsClick(String key) {
            try {
                mBarService.onNotificationSettingsViewed(key);
            } catch (RemoteException e) {
                // if we're here we're dead
            }
        }
    };
}
