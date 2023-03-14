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

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.View.OnTouchListener;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingCollector;

public abstract class KeyguardPinBasedInputViewController<T extends KeyguardPinBasedInputView>
        extends KeyguardAbsKeyInputViewController<T> {

    private final LiftToActivateListener mLiftToActivateListener;
    private final FalsingCollector mFalsingCollector;
    protected PasswordTextView mPasswordEntry;

    private final OnKeyListener mOnKeyListener = (v, keyCode, event) -> {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return mView.onKeyDown(keyCode, event);
        }
        return false;
    };

    private final OnTouchListener mActionButtonTouchListener = (v, event) -> {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mView.doHapticKeyClick();
        }
        return false;
    };

    protected KeyguardPinBasedInputViewController(T view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode,
            LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            LatencyTracker latencyTracker,
            LiftToActivateListener liftToActivateListener,
            EmergencyButtonController emergencyButtonController,
            FalsingCollector falsingCollector) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, falsingCollector,
                emergencyButtonController);
        mLiftToActivateListener = liftToActivateListener;
        mFalsingCollector = falsingCollector;
        mPasswordEntry = mView.findViewById(mView.getPasswordTextViewId());
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        for (NumPadKey button: mView.getButtons()) {
            button.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mFalsingCollector.avoidGesture();
                }
                return false;
            });
        }
        mPasswordEntry.setOnKeyListener(mOnKeyListener);
        mPasswordEntry.setUserActivityListener(this::onUserInput);

        View deleteButton = mView.findViewById(R.id.delete_button);
        deleteButton.setOnTouchListener(mActionButtonTouchListener);
        deleteButton.setOnClickListener(v -> {
            // check for time-based lockouts
            if (mPasswordEntry.isEnabled()) {
                mPasswordEntry.deleteLastChar();
            }
        });
        deleteButton.setOnLongClickListener(v -> {
            // check for time-based lockouts
            if (mPasswordEntry.isEnabled()) {
                mView.resetPasswordText(true /* animate */, true /* announce */);
            }
            mView.doHapticKeyClick();
            return true;
        });

        View okButton = mView.findViewById(R.id.key_enter);
        if (okButton != null) {
            okButton.setOnTouchListener(mActionButtonTouchListener);
            okButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mPasswordEntry.isEnabled()) {
                        verifyPasswordAndUnlock();
                    }
                }
            });
            okButton.setOnHoverListener(mLiftToActivateListener);
        }
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();

        for (NumPadKey button: mView.getButtons()) {
            button.setOnTouchListener(null);
        }
    }

    @Override
    public void onResume(int reason) {
        super.onResume(reason);
        mPasswordEntry.requestFocus();
    }

    @Override
    void resetState() {
        mView.setPasswordEntryEnabled(true);
    }

    @Override
    protected void startErrorAnimation() {
        super.startErrorAnimation();
        mView.startErrorAnimation();
    }

    @Override
    protected int getInitialMessageResId() {
        return R.string.keyguard_enter_your_pin;
    }
}
