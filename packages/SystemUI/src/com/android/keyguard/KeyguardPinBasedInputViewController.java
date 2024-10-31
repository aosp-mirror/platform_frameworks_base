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

import static com.android.systemui.Flags.pinInputFieldStyledFocusState;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.domain.interactor.KeyguardKeyboardInteractor;
import com.android.systemui.Flags;
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

public abstract class KeyguardPinBasedInputViewController<T extends KeyguardPinBasedInputView>
        extends KeyguardAbsKeyInputViewController<T> {

    private final LiftToActivateListener mLiftToActivateListener;
    private final FalsingCollector mFalsingCollector;
    private final KeyguardKeyboardInteractor mKeyguardKeyboardInteractor;
    protected PasswordTextView mPasswordEntry;

    private final OnKeyListener mOnKeyListener = (v, keyCode, event) -> {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return mView.onKeyDown(keyCode, event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return mView.onKeyUp(keyCode, event);
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
            FalsingCollector falsingCollector,
            FeatureFlags featureFlags,
            SelectedUserInteractor selectedUserInteractor,
            KeyguardKeyboardInteractor keyguardKeyboardInteractor,
            BouncerHapticPlayer bouncerHapticPlayer,
            UserActivityNotifier userActivityNotifier) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, falsingCollector,
                emergencyButtonController, featureFlags, selectedUserInteractor,
                bouncerHapticPlayer, userActivityNotifier);
        mLiftToActivateListener = liftToActivateListener;
        mFalsingCollector = falsingCollector;
        mKeyguardKeyboardInteractor = keyguardKeyboardInteractor;
        mPasswordEntry = mView.findViewById(mView.getPasswordTextViewId());
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        boolean showAnimations = !mLockPatternUtils
                .isPinEnhancedPrivacyEnabled(mSelectedUserInteractor.getSelectedUserId());
        mPasswordEntry.setShowPassword(showAnimations);
        for (NumPadKey button : mView.getButtons()) {
            button.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mFalsingCollector.avoidGesture();
                }
                return false;
            });
            button.setAnimationEnabled(showAnimations);
            button.setBouncerHapticHelper(mBouncerHapticPlayer);
        }
        mPasswordEntry.setOnKeyListener(mOnKeyListener);
        mPasswordEntry.setUserActivityListener(this::onUserInput);

        View deleteButton = mView.findViewById(R.id.delete_button);
        if (mBouncerHapticPlayer.isEnabled()) {
            deleteButton.setOnTouchListener((View view, MotionEvent event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mBouncerHapticPlayer.playDeleteKeyPressFeedback();
                }
                return false;
            });
        } else {
            deleteButton.setOnTouchListener(mActionButtonTouchListener);
        }
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
            if (mBouncerHapticPlayer.isEnabled()) {
                mBouncerHapticPlayer.playDeleteKeyLongPressedFeedback();
            } else {
                mView.doHapticKeyClick();
            }
            return true;
        });

        View okButton = mView.findViewById(R.id.key_enter);
        if (okButton != null) {
            if (!mBouncerHapticPlayer.isEnabled()) {
                okButton.setOnTouchListener(mActionButtonTouchListener);
            }
            okButton.setOnClickListener(v -> {
                if (mPasswordEntry.isEnabled()) {
                    verifyPasswordAndUnlock();
                }
            });

            if (!Flags.simPinTalkbackFixForDoubleSubmit()) {
                okButton.setOnHoverListener(mLiftToActivateListener);
            }
        }
        if (pinInputFieldStyledFocusState()) {
            collectFlow(mPasswordEntry, mKeyguardKeyboardInteractor.isAnyKeyboardConnected(),
                    this::setKeyboardBasedFocusOutline);

            /**
             * new UI Specs for PIN Input field have new dimensions go/pin-focus-states.
             * However we want these changes behind a flag, and resource files cannot be flagged
             * hence the dimension change in code. When the flags are removed these dimensions
             * should be set in resources permanently and the code below removed.
             */
            ViewGroup.LayoutParams layoutParams = mPasswordEntry.getLayoutParams();
            layoutParams.width = (int) getResources().getDimension(
                    R.dimen.keyguard_pin_field_width);
            layoutParams.height = (int) getResources().getDimension(
                    R.dimen.keyguard_pin_field_height);
        }
    }

    private void setKeyboardBasedFocusOutline(boolean isAnyKeyboardConnected) {
        Drawable background = mPasswordEntry.getBackground();
        if (!(background instanceof StateListDrawable)) return;
        Drawable stateDrawable = ((StateListDrawable) background).getStateDrawable(0);
        if (!(stateDrawable instanceof GradientDrawable gradientDrawable)) return;

        int color = getResources().getColor(R.color.bouncer_password_focus_color);
        if (!isAnyKeyboardConnected) {
            gradientDrawable.setStroke(0, color);
        } else {
            int strokeWidthInDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3,
                    getResources().getDisplayMetrics());
            gradientDrawable.setStroke(strokeWidthInDP, color);
        }
    }


    @Override
    protected void onViewDetached() {
        super.onViewDetached();

        for (NumPadKey button : mView.getButtons()) {
            button.setOnTouchListener(null);
            button.setBouncerHapticHelper(null);
        }
    }

    @Override
    public void onResume(int reason) {
        super.onResume(reason);
        // It's possible to reach a state here where mPasswordEntry believes it is focused
        // but it is not actually focused. This state will prevent the view from gaining focus,
        // as requestFocus will no-op since the focus flag is already set. By clearing focus first,
        // it's guaranteed that the view has focus.
        mPasswordEntry.clearFocus();
        mPasswordEntry.requestFocus();
    }

    @Override
    void resetState() {
        mMessageAreaController.setMessage(getInitialMessageResId());
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
