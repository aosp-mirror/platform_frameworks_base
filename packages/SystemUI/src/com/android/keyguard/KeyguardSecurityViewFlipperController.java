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
import android.util.Log;
import android.view.LayoutInflater;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardInputViewController.Factory;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.systemui.R;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Controller for a {@link KeyguardSecurityViewFlipper}.
 */
@KeyguardBouncerScope
public class KeyguardSecurityViewFlipperController
        extends ViewController<KeyguardSecurityViewFlipper> implements KeyguardSecurityView {

    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardSecurityView";

    private final List<KeyguardInputViewController> mChildren = new ArrayList<>();
    private final LayoutInflater mLayoutInflater;
    private final Factory mKeyguardSecurityViewControllerFactory;

    @Inject
    protected KeyguardSecurityViewFlipperController(KeyguardSecurityViewFlipper view,
            LayoutInflater layoutInflater,
            KeyguardInputViewController.Factory keyguardSecurityViewControllerFactory) {
        super(view);
        mKeyguardSecurityViewControllerFactory = keyguardSecurityViewControllerFactory;
        mLayoutInflater = layoutInflater;
    }

    @Override
    protected void onViewAttached() {

    }

    @Override
    protected void onViewDetached() {

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
        for (KeyguardInputViewController child : mChildren) {
            child.reset();
        }
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

    @VisibleForTesting
    KeyguardInputViewController getSecurityView(SecurityMode securityMode,
            KeyguardSecurityCallback keyguardSecurityCallback) {
        KeyguardInputViewController childController = null;
        for (KeyguardInputViewController mChild : mChildren) {
            if (mChild.getSecurityMode() == securityMode) {
                childController = mChild;
                break;
            }
        }

        if (childController == null
                && securityMode != SecurityMode.None && securityMode != SecurityMode.Invalid) {

            int layoutId = getLayoutIdFor(securityMode);
            KeyguardInputView view = null;
            if (layoutId != 0) {
                if (DEBUG) Log.v(TAG, "inflating id = " + layoutId);
                view = (KeyguardInputView) mLayoutInflater.inflate(
                        layoutId, mView, false);
                mView.addView(view);
                childController = mKeyguardSecurityViewControllerFactory.create(
                        view, securityMode, keyguardSecurityCallback);
                childController.init();

                mChildren.add(childController);
            }
        }

        return childController;
    }

    private int getLayoutIdFor(SecurityMode securityMode) {
        switch (securityMode) {
            case Pattern: return com.android.systemui.R.layout.keyguard_pattern_view;
            case PIN: return com.android.systemui.R.layout.keyguard_pin_view;
            case Password: return com.android.systemui.R.layout.keyguard_password_view;
            case SimPin: return com.android.systemui.R.layout.keyguard_sim_pin_view;
            case SimPuk: return R.layout.keyguard_sim_puk_view;
            default:
                return 0;
        }
    }

    /** Makes the supplied child visible if it is contained win this view, */
    public void show(KeyguardInputViewController childController) {
        int index = childController.getIndexIn(mView);
        if (index != -1) {
            mView.setDisplayedChild(index);
        }
    }
}
