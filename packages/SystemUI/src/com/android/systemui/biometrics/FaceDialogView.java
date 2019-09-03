/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.Context;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.android.systemui.R;

/**
 * This class loads the view for the system-provided dialog. The view consists of:
 * Application Icon, Title, Subtitle, Description, Biometric Icon, Error/Help message area,
 * and positive/negative buttons.
 */
public class FaceDialogView extends BiometricDialogView {

    private static final String TAG = "BiometricPrompt/FaceDialogView";

    private static final String KEY_DIALOG_ANIMATED_IN = "key_dialog_animated_in";

    private static final int HIDE_DIALOG_DELAY = 500; // ms

    private IconController mIconController;
    private boolean mDialogAnimatedIn;

    /**
     * Class that handles the biometric icon animations.
     */
    private final class IconController extends Animatable2.AnimationCallback {

        private boolean mLastPulseDirection; // false = dark to light, true = light to dark

        int mState;

        IconController() {
            mState = STATE_IDLE;
        }

        public void animateOnce(int iconRes) {
            animateIcon(iconRes, false);
        }

        public void showStatic(int iconRes) {
            mBiometricIcon.setImageDrawable(mContext.getDrawable(iconRes));
        }

        public void startPulsing() {
            mLastPulseDirection = false;
            animateIcon(R.drawable.face_dialog_pulse_dark_to_light, true);
        }

        public void showIcon(int iconRes) {
            final Drawable drawable = mContext.getDrawable(iconRes);
            mBiometricIcon.setImageDrawable(drawable);
        }

        private void animateIcon(int iconRes, boolean repeat) {
            final AnimatedVectorDrawable icon =
                    (AnimatedVectorDrawable) mContext.getDrawable(iconRes);
            mBiometricIcon.setImageDrawable(icon);
            icon.forceAnimationOnUI();
            if (repeat) {
                icon.registerAnimationCallback(this);
            }
            icon.start();
        }

        private void pulseInNextDirection() {
            int iconRes = mLastPulseDirection ? R.drawable.face_dialog_pulse_dark_to_light
                    : R.drawable.face_dialog_pulse_light_to_dark;
            animateIcon(iconRes, true /* repeat */);
            mLastPulseDirection = !mLastPulseDirection;
        }

        @Override
        public void onAnimationEnd(Drawable drawable) {
            super.onAnimationEnd(drawable);

            if (mState == STATE_AUTHENTICATING) {
                // Still authenticating, pulse the icon
                pulseInNextDirection();
            }
        }
    }

    private final Runnable mErrorToIdleAnimationRunnable = () -> {
        updateState(STATE_IDLE);
        mErrorText.setVisibility(View.INVISIBLE);
        announceAccessibilityEvent();
    };

    protected FaceDialogView(Context context, AuthDialogCallback callback, Injector injector) {
        super(context, callback, injector);
        mIconController = new IconController();
    }

    @Override
    public void onSaveState(Bundle bundle) {
        super.onSaveState(bundle);
        bundle.putBoolean(KEY_DIALOG_ANIMATED_IN, mDialogAnimatedIn);
    }


    @Override
    protected void handleResetMessage() {
        mErrorText.setTextColor(mTextColor);
        mErrorText.setVisibility(View.INVISIBLE);
        announceAccessibilityEvent();
    }

    @Override
    public void restoreState(Bundle bundle) {
        super.restoreState(bundle);
        mDialogAnimatedIn = bundle.getBoolean(KEY_DIALOG_ANIMATED_IN);
    }

    @Override
    public void onAuthenticationFailed(String message) {
        super.onAuthenticationFailed(message);
        showTryAgainButton(true);
    }

    @Override
    protected int getHintStringResourceId() {
        return 0;
    }

    @Override
    protected int getAuthenticatedAccessibilityResourceId() {
        if (mRequireConfirmation) {
            return com.android.internal.R.string.face_authenticated_confirmation_required;
        } else {
            return com.android.internal.R.string.face_authenticated_no_confirmation_required;
        }
    }

    @Override
    protected int getIconDescriptionResourceId() {
        return R.string.accessibility_face_dialog_face_icon;
    }

    @Override
    protected void updateIcon(int oldState, int newState) {
        mIconController.mState = newState;

        if (newState == STATE_AUTHENTICATING) {
            mHandler.removeCallbacks(mErrorToIdleAnimationRunnable);
            if (mDialogAnimatedIn) {
                mIconController.startPulsing();
            } else {
                mIconController.showIcon(R.drawable.face_dialog_pulse_dark_to_light);
            }
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticating));
        } else if (oldState == STATE_PENDING_CONFIRMATION && newState == STATE_AUTHENTICATED) {
            mIconController.animateOnce(R.drawable.face_dialog_dark_to_checkmark);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_confirmed));
        } else if (oldState == STATE_ERROR && newState == STATE_IDLE) {
            mIconController.animateOnce(R.drawable.face_dialog_error_to_idle);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_idle));
        } else if (oldState == STATE_ERROR && newState == STATE_AUTHENTICATED) {
            mHandler.removeCallbacks(mErrorToIdleAnimationRunnable);
            mIconController.animateOnce(R.drawable.face_dialog_dark_to_checkmark);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated));
        } else if (newState == STATE_ERROR) {
            // It's easier to only check newState and gate showing the animation on the
            // mErrorToIdleAnimationRunnable as a proxy, than add a ton of extra state. For example,
            // we may go from error -> error due to configuration change which is valid and we
            // should show the animation, or we can go from error -> error by receiving repeated
            // acquire messages in which case we do not want to repeatedly start the animation.
            if (!mHandler.hasCallbacks(mErrorToIdleAnimationRunnable)) {
                mIconController.animateOnce(R.drawable.face_dialog_dark_to_error);
                mHandler.postDelayed(mErrorToIdleAnimationRunnable,
                        BiometricPrompt.HIDE_DIALOG_DELAY);
            }
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_AUTHENTICATED) {
            mIconController.animateOnce(R.drawable.face_dialog_dark_to_checkmark);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated));
        } else if (newState == STATE_PENDING_CONFIRMATION) {
            mHandler.removeCallbacks(mErrorToIdleAnimationRunnable);
            mIconController.animateOnce(R.drawable.face_dialog_wink_from_dark);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated));
        } else if (newState == STATE_IDLE) {
            mIconController.showStatic(R.drawable.face_dialog_idle_static);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_idle));
        } else {
            Log.w(TAG, "Unknown animation from " + oldState + " -> " + newState);
        }

        // Note that this must be after the newState == STATE_ERROR check above since this affects
        // the logic.
        if (oldState == STATE_ERROR && newState == STATE_ERROR) {
            // Keep the error icon and text around for a while longer if we keep receiving
            // STATE_ERROR
            mHandler.removeCallbacks(mErrorToIdleAnimationRunnable);
            mHandler.postDelayed(mErrorToIdleAnimationRunnable, BiometricPrompt.HIDE_DIALOG_DELAY);
        }
    }

    @Override
    protected boolean supportsSmallDialog() {
        return true;
    }

    @Override
    public void onDialogAnimatedIn() {
        super.onDialogAnimatedIn();
        mDialogAnimatedIn = true;
        mIconController.startPulsing();
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return HIDE_DIALOG_DELAY;
    }

    @Override
    protected boolean shouldGrayAreaDismissDialog() {
        if (getSize() == SIZE_SMALL) {
            return false;
        }
        return true;
    }


}
