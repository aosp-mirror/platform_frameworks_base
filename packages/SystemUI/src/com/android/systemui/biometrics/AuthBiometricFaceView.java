/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.content.Context;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

public class AuthBiometricFaceView extends AuthBiometricView {

    private static final String TAG = "BiometricPrompt/AuthBiometricFaceView";

    // Delay before dismissing after being authenticated/confirmed.
    private static final int HIDE_DELAY_MS = 500;

    protected static class IconController extends Animatable2.AnimationCallback {
        protected Context mContext;
        protected ImageView mIconView;
        protected TextView mTextView;
        protected Handler mHandler;
        protected boolean mLastPulseLightToDark; // false = dark to light, true = light to dark
        protected @BiometricState int mState;

        protected IconController(Context context, ImageView iconView, TextView textView) {
            mContext = context;
            mIconView = iconView;
            mTextView = textView;
            mHandler = new Handler(Looper.getMainLooper());
            showStaticDrawable(R.drawable.face_dialog_pulse_dark_to_light);
        }

        protected void animateOnce(int iconRes) {
            animateIcon(iconRes, false);
        }

        protected void showStaticDrawable(int iconRes) {
            mIconView.setImageDrawable(mContext.getDrawable(iconRes));
        }

        protected void animateIcon(int iconRes, boolean repeat) {
            final AnimatedVectorDrawable icon =
                    (AnimatedVectorDrawable) mContext.getDrawable(iconRes);
            mIconView.setImageDrawable(icon);
            icon.forceAnimationOnUI();
            if (repeat) {
                icon.registerAnimationCallback(this);
            }
            icon.start();
        }

        protected void startPulsing() {
            mLastPulseLightToDark = false;
            animateIcon(R.drawable.face_dialog_pulse_dark_to_light, true);
        }

        protected void pulseInNextDirection() {
            int iconRes = mLastPulseLightToDark ? R.drawable.face_dialog_pulse_dark_to_light
                    : R.drawable.face_dialog_pulse_light_to_dark;
            animateIcon(iconRes, true /* repeat */);
            mLastPulseLightToDark = !mLastPulseLightToDark;
        }

        @Override
        public void onAnimationEnd(Drawable drawable) {
            super.onAnimationEnd(drawable);
            if (mState == STATE_AUTHENTICATING || mState == STATE_HELP) {
                pulseInNextDirection();
            }
        }

        protected void updateState(int lastState, int newState) {
            final boolean lastStateIsErrorIcon =
                    lastState == STATE_ERROR || lastState == STATE_HELP;

            if (newState == STATE_AUTHENTICATING_ANIMATING_IN) {
                showStaticDrawable(R.drawable.face_dialog_pulse_dark_to_light);
                mIconView.setContentDescription(mContext.getString(
                        R.string.biometric_dialog_face_icon_description_authenticating));
            } else if (newState == STATE_AUTHENTICATING) {
                startPulsing();
                mIconView.setContentDescription(mContext.getString(
                        R.string.biometric_dialog_face_icon_description_authenticating));
            } else if (lastState == STATE_PENDING_CONFIRMATION && newState == STATE_AUTHENTICATED) {
                animateOnce(R.drawable.face_dialog_dark_to_checkmark);
                mIconView.setContentDescription(mContext.getString(
                        R.string.biometric_dialog_face_icon_description_confirmed));
            } else if (lastStateIsErrorIcon && newState == STATE_IDLE) {
                animateOnce(R.drawable.face_dialog_error_to_idle);
                mIconView.setContentDescription(mContext.getString(
                        R.string.biometric_dialog_face_icon_description_idle));
            } else if (lastStateIsErrorIcon && newState == STATE_AUTHENTICATED) {
                animateOnce(R.drawable.face_dialog_dark_to_checkmark);
                mIconView.setContentDescription(mContext.getString(
                        R.string.biometric_dialog_face_icon_description_authenticated));
            } else if (newState == STATE_ERROR && lastState != STATE_ERROR) {
                animateOnce(R.drawable.face_dialog_dark_to_error);
            } else if (lastState == STATE_AUTHENTICATING && newState == STATE_AUTHENTICATED) {
                animateOnce(R.drawable.face_dialog_dark_to_checkmark);
                mIconView.setContentDescription(mContext.getString(
                        R.string.biometric_dialog_face_icon_description_authenticated));
            } else if (newState == STATE_PENDING_CONFIRMATION) {
                animateOnce(R.drawable.face_dialog_wink_from_dark);
                mIconView.setContentDescription(mContext.getString(
                        R.string.biometric_dialog_face_icon_description_authenticated));
            } else if (newState == STATE_IDLE) {
                showStaticDrawable(R.drawable.face_dialog_idle_static);
                mIconView.setContentDescription(mContext.getString(
                        R.string.biometric_dialog_face_icon_description_idle));
            } else {
                Log.w(TAG, "Unhandled state: " + newState);
            }
            mState = newState;
        }
    }

    protected IconController mIconController;

    public AuthBiometricFaceView(Context context) {
        this(context, null);
    }

    public AuthBiometricFaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return HIDE_DELAY_MS;
    }

    @Override
    protected int getStateForAfterError() {
        return STATE_IDLE;
    }

    @Override
    protected void handleResetAfterError() {
        resetErrorView();
    }

    @Override
    protected void handleResetAfterHelp() {
        resetErrorView();
    }

    @Override
    protected boolean supportsSmallDialog() {
        return true;
    }

    @Override
    protected boolean supportsManualRetry() {
        return true;
    }

    @NonNull
    protected IconController getIconController() {
        if (mIconController == null) {
            mIconController = new IconController(mContext, mIconView, mIndicatorView);
        }
        return mIconController;
    }

    @Override
    public void updateState(@BiometricState int newState) {
        getIconController().updateState(mState, newState);

        if (newState == STATE_AUTHENTICATING_ANIMATING_IN ||
                (newState == STATE_AUTHENTICATING && getSize() == AuthDialog.SIZE_MEDIUM)) {
            resetErrorView();
        }

        // Do this last since the state variable gets updated.
        super.updateState(newState);
    }

    @Override
    public void onAuthenticationFailed(String failureReason) {
        if (getSize() == AuthDialog.SIZE_MEDIUM) {
            if (supportsManualRetry()) {
                mTryAgainButton.setVisibility(View.VISIBLE);
                mConfirmButton.setVisibility(View.GONE);
            }
        }

        // Do this last since we want to know if the button is being animated (in the case of
        // small -> medium dialog)
        super.onAuthenticationFailed(failureReason);
    }

    private void resetErrorView() {
        mIndicatorView.setTextColor(mTextColorHint);
        mIndicatorView.setVisibility(View.INVISIBLE);
    }
}
