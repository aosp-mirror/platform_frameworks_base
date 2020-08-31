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

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityContainer.SecurityCallback;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for {@link KeyguardSecurityContainer} */
public class KeyguardSecurityContainerController extends ViewController<KeyguardSecurityContainer> {

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardSecurityViewController.Factory mKeyguardSecurityViewControllerFactory;

    @Inject
    KeyguardSecurityContainerController(KeyguardSecurityContainer view,
            LockPatternUtils lockPatternUtils,
            KeyguardSecurityViewController.Factory keyguardSecurityViewControllerFactory) {
        super(view);
        mLockPatternUtils = lockPatternUtils;
        view.setLockPatternUtils(mLockPatternUtils);
        mKeyguardSecurityViewControllerFactory = keyguardSecurityViewControllerFactory;
    }

    @Override
    protected void onViewAttached() {
    }

    @Override
    protected void onViewDetached() {
    }

    /** */
    public void onPause() {
        mView.onPause();
    }

    public void showPrimarySecurityScreen(boolean turningOff) {
        mView.showPrimarySecurityScreen(turningOff);
    }

    public void showPromptReason(int reason) {
        mView.showPromptReason(reason);
    }

    public void showMessage(CharSequence message, ColorStateList colorState) {
        mView.showMessage(message, colorState);
    }

    public SecurityMode getCurrentSecuritySelection() {
        return mView.getCurrentSecuritySelection();
    }

    public void dismiss(boolean authenticated, int targetUserId) {
        mView.dismiss(authenticated, targetUserId);
    }

    public void reset() {
        mView.reset();
    }

    public CharSequence getTitle() {
        return mView.getTitle();
    }

    public void onResume(int screenOn) {
        mView.onResume(screenOn);
    }

    public void startAppearAnimation() {
        mView.startAppearAnimation();
    }

    public boolean startDisappearAnimation(Runnable onFinishRunnable) {
        return mView.startDisappearAnimation(onFinishRunnable);
    }

    public void onStartingToHide() {
        mView.onStartingToHide();
    }

    public void setSecurityCallback(SecurityCallback securityCallback) {
        mView.setSecurityCallback(securityCallback);
    }

    public boolean showNextSecurityScreenOrFinish(boolean authenticated, int targetUserId,
            boolean bypassSecondaryLockScreen) {
        return mView.showNextSecurityScreenOrFinish(
                authenticated, targetUserId, bypassSecondaryLockScreen);
    }

    public boolean needsInput() {
        return mView.needsInput();
    }

    public SecurityMode getCurrentSecurityMode() {
        return mView.getCurrentSecurityMode();
    }
}
