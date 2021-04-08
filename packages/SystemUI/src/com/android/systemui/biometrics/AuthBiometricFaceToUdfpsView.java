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

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Manages the layout of an auth dialog for devices with a face sensor and an under-display
 * fingerprint sensor (UDFPS). Face authentication is attempted first, followed by fingerprint if
 * the initial attempt is unsuccessful.
 */
public class AuthBiometricFaceToUdfpsView extends AuthBiometricFaceView {
    private static final String TAG = "BiometricPrompt/AuthBiometricFaceToUdfpsView";

    protected static class UdfpsIconController extends IconController {
        protected UdfpsIconController(
                @NonNull Context context, @NonNull ImageView iconView, @NonNull TextView textView) {
            super(context, iconView, textView);
        }

        @Override
        protected void updateState(int lastState, int newState) {
            final boolean lastStateIsErrorIcon =
                    lastState == STATE_ERROR || lastState == STATE_HELP;

            switch (newState) {
                case STATE_IDLE:
                case STATE_AUTHENTICATING_ANIMATING_IN:
                case STATE_AUTHENTICATING:
                case STATE_PENDING_CONFIRMATION:
                case STATE_AUTHENTICATED:
                    if (lastStateIsErrorIcon) {
                        animateOnce(R.drawable.fingerprint_dialog_error_to_fp);
                    } else {
                        showStaticDrawable(R.drawable.fingerprint_dialog_fp_to_error);
                    }
                    mIconView.setContentDescription(mContext.getString(
                            R.string.accessibility_fingerprint_dialog_fingerprint_icon));
                    break;

                case STATE_ERROR:
                case STATE_HELP:
                    if (!lastStateIsErrorIcon) {
                        animateOnce(R.drawable.fingerprint_dialog_fp_to_error);
                    } else {
                        showStaticDrawable(R.drawable.fingerprint_dialog_error_to_fp);
                    }
                    mIconView.setContentDescription(mContext.getString(
                            R.string.biometric_dialog_try_again));
                    break;

                default:
                    Log.e(TAG, "Unknown biometric dialog state: " + newState);
                    break;
            }

            mState = newState;
        }
    }

    @BiometricAuthenticator.Modality private int mActiveSensorType = TYPE_FACE;

    @Nullable UdfpsDialogMeasureAdapter mMeasureAdapter;
    @Nullable private UdfpsIconController mUdfpsIconController;

    public AuthBiometricFaceToUdfpsView(Context context) {
        super(context);
    }

    public AuthBiometricFaceToUdfpsView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setFingerprintSensorProps(@NonNull FingerprintSensorPropertiesInternal sensorProps) {
        if (mMeasureAdapter == null || mMeasureAdapter.getSensorProps() != sensorProps) {
            mMeasureAdapter = new UdfpsDialogMeasureAdapter(this, sensorProps);
        }
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return mActiveSensorType == TYPE_FINGERPRINT ? 0
                : super.getDelayAfterAuthenticatedDurationMs();
    }

    @Override
    protected boolean supportsManualRetry() {
        return false;
    }

    @Override
    @NonNull
    protected IconController getIconController() {
        if (mActiveSensorType == TYPE_FINGERPRINT) {
            if (!(mIconController instanceof UdfpsIconController)) {
                mIconController = new UdfpsIconController(getContext(), mIconView, mIndicatorView);
            }
            return mIconController;
        }
        return super.getIconController();
    }

    @Override
    public void updateState(int newState) {
        if (mState == STATE_HELP || mState == STATE_ERROR) {
            mActiveSensorType = TYPE_FINGERPRINT;
            setRequireConfirmation(false);
        }
        super.updateState(newState);
    }

    @Override
    @NonNull
    AuthDialog.LayoutParams onMeasureInternal(int width, int height) {
        final AuthDialog.LayoutParams layoutParams = super.onMeasureInternal(width, height);
        return mMeasureAdapter != null
                ? mMeasureAdapter.onMeasureInternal(width, height, layoutParams)
                : layoutParams;
    }
}
