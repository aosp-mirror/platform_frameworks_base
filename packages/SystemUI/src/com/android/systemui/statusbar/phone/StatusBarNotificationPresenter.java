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

import static com.android.systemui.SysUiServiceProvider.getComponent;
import static com.android.systemui.statusbar.phone.StatusBar.CLOSE_PANEL_WHEN_EMPTIED;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG;
import static com.android.systemui.statusbar.phone.StatusBar.MULTIUSER_DEBUG;
import static com.android.systemui.statusbar.phone.StatusBar.SPEW;

import android.annotation.Nullable;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.StatusBarNotification;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.view.ViewGroup;
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
import com.android.systemui.statusbar.AmbientPulseManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.AboveShelfObserver;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager.OnSettingsClickListener;
import com.android.systemui.statusbar.notification.row.NotificationInfo.CheckSaveListener;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.util.ArrayList;

public class StatusBarNotificationPresenter implements NotificationPresenter,
        ConfigurationController.ConfigurationListener,
        NotificationRowBinderImpl.BindRowCallback {

    private final LockscreenGestureLogger mLockscreenGestureLogger =
            Dependency.get(LockscreenGestureLogger.class);

    private static final String TAG = "StatusBarNotificationPresenter";

    private final ShadeController mShadeController = Dependency.get(ShadeController.class);
    private final ActivityStarter mActivityStarter = Dependency.get(ActivityStarter.class);
    private final KeyguardMonitor mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
    private final NotificationViewHierarchyManager mViewHierarchyManager =
            Dependency.get(NotificationViewHierarchyManager.class);
    private final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);
    private final SysuiStatusBarStateController mStatusBarStateController =
            (SysuiStatusBarStateController) Dependency.get(StatusBarStateController.class);
    private final NotificationEntryManager mEntryManager =
            Dependency.get(NotificationEntryManager.class);
    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider =
            Dependency.get(NotificationInterruptionStateProvider.class);
    private final NotificationMediaManager mMediaManager =
            Dependency.get(NotificationMediaManager.class);
    private final VisualStabilityManager mVisualStabilityManager =
            Dependency.get(VisualStabilityManager.class);
    private final NotificationGutsManager mGutsManager =
            Dependency.get(NotificationGutsManager.class);
    protected AmbientPulseManager mAmbientPulseManager = Dependency.get(AmbientPulseManager.class);

    private final NotificationPanelView mNotificationPanel;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final AboveShelfObserver mAboveShelfObserver;
    private final DozeScrimController mDozeScrimController;
    private final ScrimController mScrimController;
    private final Context mContext;
    private final CommandQueue mCommandQueue;

    private final AccessibilityManager mAccessibilityManager;
    private final KeyguardManager mKeyguardManager;
    private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final int mMaxAllowedKeyguardNotifications;
    private final IStatusBarService mBarService;
    private boolean mReinflateNotificationsOnUserSwitched;
    private boolean mDispatchUiModeChangeOnUserSwitched;
    private final UnlockMethodCache mUnlockMethodCache;
    private TextView mNotificationPanelDebugText;

    protected boolean mVrMode;
    private int mMaxKeyguardNotifications;

    public StatusBarNotificationPresenter(Context context,
            NotificationPanelView panel,
            HeadsUpManagerPhone headsUp,
            StatusBarWindowView statusBarWindow,
            ViewGroup stackScroller,
            DozeScrimController dozeScrimController,
            ScrimController scrimController,
            ActivityLaunchAnimator activityLaunchAnimator,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            NotificationAlertingManager notificationAlertingManager,
            NotificationRowBinderImpl notificationRowBinder) {
        mContext = context;
        mNotificationPanel = panel;
        mHeadsUpManager = headsUp;
        mCommandQueue = getComponent(context, CommandQueue.class);
        mAboveShelfObserver = new AboveShelfObserver(stackScroller);
        mActivityLaunchAnimator = activityLaunchAnimator;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mAboveShelfObserver.setListener(statusBarWindow.findViewById(
                R.id.notification_container_parent));
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mDozeScrimController = dozeScrimController;
        mScrimController = scrimController;
        mUnlockMethodCache = UnlockMethodCache.getInstance(mContext);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mMaxAllowedKeyguardNotifications = context.getResources().getInteger(
                R.integer.keyguard_max_notification_count);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

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
        NotificationRemoteInputManager remoteInputManager =
                Dependency.get(NotificationRemoteInputManager.class);
        remoteInputManager.setUpWithCallback(
                Dependency.get(NotificationRemoteInputManager.Callback.class),
                mNotificationPanel.createRemoteInputDelegate());
        remoteInputManager.getController().addCallback(
                Dependency.get(StatusBarWindowController.class));

        NotificationListContainer notifListContainer = (NotificationListContainer) stackScroller;
        Dependency.get(InitController.class).addPostInitTask(() -> {
            NotificationEntryListener notificationEntryListener = new NotificationEntryListener() {
                @Override
                public void onNotificationAdded(NotificationEntry entry) {
                    // Recalculate the position of the sliding windows and the titles.
                    mShadeController.updateAreThereNotifications();
                }

                @Override
                public void onPostEntryUpdated(NotificationEntry entry) {
                    mShadeController.updateAreThereNotifications();
                }

                @Override
                public void onEntryRemoved(
                        @Nullable NotificationEntry entry,
                        NotificationVisibility visibility,
                        boolean removedByUser) {
                    StatusBarNotificationPresenter.this.onNotificationRemoved(
                            entry.key, entry.notification);
                    if (removedByUser) {
                        maybeEndAmbientPulse();
                    }
                }
            };

            mViewHierarchyManager.setUpWithPresenter(this, notifListContainer);
            mEntryManager.setUpWithPresenter(this, notifListContainer, mHeadsUpManager);
            mEntryManager.addNotificationEntryListener(notificationEntryListener);
            mEntryManager.addNotificationLifetimeExtender(mHeadsUpManager);
            mEntryManager.addNotificationLifetimeExtender(mAmbientPulseManager);
            mEntryManager.addNotificationLifetimeExtender(mGutsManager);
            mEntryManager.addNotificationLifetimeExtenders(
                    remoteInputManager.getLifetimeExtenders());
            notificationRowBinder.setUpWithPresenter(this, notifListContainer, mHeadsUpManager,
                    mEntryManager, this);
            mNotificationInterruptionStateProvider.setUpWithPresenter(
                    this, mHeadsUpManager, this::canHeadsUp);
            mLockscreenUserManager.setUpWithPresenter(this);
            mMediaManager.setUpWithPresenter(this);
            mVisualStabilityManager.setUpWithPresenter(this);
            mGutsManager.setUpWithPresenter(this,
                    notifListContainer, mCheckSaveListener, mOnSettingsClickListener);
            // ForegroundServiceControllerListener adds its listener in its constructor
            // but we need to request it here in order for it to be instantiated.
            // TODO: figure out how to do this correctly once Dependency.get() is gone.
            Dependency.get(ForegroundServiceNotificationListener.class);

            onUserSwitched(mLockscreenUserManager.getCurrentUserId());
        });
        Dependency.get(ConfigurationController.class).addCallback(this);

        notificationAlertingManager.setHeadsUpManager(mHeadsUpManager);
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        MessagingMessage.dropCache();
        MessagingGroup.dropCache();
        if (!KeyguardUpdateMonitor.getInstance(mContext).isSwitchingUser()) {
            updateNotificationsOnDensityOrFontScaleChanged();
        } else {
            mReinflateNotificationsOnUserSwitched = true;
        }
    }

    @Override
    public void onUiModeChanged() {
        if (!KeyguardUpdateMonitor.getInstance(mContext).isSwitchingUser()) {
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
        ArrayList<NotificationEntry> userNotifications
                = mEntryManager.getNotificationData().getNotificationsForCurrentUser();
        for (int i = 0; i < userNotifications.size(); i++) {
            NotificationEntry entry = userNotifications.get(i);
            ExpandableNotificationRow row = entry.getRow();
            if (row != null) {
                row.onUiModeChanged();
            }
        }
    }

    private void updateNotificationsOnDensityOrFontScaleChanged() {
        ArrayList<NotificationEntry> userNotifications =
                mEntryManager.getNotificationData().getNotificationsForCurrentUser();
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

    public boolean canHeadsUp(NotificationEntry entry, StatusBarNotification sbn) {
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
            updateNotificationsOnDensityOrFontScaleChanged();
            mReinflateNotificationsOnUserSwitched = false;
        }
        if (mDispatchUiModeChangeOnUserSwitched) {
            updateNotificationOnUiModeChanged();
            mDispatchUiModeChangeOnUserSwitched = false;
        }
        updateNotificationViews();
        mMediaManager.clearCurrentMediaNotification();
        mShadeController.setLockscreenUser(newUserId);
        updateMediaMetaData(true, false);
    }

    @Override
    public void onBindRow(NotificationEntry entry, PackageManager pmUser,
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
    public void onExpandClicked(NotificationEntry clickedEntry, boolean nowExpanded) {
        mHeadsUpManager.setExpanded(clickedEntry, nowExpanded);
        if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD && nowExpanded) {
            mShadeController.goToLockedShade(clickedEntry.getRow());
        }
    }

    @Override
    public boolean isDeviceInVrMode() {
        return mVrMode;
    }

    @Override
    public boolean isPresenterLocked() {
        return mStatusBarKeyguardViewManager.isShowing()
                && mStatusBarKeyguardViewManager.isSecure();
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
}
