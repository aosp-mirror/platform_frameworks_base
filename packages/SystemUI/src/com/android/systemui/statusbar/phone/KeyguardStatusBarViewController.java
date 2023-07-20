/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_SYSTEM_INFO;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.biometrics.BiometricSourceType;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.MathUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.keyguard.CarrierTextController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.logging.KeyguardLogger;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.disableflags.DisableStateTracker;
import com.android.systemui.statusbar.events.SystemStatusAnimationCallback;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.fragment.StatusBarIconBlocklistKt;
import com.android.systemui.statusbar.phone.fragment.StatusBarSystemEventAnimator;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** View Controller for {@link com.android.systemui.statusbar.phone.KeyguardStatusBarView}. */
public class KeyguardStatusBarViewController extends ViewController<KeyguardStatusBarView> {
    private static final AnimationProperties KEYGUARD_HUN_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    private float mKeyguardHeadsUpShowingAmount = 0.0f;
    private final AnimatableProperty mHeadsUpShowingAmountAnimation = AnimatableProperty.from(
            "KEYGUARD_HEADS_UP_SHOWING_AMOUNT",
            (view, aFloat) -> {
                mKeyguardHeadsUpShowingAmount = aFloat;
                updateViewState();
            },
            view -> mKeyguardHeadsUpShowingAmount,
            R.id.keyguard_hun_animator_tag,
            R.id.keyguard_hun_animator_end_tag,
            R.id.keyguard_hun_animator_start_tag);

    private final CarrierTextController mCarrierTextController;
    private final ConfigurationController mConfigurationController;
    private final SystemStatusAnimationScheduler mAnimationScheduler;
    private final BatteryController mBatteryController;
    private final UserInfoController mUserInfoController;
    private final StatusBarIconController mStatusBarIconController;
    private final StatusBarIconController.TintedIconManager.Factory mTintedIconManagerFactory;
    private final BatteryMeterViewController mBatteryMeterViewController;
    private final NotificationPanelViewController.NotificationPanelViewStateProvider
            mNotificationPanelViewStateProvider;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final BiometricUnlockController mBiometricUnlockController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final StatusBarContentInsetsProvider mInsetsProvider;
    private final UserManager mUserManager;
    private final StatusBarUserChipViewModel mStatusBarUserChipViewModel;
    private final SecureSettings mSecureSettings;
    private final CommandQueue mCommandQueue;
    private final Executor mMainExecutor;
    private final Object mLock = new Object();
    private final KeyguardLogger mLogger;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onDensityOrFontScaleChanged() {
                    mView.loadDimens();
                    // The animator is dependent on resources for offsets
                    mSystemEventAnimator = new StatusBarSystemEventAnimator(mView, getResources());
                }

                @Override
                public void onThemeChanged() {
                    mView.onOverlayChanged();
                    KeyguardStatusBarViewController.this.onThemeChanged();
                }

                @Override
                public void onConfigChanged(Configuration newConfig) {
                    updateUserSwitcher();
                }
            };

    private final SystemStatusAnimationCallback mAnimationCallback =
            new SystemStatusAnimationCallback() {
                @NonNull
                @Override
                public Animator onSystemEventAnimationFinish(boolean hasPersistentDot) {
                    return mSystemEventAnimator.onSystemEventAnimationFinish(hasPersistentDot);
                }

                @NonNull
                @Override
                public Animator onSystemEventAnimationBegin() {
                    return mSystemEventAnimator.onSystemEventAnimationBegin();
                }
            };

    private final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback =
            new BatteryController.BatteryStateChangeCallback() {
                @Override
                public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
                    mView.onBatteryLevelChanged(charging);
                }
            };

    private final UserInfoController.OnUserInfoChangedListener mOnUserInfoChangedListener =
            (name, picture, userAccount) -> mView.onUserInfoChanged(picture);

    private final ValueAnimator.AnimatorUpdateListener mAnimatorUpdateListener =
            animation -> {
                mKeyguardStatusBarAnimateAlpha = (float) animation.getAnimatedValue();
                updateViewState();
            };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onBiometricAuthenticated(
                        int userId,
                        BiometricSourceType biometricSourceType,
                        boolean isStrongBiometric) {
                    if (mFirstBypassAttempt
                            && mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                                    isStrongBiometric)) {
                        mDelayShowingKeyguardStatusBar = true;
                    }
                }

                @Override
                public void onKeyguardVisibilityChanged(boolean visible) {
                    if (visible) {
                        updateUserSwitcher();
                    }
                }

                @Override
                public void onBiometricRunningStateChanged(
                        boolean running,
                        BiometricSourceType biometricSourceType) {
                    boolean keyguardOrShadeLocked =
                            mStatusBarState == KEYGUARD
                                    || mStatusBarState == StatusBarState.SHADE_LOCKED;
                    if (!running
                            && mFirstBypassAttempt
                            && keyguardOrShadeLocked
                            && !mDozing
                            && !mDelayShowingKeyguardStatusBar
                            && !mBiometricUnlockController.isBiometricUnlock()) {
                        mFirstBypassAttempt = false;
                        animateKeyguardStatusBarIn();
                    }
                }

                @Override
                public void onFinishedGoingToSleep(int why) {
                    mFirstBypassAttempt = mKeyguardBypassController.getBypassEnabled();
                    mDelayShowingKeyguardStatusBar = false;
                }
            };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    mStatusBarState = newState;
                }
            };


    private final DisableStateTracker mDisableStateTracker;

    private final List<String> mBlockedIcons = new ArrayList<>();
    private final int mNotificationsHeaderCollideDistance;

    private boolean mBatteryListening;
    private StatusBarIconController.TintedIconManager mTintedIconManager;

    private float mKeyguardStatusBarAnimateAlpha = 1f;
    /**
     * If face auth with bypass is running for the first time after you turn on the screen.
     * (From aod or screen off)
     */
    private boolean mFirstBypassAttempt;
    /**
     * If auth happens successfully during {@code mFirstBypassAttempt}, and we should wait until
     * the keyguard is dismissed to show the status bar.
     */
    private boolean mDelayShowingKeyguardStatusBar;
    private int mStatusBarState;
    private boolean mDozing;
    private boolean mShowingKeyguardHeadsUp;
    private StatusBarSystemEventAnimator mSystemEventAnimator;

    /**
     * The alpha value to be set on the View. If -1, this value is to be ignored.
     */
    private float mExplicitAlpha = -1f;

    @Inject
    public KeyguardStatusBarViewController(
            KeyguardStatusBarView view,
            CarrierTextController carrierTextController,
            ConfigurationController configurationController,
            SystemStatusAnimationScheduler animationScheduler,
            BatteryController batteryController,
            UserInfoController userInfoController,
            StatusBarIconController statusBarIconController,
            StatusBarIconController.TintedIconManager.Factory tintedIconManagerFactory,
            BatteryMeterViewController batteryMeterViewController,
            NotificationPanelViewController.NotificationPanelViewStateProvider
                    notificationPanelViewStateProvider,
            KeyguardStateController keyguardStateController,
            KeyguardBypassController bypassController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            BiometricUnlockController biometricUnlockController,
            SysuiStatusBarStateController statusBarStateController,
            StatusBarContentInsetsProvider statusBarContentInsetsProvider,
            UserManager userManager,
            StatusBarUserChipViewModel userChipViewModel,
            SecureSettings secureSettings,
            CommandQueue commandQueue,
            @Main Executor mainExecutor,
            KeyguardLogger logger
    ) {
        super(view);
        mCarrierTextController = carrierTextController;
        mConfigurationController = configurationController;
        mAnimationScheduler = animationScheduler;
        mBatteryController = batteryController;
        mUserInfoController = userInfoController;
        mStatusBarIconController = statusBarIconController;
        mTintedIconManagerFactory = tintedIconManagerFactory;
        mBatteryMeterViewController = batteryMeterViewController;
        mNotificationPanelViewStateProvider = notificationPanelViewStateProvider;
        mKeyguardStateController = keyguardStateController;
        mKeyguardBypassController = bypassController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mBiometricUnlockController = biometricUnlockController;
        mStatusBarStateController = statusBarStateController;
        mInsetsProvider = statusBarContentInsetsProvider;
        mUserManager = userManager;
        mStatusBarUserChipViewModel = userChipViewModel;
        mSecureSettings = secureSettings;
        mCommandQueue = commandQueue;
        mMainExecutor = mainExecutor;
        mLogger = logger;

        mFirstBypassAttempt = mKeyguardBypassController.getBypassEnabled();
        mKeyguardStateController.addCallback(
                new KeyguardStateController.Callback() {
                    @Override
                    public void onKeyguardFadingAwayChanged() {
                        if (!mKeyguardStateController.isKeyguardFadingAway()) {
                            mFirstBypassAttempt = false;
                            mDelayShowingKeyguardStatusBar = false;
                        }
                    }
                }
        );

        Resources r = getResources();
        updateBlockedIcons();
        mNotificationsHeaderCollideDistance = r.getDimensionPixelSize(
                R.dimen.header_notifications_collide_distance);

        mView.setKeyguardUserAvatarEnabled(
                !mStatusBarUserChipViewModel.getChipEnabled());
        mSystemEventAnimator = new StatusBarSystemEventAnimator(mView, r);

        mDisableStateTracker = new DisableStateTracker(
                /* mask1= */ DISABLE_SYSTEM_INFO,
                /* mask2= */ DISABLE2_SYSTEM_ICONS,
                this::updateViewState
        );
    }

    @Override
    protected void onInit() {
        super.onInit();
        mCarrierTextController.init();
        mBatteryMeterViewController.init();
    }

    @Override
    protected void onViewAttached() {
        mView.init(mStatusBarUserChipViewModel);
        mConfigurationController.addCallback(mConfigurationListener);
        mAnimationScheduler.addCallback(mAnimationCallback);
        mUserInfoController.addCallback(mOnUserInfoChangedListener);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mDisableStateTracker.startTracking(mCommandQueue, mView.getDisplay().getDisplayId());
        if (mTintedIconManager == null) {
            mTintedIconManager = mTintedIconManagerFactory.create(
                    mView.findViewById(R.id.statusIcons), StatusBarLocation.KEYGUARD);
            mTintedIconManager.setBlockList(getBlockedIcons());
            mStatusBarIconController.addIconGroup(mTintedIconManager);
        }
        mView.setOnApplyWindowInsetsListener(
                (view, windowInsets) -> mView.updateWindowInsets(windowInsets, mInsetsProvider));
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.getUriFor(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON),
                false,
                mVolumeSettingObserver,
                UserHandle.USER_ALL);
        updateUserSwitcher();
        onThemeChanged();
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
        mAnimationScheduler.removeCallback(mAnimationCallback);
        mUserInfoController.removeCallback(mOnUserInfoChangedListener);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mDisableStateTracker.stopTracking(mCommandQueue);
        mSecureSettings.unregisterContentObserver(mVolumeSettingObserver);
        if (mTintedIconManager != null) {
            mStatusBarIconController.removeIconGroup(mTintedIconManager);
        }
    }

    /** Should be called when the theme changes. */
    public void onThemeChanged() {
        mView.onThemeChanged(mTintedIconManager, mInsetsProvider);
    }

    /** Sets whether user switcher is enabled. */
    public void setKeyguardUserSwitcherEnabled(boolean enabled) {
        mView.setKeyguardUserSwitcherEnabled(enabled);
    }

    /** Sets whether this controller should listen to battery updates. */
    public void setBatteryListening(boolean listening) {
        if (listening == mBatteryListening) {
            return;
        }
        mBatteryListening = listening;
        if (mBatteryListening) {
            mBatteryController.addCallback(mBatteryStateChangeCallback);
        } else {
            mBatteryController.removeCallback(mBatteryStateChangeCallback);
        }
    }

    /** Set the view to have no top clipping. */
    public void setNoTopClipping() {
        mView.setTopClipping(0);
    }

    /**
     * Update the view's top clipping based on the value of notificationPanelTop and the view's
     * current top.
     *
     * @param notificationPanelTop the current top of the notification panel view.
     */
    public void updateTopClipping(int notificationPanelTop) {
        mView.setTopClipping(notificationPanelTop - mView.getTop());
    }

    /** Sets the dozing state. */
    public void setDozing(boolean dozing) {
        mDozing = dozing;
    }

    /** Animate the keyguard status bar in. */
    public void animateKeyguardStatusBarIn() {
        mLogger.d("animating status bar in");
        if (mDisableStateTracker.isDisabled()) {
            // If our view is disabled, don't allow us to animate in.
            return;
        }
        mView.setVisibility(View.VISIBLE);
        mView.setAlpha(0f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.addUpdateListener(mAnimatorUpdateListener);
        anim.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        anim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        anim.start();
    }

    /** Animate the keyguard status bar out. */
    public void animateKeyguardStatusBarOut(long startDelay, long duration) {
        mLogger.d("animating status bar out");
        ValueAnimator anim = ValueAnimator.ofFloat(mView.getAlpha(), 0f);
        anim.addUpdateListener(mAnimatorUpdateListener);
        anim.setStartDelay(startDelay);
        anim.setDuration(duration);
        anim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mView.setVisibility(View.INVISIBLE);
                mView.setAlpha(1f);
                mKeyguardStatusBarAnimateAlpha = 1f;
            }
        });
        anim.start();
    }

    /**
     * Updates the {@link KeyguardStatusBarView} state based on what the
     * {@link NotificationPanelViewController.NotificationPanelViewStateProvider} and other
     * controllers provide.
     */
    public void updateViewState() {
        if (!isKeyguardShowing()) {
            return;
        }

        float alphaQsExpansion = 1 - Math.min(
                1, mNotificationPanelViewStateProvider.getLockscreenShadeDragProgress() * 2);

        float newAlpha;
        if (mExplicitAlpha != -1) {
            newAlpha = mExplicitAlpha;
        } else {
            newAlpha = Math.min(getKeyguardContentsAlpha(), alphaQsExpansion)
                    * mKeyguardStatusBarAnimateAlpha
                    * (1.0f - mKeyguardHeadsUpShowingAmount);
        }

        boolean hideForBypass =
                mFirstBypassAttempt && mKeyguardUpdateMonitor.shouldListenForFace()
                        || mDelayShowingKeyguardStatusBar;
        int newVisibility =
                newAlpha != 0f
                        && !mDozing
                        && !hideForBypass
                        && !mDisableStateTracker.isDisabled()
                ? View.VISIBLE : View.INVISIBLE;

        updateViewState(newAlpha, newVisibility);
    }

    /**
     * Updates the {@link KeyguardStatusBarView} state based on the provided values.
     */
    public void updateViewState(float alpha, int visibility) {
        if (mDisableStateTracker.isDisabled()) {
            visibility = View.INVISIBLE;
        }
        mView.setAlpha(alpha);
        mView.setVisibility(visibility);
    }

    /**
     * @return the alpha to be used to fade out the contents on Keyguard (status bar, bottom area)
     * during swiping up.
     */
    private float getKeyguardContentsAlpha() {
        float alpha;
        if (isKeyguardShowing()) {
            // When on Keyguard, we hide the header as soon as we expanded close enough to the
            // header
            alpha = mNotificationPanelViewStateProvider.getPanelViewExpandedHeight()
                    / (mView.getHeight() + mNotificationsHeaderCollideDistance);
        } else {
            // In SHADE_LOCKED, the top card is already really close to the header. Hide it as
            // soon as we start translating the stack.
            alpha = mNotificationPanelViewStateProvider.getPanelViewExpandedHeight()
                    / mView.getHeight();
        }
        alpha = MathUtils.saturate(alpha);
        alpha = (float) Math.pow(alpha, 0.75);
        return alpha;
    }

    /**
     * Updates visibility of the user switcher button based on {@link android.os.UserManager} state.
     */
    private void updateUserSwitcher() {
        mView.setUserSwitcherEnabled(mUserManager.isUserSwitcherEnabled(getResources().getBoolean(
                R.bool.qs_show_user_switcher_for_single_user)));
    }

    @VisibleForTesting
    void updateBlockedIcons() {
        List<String> newBlockList = StatusBarIconBlocklistKt
                .getStatusBarIconBlocklist(getResources(), mSecureSettings);

        synchronized (mLock) {
            mBlockedIcons.clear();
            mBlockedIcons.addAll(newBlockList);
        }

        mMainExecutor.execute(() -> {
            if (mTintedIconManager != null) {
                mTintedIconManager.setBlockList(getBlockedIcons());
            }
        });
    }

    @VisibleForTesting
    List<String> getBlockedIcons() {
        synchronized (mLock) {
            return new ArrayList<>(mBlockedIcons);
        }
    }

    /**
      Update {@link KeyguardStatusBarView}'s visibility based on whether keyguard is showing and
     * whether heads up is visible.
     */
    public void updateForHeadsUp() {
        updateForHeadsUp(true);
    }

    void updateForHeadsUp(boolean animate) {
        boolean showingKeyguardHeadsUp =
                isKeyguardShowing() && mNotificationPanelViewStateProvider.shouldHeadsUpBeVisible();
        if (mShowingKeyguardHeadsUp != showingKeyguardHeadsUp) {
            mShowingKeyguardHeadsUp = showingKeyguardHeadsUp;
            if (isKeyguardShowing()) {
                PropertyAnimator.setProperty(
                        mView,
                        mHeadsUpShowingAmountAnimation,
                        showingKeyguardHeadsUp ? 1.0f : 0.0f,
                        KEYGUARD_HUN_PROPERTIES,
                        animate);
            } else {
                PropertyAnimator.applyImmediately(mView, mHeadsUpShowingAmountAnimation, 0.0f);
            }
        }
    }

    private boolean isKeyguardShowing() {
        return mStatusBarState == KEYGUARD;
    }

    /** */
    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardStatusBarView:");
        pw.println("  mBatteryListening: " + mBatteryListening);
        pw.println("  mExplicitAlpha: " + mExplicitAlpha);
        pw.println("  alpha: " + mView.getAlpha());
        pw.println("  visibility: " + mView.getVisibility());
        mView.dump(pw, args);
    }

    /**
     * Sets the alpha to be set on the view.
     *
     * @param alpha a value between 0 and 1. -1 if the value is to be reset/ignored.
     */
    public void setAlpha(float alpha) {
        mExplicitAlpha = alpha;
        updateViewState();
    }

    private final ContentObserver mVolumeSettingObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            updateBlockedIcons();
        }
    };
}
