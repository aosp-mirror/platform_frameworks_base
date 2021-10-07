/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.globalactions;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_GLOBAL_ACTIONS_SHOWING;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.telecom.TelecomManager;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.IWindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.lifecycle.LifecycleOwner;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.view.RotationPolicy;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;
import com.android.systemui.plugins.GlobalActionsPanelPlugin;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.leak.RotationUtils;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that may show depending
 * on whether the keyguard is showing, and whether the device is provisioned.
 * This version includes wallet.
 */
public class GlobalActionsDialog extends GlobalActionsDialogLite
        implements DialogInterface.OnDismissListener,
        DialogInterface.OnShowListener,
        ConfigurationController.ConfigurationListener,
        GlobalActionsPanelPlugin.Callbacks,
        LifecycleOwner {

    private static final String TAG = "GlobalActionsDialog";

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardStateController mKeyguardStateController;
    private final SysUiState mSysUiState;
    private final ActivityStarter mActivityStarter;
    private final SysuiColorExtractor mSysuiColorExtractor;
    private final IStatusBarService mStatusBarService;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private GlobalActionsPanelPlugin mWalletPlugin;

    @VisibleForTesting
    boolean mShowLockScreenCards = false;

    private final KeyguardStateController.Callback mKeyguardStateControllerListener =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            if (mDialog != null) {
                ActionsDialog dialog = (ActionsDialog) mDialog;
                boolean unlocked = mKeyguardStateController.isUnlocked();
                if (dialog.mWalletViewController != null) {
                    dialog.mWalletViewController.onDeviceLockStateChanged(!unlocked);
                }

                if (unlocked) {
                    dialog.hideLockMessage();
                }
            }
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            onPowerMenuLockScreenSettingsChanged();
        }
    };

    /**
     * @param context everything needs a context :(
     */
    @Inject
    public GlobalActionsDialog(
            Context context,
            GlobalActionsManager windowManagerFuncs,
            AudioManager audioManager,
            IDreamManager iDreamManager,
            DevicePolicyManager devicePolicyManager,
            LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            TelephonyListenerManager telephonyListenerManager,
            GlobalSettings globalSettings,
            SecureSettings secureSettings,
            @Nullable Vibrator vibrator,
            @Main Resources resources,
            ConfigurationController configurationController,
            ActivityStarter activityStarter,
            KeyguardStateController keyguardStateController,
            UserManager userManager,
            TrustManager trustManager,
            IActivityManager iActivityManager,
            @Nullable TelecomManager telecomManager,
            MetricsLogger metricsLogger,
            SysuiColorExtractor colorExtractor,
            IStatusBarService statusBarService,
            NotificationShadeWindowController notificationShadeWindowController,
            IWindowManager iWindowManager,
            @Background Executor backgroundExecutor,
            UiEventLogger uiEventLogger,
            RingerModeTracker ringerModeTracker,
            SysUiState sysUiState,
            @Main Handler handler,
            PackageManager packageManager,
            StatusBar statusBar) {

        super(context,
                windowManagerFuncs,
                audioManager,
                iDreamManager,
                devicePolicyManager,
                lockPatternUtils,
                broadcastDispatcher,
                telephonyListenerManager,
                globalSettings,
                secureSettings,
                vibrator,
                resources,
                configurationController,
                keyguardStateController,
                userManager,
                trustManager,
                iActivityManager,
                telecomManager,
                metricsLogger,
                colorExtractor,
                statusBarService,
                notificationShadeWindowController,
                iWindowManager,
                backgroundExecutor,
                uiEventLogger,
                null,
                ringerModeTracker,
                sysUiState,
                handler,
                packageManager,
                statusBar);

        mLockPatternUtils = lockPatternUtils;
        mKeyguardStateController = keyguardStateController;
        mSysuiColorExtractor = colorExtractor;
        mStatusBarService = statusBarService;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mSysUiState = sysUiState;
        mActivityStarter = activityStarter;

        mKeyguardStateController.addCallback(mKeyguardStateControllerListener);

        // Listen for changes to show pay on the power menu while locked
        onPowerMenuLockScreenSettingsChanged();
        mGlobalSettings.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT),
                false /* notifyForDescendants */,
                mSettingsObserver);
    }

    @Override
    public void destroy() {
        super.destroy();
        mKeyguardStateController.removeCallback(mKeyguardStateControllerListener);
        mGlobalSettings.unregisterContentObserver(mSettingsObserver);
    }

    /**
     * Show the global actions dialog (creating if necessary)
     *
     * @param keyguardShowing True if keyguard is showing
     */
    public void showOrHideDialog(boolean keyguardShowing, boolean isDeviceProvisioned,
            GlobalActionsPanelPlugin walletPlugin) {
        mWalletPlugin = walletPlugin;
        super.showOrHideDialog(keyguardShowing, isDeviceProvisioned);
    }

    /**
     * Returns the maximum number of power menu items to show based on which GlobalActions
     * layout is being used.
     */
    @VisibleForTesting
    @Override
    protected int getMaxShownPowerItems() {
        return getContext().getResources().getInteger(
                com.android.systemui.R.integer.power_menu_max_columns);
    }

    /**
     * Create the global actions dialog.
     *
     * @return A new dialog.
     */
    @Override
    protected ActionsDialogLite createDialog() {
        initDialogItems();

        ActionsDialog dialog = new ActionsDialog(getContext(), mAdapter, mOverflowAdapter,
                this::getWalletViewController, mSysuiColorExtractor,
                mStatusBarService, mNotificationShadeWindowController,
                mSysUiState, this::onRotate, isKeyguardShowing(), mPowerAdapter, getEventLogger(),
                getStatusBar());

        if (shouldShowLockMessage(dialog)) {
            dialog.showLockMessage();
        }
        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.
        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);

        return dialog;
    }

    @Nullable
    private GlobalActionsPanelPlugin.PanelViewController getWalletViewController() {
        if (mWalletPlugin == null) {
            return null;
        }
        return mWalletPlugin.onPanelShown(this, !mKeyguardStateController.isUnlocked());
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.Callbacks#dismissGlobalActionsMenu()}, which is
     * called when the quick access wallet requests that an intent be started (with lock screen
     * shown first if needed).
     */
    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent pendingIntent) {
        mActivityStarter.startPendingIntentDismissingKeyguard(pendingIntent);
    }

    @Override
    protected int getEmergencyTextColor(Context context) {
        return context.getResources().getColor(
                com.android.systemui.R.color.global_actions_emergency_text);
    }

    @Override
    protected int getEmergencyIconColor(Context context) {
        return getContext().getResources().getColor(
                com.android.systemui.R.color.global_actions_emergency_text);
    }

    @Override
    protected int getEmergencyBackgroundColor(Context context) {
        return getContext().getResources().getColor(
                com.android.systemui.R.color.global_actions_emergency_background);
    }

    @Override
    protected int getGridItemLayoutResource() {
        return com.android.systemui.R.layout.global_actions_grid_item_v2;
    }

    @VisibleForTesting
    static class ActionsDialog extends ActionsDialogLite {

        private final Provider<GlobalActionsPanelPlugin.PanelViewController> mWalletFactory;
        @Nullable private GlobalActionsPanelPlugin.PanelViewController mWalletViewController;
        private ResetOrientationData mResetOrientationData;
        @VisibleForTesting ViewGroup mLockMessageContainer;
        private TextView mLockMessage;

        ActionsDialog(Context context, MyAdapter adapter, MyOverflowAdapter overflowAdapter,
                Provider<GlobalActionsPanelPlugin.PanelViewController> walletFactory,
                SysuiColorExtractor sysuiColorExtractor, IStatusBarService statusBarService,
                NotificationShadeWindowController notificationShadeWindowController,
                SysUiState sysuiState, Runnable onRotateCallback, boolean keyguardShowing,
                MyPowerOptionsAdapter powerAdapter, UiEventLogger uiEventLogger,
                StatusBar statusBar) {
            super(context, com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActions,
                    adapter, overflowAdapter, sysuiColorExtractor, statusBarService,
                    notificationShadeWindowController, sysuiState, onRotateCallback,
                    keyguardShowing, powerAdapter, uiEventLogger, null,
                    statusBar);
            mWalletFactory = walletFactory;

            // Update window attributes
            Window window = getWindow();
            window.getAttributes().systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.setLayout(MATCH_PARENT, MATCH_PARENT);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            setTitle(R.string.global_actions);
            initializeLayout();
        }

        private boolean isWalletViewAvailable() {
            return mWalletViewController != null && mWalletViewController.getPanelContent() != null;
        }

        private void initializeWalletView() {
            if (mWalletFactory == null) {
                return;
            }
            mWalletViewController = mWalletFactory.get();
            if (!isWalletViewAvailable()) {
                return;
            }

            boolean isLandscapeWalletViewShown = mContext.getResources().getBoolean(
                    com.android.systemui.R.bool.global_actions_show_landscape_wallet_view);

            int rotation = RotationUtils.getRotation(mContext);
            boolean rotationLocked = RotationPolicy.isRotationLocked(mContext);
            if (rotation != RotationUtils.ROTATION_NONE) {
                if (rotationLocked) {
                    if (mResetOrientationData == null) {
                        mResetOrientationData = new ResetOrientationData();
                        mResetOrientationData.locked = true;
                        mResetOrientationData.rotation = rotation;
                    }

                    // Unlock rotation, so user can choose to rotate to portrait to see the panel.
                    // This call is posted so that the rotation does not change until post-layout,
                    // otherwise onConfigurationChanged() may not get invoked.
                    mGlobalActionsLayout.post(() ->
                            RotationPolicy.setRotationLockAtAngle(
                                    mContext, false, RotationUtils.ROTATION_NONE));

                    if (!isLandscapeWalletViewShown) {
                        return;
                    }
                }
            } else {
                if (!rotationLocked) {
                    if (mResetOrientationData == null) {
                        mResetOrientationData = new ResetOrientationData();
                        mResetOrientationData.locked = false;
                    }
                }

                boolean shouldLockRotation = !isLandscapeWalletViewShown;
                if (rotationLocked != shouldLockRotation) {
                    // Locks the screen to portrait if the landscape / seascape orientation does not
                    // show the wallet view, so the user doesn't accidentally hide the panel.
                    // This call is posted so that the rotation does not change until post-layout,
                    // otherwise onConfigurationChanged() may not get invoked.
                    mGlobalActionsLayout.post(() ->
                            RotationPolicy.setRotationLockAtAngle(
                            mContext, shouldLockRotation, RotationUtils.ROTATION_NONE));
                }
            }

            // Disable rotation suggestions, if enabled
            setRotationSuggestionsEnabled(false);

            FrameLayout panelContainer =
                    findViewById(com.android.systemui.R.id.global_actions_wallet);
            FrameLayout.LayoutParams panelParams =
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT);
            panelParams.topMargin = mContext.getResources().getDimensionPixelSize(
                    com.android.systemui.R.dimen.global_actions_wallet_top_margin);
            View walletView = mWalletViewController.getPanelContent();
            panelContainer.addView(walletView, panelParams);
            // Smooth transitions when wallet is resized, which can happen when a card is added
            ViewGroup root = findViewById(com.android.systemui.R.id.global_actions_grid_root);
            if (root != null) {
                walletView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    int oldHeight = ob - ot;
                    int newHeight = b - t;
                    if (oldHeight > 0 && oldHeight != newHeight) {
                        TransitionSet transition = new AutoTransition()
                                .setDuration(250)
                                .setOrdering(TransitionSet.ORDERING_TOGETHER);
                        TransitionManager.beginDelayedTransition(root, transition);
                    }
                });
            }
        }

        @Override
        protected int getLayoutResource() {
            return com.android.systemui.R.layout.global_actions_grid_v2;
        }

        @Override
        protected void initializeLayout() {
            super.initializeLayout();
            mLockMessageContainer = requireViewById(
                    com.android.systemui.R.id.global_actions_lock_message_container);
            mLockMessage = requireViewById(com.android.systemui.R.id.global_actions_lock_message);
            initializeWalletView();
            getWindow().setBackgroundDrawable(mBackgroundDrawable);
        }

        @Override
        protected void showDialog() {
            mShowing = true;
            mNotificationShadeWindowController.setRequestTopUi(true, TAG);
            mSysUiState.setFlag(SYSUI_STATE_GLOBAL_ACTIONS_SHOWING, true)
                    .commitUpdate(mContext.getDisplayId());

            ViewGroup root = (ViewGroup) mGlobalActionsLayout.getRootView();
            root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
                root.setPadding(windowInsets.getStableInsetLeft(),
                        windowInsets.getStableInsetTop(),
                        windowInsets.getStableInsetRight(),
                        windowInsets.getStableInsetBottom());
                return WindowInsets.CONSUMED;
            });

            mBackgroundDrawable.setAlpha(0);
            float xOffset = mGlobalActionsLayout.getAnimationOffsetX();
            ObjectAnimator alphaAnimator =
                    ObjectAnimator.ofFloat(mContainer, "alpha", 0f, 1f);
            alphaAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            alphaAnimator.setDuration(183);
            alphaAnimator.addUpdateListener((animation) -> {
                float animatedValue = animation.getAnimatedFraction();
                int alpha = (int) (animatedValue * mScrimAlpha * 255);
                mBackgroundDrawable.setAlpha(alpha);
            });

            ObjectAnimator xAnimator =
                    ObjectAnimator.ofFloat(mContainer, "translationX", xOffset, 0f);
            xAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            xAnimator.setDuration(350);

            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(alphaAnimator, xAnimator);
            animatorSet.start();
        }

        @Override
        protected void dismissInternal() {
            super.dismissInternal();
        }

        @Override
        protected void completeDismiss() {
            dismissWallet();
            resetOrientation();
            super.completeDismiss();
        }

        private void dismissWallet() {
            if (mWalletViewController != null) {
                mWalletViewController.onDismissed();
                // The wallet controller should not be re-used after being dismissed.
                mWalletViewController = null;
            }
        }

        private void resetOrientation() {
            if (mResetOrientationData != null) {
                RotationPolicy.setRotationLockAtAngle(mContext, mResetOrientationData.locked,
                        mResetOrientationData.rotation);
            }
            setRotationSuggestionsEnabled(true);
        }

        @Override
        public void refreshDialog() {
            // ensure dropdown menus are dismissed before re-initializing the dialog
            dismissWallet();
            super.refreshDialog();
        }

        void hideLockMessage() {
            if (mLockMessageContainer.getVisibility() == View.VISIBLE) {
                mLockMessageContainer.animate().alpha(0).setDuration(150).setListener(
                        new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                mLockMessageContainer.setVisibility(View.GONE);
                            }
                        }).start();
            }
        }

        void showLockMessage() {
            Drawable lockIcon = mContext.getDrawable(com.android.internal.R.drawable.ic_lock);
            lockIcon.setTint(mContext.getColor(com.android.systemui.R.color.control_primary_text));
            mLockMessage.setCompoundDrawablesWithIntrinsicBounds(null, lockIcon, null, null);
            mLockMessageContainer.setVisibility(View.VISIBLE);
        }

        private static class ResetOrientationData {
            public boolean locked;
            public int rotation;
        }
    }

    /**
     * Determines whether or not debug mode has been activated for the Global Actions Panel.
     */
    private static boolean isPanelDebugModeEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.GLOBAL_ACTIONS_PANEL_DEBUG_ENABLED, 0) == 1;
    }

    /**
     * Determines whether or not the Global Actions menu should be forced to use the newer
     * grid-style layout.
     */
    private static boolean isForceGridEnabled(Context context) {
        return isPanelDebugModeEnabled(context);
    }

    private boolean shouldShowLockMessage(ActionsDialog dialog) {
        return isWalletAvailableAfterUnlock(dialog);
    }

    // Temporary while we move items out of the power menu
    private boolean isWalletAvailableAfterUnlock(ActionsDialog dialog) {
        boolean isLockedAfterBoot = mLockPatternUtils.getStrongAuthForUser(getCurrentUser().id)
                == STRONG_AUTH_REQUIRED_AFTER_BOOT;
        return !mKeyguardStateController.isUnlocked()
                && (!mShowLockScreenCards || isLockedAfterBoot)
                && dialog.isWalletViewAvailable();
    }

    private void onPowerMenuLockScreenSettingsChanged() {
        mShowLockScreenCards = mSecureSettings.getInt(
                Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT, 0) != 0;
    }
}
