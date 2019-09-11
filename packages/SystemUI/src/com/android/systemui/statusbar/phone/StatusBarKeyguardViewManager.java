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

import static com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_UNLOCK_FADING;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.StatsLog;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.ViewMediatorCallback;
import com.android.settingslib.animation.AppearAnimationUtils;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dependency;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.ViewGroupFadeHelper;
import com.android.systemui.statusbar.phone.KeyguardBouncer.BouncerExpansionCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitorImpl;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Manages creating, showing, hiding and resetting the keyguard within the status bar. Calls back
 * via {@link ViewMediatorCallback} to poke the wake lock and report that the keyguard is done,
 * which is in turn, reported to this class by the current
 * {@link com.android.keyguard.KeyguardViewBase}.
 */
public class StatusBarKeyguardViewManager implements RemoteInputController.Callback,
        StatusBarStateController.StateListener, ConfigurationController.ConfigurationListener,
        PanelExpansionListener, NavigationModeController.ModeChangedListener {

    // When hiding the Keyguard with timing supplied from WindowManager, better be early than late.
    private static final long HIDE_TIMING_CORRECTION_MS = - 16 * 3;

    // Delay for showing the navigation bar when the bouncer appears. This should be kept in sync
    // with the appear animations of the PIN/pattern/password views.
    private static final long NAV_BAR_SHOW_DELAY_BOUNCER = 320;

    private static final long WAKE_AND_UNLOCK_SCRIM_FADEOUT_DURATION_MS = 200;

    // Duration of the Keyguard dismissal animation in case the user is currently locked. This is to
    // make everything a bit slower to bridge a gap until the user is unlocked and home screen has
    // dranw its first frame.
    private static final long KEYGUARD_DISMISS_DURATION_LOCKED = 2000;

    private static String TAG = "StatusBarKeyguardViewManager";

    protected final Context mContext;
    private final StatusBarWindowController mStatusBarWindowController;
    private final BouncerExpansionCallback mExpansionCallback = new BouncerExpansionCallback() {
        @Override
        public void onFullyShown() {
            updateStates();
            mStatusBar.wakeUpIfDozing(SystemClock.uptimeMillis(), mContainer, "BOUNCER_VISIBLE");
            updateLockIcon();
        }

        @Override
        public void onStartingToHide() {
            updateStates();
        }

        @Override
        public void onStartingToShow() {
            updateLockIcon();
        }

        @Override
        public void onFullyHidden() {
            updateStates();
            updateLockIcon();
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
    protected StatusBar mStatusBar;
    private NotificationPanelView mNotificationPanelView;
    private BiometricUnlockController mBiometricUnlockController;

    private ViewGroup mContainer;
    private ViewGroup mLockIconContainer;
    private View mNotificationContainer;

    protected KeyguardBouncer mBouncer;
    protected boolean mShowing;
    protected boolean mOccluded;
    protected boolean mRemoteInputActive;
    private boolean mDozing;
    private boolean mPulsing;
    private boolean mGesturalNav;
    private boolean mIsDocked;

    protected boolean mFirstUpdate = true;
    protected boolean mLastShowing;
    protected boolean mLastOccluded;
    private boolean mLastBouncerShowing;
    private boolean mLastBouncerDismissible;
    protected boolean mLastRemoteInputActive;
    private boolean mLastDozing;
    private boolean mLastGesturalNav;
    private boolean mLastIsDocked;
    private boolean mLastPulsing;
    private int mLastBiometricMode;
    private boolean mGoingToSleepVisibleNotOccluded;
    private boolean mLastLockVisible;

    private OnDismissAction mAfterKeyguardGoneAction;
    private final ArrayList<Runnable> mAfterKeyguardGoneRunnables = new ArrayList<>();

    // Dismiss action to be launched when we stop dozing or the keyguard is gone.
    private DismissWithActionRequest mPendingWakeupAction;
    private final KeyguardMonitorImpl mKeyguardMonitor =
            (KeyguardMonitorImpl) Dependency.get(KeyguardMonitor.class);
    private final NotificationMediaManager mMediaManager =
            Dependency.get(NotificationMediaManager.class);
    private final SysuiStatusBarStateController mStatusBarStateController =
            (SysuiStatusBarStateController) Dependency.get(StatusBarStateController.class);
    private final DockManager mDockManager;
    private KeyguardBypassController mBypassController;

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

    public StatusBarKeyguardViewManager(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        mContext = context;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;
        mStatusBarWindowController = Dependency.get(StatusBarWindowController.class);
        KeyguardUpdateMonitor.getInstance(context).registerCallback(mUpdateMonitorCallback);
        mStatusBarStateController.addCallback(this);
        Dependency.get(ConfigurationController.class).addCallback(this);
        mGesturalNav = QuickStepContract.isGesturalMode(
                Dependency.get(NavigationModeController.class).addListener(this));
        mDockManager = Dependency.get(DockManager.class);
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
            mIsDocked = mDockManager.isDocked();
        }
    }

    public void registerStatusBar(StatusBar statusBar,
            ViewGroup container,
            NotificationPanelView notificationPanelView,
            BiometricUnlockController biometricUnlockController,
            DismissCallbackRegistry dismissCallbackRegistry,
            ViewGroup lockIconContainer, View notificationContainer,
            KeyguardBypassController bypassController) {
        mStatusBar = statusBar;
        mContainer = container;
        mLockIconContainer = lockIconContainer;
        if (mLockIconContainer != null) {
            mLastLockVisible = mLockIconContainer.getVisibility() == View.VISIBLE;
        }
        mBiometricUnlockController = biometricUnlockController;
        mBouncer = SystemUIFactory.getInstance().createKeyguardBouncer(mContext,
                mViewMediatorCallback, mLockPatternUtils, container, dismissCallbackRegistry,
                mExpansionCallback);
        mNotificationPanelView = notificationPanelView;
        notificationPanelView.addExpansionListener(this);
        mBypassController = bypassController;
        mNotificationContainer = notificationContainer;
    }

    @Override
    public void onPanelExpansionChanged(float expansion, boolean tracking) {
        // We don't want to translate the bounce when:
        // • Keyguard is occluded, because we're in a FLAG_SHOW_WHEN_LOCKED activity and need to
        //   conserve the original animation.
        // • The user quickly taps on the display and we show "swipe up to unlock."
        // • Keyguard will be dismissed by an action. a.k.a: FLAG_DISMISS_KEYGUARD_ACTIVITY
        // • Full-screen user switcher is displayed.
        if (mNotificationPanelView.isUnlockHintRunning()) {
            mBouncer.setExpansion(KeyguardBouncer.EXPANSION_HIDDEN);
        } else if (bouncerNeedsScrimming()) {
            mBouncer.setExpansion(KeyguardBouncer.EXPANSION_VISIBLE);
        } else if (mShowing) {
            if (!isWakeAndUnlocking() && !mStatusBar.isInLaunchTransition()) {
                mBouncer.setExpansion(expansion);
            }
            if (expansion != KeyguardBouncer.EXPANSION_HIDDEN && tracking
                    && mStatusBar.isKeyguardCurrentlySecure()
                    && !mBouncer.isShowing() && !mBouncer.isAnimatingAway()) {
                mBouncer.show(false /* resetSecuritySelection */, false /* scrimmed */);
            }
        } else if (mPulsing && expansion == KeyguardBouncer.EXPANSION_VISIBLE) {
            // Panel expanded while pulsing but didn't translate the bouncer (because we are
            // unlocked.) Let's simply wake-up to dismiss the lock screen.
            mStatusBar.wakeUpIfDozing(SystemClock.uptimeMillis(), mContainer, "BOUNCER_VISIBLE");
        }
    }

    @Override
    public void onQsExpansionChanged(float expansion) {
        updateLockIcon();
    }

    private void updateLockIcon() {
        // Not all form factors have a lock icon
        if (mLockIconContainer == null) {
            return;
        }
        boolean keyguardWithoutQs = mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                && !mNotificationPanelView.isQsExpanded();
        boolean lockVisible = (mBouncer.isShowing() || keyguardWithoutQs)
                && !mBouncer.isAnimatingAway() && !mKeyguardMonitor.isKeyguardFadingAway();

        if (mLastLockVisible != lockVisible) {
            mLastLockVisible = lockVisible;
            if (lockVisible) {
                CrossFadeHelper.fadeIn(mLockIconContainer,
                        AppearAnimationUtils.DEFAULT_APPEAR_DURATION /* duration */,
                        0 /* delay */);
            } else {
                final long duration;
                if (needsBypassFading()) {
                    duration = KeyguardBypassController.BYPASS_PANEL_FADE_DURATION;
                } else {
                    duration = AppearAnimationUtils.DEFAULT_APPEAR_DURATION / 2;
                }
                CrossFadeHelper.fadeOut(mLockIconContainer,
                        duration /* duration */,
                        0 /* delay */, null /* runnable */);
            }
        }
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public void show(Bundle options) {
        mShowing = true;
        mStatusBarWindowController.setKeyguardShowing(true);
        mKeyguardMonitor.notifyKeyguardState(
                mShowing, mKeyguardMonitor.isSecure(), mKeyguardMonitor.isOccluded());
        reset(true /* hideBouncerWhenShowing */);
        StatsLog.write(StatsLog.KEYGUARD_STATE_CHANGED,
            StatsLog.KEYGUARD_STATE_CHANGED__STATE__SHOWN);
    }

    /**
     * Shows the notification keyguard or the bouncer depending on
     * {@link KeyguardBouncer#needsFullscreenBouncer()}.
     */
    protected void showBouncerOrKeyguard(boolean hideBouncerWhenShowing) {
        if (mBouncer.needsFullscreenBouncer() && !mDozing) {
            // The keyguard might be showing (already). So we need to hide it.
            mStatusBar.hideKeyguard();
            mBouncer.show(true /* resetSecuritySelection */);
        } else {
            mStatusBar.showKeyguard();
            if (hideBouncerWhenShowing) {
                hideBouncer(shouldDestroyViewOnReset() /* destroyView */);
                mBouncer.prepare();
            }
        }
        updateStates();
    }

    protected boolean shouldDestroyViewOnReset() {
        return false;
    }

    private void hideBouncer(boolean destroyView) {
        if (mBouncer == null) {
            return;
        }
        mBouncer.hide(destroyView);
        cancelPendingWakeupAction();
    }

    public void showBouncer(boolean scrimmed) {
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

            if (!afterKeyguardGone) {
                mBouncer.showWithDismissAction(r, cancelAction);
            } else {
                mAfterKeyguardGoneAction = r;
                mBouncer.show(false /* resetSecuritySelection */);
            }
        }
        updateStates();
    }

    private boolean isWakeAndUnlocking() {
        int mode = mBiometricUnlockController.getMode();
        return mode == MODE_WAKE_AND_UNLOCK || mode == MODE_WAKE_AND_UNLOCK_PULSING;
    }

    /**
     * Adds a {@param runnable} to be executed after Keyguard is gone.
     */
    public void addAfterKeyguardGoneRunnable(Runnable runnable) {
        mAfterKeyguardGoneRunnables.add(runnable);
    }

    /**
     * Reset the state of the view.
     */
    public void reset(boolean hideBouncerWhenShowing) {
        if (mShowing) {
            if (mOccluded && !mDozing) {
                mStatusBar.hideKeyguard();
                if (hideBouncerWhenShowing || mBouncer.needsFullscreenBouncer()) {
                    hideBouncer(false /* destroyView */);
                }
            } else {
                showBouncerOrKeyguard(hideBouncerWhenShowing);
            }
            KeyguardUpdateMonitor.getInstance(mContext).sendKeyguardReset();
            updateStates();
        }
    }

    public boolean isGoingToSleepVisibleNotOccluded() {
        return mGoingToSleepVisibleNotOccluded;
    }

    public void onStartedGoingToSleep() {
        mGoingToSleepVisibleNotOccluded = isShowing() && !isOccluded();
    }

    public void onFinishedGoingToSleep() {
        mGoingToSleepVisibleNotOccluded = false;
        mBouncer.onScreenTurnedOff();
    }

    public void onStartedWakingUp() {
        // TODO: remove
    }

    public void onScreenTurningOn() {
        // TODO: remove
    }

    public void onScreenTurnedOn() {
        // TODO: remove
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
     * If {@link StatusBar} is pulsing.
     */
    public void setPulsing(boolean pulsing) {
        if (mPulsing != pulsing) {
            mPulsing = pulsing;
            updateStates();
        }
    }

    public void setNeedsInput(boolean needsInput) {
        mStatusBarWindowController.setKeyguardNeedsInput(needsInput);
    }

    public boolean isUnlockWithWallpaper() {
        return mStatusBarWindowController.isShowingWallpaper();
    }

    public void setOccluded(boolean occluded, boolean animate) {
        mStatusBar.setOccluded(occluded);
        if (occluded && !mOccluded && mShowing) {
            StatsLog.write(StatsLog.KEYGUARD_STATE_CHANGED,
                StatsLog.KEYGUARD_STATE_CHANGED__STATE__OCCLUDED);
            if (mStatusBar.isInLaunchTransition()) {
                mOccluded = true;
                mStatusBar.fadeKeyguardAfterLaunchTransition(null /* beforeFading */,
                        new Runnable() {
                            @Override
                            public void run() {
                                mStatusBarWindowController.setKeyguardOccluded(mOccluded);
                                reset(true /* hideBouncerWhenShowing */);
                            }
                        });
                return;
            }
        } else if (!occluded && mOccluded && mShowing) {
            StatsLog.write(StatsLog.KEYGUARD_STATE_CHANGED,
                StatsLog.KEYGUARD_STATE_CHANGED__STATE__SHOWN);
        }
        boolean isOccluding = !mOccluded && occluded;
        mOccluded = occluded;
        if (mShowing) {
            mMediaManager.updateMediaMetaData(false, animate && !occluded);
        }
        mStatusBarWindowController.setKeyguardOccluded(occluded);

        // setDozing(false) will call reset once we stop dozing.
        if (!mDozing) {
            // If Keyguard is reshown, don't hide the bouncer as it might just have been requested
            // by a FLAG_DISMISS_KEYGUARD_ACTIVITY.
            reset(isOccluding /* hideBouncerWhenShowing*/);
        }
        if (animate && !occluded && mShowing && !mBouncer.isShowing()) {
            mStatusBar.animateKeyguardUnoccluding();
        }
    }

    public boolean isOccluded() {
        return mOccluded;
    }

    /**
     * Starts the animation before we dismiss Keyguard, i.e. an disappearing animation on the
     * security view of the bouncer.
     *
     * @param finishRunnable the runnable to be run after the animation finished, or {@code null} if
     *                       no action should be run
     */
    public void startPreHideAnimation(Runnable finishRunnable) {
        if (mBouncer.isShowing()) {
            mBouncer.startPreHideAnimation(finishRunnable);
            mNotificationPanelView.onBouncerPreHideAnimation();
        } else if (finishRunnable != null) {
            finishRunnable.run();
        }
        mNotificationPanelView.blockExpansionForCurrentTouch();
        updateLockIcon();
    }

    /**
     * Hides the keyguard view
     */
    public void hide(long startTime, long fadeoutDuration) {
        mShowing = false;
        mKeyguardMonitor.notifyKeyguardState(
                mShowing, mKeyguardMonitor.isSecure(), mKeyguardMonitor.isOccluded());
        launchPendingWakeupAction();

        if (KeyguardUpdateMonitor.getInstance(mContext).needsSlowUnlockTransition()) {
            fadeoutDuration = KEYGUARD_DISMISS_DURATION_LOCKED;
        }
        long uptimeMillis = SystemClock.uptimeMillis();
        long delay = Math.max(0, startTime + HIDE_TIMING_CORRECTION_MS - uptimeMillis);

        if (mStatusBar.isInLaunchTransition() ) {
            mStatusBar.fadeKeyguardAfterLaunchTransition(new Runnable() {
                @Override
                public void run() {
                    mStatusBarWindowController.setKeyguardShowing(false);
                    mStatusBarWindowController.setKeyguardFadingAway(true);
                    hideBouncer(true /* destroyView */);
                    updateStates();
                }
            }, new Runnable() {
                @Override
                public void run() {
                    mStatusBar.hideKeyguard();
                    mStatusBarWindowController.setKeyguardFadingAway(false);
                    mViewMediatorCallback.keyguardGone();
                    executeAfterKeyguardGoneAction();
                }
            });
        } else {
            executeAfterKeyguardGoneAction();
            boolean wakeUnlockPulsing =
                    mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK_PULSING;
            boolean needsFading = needsBypassFading();
            if (needsFading) {
                delay = 0;
                fadeoutDuration = KeyguardBypassController.BYPASS_PANEL_FADE_DURATION;
            } else if (wakeUnlockPulsing) {
                delay = 0;
                fadeoutDuration = 240;
            }
            mStatusBar.setKeyguardFadingAway(startTime, delay, fadeoutDuration, needsFading);
            mBiometricUnlockController.startKeyguardFadingAway();
            hideBouncer(true /* destroyView */);
            if (wakeUnlockPulsing) {
                if (needsFading) {
                    ViewGroupFadeHelper.fadeOutAllChildrenExcept(mNotificationPanelView,
                            mNotificationContainer,
                            fadeoutDuration,
                                    () -> {
                        mStatusBar.hideKeyguard();
                        onKeyguardFadedAway();
                    });
                } else {
                    mStatusBar.fadeKeyguardWhilePulsing();
                }
                wakeAndUnlockDejank();
            } else {
                boolean staying = mStatusBarStateController.leaveOpenOnKeyguardHide();
                if (!staying) {
                    mStatusBarWindowController.setKeyguardFadingAway(true);
                    if (needsFading) {
                        ViewGroupFadeHelper.fadeOutAllChildrenExcept(mNotificationPanelView,
                                mNotificationContainer,
                                fadeoutDuration,
                                () -> {
                                    mStatusBar.hideKeyguard();
                                });
                    } else {
                        mStatusBar.hideKeyguard();
                    }
                    // hide() will happen asynchronously and might arrive after the scrims
                    // were already hidden, this means that the transition callback won't
                    // be triggered anymore and StatusBarWindowController will be forever in
                    // the fadingAway state.
                    mStatusBar.updateScrimController();
                    wakeAndUnlockDejank();
                } else {
                    mStatusBar.hideKeyguard();
                    mStatusBar.finishKeyguardFadingAway();
                    mBiometricUnlockController.finishKeyguardFadingAway();
                }
            }
            updateLockIcon();
            updateStates();
            mStatusBarWindowController.setKeyguardShowing(false);
            mViewMediatorCallback.keyguardGone();
        }
        StatsLog.write(StatsLog.KEYGUARD_STATE_CHANGED,
            StatsLog.KEYGUARD_STATE_CHANGED__STATE__HIDDEN);
    }

    private boolean needsBypassFading() {
        return (mBiometricUnlockController.getMode() == MODE_UNLOCK_FADING
                || mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK_PULSING
                || mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK)
                && mBypassController.getBypassEnabled();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        hideBouncer(true /* destroyView */);
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
        hideBouncer(true /* destroyView */);
        mBouncer.prepare();
    }

    public void onKeyguardFadedAway() {
        mContainer.postDelayed(() -> mStatusBarWindowController.setKeyguardFadingAway(false),
                100);
        ViewGroupFadeHelper.reset(mNotificationPanelView);
        mStatusBar.finishKeyguardFadingAway();
        mBiometricUnlockController.finishKeyguardFadingAway();
        WindowManagerGlobal.getInstance().trimMemory(
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);

    }

    private void wakeAndUnlockDejank() {
        if (mBiometricUnlockController.getMode() == MODE_WAKE_AND_UNLOCK
                && LatencyTracker.isEnabled(mContext)) {
            DejankUtils.postAfterTraversal(() ->
                    LatencyTracker.getInstance(mContext).onActionEnd(
                            LatencyTracker.ACTION_FINGERPRINT_WAKE_AND_UNLOCK));
        }
    }

    private void executeAfterKeyguardGoneAction() {
        if (mAfterKeyguardGoneAction != null) {
            mAfterKeyguardGoneAction.onDismiss();
            mAfterKeyguardGoneAction = null;
        }
        for (int i = 0; i < mAfterKeyguardGoneRunnables.size(); i++) {
            mAfterKeyguardGoneRunnables.get(i).run();
        }
        mAfterKeyguardGoneRunnables.clear();
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    public void dismissAndCollapse() {
        mStatusBar.executeRunnableDismissingKeyguard(null, null, true, false, true);
    }

    /**
     * WARNING: This method might cause Binder calls.
     */
    public boolean isSecure() {
        return mBouncer.isSecure();
    }

    /**
     * @return Whether the keyguard is showing
     */
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
            mStatusBar.endAffordanceLaunch();
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

    public boolean isBouncerShowing() {
        return mBouncer.isShowing();
    }

    /**
     * When bouncer is fully visible or {@link KeyguardBouncer#show(boolean)} was called but
     * animation didn't finish yet.
     */
    public boolean bouncerIsOrWillBeShowing() {
        return mBouncer.isShowing() || mBouncer.inTransit();
    }

    public boolean isFullscreenBouncer() {
        return mBouncer.isFullscreenBouncer();
    }

    private long getNavBarShowDelay() {
        if (mKeyguardMonitor.isKeyguardFadingAway()) {
            return mKeyguardMonitor.getKeyguardFadingAwayDelay();
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
            mStatusBar.getNavigationBarView().getRootView().setVisibility(View.VISIBLE);
        }
    };

    protected void updateStates() {
        int vis = mContainer.getSystemUiVisibility();
        boolean showing = mShowing;
        boolean occluded = mOccluded;
        boolean bouncerShowing = mBouncer.isShowing();
        boolean bouncerDismissible = !mBouncer.isFullscreenBouncer();
        boolean remoteInputActive = mRemoteInputActive;

        if ((bouncerDismissible || !showing || remoteInputActive) !=
                (mLastBouncerDismissible || !mLastShowing || mLastRemoteInputActive)
                || mFirstUpdate) {
            if (bouncerDismissible || !showing || remoteInputActive) {
                mContainer.setSystemUiVisibility(vis & ~View.STATUS_BAR_DISABLE_BACK);
            } else {
                mContainer.setSystemUiVisibility(vis | View.STATUS_BAR_DISABLE_BACK);
            }
        }

        boolean navBarVisible = isNavBarVisible();
        boolean lastNavBarVisible = getLastNavBarVisible();
        if (navBarVisible != lastNavBarVisible || mFirstUpdate) {
            updateNavigationBarVisibility(navBarVisible);
        }

        if (bouncerShowing != mLastBouncerShowing || mFirstUpdate) {
            mStatusBarWindowController.setBouncerShowing(bouncerShowing);
            mStatusBar.setBouncerShowing(bouncerShowing);
        }

        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        if ((showing && !occluded) != (mLastShowing && !mLastOccluded) || mFirstUpdate) {
            updateMonitor.onKeyguardVisibilityChanged(showing && !occluded);
        }
        if (bouncerShowing != mLastBouncerShowing || mFirstUpdate) {
            updateMonitor.sendKeyguardBouncerChanged(bouncerShowing);
        }

        mFirstUpdate = false;
        mLastShowing = showing;
        mLastOccluded = occluded;
        mLastBouncerShowing = bouncerShowing;
        mLastBouncerDismissible = bouncerDismissible;
        mLastRemoteInputActive = remoteInputActive;
        mLastDozing = mDozing;
        mLastPulsing = mPulsing;
        mLastBiometricMode = mBiometricUnlockController.getMode();
        mLastGesturalNav = mGesturalNav;
        mLastIsDocked = mIsDocked;
        mStatusBar.onKeyguardViewManagerStatesUpdated();
    }

    protected void updateNavigationBarVisibility(boolean navBarVisible) {
        if (mStatusBar.getNavigationBarView() != null) {
            if (navBarVisible) {
                long delay = getNavBarShowDelay();
                if (delay == 0) {
                    mMakeNavigationBarVisibleRunnable.run();
                } else {
                    mContainer.postOnAnimationDelayed(mMakeNavigationBarVisibleRunnable,
                            delay);
                }
            } else {
                mContainer.removeCallbacks(mMakeNavigationBarVisibleRunnable);
                mStatusBar.getNavigationBarView().getRootView().setVisibility(View.GONE);
            }
        }
    }

    /**
     * @return Whether the navigation bar should be made visible based on the current state.
     */
    protected boolean isNavBarVisible() {
        int biometricMode = mBiometricUnlockController.getMode();
        boolean keyguardShowing = mShowing && !mOccluded;
        boolean hideWhileDozing = mDozing && biometricMode != MODE_WAKE_AND_UNLOCK_PULSING;
        boolean keyguardWithGestureNav = (keyguardShowing && !mDozing || mPulsing && !mIsDocked)
                && mGesturalNav;
        return (!keyguardShowing && !hideWhileDozing || mBouncer.isShowing()
                || mRemoteInputActive || keyguardWithGestureNav);
    }

    /**
     * @return Whether the navigation bar was made visible based on the last known state.
     */
    protected boolean getLastNavBarVisible() {
        boolean keyguardShowing = mLastShowing && !mLastOccluded;
        boolean hideWhileDozing = mLastDozing && mLastBiometricMode != MODE_WAKE_AND_UNLOCK_PULSING;
        boolean keyguardWithGestureNav = (keyguardShowing && !mLastDozing
                || mLastPulsing && !mLastIsDocked) && mLastGesturalNav;
        return (!keyguardShowing && !hideWhileDozing || mLastBouncerShowing
                || mLastRemoteInputActive || keyguardWithGestureNav);
    }

    public boolean shouldDismissOnMenuPressed() {
        return mBouncer.shouldDismissOnMenuPressed();
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mBouncer.interceptMediaKey(event);
    }

    public void readyForKeyguardDone() {
        mViewMediatorCallback.readyForKeyguardDone();
    }

    public boolean shouldDisableWindowAnimationsForUnlock() {
        return mStatusBar.isInLaunchTransition();
    }


    /**
     * @return Whether subtle animation should be used for unlocking the device.
     */
    public boolean shouldSubtleWindowAnimationsForUnlock() {
        return needsBypassFading();
    }

    public boolean isGoingToNotificationShade() {
        return ((SysuiStatusBarStateController) Dependency.get(StatusBarStateController.class))
                .leaveOpenOnKeyguardHide();
    }

    public boolean isSecure(int userId) {
        return mBouncer.isSecure() || mLockPatternUtils.isSecure(userId);
    }

    public void keyguardGoingAway() {
        mStatusBar.keyguardGoingAway();
    }

    public void animateCollapsePanels(float speedUpFactor) {
        mStatusBar.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE, true /* force */,
                false /* delayed */, speedUpFactor);
    }


    /**
     * Called when cancel button in bouncer is pressed.
     */
    public void onCancelClicked() {
        // No-op
    }

    /**
     * Notifies that the user has authenticated by other means than using the bouncer, for example,
     * fingerprint.
     */
    public void notifyKeyguardAuthenticated(boolean strongAuth) {
        mBouncer.notifyKeyguardAuthenticated(strongAuth);
    }

    public void showBouncerMessage(String message, ColorStateList colorState) {
        mBouncer.showMessage(message, colorState);
    }

    public ViewRootImpl getViewRootImpl() {
        return mStatusBar.getStatusBarView().getViewRootImpl();
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
        return mOccluded || mBouncer.willDismissWithAction()
                || mStatusBar.isFullScreenUserSwitcherState()
                || (mBouncer.isShowing() && mBouncer.isScrimmed())
                || mBouncer.isFullscreenBouncer();
    }

    public void dump(PrintWriter pw) {
        pw.println("StatusBarKeyguardViewManager:");
        pw.println("  mShowing: " + mShowing);
        pw.println("  mOccluded: " + mOccluded);
        pw.println("  mRemoteInputActive: " + mRemoteInputActive);
        pw.println("  mDozing: " + mDozing);
        pw.println("  mGoingToSleepVisibleNotOccluded: " + mGoingToSleepVisibleNotOccluded);
        pw.println("  mAfterKeyguardGoneAction: " + mAfterKeyguardGoneAction);
        pw.println("  mAfterKeyguardGoneRunnables: " + mAfterKeyguardGoneRunnables);
        pw.println("  mPendingWakeupAction: " + mPendingWakeupAction);

        if (mBouncer != null) {
            mBouncer.dump(pw);
        }
    }

    @Override
    public void onStateChanged(int newState) {
        updateLockIcon();
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        setDozing(isDozing);
    }

    public KeyguardBouncer getBouncer() {
        return mBouncer;
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
}
