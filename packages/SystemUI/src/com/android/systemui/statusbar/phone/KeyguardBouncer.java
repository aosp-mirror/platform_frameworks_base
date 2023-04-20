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

import static com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import static com.android.systemui.plugins.ActivityStarter.OnDismissAction;

import android.content.Context;
import android.content.res.ColorStateList;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import com.android.internal.policy.SystemBarUtils;
import com.android.keyguard.KeyguardHostViewController;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.KeyguardSecurityView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.ViewMediatorCallback;
import com.android.keyguard.dagger.KeyguardBouncerComponent;
import com.android.systemui.DejankUtils;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ListenerSet;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * A class which manages the primary (pin/pattern/password) bouncer on the lockscreen.
 * @deprecated Use KeyguardBouncerRepository
 */
@Deprecated
public class KeyguardBouncer {

    private static final String TAG = "PrimaryKeyguardBouncer";
    static final long BOUNCER_FACE_DELAY = 1200;
    public static final float ALPHA_EXPANSION_THRESHOLD = 0.95f;
    /**
     * Values for the bouncer expansion represented as the panel expansion.
     * Panel expansion 1f = panel fully showing = bouncer fully hidden
     * Panel expansion 0f = panel fully hiding = bouncer fully showing
     */
    public static final float EXPANSION_HIDDEN = 1f;
    public static final float EXPANSION_VISIBLE = 0f;

    protected final Context mContext;
    protected final ViewMediatorCallback mCallback;
    protected final ViewGroup mContainer;
    private final FalsingCollector mFalsingCollector;
    private final DismissCallbackRegistry mDismissCallbackRegistry;
    private final Handler mHandler;
    private final List<PrimaryBouncerExpansionCallback> mExpansionCallbacks = new ArrayList<>();
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardSecurityModel mKeyguardSecurityModel;
    private final KeyguardBouncerComponent.Factory mKeyguardBouncerComponentFactory;
    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onStrongAuthStateChanged(int userId) {
                    mBouncerPromptReason = mCallback.getBouncerPromptReason();
                }

                @Override
                public void onLockedOutStateChanged(BiometricSourceType type) {
                    if (type == BiometricSourceType.FINGERPRINT) {
                        mBouncerPromptReason = mCallback.getBouncerPromptReason();
                    }
                }

                @Override
                public void onNonStrongBiometricAllowedChanged(int userId) {
                    mBouncerPromptReason = mCallback.getBouncerPromptReason();
                }
            };
    private final Runnable mRemoveViewRunnable = this::removeView;
    private final KeyguardBypassController mKeyguardBypassController;
    private KeyguardHostViewController mKeyguardViewController;
    private final ListenerSet<KeyguardResetCallback> mResetCallbacks = new ListenerSet<>();
    private final Runnable mResetRunnable = ()-> {
        if (mKeyguardViewController != null) {
            mKeyguardViewController.resetSecurityContainer();
            for (KeyguardResetCallback callback : mResetCallbacks) {
                callback.onKeyguardReset();
            }
        }
    };

    private int mStatusBarHeight;
    private float mExpansion = EXPANSION_HIDDEN;
    private boolean mShowingSoon;
    private int mBouncerPromptReason;
    private boolean mIsAnimatingAway;
    private boolean mIsScrimmed;
    private boolean mInitialized;

    private KeyguardBouncer(Context context, ViewMediatorCallback callback,
            ViewGroup container,
            DismissCallbackRegistry dismissCallbackRegistry, FalsingCollector falsingCollector,
            PrimaryBouncerExpansionCallback expansionCallback,
            KeyguardStateController keyguardStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardBypassController keyguardBypassController, @Main Handler handler,
            KeyguardSecurityModel keyguardSecurityModel,
            KeyguardBouncerComponent.Factory keyguardBouncerComponentFactory) {
        mContext = context;
        mCallback = callback;
        mContainer = container;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mFalsingCollector = falsingCollector;
        mDismissCallbackRegistry = dismissCallbackRegistry;
        mHandler = handler;
        mKeyguardStateController = keyguardStateController;
        mKeyguardSecurityModel = keyguardSecurityModel;
        mKeyguardBouncerComponentFactory = keyguardBouncerComponentFactory;
        mKeyguardUpdateMonitor.registerCallback(mUpdateMonitorCallback);
        mKeyguardBypassController = keyguardBypassController;
        mExpansionCallbacks.add(expansionCallback);
    }

    /**
     * Get the KeyguardBouncer expansion
     * @return 1=HIDDEN, 0=SHOWING, in between 0 and 1 means the bouncer is in transition.
     */
    public float getExpansion() {
        return mExpansion;
    }

    /**
     * Enable/disable only the back button
     */
    public void setBackButtonEnabled(boolean enabled) {
        int vis = mContainer.getSystemUiVisibility();
        if (enabled) {
            vis &= ~View.STATUS_BAR_DISABLE_BACK;
        } else {
            vis |= View.STATUS_BAR_DISABLE_BACK;
        }
        mContainer.setSystemUiVisibility(vis);
    }

    public void show(boolean resetSecuritySelection) {
        show(resetSecuritySelection, true /* scrimmed */);
    }

    /**
     * Shows the bouncer.
     *
     * @param resetSecuritySelection Cleans keyguard view
     * @param isScrimmed true when the bouncer show show scrimmed, false when the user will be
     *                 dragging it and translation should be deferred.
     */
    public void show(boolean resetSecuritySelection, boolean isScrimmed) {
        final int keyguardUserId = KeyguardUpdateMonitor.getCurrentUser();
        if (keyguardUserId == UserHandle.USER_SYSTEM && UserManager.isSplitSystemUser()) {
            // In split system user mode, we never unlock system user.
            return;
        }

        try {
            Trace.beginSection("KeyguardBouncer#show");

            ensureView();
            mIsScrimmed = isScrimmed;

            // On the keyguard, we want to show the bouncer when the user drags up, but it's
            // not correct to end the falsing session. We still need to verify if those touches
            // are valid.
            // Later, at the end of the animation, when the bouncer is at the top of the screen,
            // onFullyShown() will be called and FalsingManager will stop recording touches.
            if (isScrimmed) {
                setExpansion(EXPANSION_VISIBLE);
            }

            if (resetSecuritySelection) {
                // showPrimarySecurityScreen() updates the current security method. This is needed
                // in case we are already showing and the current security method changed.
                showPrimarySecurityScreen();
            }

            if (mContainer.getVisibility() == View.VISIBLE || mShowingSoon) {
                // Calls to reset must resume the ViewControllers when in fullscreen mode
                if (needsFullscreenBouncer()) {
                    mKeyguardViewController.onResume();
                }
                return;
            }

            final int activeUserId = KeyguardUpdateMonitor.getCurrentUser();
            final boolean isSystemUser =
                UserManager.isSplitSystemUser() && activeUserId == UserHandle.USER_SYSTEM;
            final boolean allowDismissKeyguard = !isSystemUser && activeUserId == keyguardUserId;

            // If allowed, try to dismiss the Keyguard. If no security auth (password/pin/pattern)
            // is set, this will dismiss the whole Keyguard. Otherwise, show the bouncer.
            if (allowDismissKeyguard && mKeyguardViewController.dismiss(activeUserId)) {
                return;
            }

            // This condition may indicate an error on Android, so log it.
            if (!allowDismissKeyguard) {
                Log.w(TAG, "User can't dismiss keyguard: " + activeUserId + " != "
                        + keyguardUserId);
            }

            mShowingSoon = true;

            // Split up the work over multiple frames.
            DejankUtils.removeCallbacks(mResetRunnable);
            if (mKeyguardStateController.isFaceAuthEnabled()
                    && !mKeyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(
                            KeyguardUpdateMonitor.getCurrentUser())
                    && !needsFullscreenBouncer()
                    && mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                            BiometricSourceType.FACE)
                    && !mKeyguardBypassController.getBypassEnabled()) {
                mHandler.postDelayed(mShowRunnable, BOUNCER_FACE_DELAY);
            } else {
                DejankUtils.postAfterTraversal(mShowRunnable);
            }

            mKeyguardStateController.notifyBouncerShowing(true /* showing */);
            dispatchStartingToShow();
        } finally {
            Trace.endSection();
        }
    }

    public boolean isScrimmed() {
        return mIsScrimmed;
    }

    /**
     * This method must be called at the end of the bouncer animation when
     * the translation is performed manually by the user, otherwise FalsingManager
     * will never be notified and its internal state will be out of sync.
     */
    private void onFullyShown() {
        mFalsingCollector.onBouncerShown();
        if (mKeyguardViewController == null) {
            Log.e(TAG, "onFullyShown when view was null");
        } else {
            mKeyguardViewController.onResume();
            mContainer.announceForAccessibility(
                    mKeyguardViewController.getAccessibilityTitleForCurrentMode());
        }
    }

    /**
     * @see #onFullyShown()
     */
    private void onFullyHidden() {

    }

    private void setVisibility(@View.Visibility int visibility) {
        mContainer.setVisibility(visibility);
        if (mKeyguardViewController != null) {
            mKeyguardViewController.onBouncerVisibilityChanged(visibility);
        }
        dispatchVisibilityChanged();
    }

    private final Runnable mShowRunnable = new Runnable() {
        @Override
        public void run() {
            setVisibility(View.VISIBLE);
            showPromptReason(mBouncerPromptReason);
            final CharSequence customMessage = mCallback.consumeCustomMessage();
            if (customMessage != null) {
                mKeyguardViewController.showErrorMessage(customMessage);
            }
            mKeyguardViewController.appear(mStatusBarHeight);
            mShowingSoon = false;
            if (mExpansion == EXPANSION_VISIBLE) {
                mKeyguardViewController.onResume();
                mKeyguardViewController.resetSecurityContainer();
                showPromptReason(mBouncerPromptReason);
            }
        }
    };

    /**
     * Show a string explaining why the security view needs to be solved.
     *
     * @param reason a flag indicating which string should be shown, see
     *               {@link KeyguardSecurityView#PROMPT_REASON_NONE}
     *               and {@link KeyguardSecurityView#PROMPT_REASON_RESTART}
     */
    public void showPromptReason(int reason) {
        if (mKeyguardViewController != null) {
            mKeyguardViewController.showPromptReason(reason);
        } else {
            Log.w(TAG, "Trying to show prompt reason on empty bouncer");
        }
    }

    public void showMessage(String message, ColorStateList colorState) {
        if (mKeyguardViewController != null) {
            mKeyguardViewController.showMessage(message, colorState);
        } else {
            Log.w(TAG, "Trying to show message on empty bouncer");
        }
    }

    private void cancelShowRunnable() {
        DejankUtils.removeCallbacks(mShowRunnable);
        mHandler.removeCallbacks(mShowRunnable);
        mShowingSoon = false;
    }

    public void showWithDismissAction(OnDismissAction r, Runnable cancelAction) {
        ensureView();
        setDismissAction(r, cancelAction);
        show(false /* resetSecuritySelection */);
    }

    /**
     * Set the actions to run when the keyguard is dismissed or when the dismiss is cancelled. Those
     * actions will still be run even if this bouncer is not shown, for instance when authenticating
     * with an alternate authenticator like the UDFPS.
     */
    public void setDismissAction(OnDismissAction r, Runnable cancelAction) {
        mKeyguardViewController.setOnDismissAction(r, cancelAction);
    }

    public void hide(boolean destroyView) {
        Trace.beginSection("KeyguardBouncer#hide");
        if (isShowing()) {
            SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED,
                    SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__HIDDEN);
            mDismissCallbackRegistry.notifyDismissCancelled();
        }
        mIsScrimmed = false;
        mFalsingCollector.onBouncerHidden();
        mKeyguardStateController.notifyBouncerShowing(false /* showing */);
        cancelShowRunnable();
        if (mKeyguardViewController != null) {
            mKeyguardViewController.cancelDismissAction();
            mKeyguardViewController.cleanUp();
        }
        mIsAnimatingAway = false;
        setVisibility(View.INVISIBLE);
        if (destroyView) {

            // We have a ViewFlipper that unregisters a broadcast when being detached, which may
            // be slow because of AM lock contention during unlocking. We can delay it a bit.
            mHandler.postDelayed(mRemoveViewRunnable, 50);
        }
        Trace.endSection();
    }

    /**
     * See {@link StatusBarKeyguardViewManager#startPreHideAnimation}.
     */
    public void startPreHideAnimation(Runnable runnable) {
        mIsAnimatingAway = true;
        if (mKeyguardViewController != null) {
            mKeyguardViewController.startDisappearAnimation(runnable);
        } else if (runnable != null) {
            runnable.run();
        }
    }

    /**
     * Reset the state of the view.
     */
    public void reset() {
        cancelShowRunnable();
        inflateView();
        mFalsingCollector.onBouncerHidden();
    }

    public void onScreenTurnedOff() {
        if (mKeyguardViewController != null && mContainer.getVisibility() == View.VISIBLE) {
            mKeyguardViewController.onPause();
        }
    }

    public boolean isShowing() {
        return (mShowingSoon || mContainer.getVisibility() == View.VISIBLE)
                && mExpansion == EXPANSION_VISIBLE && !isAnimatingAway();
    }

    /**
     * {@link #show(boolean)} was called but we're not showing yet, or being dragged.
     */
    public boolean inTransit() {
        return mShowingSoon || mExpansion != EXPANSION_HIDDEN && mExpansion != EXPANSION_VISIBLE;
    }

    /**
     * @return {@code true} when bouncer's pre-hide animation already started but isn't completely
     *         hidden yet, {@code false} otherwise.
     */
    public boolean isAnimatingAway() {
        return mIsAnimatingAway;
    }

    public void prepare() {
        boolean wasInitialized = mInitialized;
        ensureView();
        if (wasInitialized) {
            showPrimarySecurityScreen();
        }
        mBouncerPromptReason = mCallback.getBouncerPromptReason();
    }

    private void showPrimarySecurityScreen() {
        mKeyguardViewController.showPrimarySecurityScreen();
    }

    /**
     * Current notification panel expansion
     * @param fraction 0 when notification panel is collapsed and 1 when expanded.
     * @see StatusBarKeyguardViewManager#onPanelExpansionChanged
     */
    public void setExpansion(float fraction) {
        float oldExpansion = mExpansion;
        boolean expansionChanged = mExpansion != fraction;
        mExpansion = fraction;
        if (mKeyguardViewController != null && !mIsAnimatingAway) {
            mKeyguardViewController.setExpansion(fraction);
        }

        if (fraction == EXPANSION_VISIBLE && oldExpansion != EXPANSION_VISIBLE) {
            onFullyShown();
            dispatchFullyShown();
        } else if (fraction == EXPANSION_HIDDEN && oldExpansion != EXPANSION_HIDDEN) {
            DejankUtils.postAfterTraversal(mResetRunnable);
            /*
             * There are cases where #hide() was not invoked, such as when
             * NotificationPanelViewController controls the hide animation. Make sure the state gets
             * updated by calling #hide() directly.
             */
            hide(false /* destroyView */);
            dispatchFullyHidden();
        } else if (fraction != EXPANSION_VISIBLE && oldExpansion == EXPANSION_VISIBLE) {
            dispatchStartingToHide();
            if (mKeyguardViewController != null) {
                mKeyguardViewController.onStartingToHide();
            }
        }

        if (expansionChanged) {
            dispatchExpansionChanged();
        }
    }

    public boolean willDismissWithAction() {
        return mKeyguardViewController != null && mKeyguardViewController.hasDismissActions();
    }

    public int getTop() {
        if (mKeyguardViewController == null) {
            return 0;
        }

        return mKeyguardViewController.getTop();
    }

    protected void ensureView() {
        // Removal of the view might be deferred to reduce unlock latency,
        // in this case we need to force the removal, otherwise we'll
        // end up in an unpredictable state.
        boolean forceRemoval = mHandler.hasCallbacks(mRemoveViewRunnable);
        if (!mInitialized || forceRemoval) {
            inflateView();
        }
    }

    protected void inflateView() {
        removeView();
        mHandler.removeCallbacks(mRemoveViewRunnable);

        KeyguardBouncerComponent component = mKeyguardBouncerComponentFactory.create(mContainer);
        mKeyguardViewController = component.getKeyguardHostViewController();
        mKeyguardViewController.init();

        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        setVisibility(View.INVISIBLE);

        final WindowInsets rootInsets = mContainer.getRootWindowInsets();
        if (rootInsets != null) {
            mContainer.dispatchApplyWindowInsets(rootInsets);
        }
        mInitialized = true;
    }

    protected void removeView() {
        mContainer.removeAllViews();
        mInitialized = false;
    }

    /**
     * @return True if and only if the security method should be shown before showing the
     * notifications on Keyguard, like SIM PIN/PUK.
     */
    public boolean needsFullscreenBouncer() {
        SecurityMode mode = mKeyguardSecurityModel.getSecurityMode(
                KeyguardUpdateMonitor.getCurrentUser());
        return mode == SecurityMode.SimPin || mode == SecurityMode.SimPuk;
    }

    /**
     * Like {@link #needsFullscreenBouncer}, but uses the currently visible security method, which
     * makes this method much faster.
     */
    public boolean isFullscreenBouncer() {
        if (mKeyguardViewController != null) {
            SecurityMode mode = mKeyguardViewController.getCurrentSecurityMode();
            return mode == SecurityMode.SimPin || mode == SecurityMode.SimPuk;
        }
        return false;
    }

    /**
     * WARNING: This method might cause Binder calls.
     */
    public boolean isSecure() {
        return mKeyguardSecurityModel.getSecurityMode(
                KeyguardUpdateMonitor.getCurrentUser()) != SecurityMode.None;
    }

    public boolean shouldDismissOnMenuPressed() {
        return mKeyguardViewController.shouldEnableMenuKey();
    }

    public boolean interceptMediaKey(KeyEvent event) {
        ensureView();
        return mKeyguardViewController.interceptMediaKey(event);
    }

    /**
     * @return true if the pre IME back event should be handled
     */
    public boolean dispatchBackKeyEventPreIme() {
        ensureView();
        return mKeyguardViewController.dispatchBackKeyEventPreIme();
    }

    public void notifyKeyguardAuthenticated(boolean strongAuth) {
        ensureView();
        mKeyguardViewController.finish(strongAuth, KeyguardUpdateMonitor.getCurrentUser());
    }

    private void dispatchFullyShown() {
        for (PrimaryBouncerExpansionCallback callback : mExpansionCallbacks) {
            callback.onFullyShown();
        }
    }

    private void dispatchStartingToHide() {
        for (PrimaryBouncerExpansionCallback callback : mExpansionCallbacks) {
            callback.onStartingToHide();
        }
    }

    private void dispatchStartingToShow() {
        for (PrimaryBouncerExpansionCallback callback : mExpansionCallbacks) {
            callback.onStartingToShow();
        }
    }

    private void dispatchFullyHidden() {
        for (PrimaryBouncerExpansionCallback callback : mExpansionCallbacks) {
            callback.onFullyHidden();
        }
    }

    private void dispatchExpansionChanged() {
        for (PrimaryBouncerExpansionCallback callback : mExpansionCallbacks) {
            callback.onExpansionChanged(mExpansion);
        }
    }

    private void dispatchVisibilityChanged() {
        for (PrimaryBouncerExpansionCallback callback : mExpansionCallbacks) {
            callback.onVisibilityChanged(mContainer.getVisibility() == View.VISIBLE);
        }
    }

    /**
     * Apply keyguard configuration from the currently active resources. This can be called when the
     * device configuration changes, to re-apply some resources that are qualified on the device
     * configuration.
     */
    public void updateResources() {
        if (mKeyguardViewController != null) {
            mKeyguardViewController.updateResources();
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("KeyguardBouncer");
        pw.println("  isShowing(): " + isShowing());
        pw.println("  mStatusBarHeight: " + mStatusBarHeight);
        pw.println("  mExpansion: " + mExpansion);
        pw.println("  mKeyguardViewController; " + mKeyguardViewController);
        pw.println("  mShowingSoon: " + mShowingSoon);
        pw.println("  mBouncerPromptReason: " + mBouncerPromptReason);
        pw.println("  mIsAnimatingAway: " + mIsAnimatingAway);
        pw.println("  mInitialized: " + mInitialized);
    }

    /** Update keyguard position based on a tapped X coordinate. */
    public void updateKeyguardPosition(float x) {
        if (mKeyguardViewController != null) {
            mKeyguardViewController.updateKeyguardPosition(x);
        }
    }

    public void addKeyguardResetCallback(KeyguardResetCallback callback) {
        mResetCallbacks.addIfAbsent(callback);
    }

    public void removeKeyguardResetCallback(KeyguardResetCallback callback) {
        mResetCallbacks.remove(callback);
    }

    /**
     * Adds a callback to listen to bouncer expansion updates.
     */
    public void addBouncerExpansionCallback(PrimaryBouncerExpansionCallback callback) {
        if (!mExpansionCallbacks.contains(callback)) {
            mExpansionCallbacks.add(callback);
        }
    }

    /**
     * Removes a previously added callback. If the callback was never added, this methood
     * does nothing.
     */
    public void removeBouncerExpansionCallback(PrimaryBouncerExpansionCallback callback) {
        mExpansionCallbacks.remove(callback);
    }

    /**
     * Callback updated when the primary bouncer's show and hide states change.
     */
    public interface PrimaryBouncerExpansionCallback {
        /**
         * Invoked when the bouncer expansion reaches {@link KeyguardBouncer#EXPANSION_VISIBLE}.
         * This is NOT called each time the bouncer is shown, but rather only when the fully
         * shown amount has changed based on the panel expansion. The bouncer's visibility
         * can still change when the expansion amount hasn't changed.
         * See {@link KeyguardBouncer#isShowing()} for the checks for the bouncer showing state.
         */
        default void onFullyShown() {
        }

        /**
         * Invoked when the bouncer is starting to transition to a hidden state.
         */
        default void onStartingToHide() {
        }

        /**
         * Invoked when the bouncer is starting to transition to a visible state.
         */
        default void onStartingToShow() {
        }

        /**
         * Invoked when the bouncer expansion reaches {@link KeyguardBouncer#EXPANSION_HIDDEN}.
         */
        default void onFullyHidden() {
        }

        /**
         * From 0f {@link KeyguardBouncer#EXPANSION_VISIBLE} when fully visible
         * to 1f {@link KeyguardBouncer#EXPANSION_HIDDEN} when fully hidden
         */
        default void onExpansionChanged(float bouncerHideAmount) {}

        /**
         * Invoked when visibility of KeyguardBouncer has changed.
         * Note the bouncer expansion can be {@link KeyguardBouncer#EXPANSION_VISIBLE}, but the
         * view's visibility can be {@link View.INVISIBLE}.
         */
        default void onVisibilityChanged(boolean isVisible) {}
    }

    public interface KeyguardResetCallback {
        void onKeyguardReset();
    }

    /** Create a {@link KeyguardBouncer} once a container and bouncer callback are available. */
    public static class Factory {
        private final Context mContext;
        private final ViewMediatorCallback mCallback;
        private final DismissCallbackRegistry mDismissCallbackRegistry;
        private final FalsingCollector mFalsingCollector;
        private final KeyguardStateController mKeyguardStateController;
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final KeyguardBypassController mKeyguardBypassController;
        private final Handler mHandler;
        private final KeyguardSecurityModel mKeyguardSecurityModel;
        private final KeyguardBouncerComponent.Factory mKeyguardBouncerComponentFactory;

        @Inject
        public Factory(Context context, ViewMediatorCallback callback,
                DismissCallbackRegistry dismissCallbackRegistry, FalsingCollector falsingCollector,
                KeyguardStateController keyguardStateController,
                KeyguardUpdateMonitor keyguardUpdateMonitor,
                KeyguardBypassController keyguardBypassController, @Main Handler handler,
                KeyguardSecurityModel keyguardSecurityModel,
                KeyguardBouncerComponent.Factory keyguardBouncerComponentFactory) {
            mContext = context;
            mCallback = callback;
            mDismissCallbackRegistry = dismissCallbackRegistry;
            mFalsingCollector = falsingCollector;
            mKeyguardStateController = keyguardStateController;
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mKeyguardBypassController = keyguardBypassController;
            mHandler = handler;
            mKeyguardSecurityModel = keyguardSecurityModel;
            mKeyguardBouncerComponentFactory = keyguardBouncerComponentFactory;
        }

        /**
         * Construct a KeyguardBouncer that will exist in the given container.
         */
        public KeyguardBouncer create(ViewGroup container,
                PrimaryBouncerExpansionCallback expansionCallback) {
            return new KeyguardBouncer(mContext, mCallback, container,
                    mDismissCallbackRegistry, mFalsingCollector, expansionCallback,
                    mKeyguardStateController, mKeyguardUpdateMonitor,
                    mKeyguardBypassController, mHandler, mKeyguardSecurityModel,
                    mKeyguardBouncerComponentFactory);
        }
    }
}
