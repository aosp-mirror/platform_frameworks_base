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

import static com.android.keyguard.KeyguardHostView.OnDismissAction;
import static com.android.keyguard.KeyguardSecurityModel.SecurityMode;

import android.content.Context;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.StatsLog;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardHostView;
import com.android.keyguard.KeyguardSecurityView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.R;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.DejankUtils;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;

import java.io.PrintWriter;

/**
 * A class which manages the bouncer on the lockscreen.
 */
public class KeyguardBouncer {

    private static final String TAG = "KeyguardBouncer";
    static final float ALPHA_EXPANSION_THRESHOLD = 0.95f;

    protected final Context mContext;
    protected final ViewMediatorCallback mCallback;
    protected final LockPatternUtils mLockPatternUtils;
    protected final ViewGroup mContainer;
    private final FalsingManager mFalsingManager;
    private final DismissCallbackRegistry mDismissCallbackRegistry;
    private final Handler mHandler;
    private final BouncerExpansionCallback mExpansionCallback;
    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onStrongAuthStateChanged(int userId) {
                    mBouncerPromptReason = mCallback.getBouncerPromptReason();
                }
            };
    private final Runnable mRemoveViewRunnable = this::removeView;

    private int mStatusBarHeight;
    private float mExpansion;
    protected KeyguardHostView mKeyguardView;
    protected ViewGroup mRoot;
    private boolean mShowingSoon;
    private int mBouncerPromptReason;
    private boolean mIsAnimatingAway;

    public KeyguardBouncer(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils, ViewGroup container,
            DismissCallbackRegistry dismissCallbackRegistry, FalsingManager falsingManager,
            BouncerExpansionCallback expansionCallback) {
        mContext = context;
        mCallback = callback;
        mLockPatternUtils = lockPatternUtils;
        mContainer = container;
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateMonitorCallback);
        mFalsingManager = falsingManager;
        mDismissCallbackRegistry = dismissCallbackRegistry;
        mExpansionCallback = expansionCallback;
        mHandler = new Handler();
    }

    public void show(boolean resetSecuritySelection) {
        show(resetSecuritySelection, true /* notifyFalsing */);
    }

    /**
     * Shows the bouncer.
     *
     * @param resetSecuritySelection Cleans keyguard view
     * @param animated true when the bouncer show show animated, false when the user will be
     *                 dragging it and animation should be deferred.
     */
    public void show(boolean resetSecuritySelection, boolean animated) {
        final int keyguardUserId = KeyguardUpdateMonitor.getCurrentUser();
        if (keyguardUserId == UserHandle.USER_SYSTEM && UserManager.isSplitSystemUser()) {
            // In split system user mode, we never unlock system user.
            return;
        }
        ensureView();

        // On the keyguard, we want to show the bouncer when the user drags up, but it's
        // not correct to end the falsing session. We still need to verify if those touches
        // are valid.
        // Later, at the end of the animation, when the bouncer is at the top of the screen,
        // onFullyShown() will be called and FalsingManager will stop recording touches.
        if (animated) {
            mFalsingManager.onBouncerShown();
            setExpansion(0);
        }

        if (resetSecuritySelection) {
            // showPrimarySecurityScreen() updates the current security method. This is needed in
            // case we are already showing and the current security method changed.
            mKeyguardView.showPrimarySecurityScreen();
        }
        if (mRoot.getVisibility() == View.VISIBLE || mShowingSoon) {
            return;
        }

        final int activeUserId = KeyguardUpdateMonitor.getCurrentUser();
        final boolean isSystemUser =
                UserManager.isSplitSystemUser() && activeUserId == UserHandle.USER_SYSTEM;
        final boolean allowDismissKeyguard = !isSystemUser && activeUserId == keyguardUserId;

        // If allowed, try to dismiss the Keyguard. If no security auth (password/pin/pattern) is
        // set, this will dismiss the whole Keyguard. Otherwise, show the bouncer.
        if (allowDismissKeyguard && mKeyguardView.dismiss(activeUserId)) {
            return;
        }

        // This condition may indicate an error on Android, so log it.
        if (!allowDismissKeyguard) {
            Slog.w(TAG, "User can't dismiss keyguard: " + activeUserId + " != " + keyguardUserId);
        }

        mShowingSoon = true;

        // Split up the work over multiple frames.
        DejankUtils.postAfterTraversal(mShowRunnable);

        mCallback.onBouncerVisiblityChanged(true /* shown */);
    }

    /**
     * This method must be called at the end of the bouncer animation when
     * the translation is performed manually by the user, otherwise FalsingManager
     * will never be notified and its internal state will be out of sync.
     */
    private void onFullyShown() {
        mFalsingManager.onBouncerShown();
        if (mKeyguardView == null) {
            Log.wtf(TAG, "onFullyShown when view was null");
        } else {
            mKeyguardView.onResume();
        }
    }

    /**
     * @see #onFullyShown()
     */
    private void onFullyHidden() {
        if (!mShowingSoon) {
            cancelShowRunnable();
            if (mRoot != null) {
                mRoot.setVisibility(View.INVISIBLE);
            }
            mFalsingManager.onBouncerHidden();
        }
    }

    private final Runnable mShowRunnable = new Runnable() {
        @Override
        public void run() {
            mRoot.setVisibility(View.VISIBLE);
            showPromptReason(mBouncerPromptReason);
            final CharSequence customMessage = mCallback.consumeCustomMessage();
            if (customMessage != null) {
                mKeyguardView.showErrorMessage(customMessage);
            }
            // We might still be collapsed and the view didn't have time to layout yet or still
            // be small, let's wait on the predraw to do the animation in that case.
            if (mKeyguardView.getHeight() != 0 && mKeyguardView.getHeight() != mStatusBarHeight) {
                mKeyguardView.startAppearAnimation();
            } else {
                mKeyguardView.getViewTreeObserver().addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                mKeyguardView.getViewTreeObserver().removeOnPreDrawListener(this);
                                mKeyguardView.startAppearAnimation();
                                return true;
                            }
                        });
                mKeyguardView.requestLayout();
            }
            mShowingSoon = false;
            StatsLog.write(StatsLog.KEYGUARD_BOUNCER_STATE_CHANGED,
                StatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SHOWN);
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
        if (mKeyguardView != null) {
            mKeyguardView.showPromptReason(reason);
        } else {
            Log.w(TAG, "Trying to show prompt reason on empty bouncer");
        }
    }

    public void showMessage(String message, int color) {
        if (mKeyguardView != null) {
            mKeyguardView.showMessage(message, color);
        } else {
            Log.w(TAG, "Trying to show message on empty bouncer");
        }
    }

    private void cancelShowRunnable() {
        DejankUtils.removeCallbacks(mShowRunnable);
        mShowingSoon = false;
    }

    public void showWithDismissAction(OnDismissAction r, Runnable cancelAction) {
        ensureView();
        mKeyguardView.setOnDismissAction(r, cancelAction);
        show(false /* resetSecuritySelection */);
    }

    public void hide(boolean destroyView) {
        if (isShowing()) {
            StatsLog.write(StatsLog.KEYGUARD_BOUNCER_STATE_CHANGED,
                StatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__HIDDEN);
            mDismissCallbackRegistry.notifyDismissCancelled();
        }
        mFalsingManager.onBouncerHidden();
        mCallback.onBouncerVisiblityChanged(false /* shown */);
        cancelShowRunnable();
        if (mKeyguardView != null) {
            mKeyguardView.cancelDismissAction();
            mKeyguardView.cleanUp();
        }
        mIsAnimatingAway = false;
        if (mRoot != null) {
            mRoot.setVisibility(View.INVISIBLE);
            if (destroyView) {

                // We have a ViewFlipper that unregisters a broadcast when being detached, which may
                // be slow because of AM lock contention during unlocking. We can delay it a bit.
                mHandler.postDelayed(mRemoveViewRunnable, 50);
            }
        }
    }

    /**
     * See {@link StatusBarKeyguardViewManager#startPreHideAnimation}.
     */
    public void startPreHideAnimation(Runnable runnable) {
        mIsAnimatingAway = true;
        if (mKeyguardView != null) {
            mKeyguardView.startDisappearAnimation(runnable);
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
        mFalsingManager.onBouncerHidden();
    }

    public void onScreenTurnedOff() {
        if (mKeyguardView != null && mRoot != null && mRoot.getVisibility() == View.VISIBLE) {
            mKeyguardView.onPause();
        }
    }

    public boolean isShowing() {
        return (mShowingSoon || (mRoot != null && mRoot.getVisibility() == View.VISIBLE))
                && mExpansion == 0 && !isAnimatingAway();
    }

    /**
     * @return {@code true} when bouncer's pre-hide animation already started but isn't completely
     *         hidden yet, {@code false} otherwise.
     */
    public boolean isAnimatingAway() {
        return mIsAnimatingAway;
    }

    public void prepare() {
        boolean wasInitialized = mRoot != null;
        ensureView();
        if (wasInitialized) {
            mKeyguardView.showPrimarySecurityScreen();
        }
        mBouncerPromptReason = mCallback.getBouncerPromptReason();
    }

    /**
     * Current notification panel expansion
     * @param fraction 0 when notification panel is collapsed and 1 when expanded.
     * @see StatusBarKeyguardViewManager#onPanelExpansionChanged
     */
    public void setExpansion(float fraction) {
        float oldExpansion = mExpansion;
        mExpansion = fraction;
        if (mKeyguardView != null && !mIsAnimatingAway) {
            float alpha = MathUtils.map(ALPHA_EXPANSION_THRESHOLD, 1, 1, 0, fraction);
            mKeyguardView.setAlpha(MathUtils.constrain(alpha, 0f, 1f));
            mKeyguardView.setTranslationY(fraction * mKeyguardView.getHeight());
        }

        if (fraction == 0 && oldExpansion != 0) {
            onFullyShown();
            mExpansionCallback.onFullyShown();
        } else if (fraction == 1 && oldExpansion != 0) {
            onFullyHidden();
            mExpansionCallback.onFullyHidden();
        }
    }

    public boolean willDismissWithAction() {
        return mKeyguardView != null && mKeyguardView.hasDismissActions();
    }

    public int getTop() {
        if (mKeyguardView == null) {
            return 0;
        }

        int top = mKeyguardView.getTop();
        // The password view has an extra top padding that should be ignored.
        if (mKeyguardView.getCurrentSecurityMode() == SecurityMode.Password) {
            View messageArea = mKeyguardView.findViewById(R.id.keyguard_message_area);
            top += messageArea.getTop();
        }
        return top;
    }

    protected void ensureView() {
        // Removal of the view might be deferred to reduce unlock latency,
        // in this case we need to force the removal, otherwise we'll
        // end up in an unpredictable state.
        boolean forceRemoval = mHandler.hasCallbacks(mRemoveViewRunnable);
        if (mRoot == null || forceRemoval) {
            inflateView();
        }
    }

    protected void inflateView() {
        removeView();
        mHandler.removeCallbacks(mRemoveViewRunnable);
        mRoot = (ViewGroup) LayoutInflater.from(mContext).inflate(R.layout.keyguard_bouncer, null);
        mKeyguardView = mRoot.findViewById(R.id.keyguard_host_view);
        mKeyguardView.setLockPatternUtils(mLockPatternUtils);
        mKeyguardView.setViewMediatorCallback(mCallback);
        mContainer.addView(mRoot, mContainer.getChildCount());
        mStatusBarHeight = mRoot.getResources().getDimensionPixelOffset(
                com.android.systemui.R.dimen.status_bar_height);
        mRoot.setVisibility(View.INVISIBLE);
        mRoot.setAccessibilityPaneTitle(mKeyguardView.getAccessibilityTitleForCurrentMode());

        final WindowInsets rootInsets = mRoot.getRootWindowInsets();
        if (rootInsets != null) {
            mRoot.dispatchApplyWindowInsets(rootInsets);
        }
    }

    protected void removeView() {
        if (mRoot != null && mRoot.getParent() == mContainer) {
            mContainer.removeView(mRoot);
            mRoot = null;
        }
    }

    public boolean onBackPressed() {
        return mKeyguardView != null && mKeyguardView.handleBackKey();
    }

    /**
     * @return True if and only if the security method should be shown before showing the
     * notifications on Keyguard, like SIM PIN/PUK.
     */
    public boolean needsFullscreenBouncer() {
        ensureView();
        if (mKeyguardView != null) {
            SecurityMode mode = mKeyguardView.getSecurityMode();
            return mode == SecurityMode.SimPin || mode == SecurityMode.SimPuk;
        }
        return false;
    }

    /**
     * Like {@link #needsFullscreenBouncer}, but uses the currently visible security method, which
     * makes this method much faster.
     */
    public boolean isFullscreenBouncer() {
        if (mKeyguardView != null) {
            SecurityMode mode = mKeyguardView.getCurrentSecurityMode();
            return mode == SecurityMode.SimPin || mode == SecurityMode.SimPuk;
        }
        return false;
    }

    /**
     * WARNING: This method might cause Binder calls.
     */
    public boolean isSecure() {
        return mKeyguardView == null || mKeyguardView.getSecurityMode() != SecurityMode.None;
    }

    public boolean shouldDismissOnMenuPressed() {
        return mKeyguardView.shouldEnableMenuKey();
    }

    public boolean interceptMediaKey(KeyEvent event) {
        ensureView();
        return mKeyguardView.interceptMediaKey(event);
    }

    public void notifyKeyguardAuthenticated(boolean strongAuth) {
        ensureView();
        mKeyguardView.finish(strongAuth, KeyguardUpdateMonitor.getCurrentUser());
    }

    public void dump(PrintWriter pw) {
        pw.println("KeyguardBouncer");
        pw.println("  isShowing(): " + isShowing());
        pw.println("  mStatusBarHeight: " + mStatusBarHeight);
        pw.println("  mExpansion: " + mExpansion);
        pw.println("  mKeyguardView; " + mKeyguardView);
        pw.println("  mShowingSoon: " + mKeyguardView);
        pw.println("  mBouncerPromptReason: " + mBouncerPromptReason);
        pw.println("  mIsAnimatingAway: " + mIsAnimatingAway);
    }

    public interface BouncerExpansionCallback {
        void onFullyShown();
        void onFullyHidden();
    }
}
