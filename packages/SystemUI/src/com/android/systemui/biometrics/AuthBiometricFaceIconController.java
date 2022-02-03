/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

class AuthBiometricFaceIconController extends Animatable2.AnimationCallback {

    private static final String TAG = "AuthBiometricFaceIconController";

    protected Context mContext;
    protected ImageView mIconView;
    protected TextView mTextView;
    protected Handler mHandler;
    protected boolean mLastPulseLightToDark; // false = dark to light, true = light to dark
    @AuthBiometricView.BiometricState protected int mState;
    protected boolean mDeactivated;

    protected AuthBiometricFaceIconController(Context context, ImageView iconView,
            TextView textView) {
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
        Log.d(TAG, "animateIcon, state: " + mState + ", deactivated: " + mDeactivated);
        if (mDeactivated) {
            return;
        }

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
        Log.d(TAG, "onAnimationEnd, mState: " + mState + ", deactivated: " + mDeactivated);
        if (mDeactivated) {
            return;
        }

        if (mState == AuthBiometricView.STATE_AUTHENTICATING
                || mState == AuthBiometricView.STATE_HELP) {
            pulseInNextDirection();
        }
    }

    protected void deactivate() {
        mDeactivated = true;
    }

    protected void updateState(int lastState, int newState) {
        if (mDeactivated) {
            Log.w(TAG, "Ignoring updateState when deactivated: " + newState);
            return;
        }

        final boolean lastStateIsErrorIcon =
                lastState == AuthBiometricView.STATE_ERROR
                        || lastState == AuthBiometricView.STATE_HELP;

        if (newState == AuthBiometricView.STATE_AUTHENTICATING_ANIMATING_IN) {
            showStaticDrawable(R.drawable.face_dialog_pulse_dark_to_light);
            mIconView.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticating));
        } else if (newState == AuthBiometricView.STATE_AUTHENTICATING) {
            startPulsing();
            mIconView.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticating));
        } else if (lastState == AuthBiometricView.STATE_PENDING_CONFIRMATION
                && newState == AuthBiometricView.STATE_AUTHENTICATED) {
            animateOnce(R.drawable.face_dialog_dark_to_checkmark);
            mIconView.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_confirmed));
        } else if (lastStateIsErrorIcon && newState == AuthBiometricView.STATE_IDLE) {
            animateOnce(R.drawable.face_dialog_error_to_idle);
            mIconView.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_idle));
        } else if (lastStateIsErrorIcon && newState == AuthBiometricView.STATE_AUTHENTICATED) {
            animateOnce(R.drawable.face_dialog_dark_to_checkmark);
            mIconView.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated));
        } else if (newState == AuthBiometricView.STATE_ERROR
                && lastState != AuthBiometricView.STATE_ERROR) {
            animateOnce(R.drawable.face_dialog_dark_to_error);
        } else if (lastState == AuthBiometricView.STATE_AUTHENTICATING
                && newState == AuthBiometricView.STATE_AUTHENTICATED) {
            animateOnce(R.drawable.face_dialog_dark_to_checkmark);
            mIconView.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated));
        } else if (newState == AuthBiometricView.STATE_PENDING_CONFIRMATION) {
            animateOnce(R.drawable.face_dialog_wink_from_dark);
            mIconView.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated));
        } else if (newState == AuthBiometricView.STATE_IDLE) {
            showStaticDrawable(R.drawable.face_dialog_idle_static);
            mIconView.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_idle));
        } else {
            Log.w(TAG, "Unhandled state: " + newState);
        }
        mState = newState;
    }
}
