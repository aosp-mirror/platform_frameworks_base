/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import android.content.res.ColorStateList;
import android.view.MotionEvent;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;


/** Controller for a {@link KeyguardSecurityView}. */
public class KeyguardInputViewController<T extends KeyguardInputView> extends ViewController<T>
        implements KeyguardSecurityView {

    private final SecurityMode mSecurityMode;
    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardSecurityCallback mKeyguardSecurityCallback;
    private KeyguardMessageAreaController mMessageAreaController;

    protected KeyguardInputViewController(T view, SecurityMode securityMode,
            LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback) {
        super(view);
        mSecurityMode = securityMode;
        mLockPatternUtils = lockPatternUtils;
        mKeyguardSecurityCallback = keyguardSecurityCallback;
        mView.setKeyguardCallback(keyguardSecurityCallback);
    }

    @Override
    public void init() {
        super.init();
        mView.reset();
    }

    @Override
    protected void onViewAttached() {
    }

    @Override
    protected void onViewDetached() {
    }

    SecurityMode getSecurityMode() {
        return mSecurityMode;
    }


    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mView.setKeyguardCallback(callback);
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        mView.setLockPatternUtils(utils);
    }

    @Override
    public void reset() {
        mView.reset();
    }

    @Override
    public void onPause() {
        mView.onPause();
    }

    @Override
    public void onResume(int reason) {
        mView.onResume(reason);
    }

    @Override
    public boolean needsInput() {
        return mView.needsInput();
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mView.getCallback();
    }

    @Override
    public void showPromptReason(int reason) {
        mView.showPromptReason(reason);
    }

    @Override
    public void showMessage(CharSequence message, ColorStateList colorState) {
        mView.showMessage(message, colorState);
    }

    @Override
    public void showUsabilityHint() {
        mView.showUsabilityHint();
    }

    @Override
    public void startAppearAnimation() {
        mView.startAppearAnimation();
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return mView.startDisappearAnimation(finishRunnable);
    }

    @Override
    public CharSequence getTitle() {
        return mView.getTitle();
    }

    @Override
    public boolean disallowInterceptTouch(MotionEvent event) {
        return mView.disallowInterceptTouch(event);
    }

    @Override
    public void onStartingToHide() {
        mView.onStartingToHide();
    }

    /** Finds the index of this view in the suppplied parent view. */
    public int getIndexIn(KeyguardSecurityViewFlipper view) {
        return view.indexOfChild(mView);
    }

    /** Factory for a {@link KeyguardInputViewController}. */
    public static class Factory {
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final LockPatternUtils mLockPatternUtils;
        private final LatencyTracker mLatencyTracker;
        private final KeyguardMessageAreaController.Factory mMessageAreaControllerFactory;

        @Inject
        public Factory(KeyguardUpdateMonitor keyguardUpdateMonitor,
                LockPatternUtils lockPatternUtils,
                LatencyTracker latencyTracker,
                KeyguardMessageAreaController.Factory messageAreaControllerFactory) {
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mLockPatternUtils = lockPatternUtils;
            mLatencyTracker = latencyTracker;
            mMessageAreaControllerFactory = messageAreaControllerFactory;
        }

        /** Create a new {@link KeyguardInputViewController}. */
        public KeyguardInputViewController create(KeyguardInputView keyguardInputView,
                SecurityMode securityMode, KeyguardSecurityCallback keyguardSecurityCallback) {
            if (keyguardInputView instanceof KeyguardPatternView) {
                return new KeyguardPatternViewController((KeyguardPatternView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mLatencyTracker, mMessageAreaControllerFactory);
            }
            return new KeyguardInputViewController<>(keyguardInputView, securityMode,
                    mLockPatternUtils, keyguardSecurityCallback);
        }
    }
}
