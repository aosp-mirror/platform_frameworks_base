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

import static com.android.systemui.statusbar.phone.StatusBar.CLOSE_PANEL_WHEN_EMPTIED;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG;
import static com.android.systemui.statusbar.phone.StatusBar.MULTIUSER_DEBUG;
import static com.android.systemui.statusbar.phone.StatusBar.SPEW;

import android.annotation.Nullable;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Log;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.MessagingGroup;
import com.android.internal.widget.MessagingMessage;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.ForegroundServiceNotificationListener;
import com.android.systemui.InitController;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.AboveShelfObserver;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager.OnSettingsClickListener;
import com.android.systemui.statusbar.notification.row.NotificationInfo.CheckSaveListener;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.List;

public class StatusBarNotificationPresenter implements NotificationPresenter,
        ConfigurationController.ConfigurationListener,
        NotificationRowBinderImpl.BindRowCallback,
        CommandQueue.Callbacks {

    private final LockscreenGestureLogger mLockscreenGestureLogger =
            Dependency.get(LockscreenGestureLogger.class);

    private static final String TAG = "StatusBarNotificationPresenter";

    private final ActivityStarter mActivityStarter = Dependency.get(ActivityStarter.class);
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationViewHierarchyManager mViewHierarchyManager =
            Dependency.get(NotificationViewHierarchyManager.class);
    private final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);
    private final SysuiStatusBarStateController mStatusBarStateController =
            (SysuiStatusBarStateController) Dependency.get(StatusBarStateController.class);
    private final NotificationEntryManager mEntryManager =
            Dependency.get(NotificationEntryManager.class);
    private final NotificationMediaManager mMediaManager =
            Dependency.get(NotificationMediaManager.class);
    private final VisualStabilityManager mVisualStabilityManager =
            Dependency.get(VisualStabilityManager.class);
    private final NotificationGutsManager mGutsManager =
            Dependency.get(NotificationGutsManager.class);

    private final NotificationPanelViewController mNotificationPanel;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final AboveShelfObserver mAboveShelfObserver;
    private final DozeScrimController mDozeScrimController;
    private final ScrimController mScrimController;
    private final KeyguardIndicationController mKeyguardIndicationController;
    private final StatusBar mStatusBar;
    private final ShadeController mShadeController;
    private final CommandQueue mCommandQueue;

    private final AccessibilityManager mAccessibilityManager;
    private final KeyguardManager mKeyguardManager;
    private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private final IStatusBarService mBarService;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private boolean mReinflateNotificationsOnUserSwitched;
    private boolean mDispatchUiModeChangeOnUserSwitched;
    private TextView mNotificationPanelDebugText;

    protected boolean mVrMode;

    public StatusBarNotificationPresenter(Context context,
            NotificationPanelViewController panel,
            HeadsUpManagerPhone headsUp,
            NotificationShadeWindowView statusBarWindow,
            NotificationStackScrollLayoutController stackScrollerController,
            DozeScrimController dozeScrimController,
            ScrimController scrimController,
            ActivityLaunchAnimator activityLaunchAnimator,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardStateController keyguardStateController,
            KeyguardIndicationController keyguardIndicationController,
            StatusBar statusBar,
            ShadeController shadeController,
            CommandQueue commandQueue,
            InitController initController,
            NotificationInterruptStateProvider notificationInterruptStateProvider) {
        mKeyguardStateController = keyguardStateController;
        mNotificationPanel = panel;
        mHeadsUpManager = headsUp;
        mDynamicPrivacyController = dynamicPrivacyController;
        mKeyguardIndicationController = keyguardIndicationController;
        // TODO: use KeyguardStateController#isOccluded to remove this dependency
        mStatusBar = statusBar;
        mShadeController = shadeController;
        mCommandQueue = commandQueue;
        mAboveShelfObserver = new AboveShelfObserver(stackScrollerController.getView());
        mActivityLaunchAnimator = activityLaunchAnimator;
        mAboveShelfObserver.setListener(statusBarWindow.findViewById(
                R.id.notification_container_parent));
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mDozeScrimController = dozeScrimController;
        mScrimController = scrimController;
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        IVrManager vrManager = IVrManager.Stub.asInterface(ServiceManager.getService(
                Context.VR_SERVICE));
        if (vrManager != null) {
            try {
                vrManager.registerListener(mVrStateCallbacks);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register VR mode state listener: " + e);
            }
        }
        NotificationRemoteInputManager remoteInputManager =
                Dependency.get(NotificationRemoteInputManager.class);
        remoteInputManager.setUpWithCallback(
                Dependency.get(NotificationRemoteInputManager.Callback.class),
                mNotificationPanel.createRemoteInputDelegate());
        remoteInputManager.getController().addCallback(
                Dependency.get(NotificationShadeWindowController.class));

        initController.addPostInitTask(() -> {
            NotificationEntryListener notificationEntryListener = new NotificationEntryListener() {
                @Override
                public void onEntryRemoved(
                        @Nullable NotificationEntry entry,
                        NotificationVisibility visibility,
                        boolean removedByUser,
                        int reason) {
                    StatusBarNotificationPresenter.this.onNotificationRemoved(
                            entry.getKey(), entry.getSbn(), reason);
                    if (removedByUser) {
                        maybeEndAmbientPulse();
                    }
                }
            };

            mViewHierarchyManager.setUpWithPresenter(this,
                    stackScrollerController.getNotificationListContainer());
            mEntryManager.setUpWithPresenter(this);
            mEntryManager.addNotificationEntryListener(notificationEntryListener);
            mEntryManager.addNotificationLifetimeExtender(mHeadsUpManager);
            mEntryManager.addNotificationLifetimeExtender(mGutsManager);
            mEntryManager.addNotificationLifetimeExtenders(
                    remoteInputManager.getLifetimeExtenders());
            notificationInterruptStateProvider.addSuppressor(mInterruptSuppressor);
            mLockscreenUserManager.setUpWithPresenter(this);
            mMediaManager.setUpWithPresenter(this);
            mGutsManager.setUpWithPresenter(this,
                    stackScrollerController.getNotificationListContainer(), mCheckSaveListener,
                    mOnSettingsClickListener);
            // ForegroundServiceNotificationListener adds its listener in its constructor
            // but we need to request it here in order for it to be instantiated.
            // TODO: figure out how to do this correctly once Dependency.get() is gone.
            Dependency.get(ForegroundServiceNotificationListener.class);

            onUserSwitched(mLockscreenUserManager.getCurrentUserId());
        });
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        MessagingMessage.dropCache();
        MessagingGroup.dropCache();
        if (!Dependency.get(KeyguardUpdateMonitor.class).isSwitchingUser()) {
            updateNotificationsOnDensityOrFontScaleChanged();
        } else {
            mReinflateNotificationsOnUserSwitched = true;
        }
    }

    @Override
    public void onUiModeChanged() {
        if (!Dependency.get(KeyguardUpdateMonitor.class).isSwitchingUser()) {
            updateNotificationOnUiModeChanged();
        } else {
            mDispatchUiModeChangeOnUserSwitched = true;
        }
    }

    @Override
    public void onOverlayChanged() {
        onDensityOrFontScaleChanged();
    }

    private void updateNotificationOnUiModeChanged() {
        List<NotificationEntry> userNotifications =
                mEntryManager.getActiveNotificationsForCurrentUser();
        for (int i = 0; i < userNotifications.size(); i++) {
            NotificationEntry entry = userNotifications.get(i);
            ExpandableNotificationRow row = entry.getRow();
            if (row != null) {
                row.onUiModeChanged();
            }
        }
    }

    private void updateNotificationsOnDensityOrFontScaleChanged() {
        List<NotificationEntry> userNotifications =
                mEntryManager.getActiveNotificationsForCurrentUser();
        for (int i = 0; i < userNotifications.size(); i++) {
            NotificationEntry entry = userNotifications.get(i);
            entry.onDensityOrFontScaleChanged();
            boolean exposedGuts = entry.areGutsExposed();
            if (exposedGuts) {
                mGutsManager.onDensityOrFontScaleChanged(entry);
            }
        }
    }

    @Override
    public boolean isCollapsing() {
        return mNotificationPanel.isCollapsing()
                || mActivityLaunchAnimator.isAnimationPending()
                || mActivityLaunchAnimator.isAnimationRunning();
    }

    private void maybeEndAmbientPulse() {
        if (mNotificationPanel.hasPulsingNotifications() &&
                !mHeadsUpManager.hasNotifications()) {
            // We were showing a pulse for a notification, but no notifications are pulsing anymore.
            // Finish the pulse.
            mDozeScrimController.pulseOutNow();
        }
    }

    @Override
    public void updateNotificationViews(final String reason) {
        // The function updateRowStates depends on both of these being non-null, so check them here.
        // We may be called before they are set from DeviceProvisionedController's callback.
        if (mScrimController == null) return;

        // Do not modify the notifications during collapse.
        if (isCollapsing()) {
            mShadeController.addPostCollapseAction(() -> updateNotificationViews(reason));
            return;
        }

        mViewHierarchyManager.updateNotificationViews();

        mNotificationPanel.updateNotificationViews(reason);
    }

    private void onNotificationRemoved(String key, StatusBarNotification old, int reason) {
        if (SPEW) Log.d(TAG, "removeNotification key=" + key + " old=" + old);

        if (old != null) {
            if (CLOSE_PANEL_WHEN_EMPTIED && !hasActiveNotifications()
                    && !mNotificationPanel.isTracking() && !mNotificationPanel.isQsExpanded()) {
                if (mStatusBarStateController.getState() == StatusBarState.SHADE
                        && reason != NotificationListenerService.REASON_CLICK) {
                    mCommandQueue.animateCollapsePanels();
                } else if (mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED
                        && !isCollapsing()) {
                    mStatusBarStateController.setState(StatusBarState.KEYGUARD);
                }
            }
        }
    }

    public boolean hasActiveNotifications() {
        return mEntryManager.hasActiveNotifications();
    }

    @Override
    public void onUserSwitched(int newUserId) {
        // Begin old BaseStatusBar.userSwitched
        mHeadsUpManager.setUser(newUserId);
        // End old BaseStatusBar.userSwitched
        if (MULTIUSER_DEBUG) mNotificationPanel.setHeaderDebugInfo("USER " + newUserId);
        mCommandQueue.animateCollapsePanels();
        if (mReinflateNotificationsOnUserSwitched) {
            updateNotificationsOnDensityOrFontScaleChanged();
            mReinflateNotificationsOnUserSwitched = false;
        }
        if (mDispatchUiModeChangeOnUserSwitched) {
            updateNotificationOnUiModeChanged();
            mDispatchUiModeChangeOnUserSwitched = false;
        }
        updateNotificationViews("user switched");
        mMediaManager.clearCurrentMediaNotification();
        mStatusBar.setLockscreenUser(newUserId);
        updateMediaMetaData(true, false);
    }

    @Override
    public void onBindRow(ExpandableNotificationRow row) {
        row.setAboveShelfChangedListener(mAboveShelfObserver);
        row.setSecureStateProvider(mKeyguardStateController::canDismissLockScreen);
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
        mLockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_NOTIFICATION_FALSE_TOUCH);
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
            mKeyguardIndicationController.hideTransientIndication();
        }
    }

    @Override
    public void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation) {
        mMediaManager.updateMediaMetaData(metaDataChanged, allowEnterAnimation);
    }

    @Override
    public void onUpdateRowStates() {
        mNotificationPanel.onUpdateRowStates();
    }

    @Override
    public void onExpandClicked(NotificationEntry clickedEntry, boolean nowExpanded) {
        mHeadsUpManager.setExpanded(clickedEntry, nowExpanded);
        if (nowExpanded) {
            if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                mShadeController.goToLockedShade(clickedEntry.getRow());
            } else if (clickedEntry.isSensitive()
                    && mDynamicPrivacyController.isInLockedDownShade()) {
                mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
                mActivityStarter.dismissKeyguardThenExecute(() -> false /* dismissAction */
                        , null /* cancelRunnable */, false /* afterKeyguardGone */);
            }
        }
    }

    @Override
    public boolean isDeviceInVrMode() {
        return mVrMode;
    }

    private void onLockedNotificationImportanceChange(OnDismissAction dismissAction) {
        mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
        mActivityStarter.dismissKeyguardThenExecute(dismissAction, null,
                true /* afterKeyguardGone */);
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

    private final NotificationInterruptSuppressor mInterruptSuppressor =
            new NotificationInterruptSuppressor() {
        @Override
        public String getName() {
            return TAG;
        }

        @Override
        public boolean suppressAwakeHeadsUp(NotificationEntry entry) {
            final StatusBarNotification sbn = entry.getSbn();
            if (mStatusBar.isOccluded()) {
                boolean devicePublic = mLockscreenUserManager
                        .isLockscreenPublicMode(mLockscreenUserManager.getCurrentUserId());
                boolean userPublic = devicePublic
                        || mLockscreenUserManager.isLockscreenPublicMode(sbn.getUserId());
                boolean needsRedaction = mLockscreenUserManager.needsRedaction(entry);
                if (userPublic && needsRedaction) {
                    // TODO(b/135046837): we can probably relax this with dynamic privacy
                    return true;
                }
            }

            if (!mCommandQueue.panelsEnabled()) {
                if (DEBUG) {
                    Log.d(TAG, "No heads up: disabled panel : " + sbn.getKey());
                }
                return true;
            }

            if (sbn.getNotification().fullScreenIntent != null) {
                // we don't allow head-up on the lockscreen (unless there's a
                // "showWhenLocked" activity currently showing)  if
                // the potential HUN has a fullscreen intent
                if (mKeyguardStateController.isShowing() && !mStatusBar.isOccluded()) {
                    if (DEBUG) {
                        Log.d(TAG, "No heads up: entry has fullscreen intent on lockscreen "
                                + sbn.getKey());
                    }
                    return true;
                }

                if (mAccessibilityManager.isTouchExplorationEnabled()) {
                    if (DEBUG) {
                        Log.d(TAG, "No heads up: accessible fullscreen: " + sbn.getKey());
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean suppressAwakeInterruptions(NotificationEntry entry) {
            return isDeviceInVrMode();
        }

        @Override
        public boolean suppressInterruptions(NotificationEntry entry) {
            return mStatusBar.areNotificationAlertsDisabled();
        }
    };
}
