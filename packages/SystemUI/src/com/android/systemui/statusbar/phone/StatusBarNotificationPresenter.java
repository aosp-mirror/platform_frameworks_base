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

import static com.android.systemui.statusbar.phone.CentralSurfaces.CLOSE_PANEL_WHEN_EMPTIED;
import static com.android.systemui.statusbar.phone.CentralSurfaces.DEBUG;
import static com.android.systemui.statusbar.phone.CentralSurfaces.MULTIUSER_DEBUG;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.InitController;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.QuickSettingsController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.AboveShelfObserver;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.render.NotifShadeEventSource;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager.OnSettingsClickListener;
import com.android.systemui.statusbar.notification.row.NotificationInfo.CheckSaveListener;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

@CentralSurfacesComponent.CentralSurfacesScope
class StatusBarNotificationPresenter implements NotificationPresenter,
        NotificationRowBinderImpl.BindRowCallback,
        CommandQueue.Callbacks {
    private static final String TAG = "StatusBarNotificationPresenter";

    private final ActivityStarter mActivityStarter;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final NotifShadeEventSource mNotifShadeEventSource;
    private final NotificationMediaManager mMediaManager;
    private final NotificationGutsManager mGutsManager;
    private final LockscreenGestureLogger mLockscreenGestureLogger;

    private final NotificationPanelViewController mNotificationPanel;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final AboveShelfObserver mAboveShelfObserver;
    private final DozeScrimController mDozeScrimController;
    private final KeyguardIndicationController mKeyguardIndicationController;
    private final CentralSurfaces mCentralSurfaces;
    private final LockscreenShadeTransitionController mShadeTransitionController;
    private final CommandQueue mCommandQueue;

    private final AccessibilityManager mAccessibilityManager;
    private final KeyguardManager mKeyguardManager;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final NotifPipelineFlags mNotifPipelineFlags;
    private final IStatusBarService mBarService;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final NotificationListContainer mNotifListContainer;
    private final QuickSettingsController mQsController;

    protected boolean mVrMode;

    @Inject
    StatusBarNotificationPresenter(
            Context context,
            NotificationPanelViewController panel,
            QuickSettingsController quickSettingsController,
            HeadsUpManagerPhone headsUp,
            NotificationShadeWindowView statusBarWindow,
            ActivityStarter activityStarter,
            NotificationStackScrollLayoutController stackScrollerController,
            DozeScrimController dozeScrimController,
            NotificationShadeWindowController notificationShadeWindowController,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardStateController keyguardStateController,
            KeyguardIndicationController keyguardIndicationController,
            CentralSurfaces centralSurfaces,
            LockscreenShadeTransitionController shadeTransitionController,
            CommandQueue commandQueue,
            NotificationLockscreenUserManager lockscreenUserManager,
            SysuiStatusBarStateController sysuiStatusBarStateController,
            NotifShadeEventSource notifShadeEventSource,
            NotificationMediaManager notificationMediaManager,
            NotificationGutsManager notificationGutsManager,
            LockscreenGestureLogger lockscreenGestureLogger,
            InitController initController,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            NotificationRemoteInputManager remoteInputManager,
            NotifPipelineFlags notifPipelineFlags,
            NotificationRemoteInputManager.Callback remoteInputManagerCallback,
            NotificationListContainer notificationListContainer) {
        mActivityStarter = activityStarter;
        mKeyguardStateController = keyguardStateController;
        mNotificationPanel = panel;
        mQsController = quickSettingsController;
        mHeadsUpManager = headsUp;
        mDynamicPrivacyController = dynamicPrivacyController;
        mKeyguardIndicationController = keyguardIndicationController;
        // TODO: use KeyguardStateController#isOccluded to remove this dependency
        mCentralSurfaces = centralSurfaces;
        mShadeTransitionController = shadeTransitionController;
        mCommandQueue = commandQueue;
        mLockscreenUserManager = lockscreenUserManager;
        mStatusBarStateController = sysuiStatusBarStateController;
        mNotifShadeEventSource = notifShadeEventSource;
        mMediaManager = notificationMediaManager;
        mGutsManager = notificationGutsManager;
        mLockscreenGestureLogger = lockscreenGestureLogger;
        mAboveShelfObserver = new AboveShelfObserver(stackScrollerController.getView());
        mNotificationShadeWindowController = notificationShadeWindowController;
        mNotifPipelineFlags = notifPipelineFlags;
        mAboveShelfObserver.setListener(statusBarWindow.findViewById(
                R.id.notification_container_parent));
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mDozeScrimController = dozeScrimController;
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mNotifListContainer = notificationListContainer;

        IVrManager vrManager = IVrManager.Stub.asInterface(ServiceManager.getService(
                Context.VR_SERVICE));
        if (vrManager != null) {
            try {
                vrManager.registerListener(mVrStateCallbacks);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register VR mode state listener: " + e);
            }
        }
        remoteInputManager.setUpWithCallback(
                remoteInputManagerCallback,
                mNotificationPanel.createRemoteInputDelegate());

        initController.addPostInitTask(() -> {
            mKeyguardIndicationController.init();
            mNotifShadeEventSource.setShadeEmptiedCallback(this::maybeClosePanelForShadeEmptied);
            mNotifShadeEventSource.setNotifRemovedByUserCallback(this::maybeEndAmbientPulse);
            notificationInterruptStateProvider.addSuppressor(mInterruptSuppressor);
            mLockscreenUserManager.setUpWithPresenter(this);
            mGutsManager.setUpWithPresenter(
                    this, mNotifListContainer, mOnSettingsClickListener);

            onUserSwitched(mLockscreenUserManager.getCurrentUserId());
        });
    }

    /** Called when the shade has been emptied to attempt to close the shade */
    private void maybeClosePanelForShadeEmptied() {
        if (CLOSE_PANEL_WHEN_EMPTIED
                && !mNotificationPanel.isTracking()
                && !mQsController.getExpanded()
                && mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED
                && !isCollapsing()) {
            mStatusBarStateController.setState(StatusBarState.KEYGUARD);
        }
    }

    @Override
    public boolean isCollapsing() {
        return mNotificationPanel.isCollapsing()
                || mNotificationShadeWindowController.isLaunchingActivity();
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
    public void onUserSwitched(int newUserId) {
        // Begin old BaseStatusBar.userSwitched
        mHeadsUpManager.setUser(newUserId);
        // End old BaseStatusBar.userSwitched
        if (MULTIUSER_DEBUG) mNotificationPanel.setHeaderDebugInfo("USER " + newUserId);
        mCommandQueue.animateCollapsePanels();
        mMediaManager.clearCurrentMediaNotification();
        mCentralSurfaces.setLockscreenUser(newUserId);
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
    public void onExpandClicked(NotificationEntry clickedEntry, View clickedView,
            boolean nowExpanded) {
        mHeadsUpManager.setExpanded(clickedEntry, nowExpanded);
        mCentralSurfaces.wakeUpIfDozing(
                SystemClock.uptimeMillis(), clickedView, "NOTIFICATION_CLICK",
                PowerManager.WAKE_REASON_GESTURE);
        if (nowExpanded) {
            if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                mShadeTransitionController.goToLockedShade(clickedEntry.getRow());
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
            if (mCentralSurfaces.isOccluded()) {
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

            if (sbn.getNotification().fullScreenIntent != null
                    && !mNotifPipelineFlags.fullScreenIntentRequiresKeyguard()) {
                // we don't allow head-up on the lockscreen (unless there's a
                // "showWhenLocked" activity currently showing)  if
                // the potential HUN has a fullscreen intent
                if (mKeyguardStateController.isShowing() && !mCentralSurfaces.isOccluded()) {
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
            return mCentralSurfaces.areNotificationAlertsDisabled();
        }
    };
}
