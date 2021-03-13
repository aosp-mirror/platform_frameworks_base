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

import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.StatusBar;

/**
 * Class that coordinates non-HBM animations during keyguard authentication.
 */
public class UdfpsKeyguardViewController extends UdfpsAnimationViewController<UdfpsKeyguardView> {
    private boolean mForceShow;

    protected UdfpsKeyguardViewController(
            UdfpsKeyguardView view,
            StatusBarStateController statusBarStateController,
            StatusBar statusBar) {
        super(view, statusBarStateController, statusBar);
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        mStatusBarStateController.addCallback(mStateListener);
        final float dozeAmount = mStatusBarStateController.getDozeAmount();
        mStateListener.onDozeAmountChanged(dozeAmount, dozeAmount);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mStatusBarStateController.removeCallback(mStateListener);
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
        // TODO: animate show/hide background protection
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
        return super.shouldPauseAuth();
    }

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onDozeAmountChanged(float linear, float eased) {
            mView.onDozeAmountChanged(linear, eased);
        }
    };
}
