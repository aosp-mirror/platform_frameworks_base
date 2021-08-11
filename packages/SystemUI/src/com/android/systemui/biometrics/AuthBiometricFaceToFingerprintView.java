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

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

/**
 * Manages the layout of an auth dialog for devices with both a face sensor and a fingerprint
 * sensor. Face authentication is attempted first, followed by fingerprint if the initial attempt is
 * unsuccessful.
 */
public class AuthBiometricFaceToFingerprintView extends AuthBiometricFaceView {
    private static final String TAG = "BiometricPrompt/AuthBiometricFaceToFingerprintView";

    protected static class UdfpsIconController extends IconController {
        @BiometricState private int mIconState = STATE_IDLE;

        protected UdfpsIconController(
                @NonNull Context context, @NonNull ImageView iconView, @NonNull TextView textView) {
            super(context, iconView, textView);
        }

        void updateState(@BiometricState int newState) {
            updateState(mIconState, newState);
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
            mIconState = newState;
        }
    }

    @Modality private int mActiveSensorType = TYPE_FACE;
    @Nullable private ModalityListener mModalityListener;
    @Nullable private FingerprintSensorPropertiesInternal mFingerprintSensorProps;
    @Nullable private UdfpsDialogMeasureAdapter mUdfpsMeasureAdapter;
    @Nullable @VisibleForTesting UdfpsIconController mUdfpsIconController;


    public AuthBiometricFaceToFingerprintView(Context context) {
        super(context);
    }

    public AuthBiometricFaceToFingerprintView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @VisibleForTesting
    AuthBiometricFaceToFingerprintView(Context context, AttributeSet attrs, Injector injector) {
        super(context, attrs, injector);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mUdfpsIconController = new UdfpsIconController(mContext, mIconView, mIndicatorView);
    }

    @Modality
    int getActiveSensorType() {
        return mActiveSensorType;
    }

    boolean isFingerprintUdfps() {
        return mFingerprintSensorProps.isAnyUdfpsType();
    }

    void setModalityListener(@NonNull ModalityListener listener) {
        mModalityListener = listener;
    }

    void setFingerprintSensorProps(@NonNull FingerprintSensorPropertiesInternal sensorProps) {
        mFingerprintSensorProps = sensorProps;
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
    public void onAuthenticationFailed(
            @Modality int modality, @Nullable String failureReason) {
        super.onAuthenticationFailed(modality, checkErrorForFallback(failureReason));
    }

    @Override
    public void onError(int modality, String error) {
        super.onError(modality, checkErrorForFallback(error));
    }

    private String checkErrorForFallback(String message) {
        if (mActiveSensorType == TYPE_FACE) {
            Log.d(TAG, "Falling back to fingerprint: " + message);

            // switching from face -> fingerprint mode, suppress root error messages
            mCallback.onAction(Callback.ACTION_START_DELAYED_FINGERPRINT_SENSOR);
            return mContext.getString(R.string.fingerprint_dialog_use_fingerprint_instead);
        }
        return message;
    }

    @Override
    @BiometricState
    protected int getStateForAfterError() {
        if (mActiveSensorType == TYPE_FACE) {
            return STATE_AUTHENTICATING;
        }

        return super.getStateForAfterError();
    }

    @Override
    public void updateState(@BiometricState int newState) {
        if (mActiveSensorType == TYPE_FACE) {
            if (newState == STATE_HELP || newState == STATE_ERROR) {
                mActiveSensorType = TYPE_FINGERPRINT;

                setRequireConfirmation(false);
                mConfirmButton.setEnabled(false);
                mConfirmButton.setVisibility(View.GONE);

                if (mModalityListener != null) {
                    mModalityListener.onModalitySwitched(TYPE_FACE, mActiveSensorType);
                }

                // Deactivate the face icon controller so it stops drawing to the view
                mFaceIconController.deactivate();
                // Then, activate this icon controller. We need to start in the "idle" state
                mUdfpsIconController.updateState(STATE_IDLE);
            }
        } else { // Fingerprint
            mUdfpsIconController.updateState(newState);
        }

        super.updateState(newState);
    }

    @Override
    @NonNull
    AuthDialog.LayoutParams onMeasureInternal(int width, int height) {
        final AuthDialog.LayoutParams layoutParams = super.onMeasureInternal(width, height);
        return isFingerprintUdfps()
                ? getUdfpsMeasureAdapter().onMeasureInternal(width, height, layoutParams)
                : layoutParams;
    }

    @NonNull
    private UdfpsDialogMeasureAdapter getUdfpsMeasureAdapter() {
        if (mUdfpsMeasureAdapter == null
                || mUdfpsMeasureAdapter.getSensorProps() != mFingerprintSensorProps) {
            mUdfpsMeasureAdapter = new UdfpsDialogMeasureAdapter(this, mFingerprintSensorProps);
        }
        return mUdfpsMeasureAdapter;
    }

    @Override
    public void onSaveState(@NonNull Bundle outState) {
        super.onSaveState(outState);
        outState.putInt(AuthDialog.KEY_BIOMETRIC_SENSOR_TYPE, mActiveSensorType);
        outState.putParcelable(AuthDialog.KEY_BIOMETRIC_SENSOR_PROPS, mFingerprintSensorProps);
    }

    @Override
    public void restoreState(@Nullable Bundle savedState) {
        super.restoreState(savedState);
        if (savedState != null) {
            mActiveSensorType = savedState.getInt(AuthDialog.KEY_BIOMETRIC_SENSOR_TYPE, TYPE_FACE);
            mFingerprintSensorProps =
                    savedState.getParcelable(AuthDialog.KEY_BIOMETRIC_SENSOR_PROPS);
        }
    }
}
