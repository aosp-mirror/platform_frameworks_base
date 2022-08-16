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

package com.android.server.policy;

import static android.hardware.biometrics.BiometricStateListener.STATE_BP_AUTH;
import static android.hardware.biometrics.BiometricStateListener.STATE_ENROLLING;
import static android.hardware.biometrics.BiometricStateListener.STATE_IDLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.os.Handler;
import android.os.PowerManager;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Defines behavior for handling interactions between power button events and
 * fingerprint-related operations, for devices where the fingerprint sensor (side fps)
 * lives on the power button.
 */
public class SideFpsEventHandler {

    private static final int DEBOUNCE_DELAY_MILLIS = 500;

    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final PowerManager mPowerManager;
    @NonNull private final Supplier<AlertDialog.Builder> mDialogSupplier;
    @NonNull private final AtomicBoolean mSideFpsEventHandlerReady;

    @Nullable private Dialog mDialog;
    @NonNull private final DialogInterface.OnDismissListener mDialogDismissListener = (dialog) -> {
        if (mDialog == dialog) {
            mDialog = null;
        }
    };

    private @BiometricStateListener.State int mBiometricState;

    SideFpsEventHandler(Context context, Handler handler, PowerManager powerManager) {
        this(context, handler, powerManager, () -> new AlertDialog.Builder(context));
    }

    @VisibleForTesting
    SideFpsEventHandler(Context context, Handler handler, PowerManager powerManager,
            Supplier<AlertDialog.Builder> dialogSupplier) {
        mContext = context;
        mHandler = handler;
        mPowerManager = powerManager;
        mDialogSupplier = dialogSupplier;
        mBiometricState = STATE_IDLE;
        mSideFpsEventHandlerReady = new AtomicBoolean(false);

        // ensure dialog is dismissed if screen goes off for unrelated reasons
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mDialog != null) {
                    mDialog.dismiss();
                    mDialog = null;
                }
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    /**
     * Called from {@link PhoneWindowManager} after the power button is pressed and displays a
     * dialog confirming the user's intent to turn screen off if a fingerprint operation is
     * active. The device goes to sleep if confirmed otherwise the dialog is dismissed.
     *
     * @param eventTime powerPress event time
     * @return true if powerPress was consumed, false otherwise
     */
    public boolean onSinglePressDetected(long eventTime) {
        if (!mSideFpsEventHandlerReady.get()) {
            return false;
        }

        switch (mBiometricState) {
            case STATE_ENROLLING:
            case STATE_BP_AUTH:
                mHandler.post(() -> {
                    if (mDialog != null) {
                        mDialog.dismiss();
                    }
                    mDialog = showConfirmDialog(mDialogSupplier.get(),
                            mPowerManager, eventTime, mBiometricState, mDialogDismissListener);
                });
                return true;
            default:
                return false;
        }
    }

    @NonNull
    private static Dialog showConfirmDialog(@NonNull AlertDialog.Builder dialogBuilder,
            @NonNull PowerManager powerManager, long eventTime,
            @BiometricStateListener.State int biometricState,
            @NonNull DialogInterface.OnDismissListener dismissListener) {
        final boolean enrolling = biometricState == STATE_ENROLLING;
        final int title = enrolling ? R.string.fp_power_button_enrollment_title
                : R.string.fp_power_button_bp_title;
        final int message = enrolling ? R.string.fp_power_button_enrollment_message
                : R.string.fp_power_button_bp_message;
        final int positiveText = enrolling ? R.string.fp_power_button_enrollment_positive_button
                : R.string.fp_power_button_bp_positive_button;
        final int negativeText = enrolling ? R.string.fp_power_button_enrollment_negative_button
                : R.string.fp_power_button_bp_negative_button;

        final Dialog confirmScreenOffDialog = dialogBuilder
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveText,
                        (dialog, which) -> {
                            dialog.dismiss();
                            powerManager.goToSleep(
                                    eventTime,
                                    PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON,
                                    0 /* flags */
                            );
                        })
                .setNegativeButton(negativeText, (dialog, which) -> dialog.dismiss())
                .setOnDismissListener(dismissListener)
                .setCancelable(false)
                .create();
        confirmScreenOffDialog.getWindow().setType(
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL);
        confirmScreenOffDialog.show();

        return confirmScreenOffDialog;
    }

    /**
     * Awaits notification from PhoneWindowManager that fingerprint service is ready
     * to send updates about power button fps sensor state. Then configures a
     * BiometricStateListener to receive and record updates to fps state, and
     * registers the BiometricStateListener in FingerprintManager.
     */
    public void onFingerprintSensorReady() {
        final PackageManager pm = mContext.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return;
        }

        final FingerprintManager fingerprintManager =
                mContext.getSystemService(FingerprintManager.class);
        fingerprintManager.addAuthenticatorsRegisteredCallback(
                new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                    @Override
                    public void onAllAuthenticatorsRegistered(
                            List<FingerprintSensorPropertiesInternal> sensors) {
                        if (fingerprintManager.isPowerbuttonFps()) {
                            fingerprintManager.registerBiometricStateListener(
                                    new BiometricStateListener() {
                                        @Nullable
                                        private Runnable mStateRunnable = null;

                                        @Override
                                        public void onStateChanged(
                                                @BiometricStateListener.State int newState) {
                                            if (mStateRunnable != null) {
                                                mHandler.removeCallbacks(mStateRunnable);
                                                mStateRunnable = null;
                                            }

                                            // When the user hits the power button the events can
                                            // arrive in any order (success auth & power). Add a
                                            // damper when moving to idle in case auth is first
                                            if (newState == STATE_IDLE) {
                                                mStateRunnable = () -> mBiometricState = newState;
                                                mHandler.postDelayed(mStateRunnable,
                                                        DEBOUNCE_DELAY_MILLIS);
                                            } else {
                                                mBiometricState = newState;
                                            }
                                        }
                                    });
                            mSideFpsEventHandlerReady.set(true);
                        }
                    }
                });
    }
}
