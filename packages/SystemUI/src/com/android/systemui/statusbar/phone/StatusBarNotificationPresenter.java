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

import static com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.BUBBLE;
import static com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PEEK;
import static com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PULSE;
import static com.android.systemui.statusbar.phone.CentralSurfaces.CLOSE_PANEL_WHEN_EMPTIED;
import static com.android.systemui.statusbar.phone.CentralSurfaces.DEBUG;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.StatusBarNotification;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Log;
import android.util.Slog;
import android.view.View;

import androidx.annotation.NonNull;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.InitController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.res.R;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.QuickSettingsController;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.statusbar.CommandQueue;
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
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.NotifShadeEventSource;
import com.android.systemui.statusbar.notification.domain.interactor.NotificationAlertsInteractor;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionCondition;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionFilter;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionRefactor;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager.OnSettingsClickListener;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.Set;

import javax.inject.Inject;

@SysUISingleton
class StatusBarNotificationPresenter implements NotificationPresenter, CommandQueue.Callbacks {
    private static final String TAG = "StatusBarNotificationPresenter";

    private final ActivityStarter mActivityStarter;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final NotifShadeEventSource mNotifShadeEventSource;
    private final NotificationMediaManager mMediaManager;
    private final NotificationGutsManager mGutsManager;
    private final ShadeViewController mNotificationPanel;
    private final PanelExpansionInteractor mPanelExpansionInteractor;
    private final HeadsUpManager mHeadsUpManager;
    private final AboveShelfObserver mAboveShelfObserver;
    private final DozeScrimController mDozeScrimController;
    private final NotificationAlertsInteractor mNotificationAlertsInteractor;
    private final NotificationStackScrollLayoutController mNsslController;
    private final LockscreenShadeTransitionController mShadeTransitionController;
    private final PowerInteractor mPowerInteractor;
    private final CommandQueue mCommandQueue;
    private final KeyguardManager mKeyguardManager;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final IStatusBarService mBarService;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final NotificationListContainer mNotifListContainer;
    private final QuickSettingsController mQsController;

    protected boolean mVrMode;

    @Inject
    StatusBarNotificationPresenter(
            Context context,
            ShadeViewController panel,
            PanelExpansionInteractor panelExpansionInteractor,
            QuickSettingsController quickSettingsController,
            HeadsUpManager headsUp,
            NotificationShadeWindowView statusBarWindow,
            ActivityStarter activityStarter,
            NotificationStackScrollLayoutController stackScrollerController,
            DozeScrimController dozeScrimController,
            NotificationShadeWindowController notificationShadeWindowController,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardStateController keyguardStateController,
            NotificationAlertsInteractor notificationAlertsInteractor,
            LockscreenShadeTransitionController shadeTransitionController,
            PowerInteractor powerInteractor,
            CommandQueue commandQueue,
            NotificationLockscreenUserManager lockscreenUserManager,
            SysuiStatusBarStateController sysuiStatusBarStateController,
            NotifShadeEventSource notifShadeEventSource,
            NotificationMediaManager notificationMediaManager,
            NotificationGutsManager notificationGutsManager,
            InitController initController,
            VisualInterruptionDecisionProvider visualInterruptionDecisionProvider,
            NotificationRemoteInputManager remoteInputManager,
            NotificationRemoteInputManager.Callback remoteInputManagerCallback,
            NotificationListContainer notificationListContainer) {
        mActivityStarter = activityStarter;
        mKeyguardStateController = keyguardStateController;
        mNotificationPanel = panel;
        mPanelExpansionInteractor = panelExpansionInteractor;
        mQsController = quickSettingsController;
        mHeadsUpManager = headsUp;
        mDynamicPrivacyController = dynamicPrivacyController;
        mNotificationAlertsInteractor = notificationAlertsInteractor;
        mNsslController = stackScrollerController;
        mShadeTransitionController = shadeTransitionController;
        mPowerInteractor = powerInteractor;
        mCommandQueue = commandQueue;
        mLockscreenUserManager = lockscreenUserManager;
        mStatusBarStateController = sysuiStatusBarStateController;
        mNotifShadeEventSource = notifShadeEventSource;
        mMediaManager = notificationMediaManager;
        mGutsManager = notificationGutsManager;
        mAboveShelfObserver = new AboveShelfObserver(stackScrollerController.getView());
        mNotificationShadeWindowController = notificationShadeWindowController;
        mAboveShelfObserver.setListener(statusBarWindow.findViewById(
                R.id.notification_container_parent));
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
                mNsslController.createDelegate());

        initController.addPostInitTask(() -> {
            mNotifShadeEventSource.setShadeEmptiedCallback(this::maybeClosePanelForShadeEmptied);
            mNotifShadeEventSource.setNotifRemovedByUserCallback(this::maybeEndAmbientPulse);
            if (VisualInterruptionRefactor.isEnabled()) {
                visualInterruptionDecisionProvider.addCondition(mAlertsDisabledCondition);
                visualInterruptionDecisionProvider.addCondition(mVrModeCondition);
                visualInterruptionDecisionProvider.addFilter(mNeedsRedactionFilter);
                visualInterruptionDecisionProvider.addCondition(mPanelsDisabledCondition);
            } else {
                visualInterruptionDecisionProvider.addLegacySuppressor(mInterruptSuppressor);
            }
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
        return mPanelExpansionInteractor.isCollapsing()
                || mNotificationShadeWindowController.isLaunchingActivity();
    }

    private void maybeEndAmbientPulse() {
        if (mNsslController.getNotificationListContainer().hasPulsingNotifications()
                && !mHeadsUpManager.hasNotifications()) {
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
        mCommandQueue.animateCollapsePanels();
        mMediaManager.clearCurrentMediaNotification();
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
    public void onExpandClicked(NotificationEntry clickedEntry, View clickedView,
            boolean nowExpanded) {
        mHeadsUpManager.setExpanded(clickedEntry, nowExpanded);
        mPowerInteractor.wakeUpIfDozing("NOTIFICATION_CLICK", PowerManager.WAKE_REASON_GESTURE);
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
            if (mKeyguardStateController.isOccluded()) {
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

            return false;
        }

        @Override
        public boolean suppressAwakeInterruptions(NotificationEntry entry) {
            return isDeviceInVrMode();
        }

        @Override
        public boolean suppressInterruptions(NotificationEntry entry) {
            return !mNotificationAlertsInteractor.areNotificationAlertsEnabled();
        }
    };

    private final VisualInterruptionCondition mAlertsDisabledCondition =
            new VisualInterruptionCondition(Set.of(PEEK, PULSE, BUBBLE),
                    "notification alerts disabled") {
                @Override
                public boolean shouldSuppress() {
                    return !mNotificationAlertsInteractor.areNotificationAlertsEnabled();
                }
            };

    private final VisualInterruptionCondition mVrModeCondition =
            new VisualInterruptionCondition(Set.of(PEEK, BUBBLE), "device is in VR mode") {
                @Override
                public boolean shouldSuppress() {
                    return isDeviceInVrMode();
                }
            };

    private final VisualInterruptionFilter mNeedsRedactionFilter =
            new VisualInterruptionFilter(Set.of(PEEK), "needs redaction on public lockscreen") {
                @Override
                public boolean shouldSuppress(@NonNull NotificationEntry entry) {
                    if (!mKeyguardStateController.isOccluded()) {
                        return false;
                    }

                    if (!mLockscreenUserManager.needsRedaction(entry)) {
                        return false;
                    }

                    final int currentUserId = mLockscreenUserManager.getCurrentUserId();
                    final boolean currentUserPublic = mLockscreenUserManager.isLockscreenPublicMode(
                            currentUserId);

                    final int notificationUserId = entry.getSbn().getUserId();
                    final boolean notificationUserPublic =
                            mLockscreenUserManager.isLockscreenPublicMode(notificationUserId);

                    // TODO(b/135046837): we can probably relax this with dynamic privacy
                    return currentUserPublic || notificationUserPublic;
                }
            };

    private final VisualInterruptionCondition mPanelsDisabledCondition =
            new VisualInterruptionCondition(Set.of(PEEK), "disabled panel") {
                @Override
                public boolean shouldSuppress() {
                    return !mCommandQueue.panelsEnabled();
                }
            };
}
