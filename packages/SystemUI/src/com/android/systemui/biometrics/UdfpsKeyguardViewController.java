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

package com.android.systemui.biometrics;

import android.annotation.NonNull;

import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Class that coordinates non-HBM animations during keyguard authentication.
 */
public class UdfpsKeyguardViewController extends UdfpsAnimationViewController<UdfpsKeyguardView> {
    @NonNull private final StatusBarKeyguardViewManager mKeyguardViewManager;

    private boolean mForceShow;
    private boolean mQsExpanded;

    protected UdfpsKeyguardViewController(
            @NonNull UdfpsKeyguardView view,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull StatusBar statusBar,
            @NonNull StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @NonNull DumpManager dumpManager) {
        super(view, statusBarStateController, statusBar, dumpManager);
        mKeyguardViewManager = statusBarKeyguardViewManager;
    }

    @Override
    @NonNull String getTag() {
        return "UdfpsKeyguardViewController";
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        final float dozeAmount = mStatusBarStateController.getDozeAmount();
        mStatusBarStateController.addCallback(mStateListener);
        mStateListener.onDozeAmountChanged(dozeAmount, dozeAmount);
        mStateListener.onStateChanged(mStatusBarStateController.getState());
        mKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mStatusBarStateController.removeCallback(mStateListener);
        mAlternateAuthInterceptor.resetForceShow();
        mKeyguardViewManager.setAlternateAuthInterceptor(null);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("mForceShow=" + mForceShow);
    }

    /**
     * Overrides non-force show logic in shouldPauseAuth to still auth.
     */
    private void forceShow(boolean forceShow) {
        if (mForceShow == forceShow) {
            return;
        }

        mForceShow = forceShow;
        updatePauseAuth();
        if (mForceShow) {
            mView.animateHighlightFp();
        } else {
            mView.animateUnhighlightFp(() -> mKeyguardViewManager.cancelPostAuthActions());
        }
    }

    /**
     * Returns true if the fingerprint manager is running but we want to temporarily pause
     * authentication. On the keyguard, we may want to show udfps when the shade
     * is expanded, so this can be overridden with the forceShow method.
     */
    public boolean shouldPauseAuth() {
        if (mForceShow) {
            return false;
        }

        if (mQsExpanded) {
            return true;
        }

        return super.shouldPauseAuth();
    }

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onDozeAmountChanged(float linear, float eased) {
            mView.onDozeAmountChanged(linear, eased);
            if (linear != 0) forceShow(false);
        }

        @Override
        public void onStateChanged(int statusBarState) {
            mView.setStatusBarState(statusBarState);
        }
    };

    private final StatusBarKeyguardViewManager.AlternateAuthInterceptor mAlternateAuthInterceptor =
            new StatusBarKeyguardViewManager.AlternateAuthInterceptor() {
                @Override
                public boolean showAlternativeAuthMethod() {
                    if (mForceShow) {
                        return false;
                    }

                    forceShow(true);
                    return true;
                }

                @Override
                public boolean resetForceShow() {
                    if (!mForceShow) {
                        return false;
                    }

                    forceShow(false);
                    return true;
                }

                @Override
                public boolean isShowingAlternateAuth() {
                    return mForceShow;
                }

                @Override
                public boolean isAnimating() {
                    return mView.isAnimating();
                }

                @Override
                public void setQsExpanded(boolean expanded) {
                    mQsExpanded = expanded;
                    updatePauseAuth();
                }

                @Override
                public void dump(PrintWriter pw) {
                    pw.print(getTag());
                }
            };
}
