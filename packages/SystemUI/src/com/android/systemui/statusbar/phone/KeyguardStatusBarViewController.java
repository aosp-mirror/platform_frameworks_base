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
import androidx.core.animation.Animator;
import androidx.core.animation.AnimatorListenerAdapter;
import androidx.core.animation.ValueAnimator;

import com.android.app.animation.InterpolatorsAndroidX;
import com.android.keyguard.CarrierTextController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.logging.KeyguardLogger;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.log.core.LogLevel;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.shade.ShadeViewStateProvider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationMediaManager;
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
import com.android.systemui.statusbar.phone.fragment.StatusBarSystemEventDefaultAnimator;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.ui.binder.KeyguardStatusBarViewBinder;
import com.android.systemui.statusbar.ui.viewmodel.KeyguardStatusBarViewModel;
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.SecureSettings;

import kotlin.Unit;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** View Controller for {@link com.android.systemui.statusbar.phone.KeyguardStatusBarView}. */
public class KeyguardStatusBarViewController extends ViewController<KeyguardStatusBarView> {
    private static final String TAG = "KeyguardStatusBarViewController";
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
    private final ShadeViewStateProvider mShadeViewStateProvider;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardStatusBarViewModel mKeyguardStatusBarViewModel;
    private final BiometricUnlockController mBiometricUnlockController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final StatusBarContentInsetsProvider mInsetsProvider;
    private final FeatureFlags mFeatureFlags;
    private final UserManager mUserManager;
    private final StatusBarUserChipViewModel mStatusBarUserChipViewModel;
    private final SecureSettings mSecureSettings;
    private final CommandQueue mCommandQueue;
    private final Executor mMainExecutor;
    private final Object mLock = new Object();
    private final KeyguardLogger mLogger;

    private View mSystemIconsContainer;
    private final StatusOverlayHoverListenerFactory mStatusOverlayHoverListenerFactory;

    // TODO(b/273443374): remove
    private NotificationMediaManager mNotificationMediaManager;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onDensityOrFontScaleChanged() {
                    mView.loadDimens();
                    // The animator is dependent on resources for offsets
                    mSystemEventAnimator =
                            getSystemEventAnimator(mSystemEventAnimator.isAnimationRunning());
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
                    mView.onBatteryChargingChanged(charging);
                }
            };

    private final UserInfoController.OnUserInfoChangedListener mOnUserInfoChangedListener =
            (name, picture, userAccount) -> mView.onUserInfoChanged(picture);

    private final ValueAnimator.AnimatorUpdateListener mAnimatorUpdateListener =
            animation -> {
                mKeyguardStatusBarAnimateAlpha =
                        (float) ((ValueAnimator) animation).getAnimatedValue();
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
    private StatusBarSystemEventDefaultAnimator mSystemEventAnimator;
    private float mSystemEventAnimatorAlpha = 1;

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
            ShadeViewStateProvider shadeViewStateProvider,
            KeyguardStateController keyguardStateController,
            KeyguardBypassController bypassController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardStatusBarViewModel keyguardStatusBarViewModel,
            BiometricUnlockController biometricUnlockController,
            SysuiStatusBarStateController statusBarStateController,
            StatusBarContentInsetsProvider statusBarContentInsetsProvider,
            FeatureFlags featureFlags,
            UserManager userManager,
            StatusBarUserChipViewModel userChipViewModel,
            SecureSettings secureSettings,
            CommandQueue commandQueue,
            @Main Executor mainExecutor,
            KeyguardLogger logger,
            NotificationMediaManager notificationMediaManager,
            StatusOverlayHoverListenerFactory statusOverlayHoverListenerFactory
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
        mShadeViewStateProvider = shadeViewStateProvider;
        mKeyguardStateController = keyguardStateController;
        mKeyguardBypassController = bypassController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardStatusBarViewModel = keyguardStatusBarViewModel;
        mBiometricUnlockController = biometricUnlockController;
        mStatusBarStateController = statusBarStateController;
        mInsetsProvider = statusBarContentInsetsProvider;
        mFeatureFlags = featureFlags;
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
        mSystemEventAnimator = getSystemEventAnimator(/* isAnimationRunning */ false);

        mDisableStateTracker = new DisableStateTracker(
                /* mask1= */ DISABLE_SYSTEM_INFO,
                /* mask2= */ DISABLE2_SYSTEM_ICONS,
                this::updateViewState
        );
        mNotificationMediaManager = notificationMediaManager;
        mStatusOverlayHoverListenerFactory = statusOverlayHoverListenerFactory;
    }

    @Override
    protected void onInit() {
        super.onInit();
        mCarrierTextController.init();
        mBatteryMeterViewController.init();
        if (isMigrationEnabled()) {
            KeyguardStatusBarViewBinder.bind(mView, mKeyguardStatusBarViewModel);
        }
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
        } else {
            // In the old implementation, the keyguard status bar view is never detached and
            // re-attached, so only calling #addIconGroup when the IconManager is first created was
            // safe and correct.
            // In the new scene framework implementation, the keyguard status bar view *is* detached
            // whenever the shade is opened on top of lockscreen, and then re-attached when the
            // shade is closed. So, we need to re-add the IconManager each time we're re-attached to
            // get icon updates.
            if (isMigrationEnabled()) {
                mStatusBarIconController.addIconGroup(mTintedIconManager);
            }
        }

        mSystemIconsContainer = mView.findViewById(R.id.system_icons);
        StatusOverlayHoverListener hoverListener = mStatusOverlayHoverListenerFactory
                .createDarkAwareListener(mSystemIconsContainer, mView.darkChangeFlow());
        mSystemIconsContainer.setOnHoverListener(hoverListener);
        mView.setOnApplyWindowInsetsListener(
                (view, windowInsets) -> mView.updateWindowInsets(windowInsets, mInsetsProvider));
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
                false,
                mVolumeSettingObserver,
                UserHandle.USER_ALL);
        updateUserSwitcher();
        onThemeChanged();
    }

    @Override
    protected void onViewDetached() {
        mSystemIconsContainer.setOnHoverListener(null);
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
        mView.onThemeChanged(mTintedIconManager);
    }

    /** Sets whether user switcher is enabled. */
    public void setKeyguardUserSwitcherEnabled(boolean enabled) {
        if (isMigrationEnabled()) {
            return;
        }
        mView.setKeyguardUserSwitcherEnabled(enabled);
    }

    /** Sets whether this controller should listen to battery updates. */
    public void setBatteryListening(boolean listening) {
        if (isMigrationEnabled()) {
            return;
        }

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
        if (isMigrationEnabled()) {
            // [KeyguardStatusBarViewModel] will automatically handle dozing.
            return;
        }
        mDozing = dozing;
    }

    /** Animate the keyguard status bar in. */
    public void animateKeyguardStatusBarIn() {
        if (isMigrationEnabled()) {
            return;
        }

        mLogger.log(TAG, LogLevel.DEBUG, "animating status bar in");
        if (mDisableStateTracker.isDisabled()) {
            // If our view is disabled, don't allow us to animate in.
            return;
        }
        mView.setVisibility(View.VISIBLE);
        mView.setAlpha(0f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.addUpdateListener(mAnimatorUpdateListener);
        anim.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        anim.setInterpolator(InterpolatorsAndroidX.LINEAR_OUT_SLOW_IN);
        anim.start();
    }

    /** Animate the keyguard status bar out. */
    public void animateKeyguardStatusBarOut(long startDelay, long duration) {
        if (isMigrationEnabled()) {
            return;
        }

        mLogger.log(TAG, LogLevel.DEBUG, "animating status bar out");
        ValueAnimator anim = ValueAnimator.ofFloat(mView.getAlpha(), 0f);
        anim.addUpdateListener(mAnimatorUpdateListener);
        anim.setStartDelay(startDelay);
        anim.setDuration(duration);
        anim.setInterpolator(InterpolatorsAndroidX.LINEAR_OUT_SLOW_IN);
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
     * {@link ShadeViewController.NotificationPanelViewStateProvider} and other
     * controllers provide.
     */
    public void updateViewState() {
        if (!isKeyguardShowing()) {
            return;
        }
        if (isMigrationEnabled()) {
            // [KeyguardStatusBarViewBinder] will handle view state updates.
            return;
        }

        float alphaQsExpansion = 1 - Math.min(
                1, mShadeViewStateProvider.getLockscreenShadeDragProgress() * 2);

        float newAlpha;
        if (mExplicitAlpha != -1) {
            newAlpha = mExplicitAlpha;
        } else {
            newAlpha = Math.min(getKeyguardContentsAlpha(), alphaQsExpansion)
                    * mKeyguardStatusBarAnimateAlpha
                    * (1.0f - mKeyguardHeadsUpShowingAmount);
        }

        if (mSystemEventAnimator.isAnimationRunning()) {
            newAlpha = Math.min(newAlpha, mSystemEventAnimatorAlpha);
        } else {
            mView.setTranslationX(0);
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
        if (isMigrationEnabled()) {
            // [KeyguardStatusBarViewBinder] will handle view state updates.
            return;
        }

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
            alpha = mShadeViewStateProvider.getPanelViewExpandedHeight()
                    / (mView.getHeight() + mNotificationsHeaderCollideDistance);
        } else {
            // In SHADE_LOCKED, the top card is already really close to the header. Hide it as
            // soon as we start translating the stack.
            alpha = mShadeViewStateProvider.getPanelViewExpandedHeight()
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
                isKeyguardShowing() && mShadeViewStateProvider.shouldHeadsUpBeVisible();
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
        if (isMigrationEnabled()) {
            // [KeyguardStatusBarViewBinder] will handle view state updates.
            return;
        }

        mExplicitAlpha = alpha;
        updateViewState();
    }

    private boolean isMigrationEnabled() {
        return mFeatureFlags.isEnabled(Flags.MIGRATE_KEYGUARD_STATUS_BAR_VIEW);
    }

    private final ContentObserver mVolumeSettingObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            updateBlockedIcons();
        }
    };

    private StatusBarSystemEventDefaultAnimator getSystemEventAnimator(boolean isAnimationRunning) {
        return new StatusBarSystemEventDefaultAnimator(getResources(), (alpha) -> {
            mSystemEventAnimatorAlpha = alpha;
            updateViewState();
            return Unit.INSTANCE;
        }, (translationX) -> {
            mView.setTranslationX(translationX);
            return Unit.INSTANCE;
        }, isAnimationRunning);
    }
}
