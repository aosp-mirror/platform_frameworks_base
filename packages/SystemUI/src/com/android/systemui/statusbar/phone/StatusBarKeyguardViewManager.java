/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static android.view.WindowInsets.Type.navigationBars;

import static com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_UNLOCK_COLLAPSING;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_UNLOCK_FADING;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.ColorStateList;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Trace;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardMessageArea;
import com.android.keyguard.KeyguardMessageAreaController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.ViewGroupFadeHelper;
import com.android.systemui.statusbar.phone.KeyguardBouncer.BouncerExpansionCallback;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionListener;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.unfold.FoldAodAnimationController;
import com.android.systemui.unfold.SysUIUnfoldComponent;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Manages creating, showing, hiding and resetting the keyguard within the status bar. Calls back
 * via {@link ViewMediatorCallback} to poke the wake lock and report that the keyguard is done,
 * which is in turn, reported to this class by the current
 * {@link com.android.keyguard.KeyguardViewController}.
 */
@SysUISingleton
public class StatusBarKeyguardViewManager implements RemoteInputController.Callback,
        StatusBarStateController.StateListener, ConfigurationController.ConfigurationListener,
        PanelExpansionListener, NavigationModeController.ModeChangedListener,
        KeyguardViewController, FoldAodAnimationController.FoldAodAnimationStatus {

    // When hiding the Keyguard with timing supplied from WindowManager, better be early than late.
    private static final long HIDE_TIMING_CORRECTION_MS = - 16 * 3;

    // Delay for showing the navigation bar when the bouncer appears. This should be kept in sync
    // with the appear animations of the PIN/pattern/password views.
    private static final long NAV_BAR_SHOW_DELAY_BOUNCER = 320;

    // The duration to fade the nav bar content in/out when the device starts to sleep
    private static final long NAV_BAR_CONTENT_FADE_DURATION = 125;

    // Duration of the Keyguard dismissal animation in case the user is currently locked. This is to
    // make everything a bit slower to bridge a gap until the user is unlocked and home screen has
    // dranw its first frame.
    private static final long KEYGUARD_DISMISS_DURATION_LOCKED = 2000;

    private static String TAG = "StatusBarKeyguardViewManager";

    protected final Context mContext;
    private final ConfigurationController mConfigurationController;
    private final NavigationModeController mNavigationModeController;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final KeyguardBouncer.Factory mKeyguardBouncerFactory;
    private final KeyguardMessageAreaController.Factory mKeyguardMessageAreaFactory;
    private final DreamOverlayStateController mDreamOverlayStateController;
    @Nullable
    private final FoldAodAnimationController mFoldAodAnimationController;
    private KeyguardMessageAreaController mKeyguardMessageAreaController;
    private final Lazy<ShadeController> mShadeController;

    private final BouncerExpansionCallback mExpansionCallback = new BouncerExpansionCallback() {
        private boolean mBouncerAnimating;

        @Override
        public void onFullyShown() {
            mBouncerAnimating = false;
            updateStates();
            mCentralSurfaces.wakeUpIfDozing(SystemClock.uptimeMillis(),
                    mCentralSurfaces.getBouncerContainer(), "BOUNCER_VISIBLE");
        }

        @Override
        public void onStartingToHide() {
            mBouncerAnimating = true;
            updateStates();
        }

        @Override
        public void onStartingToShow() {
            mBouncerAnimating = true;
            updateStates();
        }

        @Override
        public void onFullyHidden() {
            mBouncerAnimating = false;
        }

        @Override
        public void onExpansionChanged(float expansion) {
            if (mAlternateAuthInterceptor != null) {
                mAlternateAuthInterceptor.setBouncerExpansionChanged(expansion);
            }
            if (mBouncerAnimating) {
                mCentralSurfaces.setBouncerHiddenFraction(expansion);
            }
            updateStates();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            if (!isVisible) {
                cancelPostAuthActions();
                mCentralSurfaces.setBouncerHiddenFraction(KeyguardBouncer.EXPANSION_HIDDEN);
            }
            if (mAlternateAuthInterceptor != null) {
                mAlternateAuthInterceptor.onBouncerVisibilityChanged();
            }
        }
    };
    private final DockManager.DockEventListener mDockEventListener =
            new DockManager.DockEventListener() {
                @Override
                public void onEvent(int event) {
                    boolean isDocked = mDockManager.isDocked();
            if (isDocked == mIsDocked) {
                return;
            }
            mIsDocked = isDocked;
            updateStates();
        }
    };

    protected LockPatternUtils mLockPatternUtils;
    protected ViewMediatorCallback mViewMediatorCallback;
    protected CentralSurfaces mCentralSurfaces;
    private NotificationPanelViewController mNotificationPanelViewController;
    private BiometricUnlockController mBiometricUnlockController;

    private View mNotificationContainer;

    protected KeyguardBouncer mBouncer;
    protected boolean mShowing;
    protected boolean mOccluded;
    protected boolean mRemoteInputActive;
    private boolean mGlobalActionsVisible = false;
    private boolean mLastGlobalActionsVisible = false;
    private boolean mDozing;
    private boolean mPulsing;
    private boolean mGesturalNav;
    private boolean mIsDocked;
    private boolean mScreenOffAnimationPlaying;

    protected boolean mFirstUpdate = true;
    protected boolean mLastShowing;
    protected boolean mLastOccluded;
    private boolean mLastBouncerShowing;
    private boolean mLastBouncerIsOrWillBeShowing;
    private boolean mLastBouncerDismissible;
    protected boolean mLastRemoteInputActive;
    private boolean mLastDozing;
    private boolean mLastGesturalNav;
    private boolean mLastIsDocked;
    private boolean mLastPulsing;
    private int mLastBiometricMode;
    private boolean mLastScreenOffAnimationPlaying;
    private float mQsExpansion;

    private OnDismissAction mAfterKeyguardGoneAction;
    private Runnable mKeyguardGoneCancelAction;
    private boolean mDismissActionWillAnimateOnKeyguard;
    private final ArrayList<Runnable> mAfterKeyguardGoneRunnables = new ArrayList<>();

    // Dismiss action to be launched when we stop dozing or the keyguard is gone.
    private DismissWithActionRequest mPendingWakeupAction;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationMediaManager mMediaManager;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final DockManager mDockManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateManager;
    private final LatencyTracker mLatencyTracker;
    private KeyguardBypassController mBypassController;
    @Nullable private AlternateAuthInterceptor mAlternateAuthInterceptor;

    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onEmergencyCallAction() {

            // Since we won't get a setOccluded call we have to reset the view manually such that
            // the bouncer goes away.
            if (mOccluded) {
                reset(true /* hideBouncerWhenShowing */);
            }
        }
    };

    @Inject
    public StatusBarKeyguardViewManager(
            Context context,
            ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils,
            SysuiStatusBarStateController sysuiStatusBarStateController,
            ConfigurationController configurationController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DreamOverlayStateController dreamOverlayStateController,
            NavigationModeController navigationModeController,
            DockManager dockManager,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardStateController keyguardStateController,
            NotificationMediaManager notificationMediaManager,
            KeyguardBouncer.Factory keyguardBouncerFactory,
            KeyguardMessageAreaController.Factory keyguardMessageAreaFactory,
            Optional<SysUIUnfoldComponent> sysUIUnfoldComponent,
            Lazy<ShadeController> shadeController,
            LatencyTracker latencyTracker) {
        mContext = context;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;
        mConfigurationController = configurationController;
        mNavigationModeController = navigationModeController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mDreamOverlayStateController = dreamOverlayStateController;
        mKeyguardStateController = keyguardStateController;
        mMediaManager = notificationMediaManager;
        mKeyguardUpdateManager = keyguardUpdateMonitor;
        mStatusBarStateController = sysuiStatusBarStateController;
        mDockManager = dockManager;
        mKeyguardBouncerFactory = keyguardBouncerFactory;
        mKeyguardMessageAreaFactory = keyguardMessageAreaFactory;
        mShadeController = shadeController;
        mLatencyTracker = latencyTracker;
        mFoldAodAnimationController = sysUIUnfoldComponent
                .map(SysUIUnfoldComponent::getFoldAodAnimationController).orElse(null);
    }

    @Override
    public void registerCentralSurfaces(CentralSurfaces centralSurfaces,
            NotificationPanelViewController notificationPanelViewController,
            PanelExpansionStateManager panelExpansionStateManager,
            BiometricUnlockController biometricUnlockController,
            View notificationContainer,
            KeyguardBypassController bypassController) {
        mCentralSurfaces = centralSurfaces;
        mBiometricUnlockController = biometricUnlockController;

        ViewGroup container = mCentralSurfaces.getBouncerContainer();
        mBouncer = mKeyguardBouncerFactory.create(container, mExpansionCallback);
        mNotificationPanelViewController = notificationPanelViewController;
        if (panelExpansionStateManager != null) {
            panelExpansionStateManager.addExpansionListener(this);
        }
        mBypassController = bypassController;
        mNotificationContainer = notificationContainer;
        mKeyguardMessageAreaController = mKeyguardMessageAreaFactory.create(
            KeyguardMessageArea.findSecurityMessageDisplay(container));

        registerListeners();
    }

    /**
     * Sets the given alt auth interceptor to null if it's the current auth interceptor. Else,
     * does nothing.
     */
    public void removeAlternateAuthInterceptor(@NonNull AlternateAuthInterceptor authInterceptor) {
        if (Objects.equals(mAlternateAuthInterceptor, authInterceptor)) {
            mAlternateAuthInterceptor = null;
            resetAlternateAuth(true);
        }
    }

    /**
     * Sets a new alt auth interceptor.
     */
    public void setAlternateAuthInterceptor(@NonNull AlternateAuthInterceptor authInterceptor) {
        if (!Objects.equals(mAlternateAuthInterceptor, authInterceptor)) {
            mAlternateAuthInterceptor = authInterceptor;
            resetAlternateAuth(false);
        }
    }

    private void registerListeners() {
        mKeyguardUpdateManager.registerCallback(mUpdateMonitorCallback);
        mStatusBarStateController.addCallback(this);
        mConfigurationController.addCallback(this);
        mGesturalNav = QuickStepContract.isGesturalMode(
                mNavigationModeController.addListener(this));
        if (mFoldAodAnimationController != null) {
            mFoldAodAnimationController.addCallback(this);
        }
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
            mIsDocked = mDockManager.isDocked();
        }
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        hideBouncer(true /* destroyView */);
    }

    @Override
    public void onPanelExpansionChanged(float fraction, boolean expanded, boolean tracking) {
        // Avoid having the shade and the bouncer open at the same time over a dream.
        final boolean hideBouncerOverDream =
                mDreamOverlayStateController.isOverlayActive()
                        && (mNotificationPanelViewController.isExpanded()
                        || mNotificationPanelViewController.isExpanding());

        // We don't want to translate the bounce when:
        // • Keyguard is occluded, because we're in a FLAG_SHOW_WHEN_LOCKED activity and need to
        //   conserve the original animation.
        // • The user quickly taps on the display and we show "swipe up to unlock."
        // • Keyguard will be dismissed by an action. a.k.a: FLAG_DISMISS_KEYGUARD_ACTIVITY
        // • Full-screen user switcher is displayed.
        if (mNotificationPanelViewController.isUnlockHintRunning()) {
            mBouncer.setExpansion(KeyguardBouncer.EXPANSION_HIDDEN);
        } else if (mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED
                && mKeyguardUpdateManager.isUdfpsEnrolled()) {
            // Don't expand to the bouncer. Instead transition back to the lock screen (see
            // CentralSurfaces#showBouncerOrLockScreenIfKeyguard) where the user can use the UDFPS
            // affordance to enter the device (or swipe up to the input bouncer)
            return;
        } else if (bouncerNeedsScrimming()) {
            mBouncer.setExpansion(KeyguardBouncer.EXPANSION_VISIBLE);
        } else if (mShowing && !hideBouncerOverDream) {
            if (!isWakeAndUnlocking()
                    && !mCentralSurfaces.isInLaunchTransition()
                    && !isUnlockCollapsing()) {
                mBouncer.setExpansion(fraction);
            }
            if (fraction != KeyguardBouncer.EXPANSION_HIDDEN && tracking
                    && !mKeyguardStateController.canDismissLockScreen()
                    && !mBouncer.isShowing() && !mBouncer.isAnimatingAway()) {
                mBouncer.show(false /* resetSecuritySelection */, false /* scrimmed */);
            }
        } else if (mPulsing && fraction == KeyguardBouncer.EXPANSION_VISIBLE) {
            // Panel expanded while pulsing but didn't translate the bouncer (because we are
            // unlocked.) Let's simply wake-up to dismiss the lock screen.
            mCentralSurfaces.wakeUpIfDozing(
                    SystemClock.uptimeMillis(),
                    mCentralSurfaces.getBouncerContainer(),
                    "BOUNCER_VISIBLE");
        }
    }

    /**
     * Update the global actions visibility state in order to show the navBar when active.
     */
    public void setGlobalActionsVisible(boolean isVisible) {
        mGlobalActionsVisible = isVisible;
        updateStates();
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    @Override
    public void show(Bundle options) {
        Trace.beginSection("StatusBarKeyguardViewManager#show");
        mShowing = true;
        mNotificationShadeWindowController.setKeyguardShowing(true);
        mKeyguardStateController.notifyKeyguardState(mShowing,
                mKeyguardStateController.isOccluded());
        reset(true /* hideBouncerWhenShowing */);
        SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__SHOWN);
        Trace.endSection();
    }

    /**
     * Shows the notification keyguard or the bouncer depending on
     * {@link KeyguardBouncer#needsFullscreenBouncer()}.
     */
    protected void showBouncerOrKeyguard(boolean hideBouncerWhenShowing) {
        if (mBouncer.needsFullscreenBouncer() && !mDozing) {
            // The keyguard might be showing (already). So we need to hide it.
            mCentralSurfaces.hideKeyguard();
            mBouncer.show(true /* resetSecuritySelection */);
        } else {
            mCentralSurfaces.showKeyguard();
            if (hideBouncerWhenShowing) {
                hideBouncer(false /* destroyView */);
                mBouncer.prepare();
            }
        }
        updateStates();
    }

    /**
     * If applicable, shows the alternate authentication bouncer. Else, shows the input
     * (pin/password/pattern) bouncer.
     * @param scrimmed true when the input bouncer should show scrimmed, false when the user will be
     * dragging it and translation should be deferred {@see KeyguardBouncer#show(boolean, boolean)}
     */
    public void showGenericBouncer(boolean scrimmed) {
        if (shouldShowAltAuth()) {
            updateAlternateAuthShowing(mAlternateAuthInterceptor.showAlternateAuthBouncer());
            return;
        }

        showBouncer(scrimmed);
    }

    private boolean shouldShowAltAuth() {
        return mAlternateAuthInterceptor != null
                && mKeyguardUpdateManager.isUnlockingWithBiometricAllowed(true);
    }

    /**
     * Hides the input bouncer (pin/password/pattern).
     */
    @VisibleForTesting
    void hideBouncer(boolean destroyView) {
        if (mBouncer == null) {
            return;
        }
        if (mShowing) {
            // If we were showing the bouncer and then aborting, we need to also clear out any
            // potential actions unless we actually unlocked.
            cancelPostAuthActions();
        }
        mBouncer.hide(destroyView);
        cancelPendingWakeupAction();
    }

    /**
     * Shows the keyguard input bouncer - the password challenge on the lock screen
     *
     * @param scrimmed true when the bouncer should show scrimmed, false when the user will be
     * dragging it and translation should be deferred {@see KeyguardBouncer#show(boolean, boolean)}
     */
    public void showBouncer(boolean scrimmed) {
        resetAlternateAuth(false);

        if (mShowing && !mBouncer.isShowing()) {
            mBouncer.show(false /* resetSecuritySelection */, scrimmed);
        }
        updateStates();
    }

    public void dismissWithAction(OnDismissAction r, Runnable cancelAction,
            boolean afterKeyguardGone) {
        dismissWithAction(r, cancelAction, afterKeyguardGone, null /* message */);
    }

    public void dismissWithAction(OnDismissAction r, Runnable cancelAction,
            boolean afterKeyguardGone, String message) {
        if (mShowing) {
            cancelPendingWakeupAction();
            // If we're dozing, this needs to be delayed until after we wake up - unless we're
            // wake-and-unlocking, because there dozing will last until the end of the transition.
            if (mDozing && !isWakeAndUnlocking()) {
                mPendingWakeupAction = new DismissWithActionRequest(
                        r, cancelAction, afterKeyguardGone, message);
                return;
            }

            mAfterKeyguardGoneAction = r;
            mKeyguardGoneCancelAction = cancelAction;
            mDismissActionWillAnimateOnKeyguard = r != null && r.willRunAnimationOnKeyguard();

            // If there is an an alternate auth interceptor (like the UDFPS), show that one instead
            // of the bouncer.
            if (shouldShowAltAuth()) {
                if (!afterKeyguardGone) {
                    mBouncer.setDismissAction(mAfterKeyguardGoneAction, mKeyguardGoneCancelAction);
                    mAfterKeyguardGoneAction = null;
                    mKeyguardGoneCancelAction = null;
                }

                updateAlternateAuthShowing(mAlternateAuthInterceptor.showAlternateAuthBouncer());
                return;
            }

            if (afterKeyguardGone) {
                // we'll handle the dismiss action after keyguard is gone, so just show the bouncer
                mBouncer.show(false /* resetSecuritySelection */);
            } else {
                // after authentication success, run dismiss action with the option to defer
                // hiding the keyguard based on the return value of the OnDismissAction
                mBouncer.showWithDismissAction(mAfterKeyguardGoneAction, mKeyguardGoneCancelAction);
                // bouncer will handle the dismiss action, so we no longer need to track it here
                mAfterKeyguardGoneAction = null;
                mKeyguardGoneCancelAction = null;
            }
        }
        updateStates();
    }

    private boolean isWakeAndUnlocking() {
        int mode = mBiometricUnlockController.getMode();
        return mode == MODE_WAKE_AND_UNLOCK || mode == MODE_WAKE_AND_UNLOCK_PULSING;
    }

    private boolean isUnlockCollapsing() {
        int mode = mBiometricUnlockController.getMode();
        return mode == MODE_UNLOCK_COLLAPSING;
    }

    /**
     * Adds a {@param runnable} to be executed after Keyguard is gone.
     */
    public void addAfterKeyguardGoneRunnable(Runnable runnable) {
        mAfterKeyguardGoneRunnables.add(runnable);
    }

    @Override
    public void reset(boolean hideBouncerWhenShowing) {
        if (mShowing) {
            // Hide quick settings.
            mNotificationPanelViewController.resetViews(/* animate= */ true);
            // Hide bouncer and quick-quick settings.
            if (mOccluded && !mDozing) {
                mCentralSurfaces.hideKeyguard();
                if (hideBouncerWhenShowing || mBouncer.needsFullscreenBouncer()) {
                    hideBouncer(false /* destroyView */);
                }
            } else {
                showBouncerOrKeyguard(hideBouncerWhenShowing);
            }
            resetAlternateAuth(false);
            mKeyguardUpdateManager.sendKeyguardReset();
            updateStates();
        }
    }

    @Override
    public void resetAlternateAuth(boolean forceUpdateScrim) {
        final boolean updateScrim = (mAlternateAuthInterceptor != null
                && mAlternateAuthInterceptor.hideAlternateAuthBouncer())
                || forceUpdateScrim;
        updateAlternateAuthShowing(updateScrim);
    }

    private void updateAlternateAuthShowing(boolean updateScrim) {
        if (mKeyguardMessageAreaController != null) {
            mKeyguardMessageAreaController.setAltBouncerShowing(isShowingAlternateAuth());
        }
        mBypassController.setAltBouncerShowing(isShowingAlternateAuth());

        if (updateScrim) {
            mCentralSurfaces.updateScrimController();
        }
    }

    @Override
    public void onStartedWakingUp() {
        mCentralSurfaces.getNotificationShadeWindowView().getWindowInsetsController()
                .setAnimationsDisabled(false);
        NavigationBarView navBarView = mCentralSurfaces.getNavigationBarView();
        if (navBarView != null) {
            navBarView.forEachView(view ->
                    view.animate()
                            .alpha(1f)
                            .setDuration(NAV_BAR_CONTENT_FADE_DURATION)
                            .start());
        }
    }

    @Override
    public void onStartedGoingToSleep() {
        mCentralSurfaces.getNotificationShadeWindowView().getWindowInsetsController()
                .setAnimationsDisabled(true);
        NavigationBarView navBarView = mCentralSurfaces.getNavigationBarView();
        if (navBarView != null) {
            navBarView.forEachView(view ->
                    view.animate()
                            .alpha(0f)
                            .setDuration(NAV_BAR_CONTENT_FADE_DURATION)
                            .start());
        }
    }

    @Override
    public void onFinishedGoingToSleep() {
        mBouncer.onScreenTurnedOff();
    }

    @Override
    public void onRemoteInputActive(boolean active) {
        mRemoteInputActive = active;
        updateStates();
    }

    private void setDozing(boolean dozing) {
        if (mDozing != dozing) {
            mDozing = dozing;
            if (dozing || mBouncer.needsFullscreenBouncer() || mOccluded) {
                reset(dozing /* hideBouncerWhenShowing */);
            }
            updateStates();

            if (!dozing) {
                launchPendingWakeupAction();
            }
        }
    }

    /**
     * If {@link CentralSurfaces} is pulsing.
     */
    public void setPulsing(boolean pulsing) {
        if (mPulsing != pulsing) {
            mPulsing = pulsing;
            updateStates();
        }
    }

    @Override
    public void setNeedsInput(boolean needsInput) {
        mNotificationShadeWindowController.setKeyguardNeedsInput(needsInput);
    }

    @Override
    public boolean isUnlockWithWallpaper() {
        return mNotificationShadeWindowController.isShowingWallpaper();
    }

    @Override
    public void setOccluded(boolean occluded, boolean animate) {
        final boolean isOccluding = !mOccluded && occluded;
        final boolean isUnOccluding = mOccluded && !occluded;
        setOccludedAndUpdateStates(occluded);

        if (mShowing && isOccluding) {
            SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                    SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__OCCLUDED);
            if (mCentralSurfaces.isInLaunchTransition()) {
                final Runnable endRunnable = new Runnable() {
                    @Override
                    public void run() {
                        mNotificationShadeWindowController.setKeyguardOccluded(mOccluded);
                        reset(true /* hideBouncerWhenShowing */);
                    }
                };
                mCentralSurfaces.fadeKeyguardAfterLaunchTransition(
                        null /* beforeFading */,
                        endRunnable,
                        endRunnable);
                return;
            }

            if (mCentralSurfaces.isLaunchingActivityOverLockscreen()) {
                // When isLaunchingActivityOverLockscreen() is true, we know for sure that the post
                // collapse runnables will be run.
                mShadeController.get().addPostCollapseAction(() -> {
                    mNotificationShadeWindowController.setKeyguardOccluded(mOccluded);
                    reset(true /* hideBouncerWhenShowing */);
                });
                return;
            }
        } else if (mShowing && isUnOccluding) {
            SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                    SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__SHOWN);
        }
        if (mShowing) {
            mMediaManager.updateMediaMetaData(false, animate && !mOccluded);
        }
        mNotificationShadeWindowController.setKeyguardOccluded(mOccluded);

        // setDozing(false) will call reset once we stop dozing.
        if (!mDozing) {
            // If Keyguard is reshown, don't hide the bouncer as it might just have been requested
            // by a FLAG_DISMISS_KEYGUARD_ACTIVITY.
            reset(isOccluding /* hideBouncerWhenShowing*/);
        }
        if (animate && !mOccluded && mShowing && !mBouncer.isShowing()) {
            mCentralSurfaces.animateKeyguardUnoccluding();
        }
    }

    private void setOccludedAndUpdateStates(boolean occluded) {
        mOccluded = occluded;
        updateStates();
    }

    public boolean isOccluded() {
        return mOccluded;
    }

    @Override
    public void startPreHideAnimation(Runnable finishRunnable) {
        if (mBouncer.isShowing()) {
            mBouncer.startPreHideAnimation(finishRunnable);
            mCentralSurfaces.onBouncerPreHideAnimation();

            // We update the state (which will show the keyguard) only if an animation will run on
            // the keyguard. If there is no animation, we wait before updating the state so that we
            // go directly from bouncer to launcher/app.
            if (mDismissActionWillAnimateOnKeyguard) {
                updateStates();
            }
        } else if (finishRunnable != null) {
            finishRunnable.run();
        }
        mNotificationPanelViewController.blockExpansionForCurrentTouch();
    }

    @Override
    public void blockPanelExpansionFromCurrentTouch() {
        mNotificationPanelViewController.blockExpansionForCurrentTouch();
    }

    @Override
    public void hide(long startTime, long fadeoutDuration) {
        Trace.beginSection("StatusBarKeyguardViewManager#hide");
        mShowing = false;
        mKeyguardStateController.notifyKeyguardState(mShowing,
                mKeyguardStateController.isOccluded());
        launchPendingWakeupAction();

        if (mKeyguardUpdateManager.needsSlowUnlockTransition()) {
            fadeoutDuration = KEYGUARD_DISMISS_DURATION_LOCKED;
        }
        long uptimeMillis = SystemClock.uptimeMillis();
        long delay = Math.max(0, startTime + HIDE_TIMING_CORRECTION_MS - uptimeMillis);

        if (mCentralSurfaces.isInLaunchTransition()
                || mKeyguardStateController.isFlingingToDismissKeyguard()) {
            final boolean wasFlingingToDismissKeyguard =
                    mKeyguardStateController.isFlingingToDismissKeyguard();
            mCentralSurfaces.fadeKeyguardAfterLaunchTransition(new Runnable() {
                @Override
                public void run() {
                    mNotificationShadeWindowController.setKeyguardShowing(false);
                    mNotificationShadeWindowController.setKeyguardFadingAway(true);
                    hideBouncer(true /* destroyView */);
                    updateStates();
                }
            }, /* endRunnable */ new Runnable() {
                @Override
                public void run() {
                    mCentralSurfaces.hideKeyguard();
                    mNotificationShadeWindowController.setKeyguardFadingAway(false);

                    if (wasFlingingToDismissKeyguard) {
                        mCentralSurfaces.finishKeyguardFadingAway();
                    }

                    mViewMediatorCallback.keyguardGone();
                    executeAfterKeyguardGoneAction();
                }
            }, /* cancelRunnable */ new Runnable() {
                @Override
                public void run() {
                    mNotificationShadeWindowController.setKeyguardFadingAway(false);
                    if (wasFlingingToDismissKeyguard) {
                        mCentralSurfaces.finishKeyguardFadingAway();
                    }
                    cancelPostAuthActions();
                }
            });
        } else {
            executeAfterKeyguardGoneAction();
            boolean wakeUnlockPulsing =
                    mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK_PULSING;
            boolean needsFading = needsBypassFading();
            if (needsFading) {
                delay = 0;
                fadeoutDuration = KeyguardBypassController.BYPASS_FADE_DURATION;
            } else if (wakeUnlockPulsing) {
                delay = 0;
                fadeoutDuration = 240;
            }
            mCentralSurfaces.setKeyguardFadingAway(startTime, delay, fadeoutDuration, needsFading);
            mBiometricUnlockController.startKeyguardFadingAway();
            hideBouncer(true /* destroyView */);
            if (wakeUnlockPulsing) {
                if (needsFading) {
                    ViewGroupFadeHelper.fadeOutAllChildrenExcept(
                            mNotificationPanelViewController.getView(),
                            mNotificationContainer,
                            fadeoutDuration,
                                    () -> {
                        mCentralSurfaces.hideKeyguard();
                        onKeyguardFadedAway();
                    });
                } else {
                    mCentralSurfaces.fadeKeyguardWhilePulsing();
                }
                wakeAndUnlockDejank();
            } else {
                boolean staying = mStatusBarStateController.leaveOpenOnKeyguardHide();
                if (!staying) {
                    mNotificationShadeWindowController.setKeyguardFadingAway(true);
                    if (needsFading) {
                        ViewGroupFadeHelper.fadeOutAllChildrenExcept(
                                mNotificationPanelViewController.getView(),
                                mNotificationContainer,
                                fadeoutDuration,
                                () -> {
                                    mCentralSurfaces.hideKeyguard();
                                });
                    } else {
                        mCentralSurfaces.hideKeyguard();
                    }
                    // hide() will happen asynchronously and might arrive after the scrims
                    // were already hidden, this means that the transition callback won't
                    // be triggered anymore and StatusBarWindowController will be forever in
                    // the fadingAway state.
                    mCentralSurfaces.updateScrimController();
                    wakeAndUnlockDejank();
                } else {
                    mCentralSurfaces.hideKeyguard();
                    mCentralSurfaces.finishKeyguardFadingAway();
                    mBiometricUnlockController.finishKeyguardFadingAway();
                }
            }
            updateStates();
            mNotificationShadeWindowController.setKeyguardShowing(false);
            mViewMediatorCallback.keyguardGone();
        }
        SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_STATE_CHANGED,
                SysUiStatsLog.KEYGUARD_STATE_CHANGED__STATE__HIDDEN);
        Trace.endSection();
    }

    private boolean needsBypassFading() {
        return (mBiometricUnlockController.getMode() == MODE_UNLOCK_FADING
                || mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK_PULSING
                || mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK)
                && mBypassController.getBypassEnabled();
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        boolean gesturalNav = QuickStepContract.isGesturalMode(mode);
        if (gesturalNav != mGesturalNav) {
            mGesturalNav = gesturalNav;
            updateStates();
        }
    }

    public void onThemeChanged() {
        boolean wasShowing = mBouncer.isShowing();
        boolean wasScrimmed = mBouncer.isScrimmed();

        hideBouncer(true /* destroyView */);
        mBouncer.prepare();

        if (wasShowing) showBouncer(wasScrimmed);
    }

    public void onKeyguardFadedAway() {
        mNotificationContainer.postDelayed(() -> mNotificationShadeWindowController
                        .setKeyguardFadingAway(false), 100);
        ViewGroupFadeHelper.reset(mNotificationPanelViewController.getView());
        mCentralSurfaces.finishKeyguardFadingAway();
        mBiometricUnlockController.finishKeyguardFadingAway();
        WindowManagerGlobal.getInstance().trimMemory(
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);

    }

    private void wakeAndUnlockDejank() {
        if (mBiometricUnlockController.isWakeAndUnlock() && mLatencyTracker.isEnabled()) {
            BiometricSourceType type = mBiometricUnlockController.getBiometricType();
            mLatencyTracker.onActionEnd(type == BiometricSourceType.FACE
                            ? LatencyTracker.ACTION_FACE_WAKE_AND_UNLOCK
                            : LatencyTracker.ACTION_FINGERPRINT_WAKE_AND_UNLOCK);
        }
    }

    private void executeAfterKeyguardGoneAction() {
        if (mAfterKeyguardGoneAction != null) {
            mAfterKeyguardGoneAction.onDismiss();
            mAfterKeyguardGoneAction = null;
        }
        mKeyguardGoneCancelAction = null;
        mDismissActionWillAnimateOnKeyguard = false;
        for (int i = 0; i < mAfterKeyguardGoneRunnables.size(); i++) {
            mAfterKeyguardGoneRunnables.get(i).run();
        }
        mAfterKeyguardGoneRunnables.clear();
    }

    @Override
    public void dismissAndCollapse() {
        mCentralSurfaces.executeRunnableDismissingKeyguard(null, null, true, false, true);
    }

    /**
     * WARNING: This method might cause Binder calls.
     */
    public boolean isSecure() {
        return mBouncer.isSecure();
    }

    @Override
    public boolean isShowing() {
        return mShowing;
    }

    /**
     * Notifies this manager that the back button has been pressed.
     *
     * @param hideImmediately Hide bouncer when {@code true}, keep it around otherwise.
     *                        Non-scrimmed bouncers have a special animation tied to the expansion
     *                        of the notification panel.
     * @return whether the back press has been handled
     */
    public boolean onBackPressed(boolean hideImmediately) {
        if (mBouncer.isShowing()) {
            mCentralSurfaces.endAffordanceLaunch();
            // The second condition is for SIM card locked bouncer
            if (mBouncer.isScrimmed() && !mBouncer.needsFullscreenBouncer()) {
                hideBouncer(false);
                updateStates();
            } else {
                reset(hideImmediately);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isBouncerShowing() {
        return mBouncer.isShowing() || isShowingAlternateAuth();
    }

    @Override
    public boolean bouncerIsOrWillBeShowing() {
        return isBouncerShowing() || mBouncer.getShowingSoon();
    }

    public boolean isFullscreenBouncer() {
        return mBouncer.isFullscreenBouncer();
    }

    /**
     * Clear out any potential actions that were saved to run when the device is unlocked
     */
    public void cancelPostAuthActions() {
        if (bouncerIsOrWillBeShowing()) {
            return; // allow bouncer to trigger saved actions
        }
        mAfterKeyguardGoneAction = null;
        mDismissActionWillAnimateOnKeyguard = false;
        if (mKeyguardGoneCancelAction != null) {
            mKeyguardGoneCancelAction.run();
            mKeyguardGoneCancelAction = null;
        }
    }

    private long getNavBarShowDelay() {
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            return mKeyguardStateController.getKeyguardFadingAwayDelay();
        } else if (mBouncer.isShowing()) {
            return NAV_BAR_SHOW_DELAY_BOUNCER;
        } else {
            // No longer dozing, or remote input is active. No delay.
            return 0;
        }
    }

    private Runnable mMakeNavigationBarVisibleRunnable = new Runnable() {
        @Override
        public void run() {
            NavigationBarView view = mCentralSurfaces.getNavigationBarView();
            if (view != null) {
                view.setVisibility(View.VISIBLE);
            }
            mCentralSurfaces.getNotificationShadeWindowView().getWindowInsetsController()
                    .show(navigationBars());
        }
    };

    protected void updateStates() {
        boolean showing = mShowing;
        boolean occluded = mOccluded;
        boolean bouncerShowing = mBouncer.isShowing();
        boolean bouncerIsOrWillBeShowing = bouncerIsOrWillBeShowing();
        boolean bouncerDismissible = !mBouncer.isFullscreenBouncer();
        boolean remoteInputActive = mRemoteInputActive;

        if ((bouncerDismissible || !showing || remoteInputActive) !=
                (mLastBouncerDismissible || !mLastShowing || mLastRemoteInputActive)
                || mFirstUpdate) {
            if (bouncerDismissible || !showing || remoteInputActive) {
                mBouncer.setBackButtonEnabled(true);
            } else {
                mBouncer.setBackButtonEnabled(false);
            }
        }

        boolean navBarVisible = isNavBarVisible();
        boolean lastNavBarVisible = getLastNavBarVisible();
        if (navBarVisible != lastNavBarVisible || mFirstUpdate) {
            updateNavigationBarVisibility(navBarVisible);
        }

        if (bouncerShowing != mLastBouncerShowing || mFirstUpdate) {
            mNotificationShadeWindowController.setBouncerShowing(bouncerShowing);
            mCentralSurfaces.setBouncerShowing(bouncerShowing);
            mKeyguardMessageAreaController.setBouncerShowing(bouncerShowing);
        }

        if (occluded != mLastOccluded || mFirstUpdate) {
            mKeyguardUpdateManager.onKeyguardOccludedChanged(occluded);
            mKeyguardStateController.notifyKeyguardState(showing, occluded);
        }
        if ((showing && !occluded) != (mLastShowing && !mLastOccluded) || mFirstUpdate) {
            mKeyguardUpdateManager.onKeyguardVisibilityChanged(showing && !occluded);
        }
        if (bouncerIsOrWillBeShowing != mLastBouncerIsOrWillBeShowing || mFirstUpdate
                || bouncerShowing != mLastBouncerShowing) {
            mKeyguardUpdateManager.sendKeyguardBouncerChanged(bouncerIsOrWillBeShowing,
                    bouncerShowing);
        }

        mFirstUpdate = false;
        mLastShowing = showing;
        mLastGlobalActionsVisible = mGlobalActionsVisible;
        mLastOccluded = occluded;
        mLastBouncerShowing = bouncerShowing;
        mLastBouncerIsOrWillBeShowing = bouncerIsOrWillBeShowing;
        mLastBouncerDismissible = bouncerDismissible;
        mLastRemoteInputActive = remoteInputActive;
        mLastDozing = mDozing;
        mLastPulsing = mPulsing;
        mLastScreenOffAnimationPlaying = mScreenOffAnimationPlaying;
        mLastBiometricMode = mBiometricUnlockController.getMode();
        mLastGesturalNav = mGesturalNav;
        mLastIsDocked = mIsDocked;
        mCentralSurfaces.onKeyguardViewManagerStatesUpdated();
    }

    private View getCurrentNavBarView() {
        final NavigationBarView navBarView = mCentralSurfaces.getNavigationBarView();
        return navBarView != null ? navBarView.getCurrentView() : null;
    }

    /**
     * Updates the visibility of the nav bar window (which will cause insets changes).
     */
    protected void updateNavigationBarVisibility(boolean navBarVisible) {
        if (mCentralSurfaces.getNavigationBarView() != null) {
            if (navBarVisible) {
                long delay = getNavBarShowDelay();
                if (delay == 0) {
                    mMakeNavigationBarVisibleRunnable.run();
                } else {
                    mNotificationContainer.postOnAnimationDelayed(mMakeNavigationBarVisibleRunnable,
                            delay);
                }
            } else {
                mNotificationContainer.removeCallbacks(mMakeNavigationBarVisibleRunnable);
                mCentralSurfaces.getNotificationShadeWindowView().getWindowInsetsController()
                        .hide(navigationBars());
            }
        }
    }

    /**
     * @return Whether the navigation bar should be made visible based on the current state.
     */
    public boolean isNavBarVisible() {
        boolean isWakeAndUnlockPulsing = mBiometricUnlockController != null
                && mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK_PULSING;
        boolean keyguardShowing = mShowing && !mOccluded;
        boolean hideWhileDozing = mDozing && !isWakeAndUnlockPulsing;
        boolean keyguardWithGestureNav = (keyguardShowing && !mDozing && !mScreenOffAnimationPlaying
                || mPulsing && !mIsDocked)
                && mGesturalNav;
        return (!keyguardShowing && !hideWhileDozing && !mScreenOffAnimationPlaying
                || mBouncer.isShowing() || mRemoteInputActive || keyguardWithGestureNav
                || mGlobalActionsVisible);
    }

    /**
     * @return Whether the navigation bar was made visible based on the last known state.
     */
    protected boolean getLastNavBarVisible() {
        boolean keyguardShowing = mLastShowing && !mLastOccluded;
        boolean hideWhileDozing = mLastDozing && mLastBiometricMode != MODE_WAKE_AND_UNLOCK_PULSING;
        boolean keyguardWithGestureNav = (keyguardShowing && !mLastDozing
                && !mLastScreenOffAnimationPlaying || mLastPulsing && !mLastIsDocked)
                && mLastGesturalNav;
        return (!keyguardShowing && !hideWhileDozing && !mLastScreenOffAnimationPlaying
                || mLastBouncerShowing || mLastRemoteInputActive || keyguardWithGestureNav
                || mLastGlobalActionsVisible);
    }

    public boolean shouldDismissOnMenuPressed() {
        return mBouncer.shouldDismissOnMenuPressed();
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mBouncer.interceptMediaKey(event);
    }

    /**
     * @return true if the pre IME back event should be handled
     */
    public boolean dispatchBackKeyEventPreIme() {
        return mBouncer.dispatchBackKeyEventPreIme();
    }

    public void readyForKeyguardDone() {
        mViewMediatorCallback.readyForKeyguardDone();
    }

    @Override
    public boolean shouldDisableWindowAnimationsForUnlock() {
        return mCentralSurfaces.isInLaunchTransition();
    }

    @Override
    public boolean shouldSubtleWindowAnimationsForUnlock() {
        return needsBypassFading();
    }

    @Override
    public boolean isGoingToNotificationShade() {
        return mStatusBarStateController.leaveOpenOnKeyguardHide();
    }

    public boolean isSecure(int userId) {
        return mBouncer.isSecure() || mLockPatternUtils.isSecure(userId);
    }

    @Override
    public void keyguardGoingAway() {
        mCentralSurfaces.keyguardGoingAway();
    }

    @Override
    public void setKeyguardGoingAwayState(boolean isKeyguardGoingAway) {
        mNotificationShadeWindowController.setKeyguardGoingAway(isKeyguardGoingAway);
    }

    @Override
    public void onCancelClicked() {
        // No-op
    }

    /**
     * Notifies that the user has authenticated by other means than using the bouncer, for example,
     * fingerprint.
     */
    public void notifyKeyguardAuthenticated(boolean strongAuth) {
        mBouncer.notifyKeyguardAuthenticated(strongAuth);

        if (mAlternateAuthInterceptor != null && isShowingAlternateAuthOrAnimating()) {
            resetAlternateAuth(false);
            executeAfterKeyguardGoneAction();
        }
    }

    public void showBouncerMessage(String message, ColorStateList colorState) {
        if (isShowingAlternateAuth()) {
            if (mKeyguardMessageAreaController != null) {
                mKeyguardMessageAreaController.setMessage(message);
            }
        } else {
            mBouncer.showMessage(message, colorState);
        }
    }

    @Override
    public ViewRootImpl getViewRootImpl() {
        return mNotificationShadeWindowController.getNotificationShadeView().getViewRootImpl();
    }

    public void launchPendingWakeupAction() {
        DismissWithActionRequest request = mPendingWakeupAction;
        mPendingWakeupAction = null;
        if (request != null) {
            if (mShowing) {
                dismissWithAction(request.dismissAction, request.cancelAction,
                        request.afterKeyguardGone, request.message);
            } else if (request.dismissAction != null) {
                request.dismissAction.onDismiss();
            }
        }
    }

    public void cancelPendingWakeupAction() {
        DismissWithActionRequest request = mPendingWakeupAction;
        mPendingWakeupAction = null;
        if (request != null && request.cancelAction != null) {
            request.cancelAction.run();
        }
    }

    public boolean bouncerNeedsScrimming() {
        // When a dream overlay is active, scrimming will cause any expansion to immediately expand.
        return (mOccluded && !mDreamOverlayStateController.isOverlayActive())
                || mBouncer.willDismissWithAction()
                || (mBouncer.isShowing() && mBouncer.isScrimmed())
                || mBouncer.isFullscreenBouncer();
    }

    /**
     * Apply keyguard configuration from the currently active resources. This can be called when the
     * device configuration changes, to re-apply some resources that are qualified on the device
     * configuration.
     */
    public void updateResources() {
        if (mBouncer != null) {
            mBouncer.updateResources();
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("StatusBarKeyguardViewManager:");
        pw.println("  mShowing: " + mShowing);
        pw.println("  mOccluded: " + mOccluded);
        pw.println("  mRemoteInputActive: " + mRemoteInputActive);
        pw.println("  mDozing: " + mDozing);
        pw.println("  mAfterKeyguardGoneAction: " + mAfterKeyguardGoneAction);
        pw.println("  mAfterKeyguardGoneRunnables: " + mAfterKeyguardGoneRunnables);
        pw.println("  mPendingWakeupAction: " + mPendingWakeupAction);
        pw.println("  isBouncerShowing(): " + isBouncerShowing());
        pw.println("  bouncerIsOrWillBeShowing(): " + bouncerIsOrWillBeShowing());

        if (mBouncer != null) {
            mBouncer.dump(pw);
        }

        if (mAlternateAuthInterceptor != null) {
            pw.println("AltAuthInterceptor: ");
            mAlternateAuthInterceptor.dump(pw);
        }
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        setDozing(isDozing);
    }

    @Override
    public void onFoldToAodAnimationChanged() {
        if (mFoldAodAnimationController != null) {
            mScreenOffAnimationPlaying = mFoldAodAnimationController.shouldPlayAnimation();
        }
    }

    /**
     * Whether qs is currently expanded.
     */
    public float getQsExpansion() {
        return mQsExpansion;
    }

    /**
     * Update qs expansion.
     */
    public void setQsExpansion(float qsExpansion) {
        mQsExpansion = qsExpansion;
        if (mAlternateAuthInterceptor != null) {
            mAlternateAuthInterceptor.setQsExpansion(qsExpansion);
        }
    }

    public KeyguardBouncer getBouncer() {
        return mBouncer;
    }

    public boolean isShowingAlternateAuth() {
        return mAlternateAuthInterceptor != null
                && mAlternateAuthInterceptor.isShowingAlternateAuthBouncer();
    }

    public boolean isShowingAlternateAuthOrAnimating() {
        return mAlternateAuthInterceptor != null
                && (mAlternateAuthInterceptor.isShowingAlternateAuthBouncer()
                || mAlternateAuthInterceptor.isAnimating());
    }

    /**
     * Forward touches to any alternate authentication affordances.
     */
    public boolean onTouch(MotionEvent event) {
        if (mAlternateAuthInterceptor == null) {
            return false;
        }

        return mAlternateAuthInterceptor.onTouch(event);
    }

    /** Update keyguard position based on a tapped X coordinate. */
    public void updateKeyguardPosition(float x) {
        if (mBouncer != null) {
            mBouncer.updateKeyguardPosition(x);
        }
    }

    private static class DismissWithActionRequest {
        final OnDismissAction dismissAction;
        final Runnable cancelAction;
        final boolean afterKeyguardGone;
        final String message;

        DismissWithActionRequest(OnDismissAction dismissAction, Runnable cancelAction,
                boolean afterKeyguardGone, String message) {
            this.dismissAction = dismissAction;
            this.cancelAction = cancelAction;
            this.afterKeyguardGone = afterKeyguardGone;
            this.message = message;
        }
    }

    /**
     * Request to authenticate using face.
     */
    public void requestFace(boolean request) {
        mKeyguardUpdateManager.requestFaceAuthOnOccludingApp(request);
    }

    /**
     * Request to authenticate using the fingerprint sensor.  If the fingerprint sensor is udfps,
     * uses the color provided by udfpsColor for the fingerprint icon.
     */
    public void requestFp(boolean request, int udfpsColor) {
        mKeyguardUpdateManager.requestFingerprintAuthOnOccludingApp(request);
        if (mAlternateAuthInterceptor != null) {
            mAlternateAuthInterceptor.requestUdfps(request, udfpsColor);
        }
    }

    /**
     * Returns if bouncer expansion is between 0 and 1 non-inclusive.
     */
    public boolean isBouncerInTransit() {
        if (mBouncer == null) return false;

        return mBouncer.inTransit();
    }

    /**
     * Delegate used to send show/reset events to an alternate authentication method instead of the
     * regular pin/pattern/password bouncer.
     */
    public interface AlternateAuthInterceptor {
        /**
         * Show alternate authentication bouncer.
         * @return whether alternate auth method was newly shown
         */
        boolean showAlternateAuthBouncer();

        /**
         * Hide alternate authentication bouncer
         * @return whether the alternate auth method was newly hidden
         */
        boolean hideAlternateAuthBouncer();

        /**
         * @return true if the alternate auth bouncer is showing
         */
        boolean isShowingAlternateAuthBouncer();

        /**
         * print information for the alternate auth interceptor registered
         */
        void dump(PrintWriter pw);

        /**
         * @return true if the new auth method bouncer is currently animating in or out.
         */
        boolean isAnimating();

        /**
         * How much QS is fully expanded where 0f is not showing and 1f is fully expanded.
         */
        void setQsExpansion(float qsExpansion);

        /**
         * Forward potential touches to authentication interceptor
         * @return true if event was handled
         */
        boolean onTouch(MotionEvent event);

        /**
         * Update pin/pattern/password bouncer expansion amount where 0 is visible and 1 is fully
         * hidden
         */
        void setBouncerExpansionChanged(float expansion);

        /**
         *  called when the bouncer view visibility has changed.
         */
        void onBouncerVisibilityChanged();

        /**
         * Use when an app occluding the keyguard would like to give the user ability to
         * unlock the device using udfps.
         *
         * @param color of the udfps icon. should have proper contrast with its background. only
         *              used if requestUdfps = true
         */
        void requestUdfps(boolean requestUdfps, int color);

    }
}
