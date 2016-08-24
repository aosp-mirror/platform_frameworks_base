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

import android.app.ActivityManager;
import android.content.Context;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardHostView;
import com.android.keyguard.KeyguardSecurityView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.R;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.DejankUtils;
import com.android.systemui.classifier.FalsingManager;

import static com.android.keyguard.KeyguardHostView.OnDismissAction;
import static com.android.keyguard.KeyguardSecurityModel.SecurityMode;

/**
 * A class which manages the bouncer on the lockscreen.
 */
public class KeyguardBouncer {

    final static private String TAG = "KeyguardBouncer";

    protected Context mContext;
    protected ViewMediatorCallback mCallback;
    protected LockPatternUtils mLockPatternUtils;
    protected ViewGroup mContainer;
    private StatusBarWindowManager mWindowManager;
    protected KeyguardHostView mKeyguardView;
    protected ViewGroup mRoot;
    private boolean mShowingSoon;
    private int mBouncerPromptReason;
    private FalsingManager mFalsingManager;
    private KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onStrongAuthStateChanged(int userId) {
                    mBouncerPromptReason = mCallback.getBouncerPromptReason();
                }
            };

    public KeyguardBouncer(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils, StatusBarWindowManager windowManager,
            ViewGroup container) {
        mContext = context;
        mCallback = callback;
        mLockPatternUtils = lockPatternUtils;
        mContainer = container;
        mWindowManager = windowManager;
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateMonitorCallback);
        mFalsingManager = FalsingManager.getInstance(mContext);
    }

    public void show(boolean resetSecuritySelection) {
        final int keyguardUserId = KeyguardUpdateMonitor.getCurrentUser();
        if (keyguardUserId == UserHandle.USER_SYSTEM && UserManager.isSplitSystemUser()) {
            // In split system user mode, we never unlock system user.
            return;
        }
        mFalsingManager.onBouncerShown();
        ensureView();
        if (resetSecuritySelection) {
            // showPrimarySecurityScreen() updates the current security method. This is needed in
            // case we are already showing and the current security method changed.
            mKeyguardView.showPrimarySecurityScreen();
        }
        if (mRoot.getVisibility() == View.VISIBLE || mShowingSoon) {
            return;
        }

        final int activeUserId = ActivityManager.getCurrentUser();
        final boolean allowDismissKeyguard =
                !(UserManager.isSplitSystemUser() && activeUserId == UserHandle.USER_SYSTEM)
                && activeUserId == keyguardUserId;
        // If allowed, try to dismiss the Keyguard. If no security auth (password/pin/pattern) is
        // set, this will dismiss the whole Keyguard. Otherwise, show the bouncer.
        if (allowDismissKeyguard && mKeyguardView.dismiss()) {
            return;
        }

        // This condition may indicate an error on Android, so log it.
        if (!allowDismissKeyguard) {
            Slog.w(TAG, "User can't dismiss keyguard: " + activeUserId + " != " + keyguardUserId);
        }

        mShowingSoon = true;

        // Split up the work over multiple frames.
        DejankUtils.postAfterTraversal(mShowRunnable);
    }

    private final Runnable mShowRunnable = new Runnable() {
        @Override
        public void run() {
            mRoot.setVisibility(View.VISIBLE);
            mKeyguardView.onResume();
            showPromptReason(mBouncerPromptReason);
            if (mKeyguardView.getHeight() != 0) {
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
            mKeyguardView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
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
        mKeyguardView.showPromptReason(reason);
    }

    public void showMessage(String message, int color) {
        mKeyguardView.showMessage(message, color);
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
        mFalsingManager.onBouncerHidden();
        cancelShowRunnable();
        if (mKeyguardView != null) {
            mKeyguardView.cancelDismissAction();
            mKeyguardView.cleanUp();
        }
        if (destroyView) {
            removeView();
        } else if (mRoot != null) {
            mRoot.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * See {@link StatusBarKeyguardViewManager#startPreHideAnimation}.
     */
    public void startPreHideAnimation(Runnable runnable) {
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
        return mShowingSoon || (mRoot != null && mRoot.getVisibility() == View.VISIBLE);
    }

    public void prepare() {
        boolean wasInitialized = mRoot != null;
        ensureView();
        if (wasInitialized) {
            mKeyguardView.showPrimarySecurityScreen();
        }
        mBouncerPromptReason = mCallback.getBouncerPromptReason();
    }

    protected void ensureView() {
        if (mRoot == null) {
            inflateView();
        }
    }

    protected void inflateView() {
        removeView();
        mRoot = (ViewGroup) LayoutInflater.from(mContext).inflate(R.layout.keyguard_bouncer, null);
        mKeyguardView = (KeyguardHostView) mRoot.findViewById(R.id.keyguard_host_view);
        mKeyguardView.setLockPatternUtils(mLockPatternUtils);
        mKeyguardView.setViewMediatorCallback(mCallback);
        mContainer.addView(mRoot, mContainer.getChildCount());
        mRoot.setVisibility(View.INVISIBLE);
        mRoot.setSystemUiVisibility(View.STATUS_BAR_DISABLE_HOME);
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
        mKeyguardView.finish(strongAuth);
    }
}
